// The vector pVAD takes (plan step 1.2 and 1.3): SpeechBrain ECAPA, run on the phone with ONNX Runtime over the first
// 5 s of the accepted enrolment speech, L2-normalised. The recipe was chosen by replay on 48 turns x 5 conditions
// (claude-voice PROGRESS.md, "1.3"): the first 5 s held another voice's turns open least (talker0 hold p90 0.92 s,
// against 1.0 to 2.5 s for 20 s, the mean of 5 s pieces, the best 5 s piece and the mean of 3 s windows). Which 5 s
// it is mattered in that replay, so the field recordings check it; the profile keeps the speech, so a new recipe
// recomputes the print without a new enrolment.

package com.lomemstudios.voice.gate

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.sqrt

object GatePrint {
    const val RATE = 16000
    /** Seconds of accepted speech the vector is made from. */
    const val SECONDS = 5.0

    /** The audio the vector is made from: the first [SECONDS] of the pieces, joined in order. */
    fun recipe(pieces: List<ShortArray>): ShortArray {
        val want = (SECONDS * RATE).toInt()
        val out = ShortArray(minOf(want, pieces.sumOf { it.size }))
        var at = 0
        for (p in pieces) {
            if (at >= out.size) break
            val n = minOf(p.size, out.size - at)
            System.arraycopy(p, 0, out, at, n)
            at += n
        }
        return out
    }

    fun normalized(v: FloatArray): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return if (n > 0) FloatArray(v.size) { v[it] / n } else v
    }
}

/** ECAPA as exported by tools/export_ecapa.py: `waveform [1, N]` in, `embedding [1, 192]` out. */
class EcapaEncoder(modelPath: String, threads: Int = 2) : AutoCloseable {
    private val env = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(modelPath, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
    })

    /** The L2-normalised 192-d vector of 16 kHz speech. */
    fun vector(pcm: ShortArray): FloatArray {
        require(pcm.size >= GatePrint.RATE / 2) { "ECAPA needs at least half a second of speech, not ${pcm.size} samples" }
        val wave = FloatBuffer.wrap(FloatArray(pcm.size) { pcm[it] / 32768f })
        OnnxTensor.createTensor(env, wave, longArrayOf(1, pcm.size.toLong())).use { t ->
            session.run(mapOf("waveform" to t)).use { r ->
                val out = (r.get("embedding").get() as OnnxTensor).floatBuffer
                return GatePrint.normalized(FloatArray(out.remaining()).also { out.get(it) })
            }
        }
    }

    override fun close() = session.close()
}
