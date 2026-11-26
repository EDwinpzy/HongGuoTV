package com.hongguotv.core

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Live probe for an episode that currently reports source-unavailable on S621. */
class SourceUnavailableLiveProbeTest {
    @Test fun probe() {
        assumeTrue(System.getenv("HONGGUOTV_LIVE_PROBE") == "1")
        val series = System.getenv("HONGGUOTV_LIVE_SERIES_ID")
            ?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: "7703467401337130009"
        val target = System.getenv("HONGGUOTV_LIVE_VIDEO_ID")
            ?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: "7703467407741832216"
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        val repository = ContentRepository(http)
        val detail = repository.detail(series)
        val index = detail.episodes.indexOf(target)
        println(
            "SOURCE title=${detail.series.title} episodes=${detail.episodes.size} " +
                "targetIndex=${index + 1}",
        )
        val candidates = buildList {
            add(target)
            if (index > 0) add(detail.episodes[index - 1])
            if (index in 0 until detail.episodes.lastIndex) add(detail.episodes[index + 1])
        }.distinct()
        for (id in candidates) {
            try {
                val tiers = repository.playbackTiers(id, 1080, ContentType.SHORT)
                println(
                    "SOURCE id=$id OK tiers=${tiers.size} " +
                        tiers.joinToString { "${it.quality}/${it.codec}" },
                )
                if (id == target) {
                    val tier = tiers.first()
                    val media = Request.Builder().url(tier.url)
                        .header("Range", "bytes=0-131071")
                        .build()
                    http.newCall(media).execute().use { response ->
                        val bytes = response.body?.bytes()?.size ?: 0
                        println(
                            "SOURCE media HTTP ${response.code} bytes=$bytes " +
                                "codec=${tier.codec}",
                        )
                        assertTrue("target media failed: HTTP ${response.code}", response.isSuccessful)
                        assertTrue("target media returned no bytes", bytes > 0)
                    }
                }
            } catch (error: Exception) {
                println(
                    "SOURCE id=$id FAIL ${error.javaClass.simpleName}: " +
                        error.message?.take(180),
                )
            }
        }
    }
}
