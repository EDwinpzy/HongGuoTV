package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.Call
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Delayed hedging: first usable route wins, slow/empty routes never block it. */
class HedgedCallTest {
    private val hits = AtomicInteger()
    private val cancelled = AtomicInteger()
    private val releaseSlow = CountDownLatch(1)

    /** [behaviour] maps a request path to a stub answer; "hang" blocks until cancelled. */
    private fun client(behaviour: (String) -> String): OkHttpClient =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val path = chain.request().url.encodedPath
            hits.incrementAndGet()
            val answer = behaviour(path)
            if (answer == "hang") {
                try {
                    // Park until the test releases it or the hedged winner cancels us.
                    while (!releaseSlow.await(50, TimeUnit.MILLISECONDS)) {
                        if (chain.call().isCanceled()) {
                            cancelled.incrementAndGet()
                            throw IOException("Canceled")
                        }
                    }
                } catch (interrupted: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IOException("interrupted")
                }
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(answer.toResponseBody())
                .build()
        }).build()

    private fun routes(vararg paths: String, http: OkHttpClient): List<() -> Call> =
        paths.map { path ->
            val request = Request.Builder().url("https://edge.test$path").build()
            return@map { http.newCall(request) }
        }

    private val probe: (Response) -> HedgedCall.Verdict<String> = { response ->
        val text = response.body!!.string()
        if (text.isEmpty()) HedgedCall.Verdict.Empty else HedgedCall.Verdict.Usable(text)
    }

    @Test
    fun fastFirstRouteWinsAloneAndSendsNoExtraRequest() {
        val http = client { "ok" }
        val result = HedgedCall.run(routes("/a", "/b", "/c", http = http), 1_200, { null }, probe)
        assertEquals("ok", result)
        assertEquals("healthy path costs exactly one request", 1, hits.get())
    }

    @Test
    fun hungFirstRouteStartsTheSecondAfterTheDelayAndCancelsTheFirst() {
        val http = client { path -> if (path == "/a") "hang" else "second" }
        val started = System.nanoTime()
        val result = HedgedCall.run(routes("/a", "/b", http = http), 150, { null }, probe)
        val elapsedMs = (System.nanoTime() - started) / 1_000_000
        assertEquals("second", result)
        assertTrue("second route must wait for the hedge delay, took ${elapsedMs}ms", elapsedMs >= 120)
        assertEquals(2, hits.get())
        // The loser is cancelled rather than left running.
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (cancelled.get() == 0 && System.nanoTime() < deadline) Thread.sleep(20)
        assertEquals("hung loser must be cancelled", 1, cancelled.get())
        releaseSlow.countDown()
    }

    @Test
    fun emptyFirstRouteDoesNotWinAndTheSecondRoutesAnswerIsUsed() {
        val http = client { path -> if (path == "/a") "" else "fresh" }
        val result = HedgedCall.run(routes("/a", "/b", http = http), 150, { null }, probe)
        assertEquals("fresh", result)
        assertEquals(2, hits.get())
    }

    @Test
    fun allRoutesEmptyReturnsNullSoTheCallerKeepsItsEmptySemantics() {
        val http = client { "" }
        val result = HedgedCall.run(routes("/a", "/b", http = http), 10, { null }, probe)
        assertNull(result)
        assertEquals(2, hits.get())
    }

    @Test
    fun allRoutesFailingSurfacesTheLastFailure() {
        val http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            hits.incrementAndGet()
            throw IOException("route down ${chain.request().url.encodedPath}")
        }).build()
        try {
            HedgedCall.run(routes("/a", "/b", http = http), 10, { null }, probe)
            throw AssertionError("expected IOException")
        } catch (expected: IOException) {
            assertEquals("route down /b", expected.message)
        }
        assertEquals(2, hits.get())
    }

    @Test
    fun exhaustedBudgetStopsInsteadOfWaitingForTheHedgeDelay() {
        val http = client { "hang" }
        val deadline = System.nanoTime() - 1
        try {
            HedgedCall.run(routes("/a", "/b", http = http), 5_000, { deadline }, probe)
            throw AssertionError("expected InterruptedIOException")
        } catch (expected: InterruptedIOException) {
            assertNotNull(expected.message)
        }
        releaseSlow.countDown()
    }
}
