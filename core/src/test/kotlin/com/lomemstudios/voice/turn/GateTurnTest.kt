// The voice gate in the turn detector (plan step 1.5): another voice no longer holds a turn open, a turn whose track
// never opened or whose readings fall behind runs as without the gate, and with the gate no turn ends later.

package com.lomemstudios.voice.turn

import com.lomemstudios.voice.gate.GateTrack
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * Drives a detector through 50 ms buffers with a level and pVAD's probability. The readings for a buffer reach the
 * detector [readingDelay] buffers after it, as they come from the pVAD thread; the recognizer finishes [lag] after a
 * flush, because one that answers at once hides timing bugs.
 */
class GateTimeline(withGate: Boolean, configure: TurnConfig.() -> Unit = {}) {
    val detector = TurnDetector(TurnConfig().apply(configure))
    val track = if (withGate) GateTrack().also { detector.gate = it } else null
    var t = 0.0
    val events = mutableListOf<Pair<Double, TurnEvent>>()
    var lag = 0.3
    var stuck = false
    var readingDelay = 1
    /** Readings stop reaching the detector (the pVAD thread stalled). */
    var stalled = false
    private val step = 0.05
    private val queued = ArrayDeque<Pair<Int, List<Pair<Float, Double>>>>()
    private var buffer = 0
    private var flushDue: Pair<Double, Double>? = null

    init {
        run(1.0, -60f, 0f)
        events.clear()
    }

    fun user(seconds: Double) = run(seconds, -20f, 0.9f)
    fun other(seconds: Double) = run(seconds, -20f, 0.05f)
    fun quiet(seconds: Double) = run(seconds, -60f, 0.02f)
    fun say(text: String) { detector.words(text, t).forEach { events += t to it } }
    /** The first turn's numbers, kept when it ended: another voice talking on starts a turn of its own. */
    var first: TurnStats? = null
        private set

    /** Another voice talks on, its words reaching the recognizer every [every] seconds. */
    fun otherTalking(seconds: Double, words: String, every: Double = 0.4) {
        var said = words
        repeat(Math.round(seconds / every).toInt()) {
            other(every)
            said += " and more"
            say(said)
        }
    }

    fun run(seconds: Double, level: Float, p: Float) {
        repeat(Math.round(seconds / step).toInt()) {
            t += step
            buffer++
            detector.audio(level, step, t).forEach { e ->
                events += t to e
                if ((e is TurnEvent.Ended || e is TurnEvent.Dropped) && first == null) first = detector.stats
                if (e is TurnEvent.Flush && !stuck) flushDue = Pair(t + lag, e.through)
            }
            if (!stalled) queued.addLast(buffer to (0 until 5).map { i -> p to t - step + (i + 1) * 0.01 })
            while (queued.isNotEmpty() && queued.first().first <= buffer - readingDelay) {
                queued.removeFirst().second.forEach { (pr, at) -> detector.target(pr, at) }
            }
            val due = flushDue
            if (due != null && t >= due.first - 1e-9) {
                flushDue = null
                detector.caughtUp(due.second)
            }
        }
    }

    val ended get() = events.map { it.second }.filterIsInstance<TurnEvent.Ended>()
    /** When the first turn was sent or dropped. */
    val firstEndAt: Double? get() = events.firstOrNull { it.second is TurnEvent.Ended || it.second is TurnEvent.Dropped }?.first
}

class GateTurnTest {
    @Test fun anotherVoiceNoLongerHoldsTheTurnOpen() {
        val on = GateTimeline(true)
        on.user(1.5); on.say("check the build"); val stop = on.t
        on.other(4.0)
        val first = on.ended.first()
        assertEquals("check the build", first.text)
        assertEquals("gate", on.first!!.endedBy)
        assertTrue(on.first!!.gate!!.endedByGate)
        assertTrue("ended ${on.firstEndAt!! - stop} s after the user stopped", on.firstEndAt!! - stop <= 1.3)
        assertTrue("cut at the track's close, ${first.end}", first.end >= stop && first.end <= stop + 0.45)

        val off = GateTimeline(false)
        off.user(1.5); off.say("check the build"); off.other(4.0)
        assertTrue("without the gate the talker holds it until the words are still", off.firstEndAt!! - stop >= 1.9)
    }

