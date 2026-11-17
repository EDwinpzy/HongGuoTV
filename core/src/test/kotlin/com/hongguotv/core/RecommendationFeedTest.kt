// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationFeedTest {
    private fun feedBody(ids: List<String>,session: String="recommend-session") = JSONObject()
        .put("code",0)
        .put("data",JSONObject().put("tab_item",JSONArray().put(
            JSONObject()
                .put("tab_type",16)
                .put("session_id",session)
                .put("cell_data",JSONArray().put(
                    JSONObject().put("cell_data",JSONArray().put(
                        JSONObject().put("video_data",JSONArray().apply {
                            ids.forEachIndexed { index,id ->
                                put(JSONObject()
                                    .put("series_id",id)
                                    .put("series_title","推荐$id")
                                    .put("series_cover","https://example.com/$id.jpg")
                                    .put("episode_cnt",index+10))
                            }
                        })
                    ))
                ))
        )))
        .toString()

    @Test fun accountFeedUsesOfficialSignedPagingAndCookie() {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            requests+=request
            val offset=request.url.queryParameter("offset")?.toIntOrNull() ?: 0
            val body=feedBody(if(offset==0) listOf("101","102") else listOf("103","104"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val device=PlaybackDeviceSession {
            DeviceRegister.Registered("1905892595378490","1905892595382586")
        }
        val feed=RecommendationFeed(
            http,
            AccountCredentials("session=real-account"),
            pageTarget=2,
            playbackSession=device,
            clock={ 1_760_000_000_000L },
        )

        val first=feed.page(1)
        val second=feed.page(2)

        assertEquals(listOf("101","102"),first.items.map { it.id })
        assertEquals(listOf("103","104"),second.items.map { it.id })
        assertEquals(2,requests.size)
        val firstRequest=requests.first()
        assertEquals("/reading/bookapi/bookmall/tab/v1/",firstRequest.url.encodedPath)
        assertEquals("74332",firstRequest.url.queryParameter("version_code"))
        assertEquals("16",firstRequest.url.queryParameter("tab_type"))
        assertEquals("0",firstRequest.url.queryParameter("offset"))
        assertEquals("2",requests[1].url.queryParameter("offset"))
        assertEquals("1905892595378490",firstRequest.url.queryParameter("device_id"))
        assertEquals("session=real-account",firstRequest.header("Cookie"))
        assertNotNull(firstRequest.header("x-argus"))
        assertTrue(firstRequest.url.host.endsWith(".fqnovel.com"))
    }

    @Test fun aggregatingSmallServerPagesKeepsCursorAndDeduplicates() {
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            calls++
            val ids=when(calls) {
                1 -> listOf("201")
                2 -> listOf("201","202")
                3 -> listOf("203")
                else -> listOf("204","205")
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(feedBody(ids).toResponseBody()).build()
        }.build()
        val feed=RecommendationFeed(
            http,
            AccountCredentials("session=real-account"),
            pageTarget=5,
            maxRounds=4,
            playbackSession=PlaybackDeviceSession {
                DeviceRegister.Registered("1905892595378490","1905892595382586")
            },
            clock={ 1_760_000_000_000L },
        )

        val result=feed.page(1)

        assertEquals(listOf("201","202","203","204","205"),result.items.map { it.id })
        assertEquals(4,calls)
        assertEquals(2,feed.cursor.expectedPage)
    }

    @Test fun fetchedPagesStayWarmAfterPagingAdvances() {
        val requests=mutableListOf<Request>()
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            requests+=request
            val offset=request.url.queryParameter("offset")?.toIntOrNull() ?: 0
            val body=feedBody(if(offset==0) listOf("301","302") else listOf("303","304"))
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val feed=RecommendationFeed(
            http,
            AccountCredentials("session=real-account"),
            pageTarget=2,
            playbackSession=PlaybackDeviceSession {
                DeviceRegister.Registered("1905892595378490","1905892595382586")
            },
            clock={ 1_760_000_000_000L },
        )

        val first=feed.page(1)
        val second=feed.page(2)
        val reopened=feed.page(1)
        val reopenedSecond=feed.page(2)

        assertEquals(listOf("301","302"),first.items.map { it.id })
        assertEquals(first.items.map { it.id },reopened.items.map { it.id })
        assertEquals(second.items.map { it.id },reopenedSecond.items.map { it.id })
        assertEquals(2,requests.size)
    }
}
