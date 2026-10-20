// Free-speech enrolment: the user reads a short paragraph aloud or talks about anything for about half a minute,
// and this keeps the pieces of their speech that are clear enough, until enough seconds of speech are in. Plain logic
// over the microphone's frames and their levels, unit-tested; the app feeds it, draws the level, the progress and the
// line it gives, and makes the voice print from the accepted pieces (print.VoicePrint).
//
// It is lenient on purpose. The word-by-word guide it replaces refused a sample 14 dB over the noise floor as "too
// quiet" (it wanted -45 dB whatever the room) and a 2.65 s "yo" as "too long", until the user gave up (Claude Voice
// Next's field log, 20 October). Here a frame counts as speech from 6 dB over the floor, a piece of speech is refused
// only when it is on average less than 10 dB over the floor (too quiet) or clipped (too loud), and nothing is ever
// too long: a long piece is cut, so the progress moves while the user talks on. A noisy room is said, never refused.
//
// The microphone takes no sample while the app's guide speaks or a tone plays: the app calls hold() then, which drops
// a piece of speech still open (the guide's own voice coming back is never a sample), and feeds frames again after.

package com.lomemstudios.voice.enrol

import kotlin.math.roundToInt

/** The checks' numbers (starting values). */
data class EnrolConfig(
    /** Seconds of accepted speech from which the user may finish. */
    val needS: Double = 15.0,
    /** Seconds of accepted speech at which enrolment ends by itself. */
    val fullS: Double = 20.0,
    /** A frame is speech this far over the noise floor… */
    val marginDB: Float = 6f,
    /** …and at least this loud. */
    val minSpeechDB: Float = -58f,
    /** The room check: the first this long of listening only measures the noise floor. */
    val roomS: Double = 1.0,
    /** The noise floor is this percentile of the last [floorWindowS] of levels that were not speech (the room check,
     *  the gaps, and pieces refused as too quiet, which is how a room that gets louder is followed). Speech never
     *  raises it, so talking on without a pause still counts. */
    val floorPercentile: Double = 0.2,
    val floorWindowS: Double = 8.0,
    /** A piece of speech ends after this long without speech. */
    val gapS: Double = 0.8,
    /** A piece is cut at this length, so the progress moves while the user talks on. */
    val maxPieceS: Double = 8.0,
    /** A piece with less speech than this is a click, a cough or a breath: ignored, without a word. */
    val minSpeechS: Double = 0.4,
    /** A piece whose speech is on average less than this over the floor is too quiet. */
    val quietOverDB: Float = 10f,
    /** A piece with more than this share of its samples at full scale is too loud. */
    val clipShare: Double = 0.01,
    /** A floor above this is a noisy room: the user hears so, and enrolment goes on. */
    val noisyFloorDB: Float = -35f,
    /** Nothing heard for this long while listening: the user is asked to speak up. */
    val silentS: Double = 8.0,
    /** Audio kept before the first speech frame of a piece and after its last. */
    val padS: Double = 0.15,
)

enum class PieceResult { ACCEPTED, TOO_QUIET, TOO_LOUD }

/** A piece of speech that just ended: what became of it, its seconds of speech, how far over the floor it was. */
class Piece(val result: PieceResult, val speechS: Double, val overFloorDB: Float, val pcm: ShortArray)

class SpeechEnrolment(val config: EnrolConfig = EnrolConfig(), private val rate: Int = 16000) {
    companion object {
        /** A paragraph of about 30 s read aloud, plain words with no names, for the screen. */
        const val PARAGRAPH = "The ferry to the islands leaves early in the morning, so I set an alarm and pack the " +
            "night before. I like to sit on the deck with a coffee and watch the harbour fall away behind us. When " +
            "the wind picks up, the sea turns from blue to grey, and the boat rolls gently from side to side. By " +
            "noon the first island appears, white houses climbing a brown hill, and I start to think about lunch."
        private const val FULL_SCALE = 32_700
    }

    private val accepted = mutableListOf<ShortArray>()
    private val levels = ArrayDeque<Float>()

    /** Seconds of accepted speech. */
    var seconds = 0.0
        private set

    /** The accepted pieces' audio, in order. */
    val pieces: List<ShortArray> get() = accepted.toList()

    /** How far to [EnrolConfig.needS], 0 to 1. */
    val progress: Double get() = (seconds / config.needS).coerceIn(0.0, 1.0)

    /** Enough speech to finish. */
    val enough get() = seconds >= config.needS

    /** Enough to end by itself. */
    val full get() = seconds >= config.fullS

    /** The noise floor now, dB; very low before the first frame. */
    var floorDB = -90f
        private set

    /** The room check is done: speech counts from now. */
    val roomChecked get() = listenedS >= config.roomS - 1e-9

    /** A piece of speech is open: the user is talking. */
    val speaking get() = open != null

    /** The line to show now, plain words, and whether it is good news. */
    var note = "Start whenever you are ready."
        private set
    var noteOk = true
        private set

