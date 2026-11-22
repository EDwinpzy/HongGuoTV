// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit

/** A stalled route must not consume the whole playback recovery deadline. */
internal object PlaybackRouteTransport {
    const val TIMEOUT_MS = 8_000L
    fun call(http: OkHttpClient, request: Request): Call {
        val remaining = PlaybackRequestBudget.remainingMillis()
        if (remaining != null && remaining <= 0) throw IOException("获取播放地址超时，请稍后重试")
        val timeout = remaining?.let { minOf(TIMEOUT_MS, it) } ?: TIMEOUT_MS
        return http.newCall(request).apply {
            timeout().timeout(timeout, TimeUnit.MILLISECONDS)
        }
    }
}

/** One playback-resolution deadline shared by every route and fallback source. */
internal object PlaybackRequestBudget {
    private val remaining = ThreadLocal<Long?>()

    inline fun <T> run(timeoutMs: Long, action: () -> T): T {
        val previous = remaining.get()
        val current = System.nanoTime()
        val deadline = minOf(
            current + TimeUnit.MILLISECONDS.toNanos(timeoutMs),
            previous ?: Long.MAX_VALUE,
        )
        remaining.set(deadline)
        try {
            return action()
        } finally {
            if (previous == null) remaining.remove() else remaining.set(previous)
        }
    }

    fun remainingMillis(): Long? = remaining.get()?.let {
        TimeUnit.NANOSECONDS.toMillis(it - System.nanoTime())
    }
}

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw InterruptedIOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw InterruptedIOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    companion object {
        private val current = ThreadLocal<RequestScope>()
        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try { return call.execute().use(consume) }
            finally { owner?.detach(call) }
        }
    }
}
