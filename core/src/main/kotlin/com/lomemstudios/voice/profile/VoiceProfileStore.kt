// The user's voice profile on the phone (plan, "Profile and model data on the phone"): the accepted enrolment samples
// as 16 kHz WAV, and the prints made from them, one file per model version, so a new model can recompute its print
// from the kept speech instead of asking the user to enrol again.
//
//   <root>/profile/
//     manifest.json             format, created, app version, samples (kind, text, seconds, level), prints
//     samples/NN-<kind>.wav     16 kHz mono 16-bit, the accepted samples
//     prints/<model>-<sha8>.bin the mean vectors, in VoicePrint's print format
//
// A new enrolment is written into profile.new/ and swapped in whole when it is complete: the old profile stays in use
// until then, and a crash midway leaves one or the other, never a mix. Delete removes every copy, and the print.bin
// that Claude Voice kept before the profile (the legacy folder). Each app has its own filesDir, so each enrols on its
// own. Nothing here sends anything anywhere.

package com.lomemstudios.voice.profile

import com.lomemstudios.voice.print.EnrolSample
import com.lomemstudios.voice.print.PrintStore
import com.lomemstudios.voice.print.levelDb
import com.lomemstudios.voice.print.readPrints
import com.lomemstudios.voice.print.writePrints
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.File
import java.io.RandomAccessFile

/** One kept sample, as the manifest lists it. */
data class KeptSample(val kind: String, val text: String, val seconds: Double, val levelDb: Double, val file: String)

data class ProfileStatus(
    val createdAt: Long,
    val appVersion: String,
    val samples: List<KeptSample>,
    /** The print files, by name ("titanet_small-ad4a1802"). */
    val prints: List<String>,
    /** Made from the old print.bin: a print with no speech kept, so no other model's print can be made from it. */
    val migrated: Boolean,
) {
    val secondsKept: Double get() = samples.sumOf { it.seconds }
}

