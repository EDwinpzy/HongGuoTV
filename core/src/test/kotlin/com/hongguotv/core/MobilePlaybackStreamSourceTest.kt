package com.hongguotv.core

import java.util.zip.GZIPInputStream
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobilePlaybackStreamSourceTest {
    private val now = 1_800_000_000_000L
    private val episode = "7704127035085425726"
    private val session = PlaybackDeviceSession {
        DeviceRegister.Registered("123456", "654321")
    }
    private val signer = Signer(
        { now }, random = { 0.5 }, processNonce = "synthetic", mobileV5 = true,
    )

    @Test
    fun primaryEmptyFallsBackToSameIdentityVideoTab() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.encodedPath) {
                "/novel/player/multi_video_model/preload/v1" -> ""
                "/reading/bookapi/video_tab/multi_video_model/v1/" -> tabResponse()
                else -> error("unexpected request ${request.url}")
            }
            response(request, body)
        }.build()
        val source = MobilePlaybackStreamSource(http, session, { now }, signer)

        val tiers = source.ladder(episode, 1080, ContentType.SHORT)

        assertEquals(1, tiers.size)
        assertEquals("h264", tiers.single().codec)
        assertEquals(2, requests.size)
        val post = requests[0]
        val get = requests[1]
        assertEquals("reading.snssdk.com", post.url.host)
        assertEquals("/novel/player/multi_video_model/preload/v1", post.url.encodedPath)
        assertEquals("74532", post.url.queryParameter("version_code"))
        assertEquals("7.4.5.32", post.url.queryParameter("version_name"))
        assertEquals("25053RT47C", post.url.queryParameter("device_type"))
        assertEquals("update_64", post.url.queryParameter("channel"))
        val gzip = Buffer().also { post.body!!.writeTo(it) }.readByteArray()
        val payload = JSONObject(GZIPInputStream(gzip.inputStream()).reader().readText())
        assertEquals("preload", payload.getString("dr_scene"))
        assertEquals(2, payload.getJSONObject("biz_param").getInt("device_level"))
        assertNotNull(post.header("x-ss-stub"))
        assertNull(post.header("Cookie"))

        assertEquals("api5-normal-sinfonlineb.fqnovel.com", get.url.host)
        assertEquals(post.url.queryParameter("device_id"), get.url.queryParameter("device_id"))
        assertEquals(post.url.queryParameter("iid"), get.url.queryParameter("iid"))
        assertEquals(post.url.queryParameter("cdid"), get.url.queryParameter("cdid"))
        assertEquals(episode, get.url.queryParameter("video_id"))
        assertNull(get.header("x-ss-stub"))
        assertNull(get.header("Content-Type"))
        assertNull(get.header("Content-Encoding"))
        assertNull(get.header("Cookie"))
        assertNull(get.body)
    }

    @Test
    fun bytevc2OnlyModelUsesAppFallbackCodecWithoutWebsite() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val body = when (request.url.encodedPath) {
                "/novel/player/multi_video_model/preload/v1" ->
                    JSONObject()
                        .put("code", 0)
                        .put(
                            "data",
                            JSONObject().put(
                                "1004",
                                JSONObject().put(
                                    episode,
                                    JSONObject().put(
                                        "video_model",
                                        model("https://cdn.example/unsupported", "bytevc2")
                                            .put(
                                                "fallback_api",
                                                JSONObject().put(
                                                    "fallback_api",
                                                    "https://vas.example.org/video/fplay?codec_type=4",
                                                ),
                                            ),
                                    ),
                                ),
                            ),
                        )
                        .toString()
                "/video/fplay" -> {
                    assertEquals("1", request.url.queryParameter("codec_type"))
                    assertNull(request.header("Cookie"))
                    JSONObject()
                        .put(
                            "video_info",
                            JSONObject().put(
                                "data",
                                model("https://cdn.example/hevc.mp4", "h265"),
                            ),
                        )
                        .toString()
                }
                else -> error("unexpected request ${request.url}")
            }
            response(request, body)
        }.build()
        val source = MobilePlaybackStreamSource(http, session, { now }, signer)

        val tiers = source.ladder(episode, 1080, ContentType.SHORT)

        assertEquals("h265", tiers.single().codec)
        assertEquals("https://cdn.example/hevc.mp4", tiers.single().url)
        assertEquals(2, requests.size)
        assertEquals("vas.example.org", requests[1].url.host)
    }

    @Test
    fun productionChainStartsWithCurrentAppChannel() {
        val http = OkHttpClient()
        val names = StreamSource.default(
            AppApiTransport(http), http, "UA", "https://example.test",
        ).map { it.name }
        assertEquals("mobile-v5", names.first())
        assertEquals(
            listOf("mobile-v5", "multi-video-model", "app-api", "web-fallback", "public-mp4"),
            names,
        )
    }

    /**
     * Upstream v1.2.33 `SpareDevice`: when the main identity's tab GET answers
     * empty on every host, the read moves to a pre-registered spare identity
     * instead of failing the whole episode switch.
     */
    @Test
    fun emptyMainTabAnswersAreRetriedOnASpareIdentity() {
        val requests = mutableListOf<Request>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            requests += request
            val isSpare = request.url.queryParameter("device_id") == "999999"
            val body = when {
                isSpare -> tabResponse()
                else -> ""
            }
            response(request, body)
        }.build()
        // The session registers the main identity first and the spare second.
        var registrations = 0
        val session = PlaybackDeviceSession {
            registrations++
            if (registrations == 1) DeviceRegister.Registered("123456", "654321")
            else DeviceRegister.Registered("999999", "888888")
        }
        val source = MobilePlaybackStreamSource(http, session, { now }, signer)

        val tiers = source.ladder(episode, 1080, ContentType.SHORT)

        assertEquals("1080P", tiers.single().quality)
        assertTrue(tiers.single().url.contains("ep4.mp4"))
        // The spare identity is used only when the main identity's tab fails.
        val spareUsed = requests.any { it.url.queryParameter("device_id") == "999999" }
        assertTrue(spareUsed)
    }

    private fun tabResponse(): String = JSONObject()
        .put("code", 0)
        .put(
            "data",
            JSONObject().put(
                "video_model_data",
                JSONObject().put(episode, JSONObject().put("video_model", model("https://cdn.example/ep4.mp4"))),
            ),
        )
        .toString()

    private fun model(url: String, codec: String = "h264"): JSONObject = JSONObject().put(
        "video_list",
        JSONArray().put(
            JSONObject()
                .put(
                    "video_meta",
                    JSONObject()
                        .put("codec_type", codec)
                        .put("vwidth", 1920)
                        .put("vheight", 1080)
                        .put("definition", "1080p"),
                )
                .put("main_url", url),
        ),
    )

    private fun response(request: Request, body: String) = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(200)
        .message("OK")
        .body(body.toResponseBody())
        .build()
}
