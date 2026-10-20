package com.lomemstudios.voice.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GateTrackTest {
    private fun feed(track: GateTrack, from: Double, seconds: Double, p: Float): Double {
        var t = from
        repeat(Math.round(seconds / 0.01).toInt()) { t += 0.01; track.reading(p, t) }
        return t
    }

    @Test fun opensAtTheThresholdNotBelow() {
        val track = GateTrack()
        track.reading(0.69f, 0.01)
        assertFalse(track.open)
        assertTrue(track.reading(0.7f, 0.02))
        assertTrue(track.open)
    }

    @Test fun staysOpenForTheHangAfterTheLastHighReading() {
        val track = GateTrack()
        var t = feed(track, 0.0, 1.0, 0.9f)
        t = feed(track, t, 0.29, 0.1f)
        assertTrue("open 0.29 s after the last high reading", track.open)
        t = feed(track, t, 0.02, 0.1f)
        assertFalse("closed 0.31 s after it", track.open)
        assertEquals(1.0 + 0.3, track.closedAt, 1e-9)
    }

    @Test fun aHighReadingInsideTheHangKeepsItOpen() {
        val track = GateTrack()
        var t = feed(track, 0.0, 0.5, 0.9f)
        t = feed(track, t, 0.2, 0.1f)
        t = feed(track, t, 0.01, 0.8f)
        feed(track, t, 0.25, 0.1f)
        assertTrue(track.open)
    }

    @Test fun theThresholdIsASetting() {
        val track = GateTrack(GateConfig().apply { threshold = 0.5f })
        track.reading(0.55f, 0.01)
        assertTrue(track.open)
    }

    @Test fun resetClosesIt() {
        val track = GateTrack()
        feed(track, 0.0, 0.5, 0.9f)
        track.reset()
        assertFalse(track.open)
        track.reading(0.1f, 0.01)
        assertFalse(track.open)
    }

    @Test fun healthTurnsTheGateOffAfterFiveAcceptedTurnsWithoutTheTrack() {
        val health = GateHealth()
        repeat(4) { assertFalse(health.accepted(false)) }
        assertFalse("an opened track starts the count again", health.accepted(true))
        repeat(4) { assertFalse(health.accepted(false)) }
        assertTrue(health.accepted(false))
        assertTrue(health.off)
    }
}
