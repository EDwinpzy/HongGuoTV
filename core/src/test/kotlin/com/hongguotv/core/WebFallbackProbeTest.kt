package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit live acceptance for the complete website MP4 fallback. */
class WebFallbackProbeTest {
    @Test fun probe() {
        assumeTrue(System.getenv("HONGGUOTV_LIVE_PROBE") == "1")
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val source = WebFallbackStreamSource(
            http,
            "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36",
            "https://hongguoduanju.com",
        )
        val info = source.stream("7706514645208534041", 1080, ContentType.SHORT)
        assertNull(info.key)
        assertEquals("h264", info.codec)
        assertEquals(360, info.qualityValue)
        RemoteVideo(http, info).use { video ->
            video.prepare()
            val track = video.trackInfo()
            assertTrue("expected a video track", track != null)
            assertEquals("avc1", track?.fourcc?.lowercase())
            println(
                "web-fallback ${info.quality} ${track?.fourcc} " +
                    "${track?.width}x${track?.height} total=${video.total}",
            )
        }
        val tiers = ContentRepository(http)
            .playbackTiers("7706514645208534041", 1080, ContentType.SHORT)
        tiers.forEach {
            println(
                "ladder ${it.quality} ${it.codec} " +
                    "${it.width}x${it.height} value=${it.qualityValue}",
            )
        }
        assertTrue("ladder did not include the complete H264 fallback", tiers.any {
            it.codec.equals("h264", ignoreCase = true) && it.qualityValue == 360
        })
    }
}
