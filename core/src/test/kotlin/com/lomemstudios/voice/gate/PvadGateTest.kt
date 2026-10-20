package com.lomemstudios.voice.gate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PvadGateTest {
    private class Sink : PvadGateListener {
        val readings = Collections.synchronizedList(mutableListOf<Pair<Float, Double>>())
        val logs = Collections.synchronizedList(mutableListOf<String>())
        @Volatile var failure: String? = null
        override fun onReadings(p: FloatArray, times: DoubleArray) { p.indices.forEach { readings += p[it] to times[it] } }
        override fun onFailure(cause: String) { failure = cause }
        override fun onLog(line: String) { logs += line }
    }

    /** The mean of the step's samples, so a reading shows which audio it was given. */
    private open class MeanScorer : TargetScorer {
        var resets = 0
        override fun step(audio: FloatArray, offset: Int): Float {
            var s = 0f
            for (i in offset until offset + TargetScorer.STEP) s += audio[i]
            return s / TargetScorer.STEP
        }
        override fun reset() { resets++ }
        override fun close() {}
    }

    private fun frame(n: Int, value: Short) = ShortArray(n) { value }

    @Test fun fiveReadingsPerFrameAtTheTimesOfTheirAudio() {
        val sink = Sink()
        PvadGate(MeanScorer(), sink).use { gate ->
            gate.offer(frame(800, 16384), 1.05)
            gate.drain()
            assertEquals(5, sink.readings.size)
            assertEquals(listOf(1.01, 1.02, 1.03, 1.04, 1.05), sink.readings.map { Math.round(it.second * 1000) / 1000.0 })
            assertEquals(0.5f, sink.readings[0].first, 1e-4f)
        }
    }

    @Test fun samplesLeftOverCarryIntoTheNextFrame() {
        val sink = Sink()
        PvadGate(MeanScorer(), sink).use { gate ->
            gate.offer(frame(100, 16384), 0.1)      // too short for a step: held
            gate.offer(frame(220, 0), 0.12)         // 320 samples: two steps, the first half old audio
            gate.drain()
            assertEquals(2, sink.readings.size)
            assertEquals(100 * 0.5f / 160, sink.readings[0].first, 1e-4f)
            assertEquals(0.11, sink.readings[0].second, 1e-9)
            assertEquals(0.12, sink.readings[1].second, 1e-9)
        }
    }

    @Test fun aFullQueueDropsTheFrameAndNeverBlocks() {
        val release = CountDownLatch(1)
        val blocking = object : MeanScorer() {
            override fun step(audio: FloatArray, offset: Int): Float { release.await(5, TimeUnit.SECONDS); return 0f }
        }
        val sink = Sink()
        PvadGate(blocking, sink, capacity = 2).use { gate ->
            val took = System.nanoTime()
            val accepted = (1..10).map { gate.offer(frame(800, 0), it * 0.05) }
            assertTrue("offer never waits", System.nanoTime() - took < 200_000_000L)
            assertTrue(accepted.count { !it } >= 7)
            assertTrue(sink.logs.any { it.startsWith("gate: queue full") })
            release.countDown()
        }
    }

    @Test fun aScorerThatThrowsStopsTheGateForTheSession() {
        val sink = Sink()
        val broken = object : MeanScorer() {
            override fun step(audio: FloatArray, offset: Int): Float = throw IllegalStateException("model gone")
        }
        PvadGate(broken, sink).use { gate ->
            gate.offer(frame(800, 0), 0.05)
            gate.drain()
            Thread.sleep(50)
            assertEquals("IllegalStateException: model gone", sink.failure)
            assertTrue(sink.logs.contains("gate: fallback IllegalStateException: model gone"))
            assertFalse("no frame is taken after a failure", gate.offer(frame(800, 0), 0.1))
        }
    }

    @Test fun resetClearsTheScorersState() {
        val scorer = MeanScorer()
        PvadGate(scorer, Sink()).use { gate ->
            gate.reset()
            gate.drain()
            assertEquals(1, scorer.resets)
        }
    }

    @Test fun aPerfLineEverySixtySecondsOfAudio() {
        val sink = Sink()
        PvadGate(MeanScorer(), sink, capacity = 2000).use { gate ->
            for (i in 1..1200) gate.offer(frame(800, 0), i * 0.05)
            gate.drain()
            val perf = sink.logs.filter { it.matches(Regex("gate: 1200 chunks, p50 [0-9.]+ ms, p95 [0-9.]+ ms")) }
            assertEquals(sink.logs.toString(), 1, perf.size)
            assertNull(sink.failure)
        }
    }

    @Test fun aPhoneTooSlowForTheBudgetStopsTheGate() {
        // A clock that moves 20 ms whenever a step runs: every chunk takes 100 ms by it.
        var now = 0L
        val slow = object : MeanScorer() {
            override fun step(audio: FloatArray, offset: Int): Float { now += 20_000_000L; return 0f }
        }
        val sink = Sink()
        PvadGate(slow, sink, capacity = 2000, perfEvery = 1.0, lagLogMs = 1e9, nanoTime = { now }).use { gate ->
            for (i in 1..20) gate.offer(frame(800, 0), i * 0.05)
            gate.drain()
            Thread.sleep(50)
            assertEquals("slow: p95 100.0 ms a chunk, over 15 ms", sink.failure)
        }
    }

    @Test fun readingsHandedBackLateAreLogged() {
        var now = 0L
        val late = object : MeanScorer() {
            override fun step(audio: FloatArray, offset: Int): Float { now += 60_000_000L; return 0f }
        }
        val sink = Sink()
        PvadGate(late, sink, slowP95Ms = 1e9, nanoTime = { now }).use { gate ->
            gate.offer(frame(800, 0), 0.05)
            gate.drain()
            assertTrue(sink.logs.toString(), sink.logs.contains("gate: lag 300 ms"))
        }
    }
}
