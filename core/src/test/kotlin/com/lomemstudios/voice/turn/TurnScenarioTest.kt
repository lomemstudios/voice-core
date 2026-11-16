// The turn scenarios Claude Voice and the Lo Mem apps rely on (testdata/turns/*.json, CLAUDE.md "The detector is the
// reference").
//
// A scenario's steps:
//   {"user": s}, {"other": s}    a voice for s seconds: level -20 dB, speech probability 0.9 (override with "level",
//                                "p"; "gapEvery": n puts every nth frame at -60 dB, as the gaps between syllables)
//   {"noise": s}                 sound that is not the user's: give "level" and "p" (default 0.05)
//   {"quiet": s}                 -60 dB, probability 0.02
//   {"say": "words"}             the recognizer's words so far
//   {"playing": true}            an answer starts or stops playing
// "speech": true gives the detector a fake SpeechProbability that answers each frame's "p", and the frames; without
// it the detector gets levels only, as before 0.4.0. "config" sets TurnConfig fields by name.
// Expectations (all optional): "turns" (how many started), "text" and "endedBy" (the first turn sent, its stats),
// "dropped" (the first turn ended as dropped), "cause" (its EndCause), "endsWithin"/"endsAfter" (seconds from the
// user's last step to its end), "speechEndWithin" (its speechEndAt this close to the user's last step), "bargeIn"
// ("sound", "words" or "none": the first barge-in) and "bargeInWithin" (seconds after the user's first step).

package com.lomemstudios.voice.turn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

/** A detector fed 50 ms buffers, with a recognizer that catches up [lag] seconds after each flush. With [speech], the
 *  detector also gets each frame and a fake speech detector that answers the probability the step gives. */
class ScenarioTimeline(config: TurnConfig = TurnConfig(), private val speech: Boolean = false) {
    val detector = TurnDetector(config)
    var t = 0.0
    val events = mutableListOf<Pair<Double, TurnEvent>>()
    private val lag = 0.3
    private val step = 0.05
    private var flushDue: Pair<Double, Double>? = null
    private var p = 0.02f
    private var frames = 0
    private val frame = ShortArray(800)
    /** The first turn's numbers, kept when it ended: another voice talking on starts a turn of its own. */
    var first: TurnStats? = null
        private set

    init {
        if (speech) detector.speech = SpeechProbability { p }
        run(1.0, -60f, 0.02f)
        events.clear()
    }

    fun user(seconds: Double, level: Float = -20f, p: Float = 0.9f, gapEvery: Int = 0) = run(seconds, level, p, gapEvery)
    fun other(seconds: Double, level: Float = -20f, p: Float = 0.9f, gapEvery: Int = 0) = run(seconds, level, p, gapEvery)
    fun quiet(seconds: Double, p: Float = 0.02f) = run(seconds, -60f, p)
    fun say(text: String) { detector.words(text, t).forEach { events += t to it } }

    fun run(seconds: Double, level: Float, p: Float, gapEvery: Int = 0) {
        var n = 0
        repeat(Math.round(seconds / step).toInt()) {
            t += step
            frames++
            n++
            this.p = p
            val l = if (gapEvery > 0 && n % gapEvery == 0) -60f else level
            val new = if (speech) detector.audio(frame, l, step, t) else detector.audio(l, step, t)
            new.forEach { e ->
                events += t to e
                if ((e is TurnEvent.Ended || e is TurnEvent.Dropped) && first == null) first = detector.stats
                if (e is TurnEvent.Flush) flushDue = Pair(t + lag, e.through)
            }
            val due = flushDue
            if (due != null && t >= due.first - 1e-9) {
                flushDue = null
                detector.caughtUp(due.second)
            }
        }
    }

    val ended get() = events.map { it.second }.filterIsInstance<TurnEvent.Ended>()
    val started get() = events.count { it.second is TurnEvent.Started }
    /** The first turn sent or dropped, and when. */
    val firstEnd: Pair<Double, TurnEvent>? get() = events.firstOrNull { it.second is TurnEvent.Ended || it.second is TurnEvent.Dropped }
    val firstEndAt: Double? get() = firstEnd?.first
}

class TurnScenarioTest {
    private val dir = listOf(File("../testdata/turns"), File("testdata/turns")).first { it.isDirectory }

