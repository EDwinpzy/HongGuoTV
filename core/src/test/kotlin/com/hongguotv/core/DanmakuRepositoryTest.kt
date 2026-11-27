package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuRepositoryTest {
    @Test fun `list cursor is returned and sent with the following request`() {
        val requests = mutableListOf<JSONObject>()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val payload = JSONObject(chain.request().body!!.let { body ->
                okio.Buffer().also(body::writeTo).readUtf8()
            })
            requests += payload
            val response = JSONObject().put("code", 0).put("data", JSONObject()
                .put("data_list", org.json.JSONArray())
                .put("extra", JSONObject().put("next_query_danmaku_list_time", 30_000))
                .put("common_list_info", JSONObject().put("cursor", "next-page").put("has_more", true)))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build()
        val repository = DanmakuRepository(http, AppApiTransport(http))

        val page = repository.page("123", "456", 0, 90_000)
        repository.page("123", "456", page.nextCursorMs, 90_000, page.cursor)

        assertEquals("next-page", page.cursor)
        assertTrue(page.hasMore)
        assertEquals("", requests[0].getString("cursor"))
        assertEquals("next-page", requests[1].getString("cursor"))
        assertEquals(30_000, requests[1].getJSONObject("business_param").getLong("start_offset_time"))
    }

    @Test fun `absent list metadata remains a terminal page`() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("""{"code":0,"data":{"data_list":[],"extra":{"next_query_danmaku_list_time":600000}}}""".toResponseBody()).build()
        }.build()
        val page = DanmakuRepository(http, AppApiTransport(http)).page("123", "456", 0, 600_000)
        assertEquals("", page.cursor)
        assertFalse(page.hasMore)
        assertEquals(-1, page.total)
        assertFalse(page.isEmptyButHasComments())
    }

    @Test fun `empty page with a reported comment count is retryable`() {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val response = JSONObject().put("code", 0).put("data", JSONObject()
                .put("data_list", org.json.JSONArray())
                .put("extra", JSONObject().put("next_query_danmaku_list_time", 60_000))
                .put("common_list_info", JSONObject()
                    .put("cursor", "window-0").put("has_more", false).put("total", 8)))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build()

        val page = DanmakuRepository(http, AppApiTransport(http)).page("123", "456", 0, 60_000)

        assertEquals(8, page.total)
        assertFalse(page.hasMore)
        assertTrue(page.isEmptyButHasComments())
    }

    @Test fun `cursor window count catches an empty page when total is zero`() {
        val cursor = """{"start_offset_time":0,"end_offset_time":60000,"danmaku_window_count":{"0":1,"30000":9},"danmaku_count":10}"""
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val response = JSONObject().put("code", 0).put("data", JSONObject()
                .put("data_list", org.json.JSONArray())
                .put("extra", JSONObject().put("next_query_danmaku_list_time", 60_000))
                .put("common_list_info", JSONObject()
                    .put("cursor", cursor).put("has_more", false).put("total", 0)))
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(response.toString().toResponseBody()).build()
        }.build()

        val page = DanmakuRepository(http, AppApiTransport(http)).page("123", "456", 0, 60_000)

        assertEquals(0, page.total)
        assertEquals(1, page.windowCommentCount)
        assertTrue(page.isEmptyButHasComments())
    }
}
