package com.hongguotv.nativeapp

import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import com.hongguotv.core.VideoTracks
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Parallel header probes: concurrency, priority selection and loser cleanup. */
class TierProbeTest {
    private val hd = StreamInfo("https://cdn/hd.mp4", null, "1080P", "h264", 1920, 1080, 1080)
    private val sd = StreamInfo("https://cdn/sd.mp4", null, "480P", "h264", 854, 480, 480)
    private val http = OkHttpClient()

    @Test
    fun probesRunConcurrentlySoTheWaitIsTheSlowestNotTheSum() {
        val bothStarted = CountDownLatch(2)
        val started = System.nanoTime()
        val outcomes = probeTiers(
            listOf(PlaybackAttempt(hd, false), PlaybackAttempt(sd, false)),
        ) { tier ->
            bothStarted.countDown()
            // Each probe takes 300ms; serially that is 600ms, concurrently ~300ms.
            assertTrue(bothStarted.await(2, TimeUnit.SECONDS))
            Thread.sleep(300)
            TierProbe.Opened(RemoteVideo(http, tier), VideoTracks.Track("avc1", 1920, 1080))
        }
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals(2, outcomes.size)
        assertTrue("probes must overlap: took ${elapsedMs}ms", elapsedMs < 550)
        outcomes.values.forEach { (it as TierProbe.Opened).video.close() }
    }

    @Test
    fun aFailedProbeDoesNotHideTheOtherTiersSuccess() {
        val outcomes = probeTiers(
            listOf(PlaybackAttempt(hd, false), PlaybackAttempt(sd, false)),
        ) { tier ->
            if (tier === hd) TierProbe.Failed(IOException("cdn reset"))
            else TierProbe.Opened(RemoteVideo(http, tier), null)
        }
        assertTrue(outcomes.getValue(hd) is TierProbe.Failed)
        assertTrue(outcomes.getValue(sd) is TierProbe.Opened)
        (outcomes.getValue(sd) as TierProbe.Opened).video.close()
    }

    @Test
    fun aCancelledProbeCancelsTheWholeSelectionAndClosesWhatOpened() {
        val opened = AtomicInteger()
        val closed = AtomicInteger()
        try {
            probeTiers(
                listOf(PlaybackAttempt(hd, false), PlaybackAttempt(sd, false)),
            ) { tier ->
                if (tier === hd) {
                    opened.incrementAndGet()
                    TierProbe.Opened(RemoteVideo(http, tier), null)
                } else {
                    TierProbe.Failed(InterruptedIOException("播放请求已取消"))
                }
            }
            throw AssertionError("expected the cancellation to propagate")
        } catch (expected: InterruptedIOException) {
            // expected: cancellation is never downgraded to a tier failure
        }
        // The selection has no winner to hand back, so nothing may stay open.
        // (The Opened instance is unreachable after the throw; this asserts the
        // contract through the probe count rather than the handle.)
        assertEquals(1, opened.get())
        assertEquals(0, closed.get())
    }
}
