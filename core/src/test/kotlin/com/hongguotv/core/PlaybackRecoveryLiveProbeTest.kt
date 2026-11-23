package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Live acceptance for the current-App playback recovery. The default episode is
 * the 2026-11-23 S621 failure (红兜落处是良人 ep4): both old channels answered
 * HTTP 200 + 0 bytes before the field-21/protocol fix.
 */
class PlaybackRecoveryLiveProbeTest {
    @Test fun targetEpisodeResolvesAndMediaReads() {
        assumeTrue(System.getenv("HONGGUOTV_LIVE_PROBE") == "1")
        val id = System.getenv("HONGGUOTV_LIVE_VIDEO_ID")
            ?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: "7704127035085425726"
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val stream = ContentRepository(http).stream(id, 1080, ContentType.SHORT)
        println(
            "RECOVERY episode=$id quality=${stream.quality} codec=${stream.codec} " +
                "${stream.width}x${stream.height} key=${stream.key?.size ?: 0}",
        )
        assertTrue("playback URL is not http(s): ${stream.url}", stream.url.startsWith("https://"))
        val media = Request.Builder().url(stream.url)
            .header("Range", "bytes=0-131071")
            .build()
        http.newCall(media).execute().use { response ->
            val bytes = response.body?.bytes()?.size ?: 0
            println("RECOVERY media HTTP ${response.code} bytes=$bytes")
            assertTrue("media failed: HTTP ${response.code}", response.isSuccessful)
            assertTrue("media returned no bytes", bytes > 0)
        }
    }
}
