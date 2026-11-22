package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DanmakuFetchPlanTest {
    @Test fun `server cursor and next time are both retained`() {
        val plan = DanmakuFetchPlan()
        assertEquals(DanmakuFetchPlan.Request(0, ""), plan.request())
        val next = plan.next(DanmakuPage(emptyList(), 30_000, cursor = "page-1", hasMore = true), 0)
        assertEquals(DanmakuFetchPlan.Request(30_000, "page-1"), next!!.request)
        assertEquals(DanmakuFetchPlan.Request(30_000, "page-1"), plan.request())
        assertEquals(DanmakuFetchPlan.Request(60_000, "page-2"), plan.next(DanmakuPage(emptyList(), 60_000, "page-2", true), 0)!!.request)
        assertEquals(DanmakuFetchPlan.Request(60_000, "page-2"), plan.request())
    }

    @Test fun `time hint at duration is not terminal while server has more`() {
        val plan = DanmakuFetchPlan()
        val next = plan.next(DanmakuPage(emptyList(), 600_000, "page-1", true), 0)
        assertEquals(DanmakuFetchPlan.Request(600_000, "page-1"), next!!.request)
        assertEquals(30_000, next.waitMs)
    }

    @Test fun `stalled time cursor advances minimally without dropping server cursor`() {
        val plan = DanmakuFetchPlan()
        assertEquals(DanmakuFetchPlan.Request(1_000, "page-1"), plan.next(DanmakuPage(emptyList(), 0, "page-1", true), 0)!!.request)
        assertEquals(DanmakuFetchPlan.Request(2_000, "page-2"), plan.next(DanmakuPage(emptyList(), -1, "page-2", true), 0)!!.request)
    }

    @Test fun `explicit server end terminates`() {
        val plan = DanmakuFetchPlan()
        assertNull(plan.next(DanmakuPage(emptyList(), 600_000), 0))
    }

    @Test fun `two minute boundary continues with returned cursor`() {
        val plan = DanmakuFetchPlan()
        var timeMs = 0L
        repeat(4) { index ->
            val next = plan.next(DanmakuPage(emptyList(), timeMs + 30_000, "page-$index", true), 0)!!
            timeMs = next.request.timeMs
            assertEquals("page-$index", next.request.cursor)
        }
        assertEquals(120_000, timeMs)
        assertEquals(150_000, plan.next(DanmakuPage(emptyList(), 150_000, "page-4", true), 120_000)!!.request.timeMs)
    }
}
