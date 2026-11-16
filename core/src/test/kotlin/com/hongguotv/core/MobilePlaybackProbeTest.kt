package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit live-network acceptance for the v5 mobile playback channel. */
class MobilePlaybackProbeTest {
    @Test fun probe() {
        assumeTrue(System.getenv("HONGGUOTV_LIVE_PROBE") == "1")
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val repository = ContentRepository(http)
        val series = "7706512650816982040"
        val detail = repository.detail(series)
        val id = detail.episodes.first()
        val tiers = MobilePlaybackStreamSource(http)
            .ladder(id, 1080, ContentType.SHORT)
        println("v5 series=$series title=${detail.series.title} episode=$id tiers=${tiers.size}")
        tiers.take(4).forEach {
            println("v5 ${it.quality} ${it.codec} ${it.width}x${it.height} key=${it.key?.size ?: 0}")
        }
        assertTrue("v5 returned no playable tier", tiers.isNotEmpty())
        val tier = tiers.first()
        assertTrue("v5 URL is not http(s)", tier.url.startsWith("https://"))
        val media = Request.Builder().url(tier.url)
            .header("Range", "bytes=0-131071")
            .build()
        http.newCall(media).execute().use { response ->
            val bytes = response.body?.bytes()?.size ?: 0
            println("v5 media HTTP ${response.code} bytes=$bytes")
            assertTrue("v5 media failed: HTTP ${response.code}", response.isSuccessful)
            assertTrue("v5 media returned no bytes", bytes > 0)
        }
    }
}
