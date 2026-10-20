// The phone speed test: how fast this phone runs the models the voice gate needs, measured where they will run, with
// no cable. pVAD runs the way plan Phase 1 runs it: one thread, ONNX Runtime with one intra-op thread, 160-sample
// steps over 60 s of 16 kHz audio, five steps per 50 ms chunk as the microphone delivers them, paced in real time,
// then once more as fast as it goes. Other models (TitaNet small, GTCRN, from the sherpa module) come as [BenchPart]s.
//
// The audio is SyntheticAudio and the embedding a fixed random unit vector: timing does not depend on whose voice,
// and no recording is made or read. Every line goes to [log] with the prefix "bench:", so it can be found in a trace.

package com.lomemstudios.voice.bench

import android.content.Context
import com.lomemstudios.voice.models.ModelManifest
import com.lomemstudios.voice.pvad.Pvad
import java.util.concurrent.locks.LockSupport

/** One more model to time, after pVAD. */
interface BenchPart {
    val name: String
    /** Runs on the bench's thread over [audio] (16 kHz, -1..1), reporting through [say]. */
    fun run(audio: FloatArray, say: (String) -> Unit)
}

class PhoneBench(
    private val context: Context,
    private val log: (String) -> Unit,
    private val seconds: Double = 60.0,
    /** The pVAD reference on main: the mean p over this audio, onnxruntime 1.22.0 in Python on x86-64. */
    private val referencePMean: Double = 0.1389,
) {
    companion object {
        const val PREFIX = "bench: "
        /** Steps per 50 ms chunk, the size VoiceService reads. */
        const val STEPS_PER_CHUNK = 5
    }

    @Volatile var cancelled = false
    /** pVAD's p95 per 50 ms chunk in the paced run, once it ran: the voice gate stays off over 15 ms (GatePolicy). */
    @Volatile var chunkP95Ms: Double? = null
        private set

    fun say(line: String) = log(PREFIX + line)

    /** Runs everything on the calling thread; [pvadModel] gives the checked model bytes (ModelStore.bytes). */
    fun run(pvadModel: () -> ByteArray, parts: List<BenchPart>, note: String = "") {
        val started = System.nanoTime()
        say("start: ${seconds.toInt()} s of synthetic audio${if (note.isNotEmpty()) "; $note" else ""}")
        say("device: ${Device.describe()}")
        say("cpu: ${Device.cores()}")
        say("before: ${Device.thermal(context)}")
        val audio = SyntheticAudio.audio(seconds)
        try {
            pvad(pvadModel, audio)
        } catch (e: Throwable) {
            say("pvad failed: ${e.javaClass.simpleName}: ${e.message}")
        }
        for (part in parts) {
            if (cancelled) break
            try {
                part.run(audio) { say("${part.name} $it") }
            } catch (e: Throwable) {
                say("${part.name} failed: ${e.javaClass.simpleName}: ${e.message}")
            }
        }
        say("after: ${Device.thermal(context)}")
        say("done in %.0f s%s".format((System.nanoTime() - started) / 1e9, if (cancelled) " (stopped early)" else ""))
    }

    private fun pvad(model: () -> ByteArray, audio: FloatArray) {
        val t0 = System.nanoTime()
        val bytes = model()
        val read = (System.nanoTime() - t0) / 1e6
        val t1 = System.nanoTime()
        Pvad(bytes, threads = 1).use { pvad ->
            val load = (System.nanoTime() - t1) / 1e6
            say("pvad model %s %.1f MB, read and checked in %.0f ms, session in %.0f ms; onnxruntime %s, 1 intra-op thread".format(
                ModelManifest.PVAD.short, bytes.size / 1e6, read, load, ortVersion()))
            pvad.stream(SyntheticAudio.embedding()).use { s ->
                val first = System.nanoTime()
                s.step(audio, 0)
                say("pvad first step %.1f ms".format((System.nanoTime() - first) / 1e6))
                s.reset()
                paced(s, audio)
                s.reset()
                flatOut(s, audio)
            }
        }
    }

    /** As Phase 1 runs it: every 50 ms a chunk of five steps, the thread idle in between. */
    private fun paced(s: Pvad.Stream, audio: FloatArray) {
        val steps = StepStats(6000)
        val chunks = StepStats(1200)
        val cpu = CpuWatch()
        var late = 0
        var pSum = 0.0
        var n = 0
        val chunkNs = 50_000_000L
        var due = System.nanoTime()
        var at = 0
        while (at + Pvad.STEP * STEPS_PER_CHUNK <= audio.size && !cancelled) {
            val c0 = System.nanoTime()
            repeat(STEPS_PER_CHUNK) {
                pSum += steps.time { s.step(audio, at) }
                n++
                at += Pvad.STEP
            }
            chunks.add(System.nanoTime() - c0)
            if (chunks.count % 20 == 0) cpu.sample()
            due += chunkNs
            val wait = due - System.nanoTime()
            if (wait > 0) LockSupport.parkNanos(wait) else late++
        }
        val sec = n * Pvad.STEP / SyntheticAudio.RATE.toDouble()
        say("pvad paced, %d steps of 160 samples (%.0f s, real time): per step %s".format(n, sec, steps.summary(sec).line()))
        val perChunk = chunks.summary(sec)
        if (!cancelled) chunkP95Ms = perChunk.p95Ms
        say("pvad paced, per 50 ms chunk of 5 steps: %s; %d chunks over their 50 ms".format(perChunk.line(), late))
        say("pvad paced, ${cpu.line()}")
        say("pvad p mean %.4f over the clip (reference %.4f, onnxruntime 1.22.0 on x86-64)".format(pSum / n, referencePMean))
    }

    /** The same steps back to back: what the model costs with the core kept busy. */
    private fun flatOut(s: Pvad.Stream, audio: FloatArray) {
        val steps = StepStats(6000)
        val cpu = CpuWatch()
        var at = 0
        var n = 0
        while (at + Pvad.STEP <= audio.size && !cancelled) {
            steps.time { s.step(audio, at) }
            at += Pvad.STEP
            if (++n % 100 == 0) cpu.sample()
        }
        val sec = n * Pvad.STEP / SyntheticAudio.RATE.toDouble()
        say("pvad flat out, %d steps: per step %s".format(n, steps.summary(sec).line()))
        say("pvad flat out, ${cpu.line()}")
    }

    private fun ortVersion(): String = runCatching { ai.onnxruntime.OrtEnvironment.getEnvironment().version }.getOrDefault("?")
}
