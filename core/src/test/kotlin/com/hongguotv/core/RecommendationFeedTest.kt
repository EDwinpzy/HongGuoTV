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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationFeedTest {
    private fun feedBody(
        ids: List<String>,
        session: String = "recommend-session",
        vertical: Map<String, Boolean> = emptyMap(),
    ) = JSONObject()
        .put("code",0)
        .put("data",JSONObject().put("tab_item",JSONArray().put(
            JSONObject()
                .put("tab_type",16)
                .put("session_id",session)
                .put("cell_data",JSONArray().put(
                    JSONObject().put("cell_data",JSONArray().put(
                        JSONObject().put("video_data",JSONArray().apply {
                            ids.forEachIndexed { index,id ->
                                val row=JSONObject()
                                    .put("series_id",id)
                                    .put("series_title","推荐$id")
                                    .put("series_cover","https://example.com/$id.jpg")
                                    .put("episode_cnt",index+10)
                                vertical[id]?.let { value ->
                                    row.put("video_list",JSONArray().put(
                                        JSONObject().put("vertical",value)
                                    ))
                                }
                                put(row)
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

    @Test fun resetStartsNewBatchWithoutReplayingSeenItems() {
        val requests=mutableListOf<Request>()
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            requests+=request
            calls++
            val ids=when(calls) {
                1 -> listOf("301","302")
                2 -> listOf("303","304")
                else -> listOf("301","302","305","306")
            }
            val body=feedBody(ids)
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
        feed.reset()
        val refreshed=feed.page(1)

        assertEquals(listOf("301","302"),first.items.map { it.id })
        assertEquals(listOf("303","304"),second.items.map { it.id })
        assertEquals(listOf("305","306"),refreshed.items.map { it.id })
        assertEquals(3,requests.size)
        assertEquals("0",requests.last().url.queryParameter("offset"))
        assertNull(requests.last().url.queryParameter("session_id"))
    }

    @Test fun portraitEntriesAreDroppedWhileUnknownOrientationStaysEligible() {
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            val body=feedBody(
                listOf("401","402","403"),
                vertical=mapOf("401" to false,"402" to true),
            )
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val feed=RecommendationFeed(
            http,
            AccountCredentials("session=real-account"),
            pageTarget=3,
            maxRounds=1,
            playbackSession=PlaybackDeviceSession {
                DeviceRegister.Registered("1905892595378490","1905892595382586")
            },
            clock={ 1_760_000_000_000L },
        )

        val result=feed.page(1)

        assertEquals(listOf("401","403"),result.items.map { it.id })
        assertTrue(feed.cursor.seenIds().contains("402"))
    }

    @Test fun pagingContinuesPastPortraitOnlyRoundsUntilEnoughLandscapeItems() {
        val offsets=mutableListOf<String?>()
        var calls=0
        val http=OkHttpClient.Builder().addInterceptor { chain ->
            offsets+=chain.request().url.queryParameter("offset")
            calls++
            val body=when(calls) {
                1 -> feedBody(listOf("411","412"),vertical=mapOf("411" to true,"412" to true))
                2 -> feedBody(listOf("413"),vertical=mapOf("413" to true))
                else -> feedBody(listOf("414","415"),vertical=mapOf("414" to false,"415" to false))
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(body.toResponseBody()).build()
        }.build()
        val feed=RecommendationFeed(
            http,
            AccountCredentials("session=real-account"),
            pageTarget=2,
            maxRounds=3,
            playbackSession=PlaybackDeviceSession {
                DeviceRegister.Registered("1905892595378490","1905892595382586")
            },
            clock={ 1_760_000_000_000L },
        )

        val result=feed.page(1)

        assertEquals(listOf("414","415"),result.items.map { it.id })
        assertEquals(listOf("0","2","3"),offsets)
        assertEquals(3,calls)
    }
}
