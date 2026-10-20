package com.lomemstudios.voice.enrol

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEnrolmentTest {
    /** 50 ms frames, as VoiceEngine gives them; the level is passed beside them as the engine does. */
    private val frame = 800

    private fun pcm(clipped: Boolean = false) = ShortArray(frame) { i ->
        if (clipped) (if (i % 2 == 0) 32767 else -32768).toShort() else (if (i % 2 == 0) 3000 else -3000).toShort()
    }

    /** Feeds [seconds] of frames at [db]; every piece that ended. */
    private fun SpeechEnrolment.play(seconds: Double, db: Float, clipped: Boolean = false): List<Piece> =
        (0 until (seconds * 20).toInt()).mapNotNull { feed(pcm(clipped), db) }

    @Test
    fun theFieldTestsSpeechCounts() {
        // Field log, 20 October 08:11: speech at -50 dB over a floor of -64 dB was refused as "too quiet", and a 2.65 s
        // sample as "too long". Both count now.
        val e = SpeechEnrolment()
        assertTrue(e.play(2.0, -64f).isEmpty())
        val pieces = e.play(2.65, -50f) + e.play(1.0, -64f)
        assertEquals(1, pieces.size)
        assertEquals(PieceResult.ACCEPTED, pieces[0].result)
        assertEquals(2.65, pieces[0].speechS, 0.051)
        assertEquals(2.65, e.seconds, 0.051)
        assertTrue(e.noteOk)
        assertEquals("Good, keep going.", e.note)
    }

    @Test
    fun speechJustOverTheFloorIsTooQuiet() {
        val e = SpeechEnrolment()
        e.play(2.0, -60f)
        val pieces = e.play(2.0, -52f) + e.play(1.0, -60f)  // 8 dB over: speech, but under 10 dB on average
        assertEquals(listOf(PieceResult.TOO_QUIET), pieces.map { it.result })
        assertEquals(0.0, e.seconds, 0.0)
        assertFalse(e.noteOk)
        assertEquals("A bit quiet: speak up, or hold the phone closer.", e.note)
    }

    @Test
    fun clippedSpeechIsTooLoud() {
        val e = SpeechEnrolment()
        e.play(2.0, -60f)
        val pieces = e.play(2.0, -1f, clipped = true) + e.play(1.0, -60f)
        assertEquals(listOf(PieceResult.TOO_LOUD), pieces.map { it.result })
        assertEquals(0.0, e.seconds, 0.0)
        assertEquals("Too loud: hold the phone a little further away.", e.note)
    }

    @Test
    fun talkingOnIsCutIntoPiecesAndNeverTooLong() {
        val e = SpeechEnrolment()
        e.play(1.0, -60f)
        val pieces = e.play(17.0, -35f)
        assertEquals(listOf(PieceResult.ACCEPTED, PieceResult.ACCEPTED), pieces.map { it.result })
        assertEquals(16.0, e.seconds, 0.051)
        assertTrue(e.enough)
        assertFalse(e.full)
    }

    @Test
    fun progressCountsAcceptedSpeechToFifteenSecondsAndEndsAtTwenty() {
        val e = SpeechEnrolment()
        e.play(1.0, -60f)
        e.play(5.0, -35f); e.play(1.0, -60f)
        assertEquals(5.0 / 15.0, e.progress, 0.01)
        assertEquals("5 of 15 s", e.progressLine())
        assertFalse(e.enough)
        e.play(5.0, -35f); e.play(1.0, -60f)
        e.play(5.0, -35f); e.play(1.0, -60f)
        assertTrue(e.enough)
        assertEquals(1.0, e.progress, 0.0)
        assertEquals("15 of 15 s", e.progressLine())
        assertEquals("That's enough. Tap Done, or keep talking.", e.note)
        assertFalse(e.full)
        e.play(5.0, -35f); e.play(1.0, -60f)
        assertTrue(e.full)
        assertEquals("That's all I need. Thank you.", e.note)
        assertEquals(4, e.pieces.size)
    }

    @Test
    fun speechDuringTheGuideNeverCounts() {
        // The guide's own voice or a tone coming back: the app holds while they play, which drops the open piece.
        val e = SpeechEnrolment()
        e.play(1.0, -60f)
        e.play(1.5, -30f)
        e.hold()
        val after = e.play(1.0, -60f)
        assertTrue(after.isEmpty())
        assertEquals(0.0, e.seconds, 0.0)
        assertTrue(e.pieces.isEmpty())
    }

    @Test
    fun theRoomCheckComesFirst() {
        // The first second only measures the room: speech in it is not a piece, and sets no floor of its own.
        val e = SpeechEnrolment()
        assertTrue(e.play(1.0, -60f).isEmpty())
        assertTrue(e.roomChecked)
        assertEquals(-60f, e.floorDB, 0f)
    }

    @Test
    fun aClickIsIgnoredWithoutAWord() {
        val e = SpeechEnrolment()
        e.play(1.0, -60f)
        val note = e.note
        assertTrue((e.play(0.2, -30f) + e.play(1.0, -60f)).isEmpty())
        assertEquals(note, e.note)
    }

    @Test
    fun silenceAsksToSpeakUp() {
        val e = SpeechEnrolment()
        e.play(1.0, -60f)  // the room check
        e.play(7.95, -60f)
        assertEquals("Start whenever you are ready.", e.note)
        e.play(0.05, -60f)
        assertEquals("I can't hear you. Speak up, or hold the phone closer.", e.note)
        assertFalse(e.noteOk)
    }

    @Test
    fun aNoisyRoomIsSaidAndSpeechStillCounts() {
        val e = SpeechEnrolment()
        e.play(1.5, -30f)
        assertEquals("It's noisy here. Somewhere quieter works better, but go on.", e.note)
        val pieces = e.play(3.0, -12f) + e.play(1.0, -30f)
        assertEquals(listOf(PieceResult.ACCEPTED), pieces.map { it.result })
    }

    @Test
    fun doneEndsAnOpenPiece() {
        val e = SpeechEnrolment()
        e.play(1.0, -60f)
        e.play(2.0, -35f)
        val last = e.finish()!!
        assertEquals(PieceResult.ACCEPTED, last.result)
        assertEquals(2.0, e.seconds, 0.051)
        assertNull(e.finish())
    }
}
