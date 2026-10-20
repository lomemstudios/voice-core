package com.lomemstudios.voice.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** An embedder for tests: the "voice" is the first sample's value, so a fixture says whose voice it is. */
class FakeEmbedder : Embedder {
    override val available = true
    override fun embed(pcm: ShortArray): FloatArray? =
        if (pcm.isEmpty()) null else FloatArray(4) { i -> if (i == (pcm[0].toInt() and 3)) 1f else 0.05f }
}

class VoicePrintTest {
    @get:Rule val folder = TemporaryFolder()

    /** Speech of one voice: FakeEmbedder reads the voice from the first sample of each window, so every sample is it. */
    private fun voice(who: Int, seconds: Double = 3.0): ShortArray = ShortArray((16000 * seconds).toInt()) { (4000 + who).toShort() }

    private fun enrol(print: VoicePrint, who: Int = 1) {
        repeat(5) { print.add(voice(who, seconds = 3.2), "Piece $it.") }
    }

    @Test
    fun nothingKeptSavesNothing() {
        val print = VoicePrint(folder.root, FakeEmbedder())
        assertFalse(print.save())
        assertFalse(print.enrolled)
        print.add(voice(1))
        assertTrue(print.save())
        assertTrue(print.enrolled)
    }

    @Test
    fun theSpeechIsEmbeddedInThreeSecondWindows() {
        val rate = 16000
        fun lengths(vararg s: Double) = windows(s.map { ShortArray((it * rate).toInt()) }).map { it.size.toDouble() / rate }
        assertEquals(listOf(3.0, 3.0, 3.0, 3.0, 4.0), lengths(10.0, 6.0))  // a short last window joins the one before
        assertEquals(listOf(3.0, 1.5), lengths(4.5))
        assertEquals(listOf(2.0), lengths(2.0))  // shorter than a window: one window
        assertTrue(windows(emptyList()).isEmpty())
    }

    @Test
    fun printStoredOnPhoneOnly() {
        val files = folder.newFolder("files")
        val print = VoicePrint(File(files, "voiceprint"), FakeEmbedder())
        enrol(print)
        assertTrue(print.save())
        assertTrue(File(files, "voiceprint/print.bin").exists())
        // Nothing else is written: the print is the one file, in the app's folder. (That no code which talks to a
        // server can reach it is each app's own test.)
        val written = files.walk().filter { it.isFile }.map { it.relativeTo(files).path }.toList()
        assertEquals(listOf("voiceprint/print.bin"), written)
    }

    @Test
    fun reEnrolReplacesPrint() {
        val dir = File(folder.root, "vp")
        val first = VoicePrint(dir, FakeEmbedder())
        enrol(first, who = 1)
        first.save()
        assertTrue(first.score(voice(1))!! > 0.9f)
        val second = VoicePrint(dir, FakeEmbedder())
        second.restart()
        enrol(second, who = 2)
        second.save()
        val loaded = VoicePrint(dir, FakeEmbedder())
        assertTrue(loaded.score(voice(2))!! > 0.9f)
        assertTrue(loaded.score(voice(1))!! < 0.2f)
    }

    @Test
    fun notEnrolledScoresNothing() {
        val print = VoicePrint(folder.root, FakeEmbedder())
        assertNull(print.score(voice(1)))
    }
}
