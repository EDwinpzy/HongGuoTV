package com.hongguotv.nativeapp

import com.hongguotv.core.StreamInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

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

    @Test fun learnedPrimaryFailureFallsThroughInsteadOfForcingAgain() {
        val attempts=primaryPlaybackAttempts(listOf(hdHevc,sdFallback),1080,{ it===sdFallback },{ it===hdHevc })
        assertTrue(attempts.isEmpty())
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
}
