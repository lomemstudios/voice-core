// Audio for the phone bench that no one spoke: white noise at about -40 dBFS and, every other half second, a
// harmonic tone with a voice-like fundamental (120 to 280 Hz) under a Hann envelope. Deterministic (xorshift32), so
// the same samples come out on every phone and in the Python reference that times pVAD on main
// (PROGRESS.md, "Phone bench"), and a model's output on it can be compared across machines.

package com.lomemstudios.voice.bench

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object SyntheticAudio {
    const val RATE = 16000

    /** [seconds] of 16 kHz audio in -1..1. */
    fun audio(seconds: Double, seed: Int = 1): FloatArray {
        val rng = XorShift(seed)
        val n = (seconds * RATE).toInt()
        return FloatArray(n) { i ->
            val noise = (rng.unit() * 2 - 1) * 0.01
            val t = i / RATE.toDouble()
            val seg = (t / 0.5).toInt()
            var tone = 0.0
            if (seg % 2 == 0) {
                val f0 = 120.0 + 40.0 * (seg % 5)
                val env = 0.5 - 0.5 * cos(2 * PI * ((t - seg * 0.5) / 0.5))
                for (k in 1..5) tone += (0.1 / k) * sin(2 * PI * k * f0 * t)
                tone *= env
            }
            (noise + tone).toFloat()
        }
    }

    /** A fixed unit vector of [dim] values, the stand-in for a speaker embedding: timing does not depend on whose. */
    fun embedding(dim: Int = 192, seed: Int = 7): FloatArray {
        val rng = XorShift(seed)
        val v = DoubleArray(dim) { rng.unit() * 2 - 1 }
        val norm = sqrt(v.sumOf { it * it })
        return FloatArray(dim) { (v[it] / norm).toFloat() }
    }

    fun pcm16(audio: FloatArray): ShortArray =
        ShortArray(audio.size) { (audio[it] * 32767f).coerceIn(-32768f, 32767f).toInt().toShort() }

    /** xorshift32 (Marsaglia 13/17/5), as the Python reference has it. */
    private class XorShift(seed: Int) {
        private var s = seed.toLong() and MASK

        /** The next value in [0, 1). */
        fun unit(): Double {
            s = s xor ((s shl 13) and MASK)
            s = s xor (s ushr 17)
            s = s xor ((s shl 5) and MASK)
            return s / 4294967296.0
        }

        companion object { const val MASK = 0xffffffffL }
    }
}
