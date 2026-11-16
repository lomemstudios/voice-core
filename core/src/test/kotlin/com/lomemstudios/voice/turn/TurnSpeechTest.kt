// 0.4.0's additions to the detector: the end cause, the pre-roll, the speech detector's start and barge-in, and the
// promise that nothing changes for an app that does not use them.

package com.lomemstudios.voice.turn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TurnSpeechTest {
    private val step = 0.05

    /** Feeds one detector; [p] is what the fake speech detector answers for the next frames. */
    private class Feed(val d: TurnDetector, val frames: Boolean) {
        var t = 0.0
        var p = 0.02f
        var index = 0
        val events = mutableListOf<TurnEvent>()
        fun run(seconds: Double, level: Float, p: Float = this.p, catchUp: Boolean = true) {
            this.p = p
            repeat(Math.round(seconds / 0.05).toInt()) {
                t += 0.05
                // Each sample holds its frame's number, so the pre-roll can be checked sample for sample.
                val frame = ShortArray(800) { index.toShort() }
                index++
                val new = if (frames) d.audio(frame, level, 0.05, t) else d.audio(level, 0.05, t)
                events += new
                if (catchUp) new.filterIsInstance<TurnEvent.Flush>().forEach { d.caughtUp(it.through) }
            }
        }
        fun say(text: String) { events += d.words(text, t) }
        val ends get() = events.filter { it is TurnEvent.Ended || it is TurnEvent.Dropped }
    }

    private fun speechFeed(config: TurnConfig = TurnConfig()): Feed {
        val f = Feed(TurnDetector(config), frames = true)
        f.d.speech = SpeechProbability { f.p }
        f.run(1.0, -60f)
        return f
    }

    /** The same script on levels alone and on frames with no speech detector gives the same events and numbers. */
    @Test fun framesWithoutASpeechDetectorChangeNothing() {
        fun script(f: Feed) {
            f.run(1.0, -60f)
            f.run(0.1, -10f) // a click: too short
            f.run(1.0, -60f)
            f.run(1.5, -20f); f.say("check the last three commits and"); f.run(1.0, -60f)
            f.run(1.0, -20f); f.say("check the last three commits and tell me"); f.run(1.5, -60f)
            f.run(5.0, -25f) // noise with no words
            f.run(3.0, -60f)
            f.run(2.0, -20f); f.say("still words"); repeat(30) { f.run(0.15, -20f); f.run(0.05, -60f) }
            f.run(3.0, -60f)
        }
        val levels = Feed(TurnDetector(), frames = false).also(::script)
        val frames = Feed(TurnDetector(), frames = true).also(::script)
        assertEquals(levels.events, frames.events)
        assertEquals(levels.d.stats, frames.d.stats)
        assertEquals(levels.d.noiseFloorDB, frames.d.noiseFloorDB, 0f)
        assertEquals(4, levels.ends.size)
    }

    @Test fun preRollKeepsTheLastSecondsOfFrames() {
        val f = Feed(TurnDetector(), frames = true)
        f.run(3.0, -60f) // frames 0 to 59, through t = 3.0
        val roll = f.d.preRoll()
        assertEquals(40000, roll.size) // 2.5 s at 16 kHz
        assertEquals(10, roll.first().toInt()) // frame 10 began at 0.5 s
        assertEquals(59, roll.last().toInt())
        assertEquals(0.5, f.d.preRollFrom, 1e-9)
    }

    @Test fun preRollIsEmptyOnLevelsAlone() {
        val f = Feed(TurnDetector(), frames = false)
        f.run(2.0, -60f)
        assertEquals(0, f.d.preRoll().size)
    }

    @Test fun preRollLengthIsConfigurable() {
        val c = TurnConfig().apply { preRollSeconds = 0.5 }
        val f = Feed(TurnDetector(c), frames = true)
        f.run(2.0, -60f)
        assertEquals(8000, f.d.preRoll().size)
        assertEquals(30, f.d.preRoll().first().toInt())
    }

    @Test fun silenceEndCarriesItsCauseAndSpeechEnd() {
        val f = Feed(TurnDetector(), frames = false)
        f.run(1.0, -60f)
        f.run(1.5, -20f); f.say("check the build"); f.run(1.0, -60f)
        val e = f.ends.single() as TurnEvent.Ended
        assertEquals(EndCause.SILENCE, e.cause)
        assertEquals(2.5, e.speechEndAt!!, 1e-9)
        assertEquals(e.end, e.speechEndAt!!, 1e-9)
    }

    @Test fun stillWordsMaxTurnFlushAndNoiseCarryTheirCauses() {
        // Still words: squeaks keep the sound going after the last word.
        val still = Feed(TurnDetector(), frames = false)
        still.run(1.0, -60f); still.run(1.0, -20f); still.say("check the build")
        repeat(20) { still.run(0.15, -20f); still.run(0.05, -60f) }
        val s = still.ends.first() as TurnEvent.Ended
        assertEquals(EndCause.STILL_WORDS, s.cause)
        assertEquals(s.end, s.speechEndAt!!, 1e-9)

        // Max turn: words that keep changing under steady sound.
        val cap = Feed(TurnDetector(TurnConfig().apply { maxTurn = 5.0 }), frames = false)
        cap.run(1.0, -60f)
        repeat(8) { cap.run(1.0, -20f); cap.say("word ".repeat(it + 1)) }
        assertEquals(EndCause.MAX_TURN, (cap.ends.first() as TurnEvent.Ended).cause)

        // Flush: the app ends the turn (Mute).
        val mute = Feed(TurnDetector(), frames = false)
        mute.run(1.0, -60f); mute.run(1.0, -20f); mute.say("check the build")
        val m = mute.d.finish(mute.t).single() as TurnEvent.Ended
        assertEquals(EndCause.FLUSH, m.cause)
        assertEquals(2.0, m.speechEndAt!!, 1e-9)

        // Noise: sound with no words for noWordsMaxHold, on levels alone.
        val noise = Feed(TurnDetector(), frames = false)
        noise.run(1.0, -60f); noise.run(5.0, -20f)
        val n = noise.ends.first() as TurnEvent.Dropped
        assertEquals("held by noise", n.why)
        assertEquals(EndCause.NOISE, n.cause)
    }

    @Test fun speechStartsATurnWhereTheSpeechBegan() {
        val f = speechFeed()
        f.run(0.2, -20f, 0.9f)
        assertTrue(f.events.none { it is TurnEvent.Started })
        f.run(0.05, -20f, 0.9f)
        assertEquals(1.0, f.events.filterIsInstance<TurnEvent.Started>().single().at, 1e-9)
    }

    @Test fun speechUnderHalfTheMarginStartsNoTurn() {
        val f = speechFeed()
        f.run(2.0, -55f, 0.9f) // 5 dB over the floor, under the 6 dB of half the margin
        assertTrue(f.events.none { it is TurnEvent.Started })
    }

    @Test fun noWordsTurnEndsOnSpeechSilenceNotHeldByNoise() {
        // With a speech detector, five seconds of speech with no words is a turn, not noise: no recognizer runs.
        val f = speechFeed()
        f.run(5.0, -20f, 0.9f)
        f.run(2.0, -60f, 0.02f)
        val d = f.ends.single() as TurnEvent.Dropped
        assertEquals("no words", d.why)
        assertEquals(EndCause.SILENCE, d.cause)
        assertEquals(6.0, d.speechEndAt!!, 1e-9)
    }

    @Test fun soundBargeInOnceATurnOnlyWhilePlaying() {
        val f = speechFeed()
        f.run(1.0, -20f, 0.95f) // not playing: no barge-in
        assertTrue(f.events.none { it is TurnEvent.BargeIn })
        f.run(2.0, -60f, 0.02f)
        f.d.playing = true
        f.run(0.25, -20f, 0.95f)
        assertTrue(f.events.none { it is TurnEvent.BargeIn })
        f.run(0.05, -20f, 0.95f)
        val b = f.events.filterIsInstance<TurnEvent.BargeIn>().single()
        assertTrue(b.bySound)
        assertEquals("", b.text)
        assertEquals(4.0, b.speechFrom!!, 1e-9)
        assertEquals(4.3, b.at, 1e-9)
        f.run(1.0, -20f, 0.95f)
        assertEquals(1, f.events.count { it is TurnEvent.BargeIn })
    }

    @Test fun wordBargeInIsNotBySound() {
        val f = Feed(TurnDetector(), frames = false)
        f.run(1.0, -60f)
        f.d.playing = true
        f.run(0.5, -20f); f.say("wait")
        val b = f.events.filterIsInstance<TurnEvent.BargeIn>().single()
        assertFalse(b.bySound)
        assertNull(b.speechFrom)
    }

    @Test fun newFieldsStayOutOfEquality() {
        val a = TurnEvent.Dropped("", 1.0, "no words")
        val b = TurnEvent.Dropped("", 1.0, "no words").also { it.cause = EndCause.FLUSH; it.speechEndAt = 0.5 }
        assertEquals(a, b)
    }
}
