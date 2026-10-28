// The same checks as Claude Voice's listener/Sources/TurnCheck/main.swift (claude-voice), so both ports behave alike.

package com.lomemstudios.voice.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Drives a detector through a timeline of 50 ms buffers: loud while speaking, quiet otherwise. */
class Timeline {
    val detector = TurnDetector()
    var t = 0.0
    val events = mutableListOf<TurnEvent>()
    private val step = 0.05
    /** Words held inside the recognizer until the next flush. */
    private var held: String? = null
    /** A recognizer that never reports it has caught up. */
    var stuck = false
    /** How long the recognizer takes to finish after a flush. */
    var lag = 0.0
    private var flushDue: Pair<Double, Double>? = null

    init {
        quiet(1.0)
        events.clear()
    }

    fun loud(seconds: Double) = run(seconds, -20f)
    fun quiet(seconds: Double) = run(seconds, -60f)
    /** Squeaks above the margin in three buffers of every four: the floor stays on the quiet one, and no gap is
     *  long enough to flush, so sound alone never ends the turn. */
    fun squeaks(seconds: Double) = repeat(Math.round(seconds / step).toInt()) { run(step, if (squeak++ % 4 == 3) -60f else -20f) }
    private var squeak = 0
    fun say(text: String) { events += detector.words(text, t) }
    fun sayLate(text: String) { held = text }

    private fun run(seconds: Double, level: Float) {
        repeat(Math.round(seconds / step).toInt()) {
            t += step
            val new = detector.audio(level, step, t)
            events += new
            if (!stuck) new.filterIsInstance<TurnEvent.Flush>().forEach { flushDue = Pair(t + lag, it.through) }
            val due = flushDue
            if (due != null && t >= due.first - 1e-9) {
                flushDue = null
                held?.let { held = null; say(it) }
                detector.caughtUp(due.second)
            }
        }
    }

    val ended get() = events.filterIsInstance<TurnEvent.Ended>().map { it.text }
    val endedEvents get() = events.filterIsInstance<TurnEvent.Ended>()
    val bargedIn get() = events.any { it is TurnEvent.BargeIn }
}

class TurnDetectorTest {
    @Test fun finishedSentenceEndsAfterBaseSilence() {
        val tl = Timeline()
        tl.loud(1.5); tl.say("check the last three commits"); tl.quiet(0.6)
        assertTrue(tl.ended.isEmpty())
        tl.quiet(0.2)
        assertEquals(listOf("check the last three commits"), tl.ended)
    }

    @Test fun pauseAfterAndKeepsTheTurnOpen() {
        val tl = Timeline()
        tl.loud(1.5); tl.say("check the last three commits and"); tl.quiet(1.5)
        assertTrue(tl.ended.isEmpty())
        tl.loud(1.0); tl.say("check the last three commits and tell me what changed"); tl.quiet(1.0)
        assertEquals(listOf("check the last three commits and tell me what changed"), tl.ended)
    }

    @Test fun pauseAfterUmKeepsTheTurnOpenThenEnds() {
        val tl = Timeline()
        tl.loud(1.0); tl.say("what I want is um"); tl.quiet(1.9)
        assertTrue(tl.ended.isEmpty())
        tl.quiet(0.2)
        assertEquals(listOf("what I want is um"), tl.ended)
    }

    @Test fun wordsHeldInTheRecognizerArriveBeforeTheTurnEnds() {
        val tl = Timeline()
        tl.lag = 0.8
        tl.loud(1.5); tl.say("check the commits"); tl.sayLate("check the commits and"); tl.quiet(1.0)
        assertTrue(tl.ended.isEmpty())
        tl.quiet(1.2)
        assertEquals(listOf("check the commits and"), tl.ended)
    }

    @Test fun aRecognizerThatNeverCatchesUpDelaysTheTurn() {
        val tl = Timeline()
        tl.stuck = true
        tl.loud(1.0); tl.say("list my tickets"); tl.quiet(1.5)
        assertTrue(tl.ended.isEmpty())
        tl.quiet(0.3)
        assertEquals(listOf("list my tickets"), tl.ended)
    }

    @Test fun noiseWithNoWordsIsDropped() {
        val tl = Timeline()
        tl.loud(0.3); tl.quiet(2.0)
        assertTrue(tl.ended.isEmpty())
        assertTrue(tl.events.any { it is TurnEvent.Dropped })
    }

    @Test fun aClickDoesNotStartATurn() {
        val tl = Timeline()
        tl.loud(0.05); tl.quiet(2.0)
        assertTrue(tl.events.isEmpty())
    }

