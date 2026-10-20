// The voice gate's own thread (plan step 1.7, "Threads and latency budget on the phone"): the app hands it each 50 ms
// microphone frame with its time on the audio clock; it runs five 10 ms pVAD steps per frame on the "pvad" thread and
// hands back the readings with their times, which the app posts to the main thread's TurnDetector.target.
//
// It never blocks the microphone: the queue holds 40 frames (2 s) and a full queue drops the frame. Every failure
// falls back to the detector without the gate (plan "The gate falls back to today's detector"): a scorer that throws
// stops the gate for the session (onFailure); frames dropped or readings that fall behind are logged, and the
// detector itself lets the open turn fall back when its readings lag the audio by more than GateConfig.maxLag; a
// phone too slow for it (p95 per frame over [slowP95Ms]) stops the gate for the session at the next perf line.
//
// Log lines are the plan's: `gate: lag 260 ms`, `gate: fallback <cause>`, and every 60 s of audio
// `gate: 1200 chunks, p50 6.1 ms, p95 9.8 ms`.

package com.lomemstudios.voice.gate

import com.lomemstudios.voice.bench.StepStats
import java.util.concurrent.ArrayBlockingQueue

/** One stream of the target voice's probability: pVAD in the app (PvadScorer), a fake in tests. */
interface TargetScorer : AutoCloseable {
    /** The probability for [STEP] samples of 16 kHz audio in -1..1 from [offset] of [audio]. */
    fun step(audio: FloatArray, offset: Int): Float
    /** The microphone restarted: the carried state is cleared. */
    fun reset()

    companion object {
        const val STEP = 160
        const val RATE = 16000
    }
}

/** What the gate tells the app, every call on the "pvad" thread. */
interface PvadGateListener {
    /** Readings: [p] for the 10 ms of audio ending at [times] (the audio clock), in order. */
    fun onReadings(p: FloatArray, times: DoubleArray)
    /** The gate stopped for the session; the app turns the detector's gate off. */
    fun onFailure(cause: String)
    fun onLog(line: String)
}

class PvadGate(
    private val scorer: TargetScorer,
    private val listener: PvadGateListener,
    capacity: Int = 40,
    /** Seconds of audio between perf lines. */
    private val perfEvery: Double = 60.0,
    /** A frame's readings handed back this much later than the frame was handed in are logged as lag. */
    private val lagLogMs: Double = 250.0,
    /** The plan's budget for a 50 ms frame (step 1.1: go when p95 <= 15 ms). */
    private val slowP95Ms: Double = 15.0,
    private val nanoTime: () -> Long = System::nanoTime,
) : AutoCloseable {
    private class Frame(val pcm: ShortArray, val endT: Double, val handedAt: Long)
    private object Reset

    private val queue = ArrayBlockingQueue<Any>(capacity)
    @Volatile private var stopped = false
    @Volatile var failed: String? = null
        private set
    private var dropped = 0
    private var droppedLogged = Long.MIN_VALUE / 2

    // Owned by the "pvad" thread.
    private val buf = FloatArray(TargetScorer.STEP * 64)
    private var held = 0
    private var chunks = StepStats(1400)
    private var perfAudio = 0.0
    private var lagLoggedAt = Long.MIN_VALUE / 2

    private val thread = Thread {
        try {
            while (!stopped) {
                when (val item = queue.take()) {
                    is Frame -> frame(item)
                    Reset -> { scorer.reset(); held = 0 }
                }
            }
        } catch (_: InterruptedException) {
        } catch (e: Throwable) {
            fail("${e.javaClass.simpleName}: ${e.message}")
        }
    }.apply { name = "pvad"; isDaemon = true; start() }

    /** One microphone frame (16 kHz mono) ending at [endT] on the audio clock. Any thread; never blocks. False when
     *  the frame was dropped (the gate stopped, or its queue is full). */
    fun offer(pcm: ShortArray, endT: Double): Boolean {
        if (stopped || failed != null) return false
        if (queue.offer(Frame(pcm, endT, nanoTime()))) return true
        dropped++
        val now = nanoTime()
        if (now - droppedLogged > 5_000_000_000L) {
            droppedLogged = now
            listener.onLog("gate: queue full, $dropped frames dropped")
        }
        return false
    }

    /** The microphone restarted: frames queued are dropped and pVAD's state starts afresh. */
    fun reset() {
        queue.clear()
        queue.offer(Reset)
    }

    override fun close() {
        stopped = true
        thread.interrupt()
        thread.join(500)
        scorer.close()
    }

    /** Waits until every frame handed in so far is scored (tests). */
    fun drain(timeoutMs: Long = 5000) {
        val until = System.currentTimeMillis() + timeoutMs
        while (queue.isNotEmpty() && System.currentTimeMillis() < until) Thread.sleep(2)
        Thread.sleep(5)
    }

    private fun fail(cause: String) {
        if (failed != null) return
        failed = cause
        stopped = true
        listener.onLog("gate: fallback $cause")
        listener.onFailure(cause)
    }

    private fun frame(f: Frame) {
        val c0 = nanoTime()
        val n = f.pcm.size
        // Samples left over from the last frame come first, so the steps run on the audio as it was recorded.
        if (held + n > buf.size) held = 0
        for (i in 0 until n) buf[held + i] = f.pcm[i] / 32768f
        val total = held + n
        val steps = total / TargetScorer.STEP
        val p = FloatArray(steps)
        val times = DoubleArray(steps)
        for (k in 0 until steps) {
            p[k] = scorer.step(buf, k * TargetScorer.STEP)
            // The time of this step's last sample: the frame's end less the samples after it.
            times[k] = f.endT - (total - (k + 1) * TargetScorer.STEP).toDouble() / TargetScorer.RATE
        }
        held = total - steps * TargetScorer.STEP
        if (held > 0) System.arraycopy(buf, steps * TargetScorer.STEP, buf, 0, held)
        val done = nanoTime()
        chunks.add(done - c0)
        if (steps > 0) listener.onReadings(p, times)
        val lagMs = (done - f.handedAt) / 1e6
        if (lagMs > lagLogMs && done - lagLoggedAt > 5_000_000_000L) {
            lagLoggedAt = done
            listener.onLog("gate: lag %.0f ms".format(lagMs))
        }
        perfAudio += n.toDouble() / TargetScorer.RATE
        if (perfAudio >= perfEvery - 1e-9) perf()
    }

    private fun perf() {
        val s = chunks.summary(perfAudio)
        listener.onLog("gate: %d chunks, p50 %.1f ms, p95 %.1f ms".format(s.count, s.p50Ms, s.p95Ms))
        chunks = StepStats(1400)
        perfAudio = 0.0
        if (s.p95Ms > slowP95Ms) fail("slow: p95 %.1f ms a chunk, over %.0f ms".format(s.p95Ms, slowP95Ms))
    }
}
