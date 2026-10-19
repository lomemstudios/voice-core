// The user's voice print: made once at enrolment from each command said a few times and a few sentences read,
// kept on the phone only (print.bin in the folder the app gives), never sent anywhere. Plain logic over an Embedder,
// so it is unit-tested; the real embedder is the on-device model (the sherpa module's SherpaEmbedder).

package com.lomemstudios.voice.print

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.log10
import kotlin.math.sqrt

/** Turns 16 kHz speech into a speaker embedding; null when it cannot. */
interface Embedder {
    val available: Boolean
    fun embed(pcm: ShortArray): FloatArray?
}

enum class Sample { OK, TOO_QUIET, TOO_SHORT, TOO_LONG, FAILED }

class VoicePrint(private val dir: File, private val embedder: Embedder, val commands: List<String>) {
    companion object {
        const val SENTENCE = "sentence"
        const val PER_COMMAND = 3
        const val SENTENCES = 3
        private const val RATE = 16000
        /** A sample quieter than this (RMS, dB below full scale) is asked again. */
        private const val MIN_LEVEL_DB = -45.0
    }

    private val file = File(dir, "print.bin")
    private val samples = mutableMapOf<String, MutableList<FloatArray>>()
    private var prints: Map<String, FloatArray> = load()

    val enrolled get() = "" in prints

    /** Starts enrolment again; the old print stays until the new one is saved. */
    fun restart() = samples.clear()

    fun add(kind: String, pcm: ShortArray): Sample {
        val seconds = pcm.size.toDouble() / RATE
        val (min, max) = if (kind == SENTENCE) 1.5 to 15.0 else 0.25 to 2.5
        if (seconds < min) return Sample.TOO_SHORT
        if (seconds > max) return Sample.TOO_LONG
        if (levelDb(pcm) < MIN_LEVEL_DB) return Sample.TOO_QUIET
        val e = embedder.embed(pcm) ?: return Sample.FAILED
        samples.getOrPut(kind) { mutableListOf() } += normalized(e)
        return Sample.OK
    }

    /** What still needs samples, commands first. */
    fun missing(): List<String> =
        commands.filter { (samples[it]?.size ?: 0) < PER_COMMAND } +
            (if ((samples[SENTENCE]?.size ?: 0) < SENTENCES) listOf(SENTENCE) else emptyList())

    val complete get() = missing().isEmpty()

    /** Writes the new print over the old one; false while enrolment is incomplete. */
    fun save(): Boolean {
        if (!complete) return false
        val all = samples.values.flatten()
        val out = mutableMapOf("" to mean(all))
        for (c in commands) out[c] = mean(samples[c]!!)
        dir.mkdirs()
        val tmp = File(dir, "print.tmp")
        DataOutputStream(tmp.outputStream()).use { d ->
            d.writeInt(out.size)
            for ((name, v) in out) {
                d.writeUTF(name)
                d.writeInt(v.size)
                v.forEach { d.writeFloat(it) }
            }
        }
        tmp.renameTo(file)
        prints = out
        samples.clear()
        return true
    }

    /** Cosine similarity of this speech with the user's print; null without a print. */
    fun score(pcm: ShortArray): Float? = against("", pcm)

    /** A command's half second, against that command's own print when enrolled, else the general one. */
    fun scoreCommand(word: String, pcm: ShortArray): Float? = against(if (word in prints) word else "", pcm)

    private fun against(name: String, pcm: ShortArray): Float? {
        val print = prints[name] ?: return null
        val e = embedder.embed(pcm) ?: return null
        return dot(normalized(e), print)
    }

    private fun load(): Map<String, FloatArray> {
        if (!file.exists()) return emptyMap()
        return runCatching {
            DataInputStream(file.inputStream()).use { d ->
                (0 until d.readInt()).associate { d.readUTF() to FloatArray(d.readInt()) { d.readFloat() } }
            }
        }.getOrDefault(emptyMap())
    }
}

private fun normalized(v: FloatArray): FloatArray {
    val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat().takeIf { it > 0f } ?: 1f
    return FloatArray(v.size) { v[it] / n }
}

private fun mean(vs: List<FloatArray>): FloatArray {
    val out = FloatArray(vs.first().size)
    for (v in vs) for (i in out.indices) out[i] += v[i]
    return normalized(out)
}

private fun dot(a: FloatArray, b: FloatArray): Float = a.indices.sumOf { (a[it] * b[it]).toDouble() }.toFloat()

private fun levelDb(pcm: ShortArray): Double {
    var sum = 0.0
    for (s in pcm) { val v = s / 32768.0; sum += v * v }
    return 10 * log10(maxOf(sum / pcm.size, 1e-10))
}
