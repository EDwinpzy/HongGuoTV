// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import okhttp3.Call
import okhttp3.Response

/**
 * Delayed hedging for one logical read across equivalent routes (upstream desktop
 * client's `race`: staggered starts, first success wins, the rest are cancelled).
 *
 * Route 0 is sent at once. If it has not produced a USABLE answer within
 * [hedgeDelayMs], route 1 is started, then route 2 one delay later, and so on.
 * A route that answers EMPTY does not win: an empty body is the server's
 * risk-control signal, not a slow route, so the other routes still get their
 * chance. The first non-empty answer cancels every call still in flight.
 *
 * When route 0 answers before the delay, no other request is ever sent - the
 * common (healthy) case costs exactly one request, same as before.
 */
internal object HedgedCall {
    /** One route's verdict. */
    sealed class Verdict<out T> {
        data class Usable<T>(val value: T) : Verdict<T>()
        object Empty : Verdict<Nothing>()
        data class Failed(val error: IOException) : Verdict<Nothing>()
    }

    /**
     * @param routes ordered request factories; a route is only built (and sent)
     *   once its stagger slot arrives, so a fast first answer never builds the rest.
     * @param probe runs on the dispatcher thread: returns the parsed value, or
     *   [Verdict.Empty] for a well-formed but empty answer.
     * @return the first usable value, or null when every route answered empty.
     * @throws IOException the last failure when no route was usable or empty
     * @throws InterruptedIOException when the playback budget or scope was cancelled
     */
    fun <T> run(
        routes: List<() -> Call>,
        hedgeDelayMs: Long,
        deadlineNanos: () -> Long?,
        probe: (Response) -> Verdict<T>,
    ): T? {
        if (routes.isEmpty()) throw IOException("没有可用的播放路由")
        val results = LinkedBlockingQueue<Pair<Int, Verdict<T>>>()
        val inFlight = mutableSetOf<Call>()
        var launched = 0
        var failures = 0
        var lastError: IOException? = null
        var empties = 0

        fun cancelAll() {
            synchronized(inFlight) { inFlight.forEach { it.cancel() } }
        }

        fun launch(index: Int) {
            // Count the route as launched even if its request cannot be built, so
            // the "every route answered" check below stays exact.
            launched++
            val call = try {
                routes[index]()
            } catch (error: IOException) {
                results.offer(index to Verdict.Failed(error))
                return
            }
            synchronized(inFlight) { inFlight += call }
            RequestScope.enqueue(call, { response ->
                val verdict = probe(response)
                results.offer(index to verdict)
            }, { error ->
                if (error != null) results.offer(index to Verdict.Failed(error))
            })
        }

        try {
            launch(0)
            var nextSlot = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hedgeDelayMs)
            while (true) {
                val deadline = deadlineNanos()
                val budgetLeft = deadline?.let { it - System.nanoTime() }
                if (budgetLeft != null && budgetLeft <= 0) {
                    cancelAll()
                    throw InterruptedIOException("获取播放地址超时，请稍后重试")
                }
                if (Thread.currentThread().isInterrupted) {
                    cancelAll()
                    throw InterruptedIOException("播放请求已取消")
                }
                val now = System.nanoTime()
                val waitHedge = if (launched < routes.size) (nextSlot - now).coerceAtLeast(0) else Long.MAX_VALUE
                val wait = minOf(waitHedge, budgetLeft ?: Long.MAX_VALUE)
                val received = if (wait == Long.MAX_VALUE) {
                    results.take()
                } else {
                    results.poll(wait, TimeUnit.NANOSECONDS)
                }
                if (received == null) {
                    if (launched < routes.size && System.nanoTime() >= nextSlot) {
                        launch(launched)
                        nextSlot = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(hedgeDelayMs)
                    }
                    continue
                }
                when (val verdict = received.second) {
                    is Verdict.Usable -> {
                        cancelAll()
                        return verdict.value
                    }
                    Verdict.Empty -> empties++
                    is Verdict.Failed -> {
                        failures++
                        lastError = verdict.error
                        if (verdict.error is InterruptedIOException && Thread.currentThread().isInterrupted) {
                            cancelAll()
                            throw verdict.error
                        }
                    }
                }
                // Every route has answered and none was usable.
                if (empties + failures >= launched && launched >= routes.size) {
                    cancelAll()
                    return if (empties > 0) null else throw lastError ?: IOException("播放接口没有可用路由")
                }
            }
        } finally {
            cancelAll()
        }
    }
}
