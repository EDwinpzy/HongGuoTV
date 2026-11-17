// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DanmakuTextTest {
    @Test
    fun deduper_ignores_whitespace_zero_width_and_case() {
        val deduper=DanmakuTextDeduper()

        assertTrue(deduper.isNew(" 好看 "))
        assertFalse(deduper.isNew("好看"))
        assertFalse(deduper.isNew("好 看"))
        assertFalse(deduper.isNew("好\u200B看"))
        assertTrue(deduper.isNew("好看！"))
    }

    @Test
    fun deduper_keeps_blank_comments_out() {
        val deduper=DanmakuTextDeduper()

        assertFalse(deduper.isNew("   "))
        assertFalse(deduper.isNew("\u200B"))
    }

    @Test
    fun clear_allows_a_new_episode_to_use_the_same_text() {
        val deduper=DanmakuTextDeduper()

        assertTrue(deduper.isNew("哈哈"))
        deduper.clear()
        assertTrue(deduper.isNew("哈哈"))
    }
}
