// Where each model a consumer uses comes from, and the check that its bytes are the pinned ones (ModelManifest).
//
// A model is either bundled in the app's assets (Voice Next bundles TitaNet, pVAD and GTCRN) or
// fetched on demand into the store's folder, named by its SHA-256 (ECAPA, from plan step 1.2). Nothing is used
// before its SHA-256 matches: a fetched file that does not match is deleted, a bundled one that does not match is
// reported and not used. Hashing a 40 MB model takes a moment on a phone, so a bundled model that matched once is
// not hashed again for the same app build ([stamp]).

package com.lomemstudios.voice.models

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.security.MessageDigest

/** Where a model comes from. */
sealed interface ModelSource {
    /** In the app's assets, at this path. */
    data class Bundled(val asset: String) : ModelSource
    /** Fetched on first use from this URL into the store's folder. */
    data class OnDemand(val url: String) : ModelSource
}

enum class ModelState { OK, MISSING, MISMATCH }

data class ModelStatus(val spec: ModelSpec, val source: ModelSource?, val state: ModelState, val actualSha256: String? = null) {
    val line: String get() = "${spec.name} ${spec.short}: " + when (state) {
        ModelState.OK -> "ok"
        ModelState.MISSING -> if (source == null) "not in this build" else "missing"
        ModelState.MISMATCH -> "SHA-256 mismatch, got ${actualSha256?.take(8)}; not used"
    }
}

/** The bytes are not the pinned ones. */
class ModelMismatch(val spec: ModelSpec, val actual: String) :
    IOException("${spec.name}: SHA-256 $actual, expected ${spec.sha256}")

class ModelStore(
    /** The folder for fetched models and the check record: Voice Next uses `filesDir/voice/models`. */
    private val dir: File,
    /** Opens an asset of the app (`context.assets::open`); null or a throw when it is not there. */
    private val asset: (String) -> InputStream?,
    /** The app build, e.g. its version code and install time: a bundled model is hashed once per stamp. */
    private val stamp: String,
    /** Opens a URL for an on-demand fetch; replaced in tests. */
    private val download: (String) -> InputStream = { URL(it).openStream() },
) {
    private val sources = LinkedHashMap<String, Pair<ModelSpec, ModelSource>>()
    private val record = File(dir, "checked.txt")

    /** Says where [spec] comes from in this app. */
    fun register(spec: ModelSpec, source: ModelSource): ModelStore = apply { sources[spec.name] = spec to source }

    fun source(spec: ModelSpec): ModelSource? = sources[spec.name]?.second

    /** Whether [spec] is there and its bytes are the pinned ones. A bundled match is remembered for this stamp. */
    @Synchronized
    fun check(spec: ModelSpec): ModelStatus {
        val source = source(spec) ?: return ModelStatus(spec, null, ModelState.MISSING)
        when (source) {
            is ModelSource.Bundled -> {
                if (checked().contains(key(spec))) return ModelStatus(spec, source, ModelState.OK)
                val sha = openAsset(source.asset)?.use { sha256(it) } ?: return ModelStatus(spec, source, ModelState.MISSING)
                if (sha != spec.sha256) return ModelStatus(spec, source, ModelState.MISMATCH, sha)
                remember(spec)
                return ModelStatus(spec, source, ModelState.OK)
            }
            is ModelSource.OnDemand -> {
                val f = fetched(spec)
                if (!f.exists()) return ModelStatus(spec, source, ModelState.MISSING)
                val sha = f.inputStream().use { sha256(it) }
                return if (sha == spec.sha256) ModelStatus(spec, source, ModelState.OK)
                else ModelStatus(spec, source, ModelState.MISMATCH, sha)
            }
        }
    }

    /** Every registered model's status, in the order registered. */
    fun statuses(): List<ModelStatus> = sources.values.map { check(it.first) }

    /** The model's bytes, checked against the pin as they are read; a mismatch throws [ModelMismatch]. */
    fun bytes(spec: ModelSpec): ByteArray {
        val source = source(spec) ?: throw IOException("${spec.name} is not in this build")
        val data = when (source) {
            is ModelSource.Bundled -> openAsset(source.asset)?.use { it.readBytes() } ?: throw IOException("${spec.name}: no asset ${source.asset}")
            is ModelSource.OnDemand -> fetch(spec).readBytes()
        }
        val sha = sha256(data)
        if (sha != spec.sha256) throw ModelMismatch(spec, sha)
        if (source is ModelSource.Bundled) remember(spec)
        return data
    }

    /** An on-demand model's file, fetched first when it is not there; a download that does not match is deleted and
     *  throws [ModelMismatch]. */
    @Synchronized
    fun fetch(spec: ModelSpec): File {
        val source = source(spec) as? ModelSource.OnDemand ?: throw IOException("${spec.name} is not fetched on demand")
        val f = fetched(spec)
        if (f.exists() && f.inputStream().use { sha256(it) } == spec.sha256) return f
        dir.mkdirs()
        val part = File(dir, f.name + ".part")
        try {
            download(source.url).use { input -> part.outputStream().use { input.copyTo(it) } }
            val sha = part.inputStream().use { sha256(it) }
            if (sha != spec.sha256) throw ModelMismatch(spec, sha)
            if (!part.renameTo(f)) throw IOException("${spec.name}: could not move the download into place")
            return f
        } finally {
            part.delete()
        }
    }

    /** Deletes an on-demand model (ECAPA after enrolment); bundled ones stay in the APK. */
    fun delete(spec: ModelSpec) {
        fetched(spec).delete()
    }

    private fun fetched(spec: ModelSpec) = File(dir, "${spec.sha256}-${spec.file}")

    private fun openAsset(path: String): InputStream? = runCatching { asset(path) }.getOrNull()

    private fun key(spec: ModelSpec) = "${spec.sha256} $stamp"

    private fun checked(): Set<String> = if (record.exists()) record.readLines().toSet() else emptySet()

    private fun remember(spec: ModelSpec) {
        val lines = checked()
        if (key(spec) in lines) return
        dir.mkdirs()
        // Lines of older builds go: only this build's checks count.
        val kept = lines.filter { it.endsWith(" $stamp") } + key(spec)
        val tmp = File(dir, "checked.tmp")
        tmp.writeText(kept.joinToString("\n", postfix = "\n"))
        tmp.renameTo(record)
    }

    companion object {
        fun sha256(input: InputStream): String {
            val md = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(1 shl 16)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun sha256(data: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