    @Test fun wordsThatChangeWithTheTrackClosedDoNotKeepTheTurn() {
        // A recognizer that never catches up, so only the words end the turn.
        val on = GateTimeline(true) { catchUpTimeout = 30.0 }
        on.stuck = true
        on.user(1.5); on.say("check the build"); val stop = on.t
        on.otherTalking(6.0, "check the build")
        assertEquals("words still", on.first?.endedBy)
        assertTrue("ended ${on.firstEndAt!! - stop} s after the user's last word", on.firstEndAt!! - stop <= 2.3)
    }

    @Test fun aTurnWhoseTrackNeverOpenedRunsAsWithoutTheGate() {
        fun script(tl: GateTimeline) {
            tl.other(0.6); tl.say("turn"); tl.other(0.6); tl.say("turn the radio"); tl.other(0.6); tl.say("turn the radio up")
            tl.quiet(2.0)
        }
        val on = GateTimeline(true).also(::script)
        val off = GateTimeline(false).also(::script)
        assertEquals(off.ended, on.ended)
        assertEquals(off.firstEndAt, on.firstEndAt)
        assertEquals("never opened", on.first!!.gate!!.fallback)
    }

    @Test fun readingsThatFallBehindLeaveTheTurnToTheDetector() {
        fun script(tl: GateTimeline) {
            tl.user(1.5); tl.say("check the build")
            tl.other(0.5) // the track closes
            tl.stalled = true
            tl.other(3.5)
        }
        val on = GateTimeline(true).also(::script)
        val off = GateTimeline(false).also(::script)
        assertEquals("lag", on.first!!.gate!!.fallback)
        assertEquals(off.firstEndAt, on.firstEndAt)
        assertEquals("words still", on.first!!.endedBy)
    }

    @Test fun theTurnCountsItsOpenSpeechAndOtherSeconds() {
        val on = GateTimeline(true)
        on.user(1.0); on.say("check the build"); on.other(2.0)
        val g = on.first!!.gate!!
        assertEquals("open about the user's second and the hang", 1.3, g.openS, 0.1)
        assertTrue("other ${g.otherS}", g.otherS >= 0.5)
        assertTrue(g.speechS >= g.otherS + 0.9)
        assertTrue(on.detector.otherTotal >= g.otherS)
    }

    @Test fun inQuietTheSilenceStillEndsTheTurnAsBefore() {
        val on = GateTimeline(true)
        on.user(1.5); on.say("check the build"); on.quiet(2.0)
        val off = GateTimeline(false)
        off.user(1.5); off.say("check the build"); off.quiet(2.0)
        assertEquals(off.firstEndAt, on.firstEndAt)
        assertEquals("silence", on.first!!.endedBy)
    }

    @Test fun withTheGateNoTurnEndsLater() {
        val random = Random(20261020)
        repeat(300) { n ->
            val seed = random.nextInt()
            fun script(tl: GateTimeline) {
                val r = Random(seed)
                tl.lag = r.nextDouble(0.0, 0.8)
                tl.readingDelay = r.nextInt(0, 4)
                var said = "check"
                tl.user(r.nextDouble(0.3, 2.0)); tl.say(said)
                repeat(r.nextInt(1, 6)) {
                    when (r.nextInt(5)) {
                        0 -> tl.user(r.nextDouble(0.1, 1.5)).also { said += " the build"; tl.say(said) }
                        1 -> tl.other(r.nextDouble(0.1, 2.0))
                        2 -> tl.otherTalking(r.nextDouble(0.4, 2.0), said)
                        3 -> tl.quiet(r.nextDouble(0.05, 1.0))
                        else -> tl.run(r.nextDouble(0.1, 1.0), -20f, r.nextFloat())
                    }
                }
                tl.quiet(4.0)
                tl.other(1.0)
                tl.quiet(70.0)
            }
            val on = GateTimeline(true).also(::script)
            val off = GateTimeline(false).also(::script)
            val a = on.firstEndAt!!
            val b = off.firstEndAt!!
            assertTrue("scenario $n (seed $seed): the gate ended the first turn at $a, without it at $b", a <= b + 1e-9)
        }
    }
}