    // The open piece.
    private class Open(val pcm: MutableList<ShortArray>, var frames: Int = 0, var speechFrames: Int = 0,
                       var overSum: Double = 0.0, var clipped: Int = 0, var samples: Int = 0, var quietFrames: Int = 0,
                       val levels: MutableList<Float> = mutableListOf())
    private var open: Open? = null
    /** The last frames before a piece opens, so its first syllable is kept. */
    private val before = ArrayDeque<ShortArray>()
    private var sinceSpeechS = 0.0
    private var listenedS = 0.0
    private var heardAny = false

    /** "8 of 15 s". */
    fun progressLine(): String = "%d of %d s".format(seconds.toInt().coerceAtMost(config.needS.roundToInt()), config.needS.roundToInt())

    /** The guide or a tone is playing: an open piece is dropped, and nothing counts until frames come again. */
    fun hold() {
        open = null
        before.clear()
        sinceSpeechS = 0.0
    }

    /** One microphone frame and its level in dB below full scale; the piece that ended with it, or null. */
    fun feed(pcm: ShortArray, levelDB: Float): Piece? {
        val frameS = pcm.size.toDouble() / rate
        if (!roomChecked) {
            listenedS += frameS
            floor(levelDB, frameS)
            return null
        }
        val speech = levelDB >= maxOf(floorDB + config.marginDB, config.minSpeechDB)
        val piece = open
        if (!speech) floor(levelDB, frameS)
        if (piece == null) {
            if (!speech) {
                before.addLast(pcm)
                while (before.size * frameS > config.padS + 1e-9) before.removeFirst()
                sinceSpeechS += frameS
                if (sinceSpeechS >= config.silentS - 1e-9) {
                    sinceSpeechS = 0.0
                    say(if (heardAny) "I can't hear you now. Keep talking, a little louder." else
                        "I can't hear you. Speak up, or hold the phone closer.", ok = false)
                } else if (floorDB > config.noisyFloorDB && !enough) {
                    say("It's noisy here. Somewhere quieter works better, but go on.", ok = false)
                }
                return null
            }
            open = Open(before.toMutableList()).also { it.samples = before.sumOf { b -> b.size } }
            before.clear()
        }
        val p = open!!
        p.pcm += pcm
        p.levels += levelDB
        p.frames++
        p.samples += pcm.size
        p.clipped += pcm.count { it >= FULL_SCALE || it <= -FULL_SCALE }
        if (speech) {
            p.speechFrames++
            p.overSum += (levelDB - floorDB)
            p.quietFrames = 0
            sinceSpeechS = 0.0
            heardAny = true
        } else {
            p.quietFrames++
        }
        val gap = p.quietFrames * frameS
        val length = p.frames * frameS
        if (gap >= config.gapS || length >= config.maxPieceS) return close(frameS)
        return null
    }

    /** Ends an open piece now (the user tapped Done): the piece, or null when none was open. */
    fun finish(): Piece? = if (open == null) null else close(pcmFrameS())

    private fun pcmFrameS(): Double = open?.pcm?.lastOrNull()?.let { it.size.toDouble() / rate } ?: 0.05

    private fun close(frameS: Double): Piece? {
        val p = open ?: return null
        open = null
        // Trailing quiet beyond the pad is not speech.
        val trailing = ((p.quietFrames * frameS - config.padS) / frameS).toInt().coerceAtLeast(0)
        val frames = p.pcm.dropLast(trailing.coerceAtMost(p.pcm.size))
        val audio = ShortArray(frames.sumOf { it.size })
        var at = 0
        for (f in frames) { System.arraycopy(f, 0, audio, at, f.size); at += f.size }
        val speechS = p.speechFrames * frameS
        if (speechS < config.minSpeechS) return null
        val over = (p.overSum / p.speechFrames).toFloat()
        val result = when {
            p.clipped.toDouble() / p.samples.coerceAtLeast(1) > config.clipShare -> PieceResult.TOO_LOUD
            over < config.quietOverDB -> PieceResult.TOO_QUIET
            else -> PieceResult.ACCEPTED
        }
        when (result) {
            PieceResult.ACCEPTED -> {
                accepted += audio
                seconds += speechS
                say(when {
                    full -> "That's all I need. Thank you."
                    enough -> "That's enough. Tap Done, or keep talking."
                    else -> "Good, keep going."
                }, ok = true)
            }
            PieceResult.TOO_QUIET -> {
                // Most likely the room got louder: its levels go to the floor.
                p.levels.forEach { floor(it, frameS) }
                say("A bit quiet: speak up, or hold the phone closer.", ok = false)
            }
            PieceResult.TOO_LOUD -> say("Too loud: hold the phone a little further away.", ok = false)
        }
        return Piece(result, speechS, over, audio)
    }

    private fun floor(levelDB: Float, frameS: Double) {
        levels.addLast(levelDB)
        val keep = (config.floorWindowS / frameS).toInt().coerceAtLeast(1)
        while (levels.size > keep) levels.removeFirst()
        floorDB = percentile(levels, config.floorPercentile)
    }

    private fun say(text: String, ok: Boolean) {
        note = text
        noteOk = ok
    }
}

private fun percentile(values: Collection<Float>, p: Double): Float {
    val sorted = values.sorted()
    return sorted[((sorted.size - 1) * p).toInt()]
}
