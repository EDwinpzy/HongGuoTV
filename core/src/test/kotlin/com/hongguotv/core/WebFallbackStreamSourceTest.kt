package com.hongguotv.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import okhttp3.OkHttpClient
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class WebFallbackStreamSourceTest {
    @Test fun resolvesCompleteUnencryptedMp4() {
        withServer(seconds = 178) { site, requests ->
            val source = WebFallbackStreamSource(OkHttpClient(), "test-ua", site)
            val info = source.stream("7706514645208534041", 1080, ContentType.SHORT)
            assertEquals("web-fallback", source.name)
            assertEquals("360P", info.quality)
            assertEquals("h264", info.codec)
            assertEquals(360, info.qualityValue)
            assertNull(info.key)
            assertTrue(info.url.startsWith("$site/media"))
            assertEquals(2, requests.size)
            assertTrue(requests[0].contains("7706514645208534041"))
            assertTrue(requests[1].contains("bytes=0-131071"))
        }
    }

    @Test fun rejectsTenSecondPreview() {
        withServer(seconds = 10) { site, _ ->
            val source = WebFallbackStreamSource(OkHttpClient(), "test-ua", site)
            val error = assertThrows(java.io.IOException::class.java) {
                source.stream("7706514645208534041", 1080, ContentType.SHORT)
            }
            assertTrue(error.message.orEmpty().contains("试看"))
        }
    }

    private fun withServer(
        seconds: Int,
        block: (site: String, requests: MutableList<String>) -> Unit,
    ) {
        val requests = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        val executor = Executors.newSingleThreadExecutor()
        server.executor = executor
        val site = "http://127.0.0.1:${server.address.port}"
        val media = mp4Header(seconds)
        server.createContext("/api/video/protected/fallback-play-info") { exchange ->
            requests += exchange.requestBody.readBytes().toString(Charsets.UTF_8)
            val response = JSONObject()
                .put("main_url", "$site/media")
                .put("base_resp", JSONObject().put("StatusCode", 0))
                .toString()
                .toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.createContext("/media") { exchange ->
            requests += exchange.requestHeaders.getFirst("Range").orEmpty()
            exchange.responseHeaders.add("Content-Type", "video/mp4")
            exchange.responseHeaders.add("Content-Range", "bytes 0-${media.lastIndex}/2000000")
            exchange.sendResponseHeaders(206, media.size.toLong())
            exchange.responseBody.use { it.write(media) }
        }
        server.start()
        try {
            block(site, requests)
        } finally {
            server.stop(0)
            executor.shutdownNow()
        }
    }

    private fun mp4Header(seconds: Int): ByteArray {
        val data = ByteArray(64)
        val buffer = ByteBuffer.wrap(data)
        buffer.putInt(0, 16)
        "ftyp".toByteArray().copyInto(data, 4)
        "isom".toByteArray().copyInto(data, 8)
        buffer.putInt(16, 36)
        "mvhd".toByteArray().copyInto(data, 20)
        data[24] = 0
        buffer.putInt(36, 1000)
        buffer.putInt(40, seconds * 1000)
        return data
    }
}
