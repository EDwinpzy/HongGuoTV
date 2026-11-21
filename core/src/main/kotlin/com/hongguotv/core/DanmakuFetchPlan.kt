// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Follows the server's list cursor instead of treating its time hint as end-of-video. */
class DanmakuFetchPlan {
    data class Request(val timeMs: Long, val cursor: String)
    data class Advance(val request: Request, val waitMs: Long)

    private var timeMs = 0L

    fun request() = Request(timeMs, "")

    fun next(page: DanmakuPage, playheadMs: Long): Advance? {
        if (!page.hasMore) return null
        timeMs = if (page.nextCursorMs > timeMs) page.nextCursorMs else timeMs + FALLBACK_SPAN_MS
        val aheadMs = timeMs - playheadMs.coerceAtLeast(0L) - LOOKAHEAD_MS
        return Advance(Request(timeMs, page.cursor), aheadMs.coerceIn(0L, MAX_WAIT_MS))
    }

    private companion object {
        const val LOOKAHEAD_MS = 60_000L
        const val MAX_WAIT_MS = 30_000L
        const val FALLBACK_SPAN_MS = 1_000L
    }
}