    @Test fun yesIsATurnWhenNothingPlays() {
        val tl = Timeline()
        tl.loud(0.4); tl.say("yes"); tl.quiet(1.0)
        assertEquals(listOf("yes"), tl.ended)
    }

    @Test fun mmHmIsDroppedWhenNothingPlays() {
        val tl = Timeline()
        tl.loud(0.4); tl.say("mm-hm"); tl.quiet(2.0)
        assertTrue(tl.ended.isEmpty())
    }

    @Test fun backchannelDoesNotCutIntoAnAnswer() {
        val tl = Timeline()
        tl.detector.playing = true
        tl.loud(0.4); tl.say("yeah"); tl.quiet(2.0)
        assertFalse(tl.bargedIn)
        assertTrue(tl.ended.isEmpty())
    }

    @Test fun twoRealWordsCutIntoAnAnswer() {
        val tl = Timeline()
        tl.detector.playing = true
        tl.loud(0.6); tl.say("actually check")
        assertTrue(tl.bargedIn)
        tl.say("actually check the other branch"); tl.quiet(1.0)
        assertEquals(listOf("actually check the other branch"), tl.ended)
    }

    @Test fun stopCutsInOnItsOwn() {
        val tl = Timeline()
        tl.detector.playing = true
        tl.loud(0.3); tl.say("stop")
        assertTrue(tl.bargedIn)
    }

    @Test fun punctuationDoesNotHideAContinuationWord() {
        assertTrue(Words.expectsMore("check the commits and."))
        assertFalse(Words.expectsMore("check the commits."))
    }

    @Test fun digitalSilenceDoesNotLowerTheNoiseFloor() {
        val d = TurnDetector()
        var t = 0.0
        val events = mutableListOf<TurnEvent>()
        repeat(20) { t += 0.05; events += d.audio(-100f, 0.05, t) }
        repeat(40) { t += 0.05; events += d.audio(-74f, 0.05, t) }
        assertTrue(events.isEmpty())
    }

    // 17 October, 01:18: after a pause, "I'm back again" in the car became a 69 s turn. Road noise sat far
    // above the -60 dB starting floor, so every buffer counted as speech until the floor had crept up.
    @Test fun roadNoiseAtStartSetsTheNoiseFloor() {
        val d = TurnDetector()
        var t = 0.0
        val events = mutableListOf<TurnEvent>()
        fun run(seconds: Double, level: Float) =
            repeat(Math.round(seconds / 0.05).toInt()) { t += 0.05; events += d.audio(level, 0.05, t) }
        run(1.0, -40f)
        assertTrue(events.isEmpty())
        assertEquals(-40f, d.noiseFloorDB, 0.5f)
        run(1.0, -20f)
        events += d.words("I'm back again", t)
        d.caughtUp(t)
        run(1.0, -40f)
        assertEquals(listOf("I'm back again"), events.filterIsInstance<TurnEvent.Ended>().map { it.text })
    }

    // 17 October 00:02: "We can do." was a turn Petros never said. The phone's recognizer delivered the tail of
    // the turn that had ended five seconds before; with no new sound, those words started a new turn.
    @Test fun lateWordsOfAnEndedTurnStartNoTurn() {
        val tl = Timeline()
        tl.loud(1.5); tl.say("can you think of tricks we can do"); tl.quiet(1.0)
        assertEquals(listOf("can you think of tricks we can do"), tl.ended)
        tl.events.clear()
        tl.say("multiple sessions I don't know can we make a tree of sessions tricks we can do")
        tl.quiet(3.0)
        assertTrue(tl.events.none { it is TurnEvent.Started || it is TurnEvent.Ended })
        assertTrue(tl.events.any { it is TurnEvent.LateWords })
    }

    @Test fun wordsAfterNewSoundStillStartATurn() {
        val tl = Timeline()
        tl.loud(1.5); tl.say("first question"); tl.quiet(1.0)
        tl.events.clear()
        tl.loud(0.05) // new sound, too short to start a turn by sound alone
        tl.say("second question")
        tl.quiet(1.0)
        assertEquals(listOf("second question"), tl.ended)
    }

    @Test fun wordsBeforeAnyTurnStartOne() {
        val tl = Timeline()
        tl.say("hello there")
        tl.quiet(1.0)
        assertEquals(listOf("hello there"), tl.ended)
    }

