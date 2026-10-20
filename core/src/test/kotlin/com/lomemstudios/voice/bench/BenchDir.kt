// Recordings and model outputs that may not be in this public repository (CLAUDE.md, "This repository is public"):
// tests read them from VOICE_BENCH_DIR and skip when it is unset or a file is missing.

package com.lomemstudios.voice.bench

import org.json.JSONObject
import org.junit.Assume.assumeTrue
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

object BenchDir {
    val dir: File? = System.getenv("VOICE_BENCH_DIR")?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }

    /** The file at [path] under the bench directory; the test is skipped without it. */
    fun need(path: String): File {
        assumeTrue("VOICE_BENCH_DIR is not set", dir != null)
        val f = File(dir, path)
        assumeTrue("$f is missing", f.exists())
        return f
    }

    fun json(path: String) = JSONObject(need(path).readText())

    /** Little-endian float32 with no header. */
    fun f32(f: File): FloatArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(b.remaining()).also { b.get(it) }
    }

    fun u8(f: File): ByteArray = f.readBytes()

    /** The samples of a 16-bit PCM WAV, whatever chunks come before its data. */
    fun wav(f: File): ShortArray {
        val b = ByteBuffer.wrap(f.readBytes()).order(ByteOrder.LITTLE_ENDIAN)
        b.position(12)
        while (b.remaining() >= 8) {
            val id = ByteArray(4).also { b.get(it) }.decodeToString()
            val size = b.int
            if (id == "data") {
                val n = minOf(size, b.remaining()) / 2
                return ShortArray(n) { b.short }
            }
            b.position(b.position() + size + (size and 1))
        }
        error("$f has no data chunk")
    }
}
