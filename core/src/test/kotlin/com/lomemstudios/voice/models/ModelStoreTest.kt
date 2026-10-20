package com.lomemstudios.voice.models

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

class ModelStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val good = "the pinned model".toByteArray()
    private val spec = ModelSpec("test", "a test model", "test.onnx", ModelStore.sha256(good), good.size.toLong(), "MIT", "here")

    /** Assets as a map; counts how often each is opened, to see a check remembered. */
    private class Assets(val files: Map<String, ByteArray>) {
        val opened = mutableMapOf<String, Int>()
        fun open(path: String) = files[path]?.let { opened[path] = (opened[path] ?: 0) + 1; ByteArrayInputStream(it) }
    }

    private fun store(assets: Assets, stamp: String = "21-1000", download: (String) -> java.io.InputStream = { error("no download") }) =
        ModelStore(File(folder.root, "models"), assets::open, stamp, download)

    @Test
    fun aBundledModelThatMatchesIsOk() {
        val s = store(Assets(mapOf("models/test.onnx" to good))).register(spec, ModelSource.Bundled("models/test.onnx"))
        assertEquals(ModelState.OK, s.check(spec).state)
        assertArrayEquals(good, s.bytes(spec))
    }

    @Test
    fun aBundledModelWithOtherBytesIsAMismatchAndNeverRead() {
        val s = store(Assets(mapOf("models/test.onnx" to "another model".toByteArray())))
            .register(spec, ModelSource.Bundled("models/test.onnx"))
        val status = s.check(spec)
        assertEquals(ModelState.MISMATCH, status.state)
        assertEquals(ModelStore.sha256("another model".toByteArray()), status.actualSha256)
        try {
            s.bytes(spec)
            fail("bytes() returned a model whose SHA-256 does not match")
        } catch (e: ModelMismatch) {
            assertEquals(spec, e.spec)
        }
    }

    @Test
    fun aMissingOrUnregisteredModelSaysSo() {
        val s = store(Assets(emptyMap())).register(spec, ModelSource.Bundled("models/test.onnx"))
        assertEquals(ModelState.MISSING, s.check(spec).state)
        val other = spec.copy(name = "other")
        assertEquals("other ${spec.short}: not in this build", s.check(other).line)
    }

    @Test
    fun aBundledMatchIsHashedOncePerBuild() {
        val assets = Assets(mapOf("m" to good))
        store(assets).register(spec, ModelSource.Bundled("m")).check(spec)
        store(assets).register(spec, ModelSource.Bundled("m")).check(spec)
        assertEquals(1, assets.opened["m"])
        // A new build hashes it again.
        store(assets, stamp = "22-2000").register(spec, ModelSource.Bundled("m")).check(spec)
        assertEquals(2, assets.opened["m"])
    }

    @Test
    fun anOnDemandDownloadThatDoesNotMatchIsDeleted() {
        val s = store(Assets(emptyMap())) { ByteArrayInputStream("a tampered model".toByteArray()) }
            .register(spec, ModelSource.OnDemand("https://example.org/test.onnx"))
        try {
            s.fetch(spec)
            fail("fetch() kept a download whose SHA-256 does not match")
        } catch (e: ModelMismatch) {
            assertEquals(ModelStore.sha256("a tampered model".toByteArray()), e.actual)
        }
        assertEquals(emptyList<String>(), File(folder.root, "models").list()?.toList() ?: emptyList<String>())
        assertEquals(ModelState.MISSING, s.check(spec).state)
    }

    @Test
    fun anOnDemandDownloadThatMatchesIsKeptByItsHash() {
        var downloads = 0
        val s = store(Assets(emptyMap())) { downloads++; ByteArrayInputStream(good) }
            .register(spec, ModelSource.OnDemand("https://example.org/test.onnx"))
        val f = s.fetch(spec)
        assertEquals("${spec.sha256}-test.onnx", f.name)
        s.fetch(spec)
        assertEquals(1, downloads)
        assertEquals(ModelState.OK, s.check(spec).state)
        s.delete(spec)
        assertFalse(f.exists())
    }

    @Test
    fun theManifestPinsEveryModel() {
        val names = ModelManifest.ALL.map { it.name }
        assertEquals(names.distinct(), names)
        for (m in ModelManifest.ALL) {
            assertTrue(m.name, Regex("[0-9a-f]{64}").matches(m.sha256))
            assertTrue(m.name, m.bytes > 0 && m.licence.isNotBlank() && m.source.startsWith("https://"))
        }
        assertNotNull(ModelManifest.byName("pvad"))
        assertEquals("2114fd3c", ModelManifest.PVAD.short)
    }
}
