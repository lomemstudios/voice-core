// The user's voice print: made once at enrolment from each command said a few times and a few sentences read,
// kept on the phone only, never sent anywhere by this library. Where it is kept is a PrintStore: the voice profile
// (profile.VoiceProfileStore, which also keeps the accepted samples' audio), or the old single file print.bin in the
// folder the app gives (PrintFile). Plain logic over an Embedder, so it is unit-tested; the real embedder is the
// on-device model (the sherpa module's SherpaEmbedder).

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

/** One accepted enrolment sample: its kind (a command word, or [VoicePrint.SENTENCE]), what was to be said, its audio. */
class EnrolSample(val kind: String, val text: String, val pcm: ShortArray)

/** Where a print is kept: the named mean vectors ("" the general print, then one per command). */
interface PrintStore {
    fun load(): Map<String, FloatArray>
    /** Replaces the kept print, and the samples it was made from where the store keeps them; false when it could not. */
    fun save(prints: Map<String, FloatArray>, samples: List<EnrolSample>): Boolean
}

/** The print as one file, print.bin in [dir], with no audio kept: how Claude Voice kept it up to version 20. */
class PrintFile(private val dir: File) : PrintStore {
    private val file = File(dir, "print.bin")

    override fun load(): Map<String, FloatArray> = if (file.exists()) readPrints(file) else emptyMap()

    override fun save(prints: Map<String, FloatArray>, samples: List<EnrolSample>): Boolean {
        dir.mkdirs()
        val tmp = File(dir, "print.tmp")
        writePrints(tmp, prints)
        return tmp.renameTo(file)
    }
}

/** The print file's format: a count, then each name, its length and its floats. */
fun writePrints(file: File, prints: Map<String, FloatArray>) {
    DataOutputStream(file.outputStream()).use { d ->
        d.writeInt(prints.size)
        for ((name, v) in prints) {
            d.writeUTF(name)
            d.writeInt(v.size)
            v.forEach { d.writeFloat(it) }
        }
    }
}

fun readPrints(file: File): Map<String, FloatArray> = runCatching {
    DataInputStream(file.inputStream()).use { d ->
        (0 until d.readInt()).associate { d.readUTF() to FloatArray(d.readInt()) { d.readFloat() } }
    }
}.getOrDefault(emptyMap())

class VoicePrint(private val store: PrintStore, private val embedder: Embedder, val commands: List<String>) {
    /** The print in print.bin in [dir], as before the voice profile. */
    constructor(dir: File, embedder: Embedder, commands: List<String>) : this(PrintFile(dir), embedder, commands)

    companion object {
        const val SENTENCE = "sentence"
        const val PER_COMMAND = 3
        const val SENTENCES = 3
        private const val RATE = 16000
        /** A sample quieter than this (RMS, dB below full scale) is asked again. */
        private const val MIN_LEVEL_DB = -45.0
    }

    private val samples = mutableMapOf<String, MutableList<FloatArray>>()
    private val kept = mutableListOf<EnrolSample>()
    private var prints: Map<String, FloatArray> = store.load()

    val enrolled get() = "" in prints

    /** Starts enrolment again; the old print stays until the new one is saved. */
    fun restart() {
        samples.clear()
        kept.clear()
    }

    /** Reads the kept print again: after it was deleted, or replaced by another copy of the app's store. */
    fun reload() {
        prints = store.load()
    }

    /** Checks and adds one sample; [text] is what was to be said, kept with the audio where the store keeps it. */
    fun add(kind: String, pcm: ShortArray, text: String = ""): Sample {
        val seconds = pcm.size.toDouble() / RATE
        val (min, max) = if (kind == SENTENCE) 1.5 to 15.0 else 0.25 to 2.5
        if (seconds < min) return Sample.TOO_SHORT
        if (seconds > max) return Sample.TOO_LONG
        if (levelDb(pcm) < MIN_LEVEL_DB) return Sample.TOO_QUIET
        val e = embedder.embed(pcm) ?: return Sample.FAILED
        samples.getOrPut(kind) { mutableListOf() } += normalized(e)
        kept += EnrolSample(kind, text, pcm)
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
        if (!store.save(out, kept.toList())) return false
        prints = out
        restart()
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

/** RMS level of 16-bit audio, dB below full scale. */
fun levelDb(pcm: ShortArray): Double {
    if (pcm.isEmpty()) return -200.0
    var sum = 0.0
    for (s in pcm) { val v = s / 32768.0; sum += v * v }
    return 10 * log10(maxOf(sum / pcm.size, 1e-10))
}
