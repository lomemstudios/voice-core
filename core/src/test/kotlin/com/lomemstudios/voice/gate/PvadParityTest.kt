// The Kotlin pVAD runner (pvad.Pvad, as the gate runs it through PvadGate) gives Python's p track within 0.02 on three
// mixtures (plan "Tests", PvadParityTest), with ONNX Runtime 1.22.0 on the JVM. Needs VOICE_BENCH_DIR (gate/parity,
// written by the research scripts on main); skipped without it.

package com.lomemstudios.voice.gate

import com.lomemstudios.voice.bench.BenchDir
import com.lomemstudios.voice.pvad.PvadScorer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import kotlin.math.abs

class PvadParityTest {
    @Test fun theGatesRunnerMatchesPythonOnThreeMixtures() {
        val index = BenchDir.json("gate/parity/index.json")
        val model = BenchDir.need("tse-research/dl-pvad/pvad/pvad.onnx").readBytes()
        val spk = BenchDir.f32(BenchDir.need("gate/parity/" + index.getString("spk")))
        val items = index.getJSONArray("items")
        assertEquals(3, items.length())
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val want = BenchDir.f32(BenchDir.need("gate/parity/" + item.getString("p")))
            val pcm = BenchDir.wav(BenchDir.need(item.getString("wav")))
            val got = Collections.synchronizedList(mutableListOf<Float>())
            val sink = object : PvadGateListener {
                override fun onReadings(p: FloatArray, times: DoubleArray) { p.forEach { got += it } }
                override fun onFailure(cause: String) = error(cause)
                override fun onLog(line: String) {}
            }
            // Through the gate's thread in 50 ms frames, as the phone feeds it.
            PvadGate(PvadScorer(model, spk), sink, capacity = 4000).use { gate ->
                var at = 0
                var t = 0.0
                while (at < pcm.size) {
                    val n = minOf(800, pcm.size - at)
                    t += n / 16000.0
                    assertTrue(gate.offer(pcm.copyOfRange(at, at + n), t))
                    at += n
                }
                gate.drain(60_000)
            }
            assertEquals("${item.getString("id")} frames", want.size, got.size)
            val worst = want.indices.maxOf { abs(want[it] - got[it]) }
            println("pvad parity ${item.getString("id")}__${item.getString("condition")}: ${got.size} frames, worst |dp| $worst")
            assertTrue("${item.getString("id")}__${item.getString("condition")}: worst |dp| $worst", worst <= 0.02f)
        }
    }
}
