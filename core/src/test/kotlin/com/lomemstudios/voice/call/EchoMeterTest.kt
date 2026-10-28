package com.lomemstudios.voice.call

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EchoMeterTest {
    @Test
    fun anItemPlayedOverACancellerReadsNearTheFloor() {
        val m = EchoMeter()
        m.start("0", floorDB = -52f)
        repeat(40) { m.level(-50f) }
        val r = m.stop()!!
        assertEquals(-50f, r.medianDB, 0.01f)
        assertEquals(2f, r.overFloorDB, 0.01f)
        assertEquals(40, r.frames)
    }

    @Test
    fun theMedianIgnoresABriefWordOfTheUser() {
        // An echo 24 dB over the floor, and the user saying something short over a few frames.
        val m = EchoMeter()
        m.start("1", floorDB = -52f)
        repeat(30) { m.level(-28f) }
        repeat(6) { m.level(-12f) }
        assertEquals(24f, m.stop()!!.overFloorDB, 0.01f)
    }

    @Test
    fun framesWhileTheUserSpeaksAreLeftOut() {
        val m = EchoMeter()
        m.start("2", floorDB = -50f)
        repeat(30) { m.level(-45f) }
        repeat(30) { m.level(-10f, userSpeaking = true) }
        val r = m.stop()!!
        assertEquals(30, r.frames)
        assertEquals(5f, r.overFloorDB, 0.01f)
    }

    @Test
    fun lessThanASecondTellsNothing() {
        val m = EchoMeter()
        m.start("p1", floorDB = -50f)
        repeat(19) { m.level(-30f) }
        assertNull(m.stop())
    }

    @Test
    fun levelsWithNothingPlayingAreNotCounted() {
        val m = EchoMeter()
        repeat(40) { m.level(-30f) }
        assertNull(m.stop())
        m.start("3", floorDB = -50f)
        repeat(20) { m.level(-40f) }
        assertEquals(20, m.stop()!!.frames)
        assertNull(m.stop())
    }

    @Test
    fun theSessionSummaryIsTheMedianOfItems() {
        val m = EchoMeter()
        for (over in listOf(3f, 5f, 30f)) {
            m.start("i", floorDB = -50f)
            repeat(20) { m.level(-50f + over) }
            m.stop()
        }
        assertEquals(5f, m.sessionMedianDB()!!, 0.01f)
        assertEquals(3, m.items)
    }
}
