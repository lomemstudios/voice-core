// The user's voice print: made once at enrolment from about 15 to 20 s of their free speech (enrol.SpeechEnrolment
// decides which speech counts), kept on the phone only, never sent anywhere by this library. Where it is kept is a
// PrintStore: the voice profile (profile.VoiceProfileStore, which also keeps the accepted speech as audio, ready for
// the pVAD's ECAPA vector later), or the old single file print.bin in the folder the app gives (PrintFile). Plain
// logic over an Embedder, so it is unit-tested; the real embedder is the on-device model (the sherpa module's
// SherpaEmbedder).

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

/** One accepted piece of enrolment speech: its kind ([VoicePrint.SPEECH]), what was read if anything, its audio. */
class EnrolSample(val kind: String, val text: String, val pcm: ShortArray)

/** Where a print is kept: the named mean vectors ("" the print; a profile of version 0.1 also has one per command
 *  word, which nothing reads any more). */
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

class VoicePrint(private val store: PrintStore, private val embedder: Embedder) {
    /** The print in print.bin in [dir], as before the voice profile. */
    constructor(dir: File, embedder: Embedder) : this(PrintFile(dir), embedder)

    companion object {
        const val SPEECH = "speech"
        /** The kept speech is embedded in windows this long: TitaNet small's error was lowest from 3 s of speech
         *  (Claude Voice's bench of 17 October: 10% on 1 s, 5.5% on 2 s, 3.7% on 3 s). */
        const val WINDOW_S = 3.0
        /** A last window shorter than this is not embedded on its own. */
        const val MIN_WINDOW_S = 1.5
        private const val RATE = 16000
    }

    private val kept = mutableListOf<EnrolSample>()
    private var prints: Map<String, FloatArray> = store.load()

    val enrolled get() = "" in prints

    /** Starts enrolment again; the old print stays until the new one is saved. */
    fun restart() {
        kept.clear()
    }

    /** Reads the kept print again: after it was deleted, or replaced by another copy of the app's store. */
    fun reload() {
        prints = store.load()
    }

    /** Keeps one piece of accepted speech; [text] is what was read, if the user read something. */
    fun add(pcm: ShortArray, text: String = "", kind: String = SPEECH) {
        if (pcm.isNotEmpty()) kept += EnrolSample(kind, text, pcm)
    }

    /** Seconds of audio kept so far. */
    val seconds: Double get() = kept.sumOf { it.pcm.size }.toDouble() / RATE

    /** Makes the print from the kept speech, joined and embedded in [WINDOW_S] windows, and writes it over the old one
     *  with the speech; false when nothing was kept, nothing could be embedded, or the store failed. */
    fun save(): Boolean {
        val vectors = windows(kept.map { it.pcm }).mapNotNull { embedder.embed(it) }.map(::normalized)
        if (vectors.isEmpty()) return false
        val out = mapOf("" to mean(vectors))
        if (!store.save(out, kept.toList())) return false
        prints = out
        restart()
        return true
    }

    /** Cosine similarity of this speech with the user's print; null without a print. */
    fun score(pcm: ShortArray): Float? {
        val print = prints[""] ?: return null
        val e = embedder.embed(pcm) ?: return null
        return dot(normalized(e), print)
    }
}

/** The pieces joined and cut into [VoicePrint.WINDOW_S] windows; a last window under [VoicePrint.MIN_WINDOW_S] joins
 *  the one before, and speech shorter than one window is one window. */
internal fun windows(pieces: List<ShortArray>, rate: Int = 16000): List<ShortArray> {
    val total = pieces.sumOf { it.size }
    if (total == 0) return emptyList()
    val all = ShortArray(total)
    var at = 0
    for (p in pieces) { System.arraycopy(p, 0, all, at, p.size); at += p.size }
    val size = (VoicePrint.WINDOW_S * rate).toInt()
    val min = (VoicePrint.MIN_WINDOW_S * rate).toInt()
    if (total < size + min) return listOf(all)
    val out = mutableListOf<ShortArray>()
    var from = 0
    while (total - from >= size + min) {
        out += all.copyOfRange(from, from + size)
        from += size
    }
    out += all.copyOfRange(from, total)
    return out
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
