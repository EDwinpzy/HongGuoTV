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
    }
}
