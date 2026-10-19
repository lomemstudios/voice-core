package com.lomemstudios.voice.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Scores are cosine similarities against the user's print, as TitaNet small gave them on Claude Voice's test set
 *  (17 October): on 3 s of speech the user's median 0.63, other voices at most 0.41, Claude from the car speakers at
 *  most 0.31; on 2 s the user 0.58, others up to 0.44. */
class SpeakerCheckTest {
    private val user = 0.63f
    private val stranger = 0.21f
    private val answerFromTheCar = 0.18f

    @Test
    fun theUsersTurnIsKeptAndAnotherVoiceIsNot() {
        val check = SpeakerCheck()
        assertEquals(TurnVerdict.KEEP, check.turn(user))
        assertEquals(TurnVerdict.OTHER_VOICE, check.turn(stranger))
        assertEquals(TurnVerdict.OTHER_VOICE, check.turn(answerFromTheCar))
    }

    @Test
    fun withoutAVoicePrintEveryTurnIsKeptAndMayCutIn() {
        val check = SpeakerCheck()
        assertEquals(TurnVerdict.KEEP, check.turn(null))
        assertEquals(TurnVerdict.KEEP, check.turn(null, seconds = 0.5))
        assertTrue(check.bargeIn(null))
    }

    @Test
    fun eachLengthHasItsOwnThreshold() {
        // Just under and at each step: 3 s and more 0.32, from 2 s 0.23, from 1.5 s 0.16, shorter 0.12.
        val check = SpeakerCheck()
        for ((seconds, needed) in listOf(3.0 to 0.32f, 5.0 to 0.32f, 2.0 to 0.23f, 2.9 to 0.23f, 1.5 to 0.16f,
            1.99 to 0.16f, 1.0 to 0.12f, 0.3 to 0.12f)) {
            assertEquals("$seconds s at $needed", TurnVerdict.KEEP, check.turn(needed, seconds))
            assertEquals("$seconds s under $needed", TurnVerdict.OTHER_VOICE, check.turn(needed - 0.001f, seconds))
        }
    }

    @Test
    fun theLongThresholdsFollowTheSettings() {
        val check = SpeakerCheck(turnThreshold = 0.5f, bargeInThreshold = 0.4f)
        assertEquals(TurnVerdict.OTHER_VOICE, check.turn(0.45f, seconds = 3.0))
        assertEquals(TurnVerdict.KEEP, check.turn(0.45f, seconds = 2.0))
        assertFalse(check.bargeIn(0.39f))
        assertTrue(check.bargeIn(0.4f))
    }

    @Test
    fun anAnswerFromTheSpeakersNeverCutsIn() {
        val check = SpeakerCheck()
        assertFalse(check.bargeIn(answerFromTheCar))
        assertTrue(check.bargeIn(0.58f))
    }
}
