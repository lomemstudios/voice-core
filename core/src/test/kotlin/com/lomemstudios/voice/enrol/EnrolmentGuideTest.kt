package com.lomemstudios.voice.enrol

import com.lomemstudios.voice.print.Sample
import com.lomemstudios.voice.print.VoicePrint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnrolmentGuideTest {
    private val prompt = "Say \"%s\", three times. Wait for the tone after each one."

    @Test
    fun theGuideWalksEveryCommandThenTheSentences() {
        val guide = EnrolmentGuide(listOf("stop", "mute"), sentences = listOf("The quick brown fox.", "A second line."))
        assertEquals(prompt.format("stop"), guide.prompt()?.say)
        repeat(3) { guide.heard(Sample.OK) }
        assertEquals(prompt.format("mute"), guide.prompt()?.say)
        guide.heard(Sample.OK)
        assertEquals("Too quiet. Say \"mute\" again.", guide.heard(Sample.TOO_QUIET)?.say)
        repeat(2) { guide.heard(Sample.OK) }
        assertEquals("Now read this sentence: The quick brown fox.", guide.prompt()?.say)
        assertEquals(VoicePrint.SENTENCE, guide.prompt()?.kind)
        guide.heard(Sample.OK)
        assertEquals("Now read this sentence: A second line.", guide.prompt()?.say)
        guide.heard(Sample.OK)
        assertNull(guide.prompt())
        assertTrue(guide.done)
        assertNull(guide.heard(Sample.OK))
    }

    @Test
    fun anAcceptedSampleGetsAToneAndItsCountAndNoSpeech() {
        val guide = EnrolmentGuide(listOf("stop", "mute"), sentences = listOf("One."))
        assertEquals("Word 1 of 2 · sample 1 of 3", guide.progress())
        val first = guide.heard(Sample.OK)!!
        assertEquals(Feedback(accepted = true, tone = EnrolmentGuide.TONE_OK, show = "\"stop\": 1 of 3", say = null), first)
        assertEquals("Word 1 of 2 · sample 2 of 3", guide.progress())
        assertEquals("\"stop\": 2 of 3", guide.heard(Sample.OK)!!.show)
        assertEquals("stop", guide.target)
    }

    @Test
    fun theThirdSampleMovesToTheNextWordAndSpeaksItsPrompt() {
        val guide = EnrolmentGuide(listOf("stop", "mute"), sentences = listOf("One."))
        repeat(2) { guide.heard(Sample.OK) }
        val third = guide.heard(Sample.OK)!!
        assertEquals("\"stop\": 3 of 3", third.show)
        assertEquals(prompt.format("mute"), third.say)
        assertEquals(EnrolmentGuide.TONE_OK, third.tone)
        assertEquals("mute", guide.target)
        assertEquals("Word 2 of 2 · sample 1 of 3", guide.progress())
    }

    @Test
    fun aRejectedSampleGivesTheAgainToneAndTheReasonAndCountsNothing() {
        val guide = EnrolmentGuide(listOf("stop"), sentences = listOf("One."))
        guide.heard(Sample.OK)
        val quiet = guide.heard(Sample.TOO_QUIET)!!
        assertEquals(Feedback(accepted = false, tone = EnrolmentGuide.TONE_AGAIN, show = "Too quiet. Say \"stop\" again.",
            say = "Too quiet. Say \"stop\" again."), quiet)
        assertEquals("Word 1 of 1 · sample 2 of 3", guide.progress())
        assertEquals("Too long: say the word once. Say \"stop\" again.", guide.heard(Sample.TOO_LONG)!!.show)
        assertEquals("Too short. Say \"stop\" again.", guide.heard(Sample.TOO_SHORT)!!.show)
        assertEquals("I could not use that. Say \"stop\" again.", guide.heard(Sample.FAILED)!!.show)
    }

    @Test
    fun theSentencesShowTheirOwnProgress() {
        val guide = EnrolmentGuide(listOf("stop"), sentences = listOf("One.", "Two."))
        repeat(3) { guide.heard(Sample.OK) }
        assertEquals("Sentence 1 of 2", guide.progress())
        assertEquals("One.", guide.target)
        assertTrue(guide.sentence)
        assertEquals("Too short. Read the sentence again.", guide.heard(Sample.TOO_SHORT)!!.say)
        val first = guide.heard(Sample.OK)!!
        assertEquals("Sentence 1 of 2 done", first.show)
        assertEquals("Now read this sentence: Two.", first.say)
        assertEquals("Sentence 2 of 2", guide.progress())
        val last = guide.heard(Sample.OK)!!
        assertEquals("Sentence 2 of 2 done", last.show)
        assertNull(last.say)
        assertTrue(guide.done)
        assertEquals("Done", guide.progress())
    }

    @Test
    fun silenceAndAHeldOpenTurnSayWhatHappened() {
        val guide = EnrolmentGuide(listOf("stop"), sentences = listOf("One."))
        val silent = guide.notHeard()
        assertEquals(EnrolmentGuide.TONE_AGAIN, silent.tone)
        assertEquals("I did not hear you. Say \"stop\".", silent.show)
        assertEquals("I did not hear you. Say \"stop\".", silent.say)
        assertFalse(silent.accepted)
        assertEquals("Too noisy: I could not tell where you stopped. Say \"stop\" again.", guide.noisy().show)
        assertEquals("Word 1 of 1 · sample 1 of 3", guide.progress())
        repeat(3) { guide.heard(Sample.OK) }
        assertEquals("I did not hear you. Read the sentence.", guide.notHeard().show)
    }
}