class VoiceProfileStore(
    /** The app's voice folder: Voice Next uses `filesDir/voice`. */
    private val root: File,
    /** The folder of the old print.bin (Voice Next: `filesDir/voiceprint`), migrated once and deleted with the rest. */
    private val legacy: File? = null,
) {
    companion object {
        const val FORMAT = 1
        private const val RATE = 16000

        /** A print's name: the model and the first 8 hex digits of its SHA-256. */
        fun printName(model: String, sha256: String) = "$model-${sha256.take(8)}"
    }

    private val dir = File(root, "profile")
    /** The profile in use, for a copy elsewhere (Voice Next sends it to its server); read it under no write. */
    val folder: File get() = dir
    private val next = File(root, "profile.new")
    private val old = File(root, "profile.old")

    init {
        recover()
    }

    /** The profile in use, or null when there is none. */
    @Synchronized
    fun status(): ProfileStatus? {
        val m = File(dir, "manifest.json").takeIf { it.exists() } ?: return null
        val j = runCatching { JSONObject(m.readText()) }.getOrNull() ?: return null
        val samples = j.optJSONArray("samples") ?: JSONArray()
        val prints = j.optJSONArray("prints") ?: JSONArray()
        return ProfileStatus(
            createdAt = j.optLong("created"),
            appVersion = j.optString("app_version"),
            samples = (0 until samples.length()).map { samples.getJSONObject(it) }.map {
                KeptSample(it.getString("kind"), it.optString("text"), it.getDouble("seconds"), it.getDouble("level_db"), it.getString("file"))
            },
            prints = (0 until prints.length()).map { prints.getString(it) },
            migrated = j.optBoolean("migrated"),
        )
    }

    /** The named print's vectors, or null when the profile has none. */
    @Synchronized
    fun print(name: String): Map<String, FloatArray>? {
        val f = File(dir, "prints/$name.bin")
        return if (f.exists()) readPrints(f).takeIf { it.isNotEmpty() } else null
    }

    /** A kept sample's audio. */
    fun audio(sample: KeptSample): ShortArray = readWav(File(dir, "samples/${sample.file}"))

    /**
     * Adds a print made later from the kept samples (the voice gate's ECAPA print, made after the TitaNet print), or
     * replaces one of that name. The print file is written aside and renamed in, then the manifest the same way, so a
     * cut leaves either the old profile or the new one. False without a profile.
     */
    @Synchronized
    fun addPrint(name: String, vectors: Map<String, FloatArray>): Boolean {
        val m = File(dir, "manifest.json").takeIf { it.exists() } ?: return false
        val j = runCatching { JSONObject(m.readText()) }.getOrNull() ?: return false
        val tmp = File(dir, "prints/$name.bin.tmp")
        tmp.parentFile?.mkdirs()
        writePrints(tmp, vectors)
        if (!tmp.renameTo(File(dir, "prints/$name.bin"))) { tmp.delete(); return false }
        val prints = j.optJSONArray("prints") ?: JSONArray()
        if ((0 until prints.length()).none { prints.getString(it) == name }) prints.put(name)
        j.put("prints", prints)
        val mt = File(dir, "manifest.json.tmp")
        mt.writeText(j.toString(1))
        return mt.renameTo(m)
    }

    /** A new profile, written aside until [Draft.commit] swaps it in. */
    @Synchronized
    fun draft(appVersion: String, now: Long = System.currentTimeMillis()): Draft {
        next.deleteRecursively()
        File(next, "samples").mkdirs()
        File(next, "prints").mkdirs()
        return Draft(appVersion, now)
    }

    inner class Draft internal constructor(private val appVersion: String, private val created: Long) {
        private val samples = mutableListOf<KeptSample>()
        private val prints = mutableListOf<String>()
        private var migrated = false

        fun addSample(kind: String, text: String, pcm: ShortArray) {
            val name = "%02d-%s.wav".format(samples.size + 1, kind.replace(Regex("[^a-z0-9]+"), "_"))
            writeWav(File(next, "samples/$name"), pcm)
            samples += KeptSample(kind, text, pcm.size.toDouble() / RATE, levelDb(pcm), name)
        }

        fun putPrint(name: String, vectors: Map<String, FloatArray>) {
            writePrints(File(next, "prints/$name.bin"), vectors)
            prints += name
        }

        internal fun markMigrated() { migrated = true }

        /** Swaps the new profile in whole; the old one, and the old print.bin, are gone after. */
        fun commit(): Boolean = synchronized(this@VoiceProfileStore) {
            val manifest = JSONObject()
                .put("format", FORMAT)
                .put("created", created)
                .put("app_version", appVersion)
                .put("migrated", migrated)
                .put("samples", JSONArray(samples.map {
                    JSONObject().put("kind", it.kind).put("text", it.text).put("seconds", it.seconds)
                        .put("level_db", it.levelDb).put("file", it.file)
                }))
                .put("prints", JSONArray(prints))
            File(next, "manifest.json").writeText(manifest.toString(1))
            old.deleteRecursively()
            if (dir.exists() && !dir.renameTo(old)) return false
            if (!next.renameTo(dir)) {
                old.renameTo(dir)
                return false
            }
            old.deleteRecursively()
            if (!migrated) legacyFile()?.delete()
            true
        }

        fun discard() = synchronized(this@VoiceProfileStore) { next.deleteRecursively(); Unit }
    }

    /** Removes the profile, any half-written one, and the old print.bin. */
    @Synchronized
    fun deleteAll() {
        next.deleteRecursively()
        old.deleteRecursively()
        dir.deleteRecursively()
        legacyFile()?.delete()
        legacy?.takeIf { it.isDirectory && it.list().isNullOrEmpty() }?.delete()
    }

    /**
     * The old print.bin, when there is no profile yet, becomes a profile with that print under [printName] and no
     * kept speech. The old file stays until a new enrolment or a delete, so an older app build still finds it.
     * True when it migrated.
     */
    @Synchronized
    fun migrate(printName: String, appVersion: String, now: Long = System.currentTimeMillis()): Boolean {
        if (status() != null) return false
        val f = legacyFile() ?: return false
        val vectors = readPrints(f).takeIf { it.isNotEmpty() } ?: return false
        val d = draft(appVersion, now)
        d.putPrint(printName, vectors)
        d.markMigrated()
        return d.commit()
    }

    /** The profile as a [PrintStore] for one model's print: it migrates the old print.bin on first load. */
    fun printStore(printName: String, appVersion: String, clock: () -> Long = System::currentTimeMillis): PrintStore =
        object : PrintStore {
            override fun load(): Map<String, FloatArray> {
                print(printName)?.let { return it }
                if (migrate(printName, appVersion, clock())) print(printName)?.let { return it }
                return emptyMap()
            }

            override fun save(prints: Map<String, FloatArray>, samples: List<EnrolSample>): Boolean {
                val d = draft(appVersion, clock())
                return try {
                    samples.forEach { d.addSample(it.kind, it.text, it.pcm) }
                    d.putPrint(printName, prints)
                    d.commit()
                } catch (e: Exception) {
                    d.discard()
                    false
                }
            }
        }

    private fun legacyFile(): File? = legacy?.let { File(it, "print.bin") }?.takeIf { it.exists() }

    /** After a crash: a swap cut between its two renames is finished backwards (the old profile back in place), and
     *  a half-written profile is dropped. */
    private fun recover() {
        if (old.exists()) {
            if (dir.exists()) old.deleteRecursively() else old.renameTo(dir)
        }
        next.deleteRecursively()
    }
}

/** 16 kHz mono 16-bit PCM as a WAV file. */
fun writeWav(file: File, pcm: ShortArray, rate: Int = 16000) {
    RandomAccessFile(file, "rw").use { f ->
        f.setLength(0)
        val data = pcm.size * 2
        val b = java.nio.ByteBuffer.allocate(44 + data).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36 + data).put("WAVE".toByteArray())
        b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
        b.put("data".toByteArray()).putInt(data)
        pcm.forEach { b.putShort(it) }
        f.write(b.array())
    }
}

/** The samples of a WAV file written by [writeWav]. */
fun readWav(file: File): ShortArray {
    val bytes = DataInputStream(file.inputStream()).use { it.readBytes() }
    val b = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN)
    b.position(40)
    val n = minOf(b.int, bytes.size - 44) / 2
    return ShortArray(n) { b.getShort(44 + it * 2) }
}
