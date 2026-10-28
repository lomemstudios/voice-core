// The turn scenarios Voice Next and the Lo Mem apps rely on (testdata/turns/*.json, CLAUDE.md "The detector is the
// reference").

package com.lomemstudios.voice.turn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A detector fed 50 ms buffers, with a recognizer that catches up [lag] seconds after each flush. */
class ScenarioTimeline {
    val detector = TurnDetector()
    var t = 0.0
    val events = mutableListOf<Pair<Double, TurnEvent>>()
    private val lag = 0.3
    private val step = 0.05
    private var flushDue: Pair<Double, Double>? = null
    /** The first turn's numbers, kept when it ended: another voice talking on starts a turn of its own. */
    var first: TurnStats? = null
        private set

    init {
        run(1.0, -60f)
        events.clear()
    }

    fun user(seconds: Double) = run(seconds, -20f)
    fun other(seconds: Double) = run(seconds, -20f)
    fun quiet(seconds: Double) = run(seconds, -60f)
    fun say(text: String) { detector.words(text, t).forEach { events += t to it } }

    private fun run(seconds: Double, level: Float) {
        repeat(Math.round(seconds / step).toInt()) {
            t += step
            detector.audio(level, step, t).forEach { e ->
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
    /** When the first turn was sent or dropped. */
    val firstEndAt: Double? get() = events.firstOrNull { it.second is TurnEvent.Ended || it.second is TurnEvent.Dropped }?.first
}

class TurnScenarioTest {
    private val dir = listOf(File("../testdata/turns"), File("testdata/turns")).first { it.isDirectory }

    private fun run(steps: JSONArray): Pair<ScenarioTimeline, Double> {
        val tl = ScenarioTimeline()
        var userStopped = 0.0
        for (i in 0 until steps.length()) {
            val step = steps.getJSONObject(i)
            when {
                step.has("user") -> { tl.user(step.getDouble("user")); userStopped = tl.t }
                step.has("other") -> tl.other(step.getDouble("other"))
                step.has("quiet") -> tl.quiet(step.getDouble("quiet"))
                step.has("say") -> tl.say(step.getString("say"))
                else -> error("unknown step $step")
            }
        }
        tl.quiet(70.0)
        return tl to userStopped
    }

    @Test fun everyScenario() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("scenarios in $dir", files.size >= 4)
        for (f in files) {
            val s = JSONObject(f.readText())
            val (tl, userStopped) = run(s.getJSONArray("steps"))
            val expect = s.getJSONObject("expect")
            val name = f.name
            val first = tl.first ?: error("$name: the turn never ended")
            val after = tl.firstEndAt!! - userStopped
            assertEquals(name, expect.getString("text"), tl.ended.first().text)
            assertEquals(name, expect.getString("endedBy"), first.endedBy)
            if (expect.has("endsWithin")) assertTrue("$name: ended $after s after the user", after <= expect.getDouble("endsWithin"))
            if (expect.has("endsAfter")) assertTrue("$name: ended $after s after the user", after >= expect.getDouble("endsAfter"))
        }
    }
}