    // 19 October: Mute pressed in a crowd while still talking threw the turn away ("muted"), so Claude never
    // started. Mute now ends the open turn there, as its silence would have, and the words so far go to Claude.
    @Test fun muteMidSpeechEndsTheTurnWithItsWords() {
        val tl = Timeline()
        tl.loud(1.5); tl.say("what time does the ferry leave")
        assertTrue(tl.ended.isEmpty())
        val events = tl.detector.finish(tl.t)
        assertEquals(1, events.size)
        val ended = events[0] as TurnEvent.Ended
        assertEquals("what time does the ferry leave", ended.text)
        assertEquals(1.0, ended.start, 1e-9)
        assertEquals(2.5, ended.end, 1e-9)
        assertEquals(0.0, ended.silence, 1e-9)
        assertEquals("mute", tl.detector.stats.endedBy)
        // The turn is closed: the silence after it ends nothing more.
        tl.events.clear()
        tl.quiet(2.0)
        assertTrue(tl.events.none { it is TurnEvent.Ended || it is TurnEvent.Dropped })
    }

    @Test fun muteWithNoWordsYetDropsTheTurnAsNoWords() {
        val tl = Timeline()
        tl.loud(1.0)
        val events = tl.detector.finish(tl.t)
        assertEquals(listOf<TurnEvent>(TurnEvent.Dropped("", tl.t, "no words")), events)
    }

    @Test fun muteWithNoTurnOpenDoesNothing() {
        val tl = Timeline()
        tl.loud(1.5); tl.say("first question"); tl.quiet(1.0)
        assertEquals(listOf("first question"), tl.ended)
        assertEquals(emptyList<TurnEvent>(), tl.detector.finish(tl.t))
    }

    // 19 October: squeaks and small noises above the margin reset the silence every 50 ms, so once Petros had
    // spoken the turn never ended. Words that have not changed for 2 s end it, whatever the level.
    @Test fun wordsUnderNoiseEndTwoSecondsAfterTheirLastChange() {
        val tl = Timeline()
        tl.squeaks(1.0); tl.say("check the last three commits"); tl.squeaks(1.95)
        assertTrue(tl.events.toString(), tl.events.none { it is TurnEvent.Ended || it is TurnEvent.Dropped })
        tl.squeaks(0.05)
        assertEquals(4.0, tl.t, 1e-9)
        assertEnded(tl, "check the last three commits", 1.0, 3.0, 1.0)
        assertEquals("words still", tl.detector.stats.endedBy)
    }

    @Test fun wordsUnderNoiseEndingOnAndEndThreeSecondsAfterTheirLastChange() {
        val tl = Timeline()
        tl.squeaks(1.0); tl.say("check the last three commits and"); tl.squeaks(2.95)
        assertTrue(tl.events.toString(), tl.events.none { it is TurnEvent.Ended || it is TurnEvent.Dropped })
        tl.squeaks(0.05)
        assertEquals(5.0, tl.t, 1e-9)
        assertEnded(tl, "check the last three commits and", 1.0, 3.0, 2.0)
        assertEquals("words still", tl.detector.stats.endedBy)
    }

    @Test fun wordsChangingEveryOneAndAHalfSecondsUnderNoiseKeepTheTurnOpen() {
        val tl = Timeline()
        tl.squeaks(1.0)
        for (text in listOf("I was thinking", "I was thinking about tomorrow", "I was thinking about tomorrow morning",
                "I was thinking about tomorrow morning early")) {
            tl.say(text); tl.squeaks(1.5)
        }
        tl.squeaks(0.45)
        assertTrue(tl.events.toString(), tl.events.none { it is TurnEvent.Ended || it is TurnEvent.Dropped })
        tl.squeaks(0.05)
        assertEquals(8.5, tl.t, 1e-9)
        assertEnded(tl, "I was thinking about tomorrow morning early", 1.0, 7.5, 1.0)
    }

    @Test fun aTurnOpenSixtySecondsEndsAtTheCap() {
        val tl = Timeline()
        var words = "so"
        repeat(40) { tl.squeaks(1.5); words += " far"; tl.say(words) }
        // The words changed every 1.5 s from 2.5 s on; the cap ended the turn at 61.0 s, before the 40th change.
        val capped = tl.endedEvents
        assertEquals(1, capped.size)
        assertEquals("so" + " far".repeat(39), capped[0].text)
        assertEquals(1.0, capped[0].start, 1e-9)
        assertEquals(60.5, capped[0].end, 1e-9)
        assertEquals(0.5, capped[0].silence, 1e-9)
        assertEquals("cap", tl.detector.stats.endedBy)
    }

    /** The one turn ended, its words and its times to the nanosecond: the 50 ms steps add up with rounding. */
    private fun assertEnded(tl: Timeline, text: String, start: Double, end: Double, silence: Double) {
        val ended = tl.endedEvents
        assertEquals(ended.toString(), 1, ended.size)
        assertEquals(text, ended[0].text)
        assertEquals(start, ended[0].start, 1e-9)
        assertEquals(end, ended[0].end, 1e-9)
        assertEquals(silence, ended[0].silence, 1e-9)
    }
}
