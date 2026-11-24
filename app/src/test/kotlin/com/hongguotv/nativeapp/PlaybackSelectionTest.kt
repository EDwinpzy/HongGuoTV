package com.hongguotv.nativeapp

import com.hongguotv.core.StreamInfo
import com.hongguotv.core.VideoTracks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.SocketTimeoutException

class PlaybackSelectionTest {
    private val hdHevc=StreamInfo("https://signed/1080.mp4",null,"1080P","bytevc1",1080,1920,1080)
    private val hdAvc=StreamInfo("https://signed/1080-avc.mp4",null,"1080P","h264",1080,1920,1080)
    private val sdFallback=StreamInfo("https://site/360.mp4",null,"360P","h264",640,360,360,true)

    @Test fun primaryHevcIsTriedBeforeWebsiteFallback() {
        val attempts=primaryPlaybackAttempts(listOf(hdHevc,sdFallback),1080,{ it===sdFallback },{ false })
        assertEquals(1,attempts.size)
        assertEquals(hdHevc,attempts.single().tier)
        assertTrue(attempts.single().forced)
        assertFalse(attempts.single().tier.isFallback)
    }

    @Test fun learnedPrimaryFailureGetsOneBestEffortAttemptWithoutAlternateTier() {
        val attempts=primaryPlaybackAttempts(listOf(hdHevc,sdFallback),1080,{ it===sdFallback },{ it===hdHevc })
        assertEquals(1,attempts.size)
        assertEquals(hdHevc,attempts.single().tier)
        assertTrue(attempts.single().forced)
    }

    @Test fun explicitQualityChangeRetriesLearnedFailedTopPrimary() {
        val attempts=primaryPlaybackAttempts(
            listOf(hdHevc,sdFallback),
            1080,
            { it===sdFallback },
            { it===hdHevc },
            retryLearned=true,
        )
        assertEquals(listOf(hdHevc),attempts.map { it.tier })
        assertTrue(attempts.single().forced)
    }

    @Test fun explicitQualityChangeTriesTopPrimaryBeforeLowerSupportedTier() {
        val sdAvc=StreamInfo("https://signed/480-avc.mp4",null,"480P","h264",854,480,480)
        val attempts=primaryPlaybackAttempts(
            listOf(hdHevc,sdAvc,sdFallback),
            1080,
            { it===sdAvc },
            { it===hdHevc },
            retryLearned=true,
        )
        assertEquals(listOf(hdHevc,sdAvc),attempts.map { it.tier })
        assertTrue(attempts.first().forced)
        assertFalse(attempts.last().forced)
    }

    @Test fun supportedHigherTierWinsWithoutForcing() {
        val attempts=primaryPlaybackAttempts(listOf(hdHevc,hdAvc,sdFallback),1080,{ it===hdAvc },{ false })
        assertEquals(listOf(hdAvc),attempts.map { it.tier })
        assertFalse(attempts.single().forced)
    }

    @Test fun sameResolutionPrefersHigherSourceBitrate() {
        val low=StreamInfo(
            "https://signed/1080-low.mp4",null,"1080P","bytevc1",1922,1080,1080,
            bitrate=571629,
        )
        val high=StreamInfo(
            "https://signed/1080-high.mp4",null,"1080P","bytevc1",1922,1080,1080,
            bitrate=2000000,
        )
        val attempts=primaryPlaybackAttempts(listOf(low,high),1080,{ true },{ false })
        assertEquals(listOf(high,low),attempts.map { it.tier })
    }

    @Test fun actualQualityLabelShowsDecodedSizeAndSourceBitrate() {
        val tier=StreamInfo(
            "https://signed/1080.mp4",null,"1080P","bytevc1",1922,1080,1080,
            bitrate=571629,fps=30f,
        )
        val track=VideoTracks.Track("hvc1",1922,1080)
        assertEquals("1080P · 1922×1080 · 片源码率 572 kbps",actualQualityLabel("1080P",track,tier))
        assertEquals("1080P · 1922×1080",actualQualityLabel("1080P",track,null))
        assertEquals("1.03 Mbps",formatSourceBitrate(1030858))
    }

