package com.hongguotv.core

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackRequestBudgetTest {
    @Test
    fun nestedBudgetNeverExtendsTheOuterDeadline() {
        PlaybackRequestBudget.run(1_000) {
            val outer = PlaybackRequestBudget.remainingMillis()
            assertNotNull(outer)
            PlaybackRequestBudget.run(30_000) {
                val inner = PlaybackRequestBudget.remainingMillis()
                assertNotNull(inner)
                assertTrue("inner=$inner outer=$outer", inner!! <= outer!!)
            }
        }
    }

    @Test
    fun millisecondBudgetIsNotAppliedAsNanoseconds() {
        PlaybackRequestBudget.run(1_000) {
            val remaining = PlaybackRequestBudget.remainingMillis()
            assertNotNull(remaining)
            assertTrue("remaining=$remaining", remaining!! > 900)
        }
    }
}
