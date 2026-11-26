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

    /** Absolute deadline in System.nanoTime() units; null when no budget is active. */
    fun deadlineNanos(): Long? = remaining.get()
}

/**
 * Public wrapper around the playback budget for the app layer: the ladder read
 * and the MP4 header probes of one episode switch must share ONE deadline, so a
 * slow tier's header read can never push the switch past the user-facing budget.
 */
object PlaybackBudget {
    fun <T> run(timeoutMs: Long, action: () -> T): T = PlaybackRequestBudget.run(timeoutMs, action)

    /** Remaining budget of the current thread's playback deadline; null when none is active. */
    fun remainingMillis(): Long? = PlaybackRequestBudget.remainingMillis()
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

        /**
         * Async twin of [execute] for hedged requests: the call is attached to the
         * requesting scope from the calling thread (so scope.cancel() still reaches
         * it), then runs on OkHttp's dispatcher. [consume] runs on the dispatcher
         * thread and its result/exception is delivered to [onDone].
         */
        fun enqueue(call: Call, consume: (Response) -> Unit, onDone: (IOException?) -> Unit) {
            val owner = current.get()
            try {
                owner?.attach(call)
            } catch (error: IOException) {
                onDone(error)
                return
            }
            call.enqueue(object : okhttp3.Callback {
                override fun onFailure(call: Call, e: IOException) {
                    owner?.detach(call)
                    onDone(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        response.use(consume)
                        onDone(null)
                    } catch (error: IOException) {
                        onDone(error)
                    } catch (error: RuntimeException) {
                        onDone(IOException("播放接口返回了无法解析的内容", error))
                    } finally {
                        owner?.detach(call)
                    }
                }
            })
        }
    }
}