    private fun configOf(s: JSONObject): TurnConfig {
        val c = TurnConfig()
        val o = s.optJSONObject("config") ?: return c
        for (k in o.keys()) when (k) {
            "freezeFloorInTurn" -> c.freezeFloorInTurn = o.getBoolean(k)
            "fallbackSilence" -> c.fallbackSilence = o.getDouble(k)
            "maxTurn" -> c.maxTurn = o.getDouble(k)
            "noWordsSilence" -> c.noWordsSilence = o.getDouble(k)
            else -> error("unknown config $k")
        }
        return c
    }

    private class Run(val tl: ScenarioTimeline, val userStarted: Double?, val userStopped: Double)

    private fun run(s: JSONObject): Run {
        val tl = ScenarioTimeline(configOf(s), s.optBoolean("speech"))
        val steps: JSONArray = s.getJSONArray("steps")
        var userStarted: Double? = null
        var userStopped = 0.0
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            val level = step.optDouble("level", -20.0).toFloat()
            val p = step.optDouble("p", 0.9).toFloat()
            val gap = step.optInt("gapEvery", 0)
            when {
                step.has("user") -> {
                    if (userStarted == null) userStarted = tl.t
                    tl.user(step.getDouble("user"), level, p, gap); userStopped = tl.t
                }
                step.has("other") -> tl.other(step.getDouble("other"), level, p, gap)
                step.has("noise") -> tl.run(step.getDouble("noise"), level, step.optDouble("p", 0.05).toFloat())
                step.has("quiet") -> tl.quiet(step.getDouble("quiet"))
                step.has("say") -> tl.say(step.getString("say"))
                step.has("playing") -> tl.detector.playing = step.getBoolean("playing")
                else -> error("unknown step $step")
            }
        }
        tl.quiet(70.0)
        return Run(tl, userStarted, userStopped)
    }

    @Test fun everyScenario() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("scenarios in $dir", files.size >= 4)
        // Every scenario is checked and every failure reported, one line each.
        val failures = mutableListOf<String>()
        for (f in files) try { check(f) } catch (e: AssertionError) { failures += "${f.name}: ${e.message}" }
        assertTrue("failed scenarios:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    private fun check(f: File) {
        run {
            val s = JSONObject(f.readText())
            val r = run(s)
            val tl = r.tl
            val expect = s.getJSONObject("expect")
            val name = f.name
            if (expect.has("turns")) assertEquals("$name: turns started", expect.getInt("turns"), tl.started)
            if (expect.has("bargeIn")) {
                val b = tl.events.map { it.second }.filterIsInstance<TurnEvent.BargeIn>().firstOrNull()
                val kind = if (b == null) "none" else if (b.bySound) "sound" else "words"
                assertEquals("$name: first barge-in", expect.getString("bargeIn"), kind)
                if (expect.has("bargeInWithin")) {
                    val after = b!!.at - r.userStarted!!
                    assertTrue("$name: barge-in $after s after the user began", after <= expect.getDouble("bargeInWithin") + 1e-9)
                }
            }
            if (!expect.has("text") && !expect.has("endedBy") && !expect.has("cause") && !expect.has("dropped")) return
            val first = tl.first ?: throw AssertionError("the turn never ended")
            val (endAt, end) = tl.firstEnd!!
            val after = endAt - r.userStopped
            if (expect.has("text")) assertEquals(name, expect.getString("text"), tl.ended.first().text)
            if (expect.has("endedBy")) assertEquals(name, expect.getString("endedBy"), first.endedBy)
            if (expect.has("dropped")) assertEquals("$name: dropped", expect.getBoolean("dropped"), end is TurnEvent.Dropped)
            val cause = (end as? TurnEvent.Ended)?.cause ?: (end as? TurnEvent.Dropped)?.cause
            val speechEnd = (end as? TurnEvent.Ended)?.speechEndAt ?: (end as? TurnEvent.Dropped)?.speechEndAt
            if (expect.has("cause")) assertEquals("$name: cause", expect.getString("cause"), cause?.name)
            if (expect.has("speechEndWithin")) {
                assertNotNull("$name: speechEndAt", speechEnd)
                assertTrue("$name: speech ended at $speechEnd, the user at ${r.userStopped}",
                    abs(speechEnd!! - r.userStopped) <= expect.getDouble("speechEndWithin") + 1e-9)
            }
            if (expect.has("endsWithin")) assertTrue("$name: ended $after s after the user", after <= expect.getDouble("endsWithin"))
            if (expect.has("endsAfter")) assertTrue("$name: ended $after s after the user", after >= expect.getDouble("endsAfter"))
        }
    }
}
