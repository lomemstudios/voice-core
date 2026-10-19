// The guide for enrolment: what to say next, the feedback for each sample, and the progress on screen.
// Plain logic; the app plays the tones, speaks the prompts between samples and records the answers, and draws
// the progress (in Claude Voice Next: VoiceService's enrolment mode and EnrolActivity).

package com.lomemstudios.voice.enrol

import com.lomemstudios.voice.print.Sample
import com.lomemstudios.voice.print.VoicePrint

data class Prompt(val say: String, val kind: String)

/** What follows a sample: a tone at once, a line on screen, and words to speak after the tone (null: none). */
data class Feedback(val accepted: Boolean, val tone: String, val show: String, val say: String?)

class EnrolmentGuide(
    private val commands: List<String>,
    private val sentences: List<String> = listOf(
        "The ferry to the islands leaves early in the morning, so I set an alarm.",
        "Remind me to check the bills and the plan when I get home tonight.",
        "I would like a short summary of what changed since yesterday.",
    ),
) {
    companion object {
        /** The app's sounds for a sample that counted and for one to say again. */
        const val TONE_OK = "got_it"
        const val TONE_AGAIN = "ignored"
    }

    private var command = 0
    private var said = 0
    private var read = 0

    val done get() = command >= commands.size && read >= sentences.size

    /** Whether the sentences have begun. */
    val sentence get() = command >= commands.size

    /** What to say now, as shown in large type: the word, or the sentence's text. */
    val target: String get() = when {
        !sentence -> commands[command]
        read < sentences.size -> sentences[read]
        else -> ""
    }

    /** "Word 2 of 5 · sample 1 of 3", counting the sample awaited now. */
    fun progress(): String = when {
        done -> "Done"
        !sentence -> "Word ${command + 1} of ${commands.size} · sample ${said + 1} of ${VoicePrint.PER_COMMAND}"
        else -> "Sentence ${read + 1} of ${sentences.size}"
    }

    /** What to ask for now, or null when enrolment is done. */
    fun prompt(): Prompt? = when {
        !sentence -> Prompt("Say \"${commands[command]}\", three times. Wait for the tone after each one.", commands[command])
        read < sentences.size -> Prompt("Now read this sentence: ${sentences[read]}", VoicePrint.SENTENCE)
        else -> null
    }

    /** One sample was taken: a tone and its count when it counted, the reason when it did not; null when done. */
    fun heard(result: Sample): Feedback? {
        if (done) return null
        if (result != Sample.OK) {
            val why = when (result) {
                Sample.TOO_QUIET -> "Too quiet"
                Sample.TOO_SHORT -> "Too short"
                Sample.TOO_LONG -> if (sentence) "Too long" else "Too long: say the word once"
                else -> "I could not use that"
            }
            return retry("$why. ${sayAgain()}")
        }
        if (!sentence) {
            said++
            val show = "\"${commands[command]}\": $said of ${VoicePrint.PER_COMMAND}"
            if (said < VoicePrint.PER_COMMAND) return Feedback(true, TONE_OK, show, null)
            command++
            said = 0
            return Feedback(true, TONE_OK, show, prompt()?.say)
        }
        read++
        return Feedback(true, TONE_OK, "Sentence $read of ${sentences.size} done", prompt()?.say)
    }

    /** Nothing was heard while the microphone waited for a sample. */
    fun notHeard(): Feedback =
        retry("I did not hear you. " + if (sentence) "Read the sentence." else "Say \"${commands[command]}\".")

    /** A sample that never ended: the noise around it held the turn open. */
    fun noisy(): Feedback = retry("Too noisy: I could not tell where you stopped. ${sayAgain()}")

    private fun sayAgain(): String = if (sentence) "Read the sentence again." else "Say \"${commands[command]}\" again."

    private fun retry(text: String) = Feedback(false, TONE_AGAIN, text, text)
}
