package com.lomemstudios.voice.profile

import com.lomemstudios.voice.print.FakeEmbedder
import com.lomemstudios.voice.print.PrintFile
import com.lomemstudios.voice.print.VoicePrint
import com.lomemstudios.voice.print.writePrints
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class VoiceProfileStoreTest {
    @get:Rule val folder = TemporaryFolder()

    private val name = VoiceProfileStore.printName("titanet_small", "ad4a1802485d8b34")
    private val files get() = folder.root
    private val voice get() = File(files, "voice")
    private val legacy get() = File(files, "voiceprint")

    private fun store() = VoiceProfileStore(voice, legacy)

    private fun tone(seconds: Double, level: Short = 8000) =
        ShortArray((16000 * seconds).toInt()) { if (it % 2 == 0) level else (-level).toShort() }

    private fun written(): List<String> = files.walk().filter { it.isFile }.map { it.relativeTo(files).path }.sorted().toList()

    @Test
    fun aDraftIsInvisibleUntilCommittedThenReplacesTheOldProfileWhole() {
        val s = store()
        val first = s.draft("0.21", now = 1000)
        first.addSample("stop", "stop", tone(0.5))
        first.putPrint(name, mapOf("" to floatArrayOf(1f, 0f)))
        assertTrue(first.commit())

        val second = s.draft("0.21", now = 2000)
        second.addSample("sentence", "The quick brown fox.", tone(3.0))
        second.putPrint(name, mapOf("" to floatArrayOf(0f, 1f)))
        // Until the swap, the old profile is the one in use.
        assertEquals(1000L, s.status()!!.createdAt)
        assertArrayEquals(floatArrayOf(1f, 0f), s.print(name)!![""], 0f)
        assertTrue(second.commit())

        val st = s.status()!!
        assertEquals(2000L, st.createdAt)
        assertEquals(listOf("sentence"), st.samples.map { it.kind })
        assertEquals(3.0, st.secondsKept, 1e-9)
        assertEquals("The quick brown fox.", st.samples[0].text)
        assertEquals(listOf(name), st.prints)
        assertArrayEquals(floatArrayOf(0f, 1f), s.print(name)!![""], 0f)
        assertArrayEquals(tone(3.0), s.audio(st.samples[0]))
        assertEquals(listOf("voice/profile/manifest.json", "voice/profile/prints/$name.bin", "voice/profile/samples/01-sentence.wav"), written())
    }

    @Test
    fun aSwapCutBetweenItsRenamesFallsBackToTheOldProfile() {
        val s = store()
        val d = s.draft("0.21", now = 1000)
        d.putPrint(name, mapOf("" to floatArrayOf(1f)))
        d.commit()
        // A crash after profile -> profile.old, before profile.new -> profile; and a draft left half-written.
        File(voice, "profile").renameTo(File(voice, "profile.old"))
        File(voice, "profile.new/samples").mkdirs()
        val again = store()
        assertEquals(1000L, again.status()!!.createdAt)
        assertFalse(File(voice, "profile.old").exists())
        assertFalse(File(voice, "profile.new").exists())
    }

    @Test
    fun deleteAllRemovesTheProfileAnyDraftAndTheOldPrint() {
        writePrints(File(legacy.apply { mkdirs() }, "print.bin"), mapOf("" to floatArrayOf(1f)))
        val s = store()
        val d = s.draft("0.21")
        d.addSample("stop", "stop", tone(0.5))
        d.putPrint(name, mapOf("" to floatArrayOf(1f)))
        d.commit()
        s.draft("0.21").addSample("mute", "mute", tone(0.5))  // a draft under way
        s.deleteAll()
        assertNull(s.status())
        assertNull(s.print(name))
        assertEquals(emptyList<String>(), written())
    }

    @Test
    fun theOldPrintBinMigratesWithNoSpeechAndStaysUntilANewEnrolment() {
        val vectors = mapOf("" to floatArrayOf(0.6f, 0.8f), "stop" to floatArrayOf(1f, 0f))
        writePrints(File(legacy.apply { mkdirs() }, "print.bin"), vectors)
        val s = store()
        val loaded = s.printStore(name, "0.21") { 5000 }.load()
        assertEquals(vectors.keys, loaded.keys)
        assertArrayEquals(vectors["stop"], loaded["stop"], 0f)
        val st = s.status()!!
        assertTrue(st.migrated)
        assertEquals(0, st.samples.size)
        assertEquals(5000L, st.createdAt)
        assertTrue(File(legacy, "print.bin").exists())
        // Migration happens once: a second load reads the profile.
        assertFalse(s.migrate(name, "0.21"))

        // A new enrolment through VoicePrint keeps its samples and replaces the old file.
        val print = VoicePrint(s.printStore(name, "0.21") { 6000 }, FakeEmbedder())
        assertTrue(print.enrolled)
        print.restart()
        repeat(5) { print.add(tone(3.5), "Piece $it.") }
        assertTrue(print.save())
        val after = s.status()!!
        assertFalse(after.migrated)
        assertEquals(5, after.samples.size)
        assertEquals(5 * 3.5, after.secondsKept, 1e-9)
        assertEquals(List(5) { "Piece $it." }, after.samples.filter { it.kind == VoicePrint.SPEECH }.map { it.text })
        assertFalse(File(legacy, "print.bin").exists())
        assertTrue(VoicePrint(s.printStore(name, "0.21"), FakeEmbedder()).enrolled)
    }

    @Test
    fun aPrintFileStoreKeepsNoAudio() {
        val print = VoicePrint(PrintFile(legacy), FakeEmbedder())
        repeat(3) { print.add(tone(3.0)) }
        assertTrue(print.save())
        assertEquals(listOf("voiceprint/print.bin"), written())
    }

    @Test
    fun aPrintAddedLaterJoinsTheProfileAndGoesWithIt() {
        val s = store()
        assertFalse("no profile to add to", s.addPrint("ecapa_sb-04b8df18", mapOf("" to floatArrayOf(1f))))
        val d = s.draft("0.24", now = 1000)
        d.addSample("speech", "", tone(1.0))
        d.putPrint(name, mapOf("" to floatArrayOf(1f, 0f)))
        assertTrue(d.commit())
        assertTrue(s.addPrint("ecapa_sb-04b8df18", mapOf("" to floatArrayOf(0.6f, 0.8f))))
        assertArrayEquals(floatArrayOf(0.6f, 0.8f), s.print("ecapa_sb-04b8df18")!![""], 0f)
        assertEquals(listOf(name, "ecapa_sb-04b8df18"), s.status()!!.prints)
        assertTrue("added again, replaced", s.addPrint("ecapa_sb-04b8df18", mapOf("" to floatArrayOf(1f, 0f))))
        assertEquals(listOf(name, "ecapa_sb-04b8df18"), s.status()!!.prints)
        assertEquals(1, s.status()!!.samples.size)
        s.deleteAll()
        assertNull(s.print("ecapa_sb-04b8df18"))
    }
}
