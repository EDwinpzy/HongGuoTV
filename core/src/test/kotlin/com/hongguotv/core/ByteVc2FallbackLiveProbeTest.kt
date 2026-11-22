package com.hongguotv.core

import java.io.File
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit live diagnosis for episodes whose video_tab response is bytevc2-only. */
class ByteVc2FallbackLiveProbeTest {
    @Test fun probe() {
        assumeTrue(System.getenv("HONGGUOTV_LIVE_PROBE") == "1")
        val videoId = System.getenv("HONGGUOTV_LIVE_VIDEO_ID")
            ?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            ?: "7708197063116196888"
        val out = File("build/ep6-playback-diag").apply { mkdirs() }
        var sequence = 0
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                val request = chain.request()
                val response = chain.proceed(request)
                val body = response.peekBody(2L * 1024 * 1024).string()
                val index = sequence++
                val path = request.url.encodedPath
                val queryKeys = request.url.queryParameterNames.sorted().joinToString(",")
                val label = "%02d %s %s -> HTTP %d bytes=%d query=[%s]".format(
                    index,
                    request.method,
                    path,
                    response.code,
                    body.toByteArray(Charsets.UTF_8).size,
                    queryKeys,
                )
                println("DIAG $label")
                if (!path.contains("fallback-play-info") && body.isNotBlank()) {
                    File(out, "%02d-%s.json".format(index, path.trim('/').replace('/', '-'))).writeText(body)
                }
                response
            }
            .build()
        val transport = AppApiTransport(http)

        println("DIAG target videoId=$videoId")
        println("== multi-video-model ==")
        runCatching {
            MultiVideoModelStreamSource(transport, http).ladder(videoId, 1080, ContentType.SHORT)
        }.onSuccess { tiers ->
            println("DIAG multi tiers=${tiers.size}")
            tiers.forEach {
                println("DIAG multi ${it.quality} ${it.codec} ${it.width}x${it.height} url=${it.url.take(80)}")
            }
        }.onFailure {
            println("DIAG multi failed ${it.javaClass.simpleName}: ${it.message}")
        }

        println("== mobile-v5 ==")
        runCatching {
            MobilePlaybackStreamSource(http).ladder(videoId, 1080, ContentType.SHORT)
        }.onSuccess { tiers ->
            println("DIAG mobile tiers=${tiers.size}")
            tiers.forEach {
                println("DIAG mobile ${it.quality} ${it.codec} ${it.width}x${it.height} url=${it.url.take(80)}")
            }
        }.onFailure {
            println("DIAG mobile failed ${it.javaClass.simpleName}: ${it.message}")
        }

        println("DIAG dumps=${out.absolutePath}")
    }
}
