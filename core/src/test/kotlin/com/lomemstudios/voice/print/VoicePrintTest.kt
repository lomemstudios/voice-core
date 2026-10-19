package com.lomemstudios.voice.print

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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

    private fun voice(who: Int, seconds: Double = 0.6, level: Short = 8000): ShortArray =
        ShortArray((16000 * seconds).toInt()) { i -> if (i == 0) who.toShort() else (if (i % 2 == 0) level else (-level).toShort()) }

    private fun enrolAll(print: VoicePrint, who: Int = 1) {
        for (word in print.commands) repeat(3) { assertEquals(Sample.OK, print.add(word, voice(who))) }
        repeat(3) { assertEquals(Sample.OK, print.add(VoicePrint.SENTENCE, voice(who, seconds = 3.0))) }
    }

    @Test
    fun enrolmentNeedsEveryCommand() {
        val print = VoicePrint(folder.root, FakeEmbedder(), listOf("stop", "mute"))
        repeat(3) { print.add("stop", voice(1)) }
        repeat(3) { print.add(VoicePrint.SENTENCE, voice(1, seconds = 3.0)) }
        assertFalse(print.complete)
        assertEquals(listOf("mute"), print.missing())
        assertFalse(print.save())
        repeat(3) { print.add("mute", voice(1)) }
        assertTrue(print.complete)
        assertTrue(print.save())
        assertTrue(print.enrolled)
    }

    @Test
    fun aBadSampleIsAskedAgain() {
        val print = VoicePrint(folder.root, FakeEmbedder(), listOf("stop"))
        assertEquals(Sample.TOO_QUIET, print.add("stop", voice(1, level = 30)))
        assertEquals(Sample.TOO_SHORT, print.add("stop", voice(1, seconds = 0.1)))
        assertEquals(Sample.TOO_LONG, print.add("stop", voice(1, seconds = 4.0)))
        assertEquals(Sample.TOO_SHORT, print.add(VoicePrint.SENTENCE, voice(1, seconds = 0.8)))
        assertEquals(listOf("stop", VoicePrint.SENTENCE), print.missing())
    }

    @Test
    fun printStoredOnPhoneOnly() {
        val files = folder.newFolder("files")
        val print = VoicePrint(File(files, "voiceprint"), FakeEmbedder(), listOf("stop"))
        enrolAll(print)
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
        val first = VoicePrint(dir, FakeEmbedder(), listOf("stop"))
        enrolAll(first, who = 1)
        first.save()
        assertTrue(first.score(voice(1))!! > 0.9f)
        val second = VoicePrint(dir, FakeEmbedder(), listOf("stop"))
        second.restart()
        enrolAll(second, who = 2)
        second.save()
        val loaded = VoicePrint(dir, FakeEmbedder(), listOf("stop"))
        assertTrue(loaded.score(voice(2))!! > 0.9f)
        assertTrue(loaded.score(voice(1))!! < 0.2f)
    }

    @Test
    fun aCommandIsScoredAgainstItsOwnPrint() {
        val print = VoicePrint(folder.root, FakeEmbedder(), listOf("stop"))
        enrolAll(print, who = 3)
        print.save()
        assertTrue(print.scoreCommand("stop", voice(3))!! > 0.9f)
        assertNotNull(print.scoreCommand("repeat", voice(3)))  // not enrolled as a command: the general print
    }

    @Test
    fun notEnrolledScoresNothing() {
        val print = VoicePrint(folder.root, FakeEmbedder(), listOf("stop"))
        assertNull(print.score(voice(1)))
    }
}
