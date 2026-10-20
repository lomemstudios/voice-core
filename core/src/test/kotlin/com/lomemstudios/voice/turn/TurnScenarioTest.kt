// The turn scenarios Voice Next and the Lo Mem apps rely on (testdata/turns/*.json, CLAUDE.md "The detector is the
// reference"), each run with and without the voice gate.

package com.lomemstudios.voice.turn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TurnScenarioTest {
    private val dir = listOf(File("../testdata/turns"), File("testdata/turns")).first { it.isDirectory }

    private fun run(steps: org.json.JSONArray, gate: Boolean): Pair<GateTimeline, Double> {
        val tl = GateTimeline(gate)
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

    private fun check(name: String, expect: JSONObject, tl: GateTimeline, userStopped: Double) {
        val first = tl.first ?: error("$name: the turn never ended")
        val after = tl.firstEndAt!! - userStopped
        assertEquals(name, expect.getString("text"), tl.ended.first().text)
        assertEquals(name, expect.getString("endedBy"), first.endedBy)
        if (expect.has("endsWithin")) assertTrue("$name: ended $after s after the user", after <= expect.getDouble("endsWithin"))
        if (expect.has("endsAfter")) assertTrue("$name: ended $after s after the user", after >= expect.getDouble("endsAfter"))
        if (expect.has("fallback")) assertEquals(name, expect.getString("fallback"), first.gate?.fallback)
    }

    @Test fun everyScenarioWithAndWithoutTheGate() {
        val files = dir.listFiles { f -> f.name.endsWith(".json") }!!.sortedBy { it.name }
        assertTrue("scenarios in $dir", files.size >= 4)
        for (f in files) {
            val s = JSONObject(f.readText())
            val (on, stopOn) = run(s.getJSONArray("steps"), gate = true)
            check("${f.name} with the gate", s.getJSONObject("gate"), on, stopOn)
            val (off, stopOff) = run(s.getJSONArray("steps"), gate = false)
            check("${f.name} without it", s.getJSONObject("noGate"), off, stopOff)
        }
    }
}
