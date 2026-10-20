package com.lomemstudios.voice.bench

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.sqrt

class BenchTest {
    @Test
    fun theSyntheticAudioIsThePythonReferences() {
        // Printed by the reference script on main (PROGRESS.md, "Phone bench"): the first samples, the RMS of 60 s,
        // and the embedding's first values. pVAD's mean p over this clip, 0.1389, was measured on these samples.
        val a = SyntheticAudio.audio(60.0)
        assertEquals(960_000, a.size)
        assertArrayEquals(floatArrayOf(-0.009998740628361702f, -0.009685047902166843f, 0.0023281106259673834f), a.copyOf(3), 0f)
        assertEquals(0.03748030242713926, sqrt(a.sumOf { it.toDouble() * it } / a.size), 1e-9)
        val e = SyntheticAudio.embedding()
        assertEquals(192, e.size)
        assertArrayEquals(floatArrayOf(-0.12953297793865204f, -0.10124903917312622f, 0.104728102684021f), e.copyOf(3), 0f)
        assertEquals(1.0, sqrt(e.sumOf { it.toDouble() * it }), 1e-6)
    }

    @Test
    fun aStepSummaryHasNumpysPercentiles() {
        val s = StepStats(capacity = 4)  // grows past its capacity
        for (ms in 1..100) s.add(ms * 1_000_000L)
        val sum = s.summary(audioSeconds = 10.0)
        assertEquals(100, sum.count)
        assertEquals(50.5, sum.meanMs, 1e-9)
        assertEquals(50.5, sum.p50Ms, 1e-9)
        assertEquals(95.05, sum.p95Ms, 1e-9)
        assertEquals(100.0, sum.worstMs, 1e-9)
        assertEquals(0.505, sum.rtf, 1e-9)
        assertEquals("mean 50.5 ms, p50 50.5, p95 95.1, worst 100.0, RTF 0.5050", sum.line())
    }

    @Test
    fun aShortRunHasItsOwnPercentiles() {
        val s = StepStats()
        listOf(0.5, 0.7, 9.0).forEach { s.add((it * 1e6).toLong()) }
        val sum = s.summary(0.03)
        assertEquals(0.7, sum.p50Ms, 1e-9)
        assertEquals(0.7 + (9.0 - 0.7) * 0.9, sum.p95Ms, 1e-9)
        assertEquals("mean 3.40 ms, p50 0.70, p95 8.17, worst 9.00, RTF 0.3400", sum.line())
    }

    @Test
    fun thermalStatusesHaveTheirNames() {
        assertEquals(listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown", "unknown"),
            (0..7).map { Device.thermalName(it) })
    }
}
