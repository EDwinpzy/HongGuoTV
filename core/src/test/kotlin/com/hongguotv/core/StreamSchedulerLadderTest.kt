// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * 2026-11-14 regression: the capability-first ladder must retry sporadic empty
 * answers AND risk-control rejections (code 110001) on the signed channel with a
 * FRESH device identity, must return to the PRIMARY channel after a refresh, and
 * may use a verified full public MP4 only after the signed retries are exhausted.
 */
class StreamSchedulerLadderTest {
    private class ScriptedSource(
        override val name: String,
        private val script: MutableList<() -> List<StreamInfo>>,
    ) : StreamSource {
        var calls = 0
        var refreshes = 0
        override fun stream(
            id: String,
            maxQuality: Int,
            type: ContentType,
            metadataAhead: List<String>,
            speculative: Boolean,
            seriesId: String,
        ): StreamInfo =
            throw UnsupportedOperationException()
        override fun ladder(
            id: String,
            maxQuality: Int,
            type: ContentType,
            metadataAhead: List<String>,
            speculative: Boolean,
            seriesId: String,
        ): List<StreamInfo> {
            calls++
            return script.removeAt(0)()
        }
        override fun refreshIdentity() { refreshes++ }
    }

    private val hd = StreamInfo("https://signed/720.mp4", null, "720P", "h264", 720, 1280, 720)
    private val preview = StreamInfo("https://site/video/public/123/360p.mp4", null, "360P")

    @Test
    fun retriesTransientEmptyAnswersOnFreshIdentities() {
        val signed = ScriptedSource("app-api", mutableListOf(
            { throw AppApiEmptyResponseException() },
            { listOf(hd) },
        ))
        val website = ScriptedSource("public-mp4", mutableListOf({ listOf(preview) }))
        val tiers = StreamScheduler(listOf(signed, website)).ladder("123", 1080, ContentType.SHORT)
        assertEquals(1, tiers.size)
        assertEquals("720P", tiers[0].quality)
        assertEquals(2, signed.calls)
        assertEquals(1, signed.refreshes) // one retry gets a re-registered device
        assertEquals(0, website.calls)
    }

    @Test
    fun previewRejectionDoesNotBecomeAPlayableTier() {
        val signed = ScriptedSource("app-api", MutableList(2) { { throw AppApiEmptyResponseException() } })
        val website = ScriptedSource("public-mp4", mutableListOf({ throw IOException("网站公开片源仅为试看片段") }))
        val error = runCatching { StreamScheduler(listOf(signed, website)).ladder("123", 1080, ContentType.SHORT) }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertTrue(error is PlaybackSourceUnavailableException)
        assertEquals(2, signed.calls)
        assertEquals(1, signed.refreshes)
        assertEquals(1, website.calls)
    }

    @Test
    fun exhaustedSignedChannelsUseVerifiedFullPublicFallback() {
        val signed = ScriptedSource("app-api", MutableList(2) { { throw AppApiEmptyResponseException() } })
        val protectedWeb = ScriptedSource("web-fallback", MutableList(2) {
            { throw PlaybackSourceUnavailableException("网页完整片源 HTTP 403") }
        })
        val fullPublic = StreamInfo("https://site/video/public/123/360p.mp4", null, "360P", isFallback = true)
        val public = ScriptedSource("public-mp4", mutableListOf({ listOf(fullPublic) }))
        val tiers = StreamScheduler(listOf(signed, protectedWeb, public)).ladder("123", 1080, ContentType.SHORT)
        assertEquals(listOf(fullPublic), tiers)
        assertEquals(2, signed.calls)
        assertEquals(1, signed.refreshes)
        assertEquals(1, public.calls)
    }

    @Test
    fun backgroundReadsDoNotBurnRetries() {
        val signed = ScriptedSource("app-api", mutableListOf({ throw AppApiEmptyResponseException() }, { listOf(hd) }))
        val website = ScriptedSource("public-mp4", mutableListOf({ listOf(preview) }))
        val error = runCatching {
            StreamScheduler(listOf(signed, website)).ladder("123", 1080, ContentType.SHORT, speculative = true)
        }.exceptionOrNull()
        assertTrue(error is IOException)
        assertEquals(1, signed.calls)   // speculative: no retry, no quota burn
        assertEquals(0, signed.refreshes)
        assertEquals(0, website.calls)
    }

    /**
     * Live 2026-11-14 incident: a blocked identity answers the primary channel with
     * HTTP 200 + {"code":110001}, while the backup channel keeps answering 0 bytes.
     * The old scheduler called refreshIdentity() only on the channel that failed
     * LAST (the backup, whose implementation is a no-op), so the primary was never
     * retried with the fresh device - endless "refreshing identity" retries even
     * though upstream on the same emulator played the episode at 540x960.
     */
    @Test
    fun riskControlRejectionReturnsToPrimaryChannelWithFreshIdentity() {
        val primary = ScriptedSource("multi-video-model", mutableListOf(
            { throw AppApiRejectedException(110001) },
            { listOf(hd) },
        ))
        val backup = ScriptedSource("app-api", MutableList(5) { { throw AppApiEmptyResponseException() } })
        val website = ScriptedSource("public-mp4", mutableListOf({ listOf(preview) }))
        val tiers = StreamScheduler(listOf(primary, backup, website)).ladder("123", 1080, ContentType.SHORT)
        assertEquals(1, tiers.size)
        assertEquals("720P", tiers[0].quality)
        assertEquals(2, primary.calls)      // chain restarts at the primary channel
        assertEquals(1, primary.refreshes)  // ALL channels rotate, not just the last one
        assertEquals(1, backup.calls)
        assertEquals(1, backup.refreshes)
        assertEquals(0, website.calls)      // the 10s preview must never reach the player
    }

    /** A real network/parse failure is not an identity shield: fail fast, no re-registration storm. */
    @Test
    fun hardErrorsDoNotBurnIdentityRefreshes() {
        val primary = ScriptedSource("multi-video-model", mutableListOf({ throw IOException("网络不可达") }))
        val backup = ScriptedSource("app-api", mutableListOf({ throw IOException("网络不可达") }))
        val error = runCatching {
            StreamScheduler(listOf(primary, backup)).ladder("123", 1080, ContentType.SHORT)
        }.exceptionOrNull()
        assertTrue("expected IOException, got $error", error is IOException)
        assertEquals(1, primary.calls)
        assertEquals(1, backup.calls)
        assertEquals(0, primary.refreshes)
        assertEquals(0, backup.refreshes)
    }

    @Test
    fun emptyThenTimeoutStaysRetryableInsteadOfBecomingDeterministic() {
        val primary = ScriptedSource("multi-video-model", mutableListOf(
            { throw AppApiEmptyResponseException() },
            { throw SocketTimeoutException("network stalled") },
        ))
        val error = runCatching {
            StreamScheduler(listOf(primary)).ladder("123", 1080, ContentType.SHORT)
        }.exceptionOrNull()
        assertTrue("expected timeout, got $error", error is SocketTimeoutException)
        assertEquals(2, primary.calls)
        assertEquals(1, primary.refreshes)
    }
}