    @Test fun qualityCapExcludesHigherSupportedPrimary() {
        val sdAvc=StreamInfo("https://signed/480-avc.mp4",null,"480P","h264",854,480,480)
        val attempts=primaryPlaybackAttempts(listOf(hdAvc,sdAvc,sdFallback),720,{ true },{ false })
        assertEquals(listOf(sdAvc),attempts.map { it.tier })
        assertFalse(attempts.single().forced)
    }

    @Test fun explicitRetryStillRespectsQualityCap() {
        val hd720=StreamInfo("https://signed/720.mp4",null,"720P","bytevc1",720,1280,720)
        val attempts=primaryPlaybackAttempts(
            listOf(hdHevc,hd720,sdFallback),
            720,
            { it===hd720 },
            { it===hd720 },
            retryLearned=true,
        )
        assertEquals(listOf(hd720),attempts.map { it.tier })
        assertTrue(attempts.single().forced)
    }

    @Test fun fallbackOnlyLadderProducesNoPrimaryAttempts() {
        val attempts=primaryPlaybackAttempts(listOf(sdFallback),1080,{ true },{ false })
        assertTrue(attempts.isEmpty())
    }

    @Test fun transportFailureDuringSelectionRemainsRetryable() {
        val timeout=SocketTimeoutException("stalled media route")
        val primary=StreamInfo("https://signed/retry.mp4",null,"1080P","bytevc1",1920,1080,1080)
        val thrown=assertThrows(SocketTimeoutException::class.java) {
            throwNoOpenedStream(listOf(primary),timeout)
        }
        assertSame(timeout,thrown)
        assertTrue(com.hongguotv.core.RecoveryBudget().remaining)
        assertThrows(NoPlayableQualityException::class.java) {
            throwNoOpenedStream(listOf(primary),null)
        }
    }

    @Test fun cancellationIsNotConvertedIntoAnotherTierAttempt() {
        assertTrue(cancelledPlayback(java.io.InterruptedIOException(" canceled")))
        // OkHttp uses the same exception type for a call timeout; that must fall
        // through to the next tier, not abort playback as a user cancellation.
        assertFalse(cancelledPlayback(java.io.InterruptedIOException("timeout")))
        assertTrue(cancelledPlayback(java.net.SocketException("Canceled")))
        assertFalse(cancelledPlayback(SocketTimeoutException("timeout")))
    }

    @Test fun twentyFiveFpsUsesFiftyHzDisplayCadence() {
        assertEquals(50f,targetDisplayRefreshRate(25f))
        assertEquals(50f,targetDisplayRefreshRate(24f))
        assertEquals(50f,targetDisplayRefreshRate(50f))
        assertEquals(listOf(25f,50f),displayRefreshTargets(25f))
    }

    @Test fun thirtyFpsUsesSixtyHzDisplayCadence() {
        assertEquals(60f,targetDisplayRefreshRate(30f))
        assertEquals(60f,targetDisplayRefreshRate(60f))
        assertEquals(listOf(30f,60f),displayRefreshTargets(30f))
        assertEquals(listOf(120f),displayRefreshTargets(120f))
        assertTrue(displayRefreshTargets(0f).isEmpty())
    }

    @Test fun s621WebsiteFallbackUsesTextureRegardlessOfAdvertisedSize() {
        assertEquals(
            PlaybackSurfaceKind.TEXTURE_VIEW,
            playbackSurfaceKind("S621",true,1920,1080),
        )
        assertEquals(
            PlaybackSurfaceKind.TEXTURE_VIEW,
            playbackSurfaceKind("s621",true,null,null),
        )
    }

    @Test fun s621LowResolutionPrimaryUsesTextureButHdKeepsHardwareSurface() {
        assertEquals(
            PlaybackSurfaceKind.TEXTURE_VIEW,
            playbackSurfaceKind("S621",false,640,360),
        )
        assertEquals(
            PlaybackSurfaceKind.TEXTURE_VIEW,
            playbackSurfaceKind("S621",false,360,640),
        )
        assertEquals(
            PlaybackSurfaceKind.SURFACE_VIEW,
            playbackSurfaceKind("S621",false,1920,1080),
        )
    }

    @Test fun otherDevicesKeepHardwareSurfaceForLowResolutionFallback() {
        assertEquals(
            PlaybackSurfaceKind.SURFACE_VIEW,
            playbackSurfaceKind("Other TV",true,640,360),
        )
    }
}
