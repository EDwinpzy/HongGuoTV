// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderLessonsTest {
    @Test fun trackFourccAndApiNameShareOneHevcFailure() {
        val lessons = DecoderLessons()
        lessons.remember("hvc1", 1920, 1080)

        assertTrue(lessons.fails("bytevc1", 1920, 1080))
        assertTrue(lessons.fails("hevc", 1920, 1080))
        assertFalse(lessons.fails("bytevc1", 1280, 720))
        assertFalse(lessons.fails("h264", 1920, 1080))
    }

    @Test fun avcAliasesShareOneFailure() {
        val lessons = DecoderLessons()
        lessons.remember("avc1", 1920, 1080)

        assertTrue(lessons.fails("h264", 1920, 1080))
        assertTrue(lessons.fails("avc3", 1920, 1080))
        assertFalse(lessons.fails("bytevc1", 1920, 1080))
    }

    @Test fun bytevc2IsNotHevc() {
        val lessons = DecoderLessons()
        lessons.remember("bytevc2", 720, 1280)

        assertTrue(lessons.fails("bvc2", 720, 1280))
        assertFalse(lessons.fails("bytevc1", 720, 1280))
    }

    @Test fun restoreMigratesLegacyKeys() {
        val lessons = DecoderLessons()
        lessons.restore(setOf("hvc1|1920|1080", "bytevc1"))

        assertTrue(lessons.fails("bytevc1", 1920, 1080))
        assertTrue(lessons.fails("hevc", 640, 360))
        assertFalse(lessons.fails("h264", 1920, 1080))
    }
}
