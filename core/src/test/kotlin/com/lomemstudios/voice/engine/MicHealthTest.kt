package com.lomemstudios.voice.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MicHealthTest {
    /** Feeds [frames] 50 ms frames and returns every event reported. */
    private fun MicWatch.feed(frames: Int, zero: Boolean): List<MicHealth> = (1..frames).mapNotNull { frame(zero, 50) }

    @Test fun soundIsHealthyOnce() {
        val w = MicWatch()
        assertEquals(listOf(MicHealth.Healthy), w.feed(10, zero = false))
    }

    @Test fun zerosAreBlankAfter300msThenEverySecond() {
        val w = MicWatch()
        w.feed(4, zero = false)
        assertEquals(listOf(MicHealth.Blank(300), MicHealth.Blank(1300), MicHealth.Blank(2300)), w.feed(50, zero = true))
        assertEquals(listOf(MicHealth.Healthy), w.feed(2, zero = false))
    }

    @Test fun zerosFromTheFirstFrameAreBlank() {
        assertEquals(listOf(MicHealth.Blank(300)), MicWatch().feed(6, zero = true))
    }

    @Test fun deadAndTakenAreReportedOnceAndHideBlank() {
        val w = MicWatch()
        w.feed(4, zero = false)
        assertEquals(MicHealth.Dead("read error -6"), w.dead("read error -6"))
        assertNull(w.dead("read error -6 again"))
        assertTrue(w.feed(40, zero = true).isEmpty())
        assertEquals(MicHealth.Taken("silenced"), w.taken("silenced"))
        assertTrue(w.feed(40, zero = true).isEmpty())
        assertEquals(listOf(MicHealth.Healthy), w.feed(1, zero = false))
    }

    @Test fun backoffDoublesToItsCapAndResets() {
        val b = Backoff(500, 8000)
        assertEquals(listOf(500L, 1000L, 2000L, 4000L, 8000L, 8000L), (1..6).map { b.next() })
        b.reset()
        assertEquals(500L, b.next())
    }

    @Test fun allZeroIsExact() {
        assertTrue(allZero(ShortArray(800)))
        assertFalse(allZero(ShortArray(800).also { it[799] = 1 }))
        assertFalse(allZero(ShortArray(0)))
    }
}
