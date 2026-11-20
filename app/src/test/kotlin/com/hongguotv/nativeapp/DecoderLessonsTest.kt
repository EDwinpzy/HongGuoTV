// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
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

    @Test fun small1080pAlignmentPaddingDoesNotRejectTheWholeLadder() {
        assertEquals(1920 to 1080,decoderProbeSize(1922,1080))
        assertEquals(1920 to 1080,decoderProbeSize(1936,1080))
        assertEquals(1280 to 720,decoderProbeSize(1282,720))
        assertEquals(1280 to 720,decoderProbeSize(1296,720))
        assertEquals(960 to 540,decoderProbeSize(962,540))
        assertEquals(960 to 540,decoderProbeSize(976,540))
        assertEquals(978 to 540,decoderProbeSize(978,540))
        assertEquals(1080 to 1920,decoderProbeSize(1080,1920))
        assertEquals(1920 to 1080,decoderProbeSize(1922,1088))
        assertEquals(1298 to 720,decoderProbeSize(1298,720))
        assertEquals(1938 to 1080,decoderProbeSize(1938,1080))
    }

    @Test fun paddedSizesShareOneLearnedFailureAndSuccessClearsIt() {
        val lessons = DecoderLessons()
        lessons.remember("hvc1", 1922, 1080)

        assertTrue(lessons.fails("bytevc1", 1920, 1080))
        assertTrue(lessons.fails("hevc", 1936, 1080))
        assertTrue(lessons.forget("bytevc1", 1920, 1080))
        assertFalse(lessons.fails("hvc1", 1922, 1080))
    }

    @Test fun firstFailureIsOnlyProbationUntilItRepeats() {
        val lessons = DecoderLessons()
        val first=lessons.observeFailure("hvc1",1922,1080)
        assertEquals("hevc|1920|1080",first?.first)
        assertFalse(first?.second ?: true)
        assertFalse(lessons.fails("bytevc1",1920,1080))

        val second=lessons.observeFailure("bytevc1",1920,1080)
        assertEquals("hevc|1920|1080",second?.first)
        assertTrue(second?.second ?: false)
        assertTrue(lessons.fails("hevc",1936,1080))
        assertEquals(setOf("hevc|1920|1080"),lessons.keys())
        assertTrue(lessons.pendingKeys().isEmpty())

        assertTrue(lessons.forget("hvc1",1922,1080))
        assertFalse(lessons.fails("hevc",1920,1080))
    }

    @Test fun successfulFrameClearsProbationToo() {
        val lessons = DecoderLessons()
        lessons.observeFailure("hvc1",1282,720)

        assertTrue(lessons.forget("bytevc1",1280,720))
        assertTrue(lessons.pendingKeys().isEmpty())
        assertFalse(lessons.fails("hevc",1280,720))
    }
}
