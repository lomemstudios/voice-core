// Whether speech is the user's, from its voice print score and how long a piece was scored. Plain logic,
// unit-tested; the score comes from VoicePrint. The app decides what a verdict does (Claude Voice Next's SpeakerGate
// drops another voice's turn and keeps it from cutting into an answer).
//
// Thresholds are cosine similarities against the user's print, set from Claude Voice's bench of 17 October
// (claude-voice android/bench, TitaNet small, 409 of the author's car turns against 80 other voices and Claude's own
// voice from the car speakers): on 3 s of speech, rejecting 5% of the user's turns accepts 1% of other voices and none
// of Claude's (threshold 0.32); on 2 s, 6% of other voices (0.23); on 1.5 s, 21% (0.16); on 1 s, 39% (0.12). A short
// turn is weakly checked. Without a voice print (score null) every turn is kept, as before.

package com.lomemstudios.voice.print

enum class TurnVerdict { KEEP, OTHER_VOICE }

class SpeakerCheck(
    var turnThreshold: Float = 0.32f,
    var bargeInThreshold: Float = 0.23f,
) {
    /** Whether a turn, scored on [seconds] of its speech, is the user's. A shorter piece scores lower for them too,
     *  so it needs less (the bench's thresholds that reject 5% of their turns at each length). */
    fun turn(score: Float?, seconds: Double = 3.0): TurnVerdict {
        if (score == null) return TurnVerdict.KEEP
        val needed = when {
            seconds >= 3.0 -> turnThreshold
            seconds >= 2.0 -> bargeInThreshold
            seconds >= 1.5 -> 0.16f
            else -> 0.12f
        }
        return if (score >= needed) TurnVerdict.KEEP else TurnVerdict.OTHER_VOICE
    }

    /** Whether talking over an answer (scored on about a second or two of speech) may cut into it. */
    fun bargeIn(score: Float?): Boolean = score == null || score >= bargeInThreshold
}
