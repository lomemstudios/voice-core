// The turn-detection algorithm, the reference for every port (CLAUDE.md, "The detector is the reference"). Claude
// Voice's Swift TurnCore (claude-voice, listener/Sources/TurnCore/TurnDetector.swift) is ported line for line from
// it: a change here is made there too.
//
// Silence is measured from the last loud buffer. When the sound stops the detector asks the caller to
// flush the recognizer (Flush), and a turn ends only once the caller reports that the recognizer has
// caught up (caughtUp). The words then decide how much silence ends the turn.
//
// Sound alone cannot end a turn in noise: a squeak above the margin every 50 ms keeps it open. So a turn with words
// also ends when its words have not changed for wordsStill (longer after a word that expects more), whatever the
// level, and no turn stays open past maxTurn.

package com.lomemstudios.voice.turn

class TurnConfig {
    /** Silence that ends a turn whose words look finished. */
    var baseSilence = 0.7
    /** Silence that ends a turn whose last word expects more ("and", "the", "um"). */
    var extendedSilence = 2.0
    /** Silence that ends a burst of sound with no words recognized: then it was noise, and is dropped. */
    var noWordsSilence = 0.7
    /** Silence after which the recognizer is asked to finish the words it has heard. */
    var flushAfter = 0.2
    /** When the recognizer never reports it caught up, the turn ends this much later than it would. */
    var catchUpTimeout = 1.0
    /** Buffers below this are digital silence, and leave the noise floor alone. */
    var digitalSilenceDB = -95f
    /** Audio after the microphone starts that sets the noise floor; it counts as no speech. */
    var floorSeedSeconds = 1.0
    /** The floor is set to this low percentile of the seed audio's levels. */
    var floorSeedPercentile = 0.1
    /** How far above the noise floor a buffer must be to count as speech. */
    var speechMarginDB = 12f
    /** Sound must stay above the margin this long to start a turn, so a click does not. */
    var minSpeech = 0.12
    /** Real words, beyond backchannels, that cut into a playing answer. */
    var bargeInWords = 2
    /** The recent levels the noise floor follows, so steady noise (an engine, a ship) lifts it within seconds. */
    var floorWindow = 3.0
    /** The floor follows this low percentile of the recent levels: speech has gaps, so it stays on the noise. */
    var floorPercentile = 0.2
    /** How fast the floor rises toward the recent levels, in dB a second (it drops at once). */
    var floorRiseDBPerS = 6f
    /** Noise that wavers needs more margin: the margin is at least this much above the noise's own spread
     *  (the recent levels' 90th percentile over the floor). */
    var spreadMarginDB = 5f
    /** A turn held open this long by sound with no words was noise: it is dropped, and the floor set again. */
    var noWordsMaxHold = 4.0
    /** A turn with words ends when its words have not changed this long, whatever the level: noise above the margin
     *  held turns open after the last word (19 October; 62 of 568 saved turns over 2 s). Inside normal turns the gap
     *  between word changes was under 1.76 s in 99 of 100, and the server joins speech that goes on within 3 s. */
    var wordsStill = 2.0
    /** The same when the words end on one that expects more ("and", "the", "um"). */
    var wordsStillExtended = 3.0
    /** A turn ended by its words is cut this long after their last change, or at its last sound if that is sooner. */
    var wordsEndPad = 1.0
    /** No turn stays open longer: with words it ends, without it is dropped. */
    var maxTurn = 60.0
}

sealed class TurnEvent {
    data class Started(val at: Double) : TurnEvent()
    /** The sound has stopped: have the recognizer finish everything up to [through], then call caughtUp. */
    data class Flush(val through: Double) : TurnEvent()
    /** The speaker has finished. [start] and [end] bound the speech, to cut the recording. */
    data class Ended(val text: String, val start: Double, val end: Double, val silence: Double) : TurnEvent()
    /** The speaker is cutting into the answer that is playing: stop it now. */
    data class BargeIn(val text: String, val at: Double) : TurnEvent()
    /** Sound or backchannel words that make no turn. */
    data class Dropped(val text: String, val at: Double, val why: String = "") : TurnEvent()
    /** Words with no sound since the last turn ended: the recognizer's late words of that turn. The caller
     *  forgets them, so they do not start the next turn either. */
    data class LateWords(val text: String, val at: Double) : TurnEvent()
}

class TurnDetector(val config: TurnConfig = TurnConfig()) {
    /** Set by the caller while an answer is playing. */
    var playing = false

    var noiseFloorDB = -60f
        private set
    private var inTurn = false
    private var loudSince: Double? = null
    private var turnStart = 0.0
    private var lastSound = 0.0
    /** When the turn's words last changed (its start until it has words). */
    private var textChangedAt = 0.0
    private var heardThrough = 0.0
    private var flushed = false
    private var text = ""
    private var bargedIn = false
    /** Sound above the margin was heard since the last turn ended, so new words may start a turn. */
    private var soundSinceEnd = true
    private val seedLevels = mutableListOf<Float>()
    private var seeded = 0.0
    private val recent = ArrayDeque<Float>()
    private var soft = false
    private var softFrom: Double? = null
    /** The levels since the last turn, for the noise's spread; emptied when a turn starts. */
    private val between = ArrayDeque<Float>()
    private var recentCapacity = 0
    /** The margin in force: [TurnConfig.speechMarginDB], or more when the noise wavers. */
    var marginDB = config.speechMarginDB
        private set
    /** The numbers of the turn now open or last ended, for the server's record of it. */
    var stats = TurnStats()
        private set

    /** Whether the noise floor has been set from the first second of audio. */
    val floorSeeded get() = seeded >= config.floorSeedSeconds - 1e-9

    /** One audio buffer's loudness, at the buffer's end time. */
    fun audio(levelDB: Float, duration: Double, t: Double): List<TurnEvent> {
        if (!floorSeeded) {
            seedNoiseFloor(levelDB, duration)
            return emptyList()
        }
        trackNoiseFloor(levelDB, duration)
        val events = mutableListOf<TurnEvent>()
        if (inTurn) stats.peakDB = maxOf(stats.peakDB, levelDB)
        // Softer sound, half the margin up, marks where words that start a turn began.
        if (levelDB > noiseFloorDB + marginDB / 2) {
            if (!soft) softFrom = t - duration
            soft = true
        } else {
            soft = false
        }
        if (levelDB > noiseFloorDB + marginDB) {
            soundSinceEnd = true
            if (loudSince == null) loudSince = t - duration
            val since = loudSince
            if (!inTurn && since != null && t - since >= config.minSpeech) events += start(since, "sound")
            if (inTurn) {
                lastSound = t
                flushed = false
            }
        } else {
            loudSince = null
        }
        if (inTurn && text.isEmpty() && t - turnStart >= config.noWordsMaxHold) {
            // Sound with no words for this long is noise the floor has not caught up with: the floor jumps to
            // the recent levels' middle, and the turn is dropped.
            noiseFloorDB = maxOf(noiseFloorDB, percentile(0.5))
            return events + endTurn(t, "held by noise")
        }
        if (inTurn) {
            if (!flushed && t - lastSound >= config.flushAfter) {
                flushed = true
                events += TurnEvent.Flush(t)
            }
            events += checkEnd(t)
        }
        if (inTurn && t - turnStart >= config.maxTurn - 1e-9) return events + endTurn(t, "", "cap", wordsEnd())
        if (inTurn && text.isNotEmpty() && t - textChangedAt >= wordsStill(text) - 1e-9) {
            // The recognizer has had its words unchanged for seconds, far past the 400 ms that counts as caught up,
            // so nothing waits for caughtUp here.
            return events + endTurn(t, "", "words still", wordsEnd())
        }
        return events
    }

    /** The recognizer's words for this turn so far. */
    fun words(newText: String, t: Double): List<TurnEvent> {
        val trimmed = newText.trim()
        if (trimmed == text) return emptyList()
        val events = mutableListOf<TurnEvent>()
        if (!inTurn) {
            if (trimmed.isEmpty()) return emptyList()
            // Words alone start a turn only after new sound: the recognizer can deliver the end of the last
            // turn seconds after it ended (the false turns of 16 and 17 October).
            if (!soundSinceEnd) return listOf(TurnEvent.LateWords(trimmed, t))
            // The words began with the last soft sound, not when the recognizer delivered them.
            val from = softFrom?.takeIf { it >= t - 2.0 } ?: t
            events += start(from, "words")
        }
        text = trimmed
        textChangedAt = t
        if (playing && !bargedIn && Words.cutsIn(text, config.bargeInWords)) {
            bargedIn = true
            events += TurnEvent.BargeIn(text, t)
        }
        return events
    }

    /** The recognizer has delivered every word for the audio up to [through]. */
    fun caughtUp(through: Double) {
        heardThrough = maxOf(heardThrough, through)
    }

    /**
     * The microphone is going off mid-turn (Mute): the open turn ends now, as if its silence had ended it, so its
     * words so far are sent. With no words, or only a backchannel, it is dropped as any such turn is. Nothing if no
     * turn is open.
     */
    fun finish(t: Double): List<TurnEvent> {
        if (!inTurn) return emptyList()
        val events = endTurn(t, "")
        if (events.firstOrNull() is TurnEvent.Ended) stats.endedBy = "mute"
        return events
    }

    /** The silence that ends the turn, given the words so far. */
    /** How long unchanged words end the turn, whatever the level. */
    fun wordsStill(text: String): Double = if (Words.expectsMore(text)) config.wordsStillExtended else config.wordsStill

    /** Where a turn ended by its words is cut: just after their last change, never after its last sound. */
    private fun wordsEnd(): Double = minOf(lastSound, textChangedAt + config.wordsEndPad)

    fun requiredSilence(text: String): Double {
        if (Words.list(text).isEmpty()) return config.noWordsSilence
        return if (Words.expectsMore(text)) config.extendedSilence else config.baseSilence
    }

    private fun start(t: Double, by: String): List<TurnEvent> {
        stats = TurnStats(startedBy = by, floorDB = noiseFloorDB, marginDB = marginDB,
            startLevelsDB = recent.toList().takeLast(6), peakDB = recent.lastOrNull() ?: -200f)
        inTurn = true
        between.clear()
        turnStart = t
        lastSound = t
        textChangedAt = t
        flushed = false
        text = ""
        bargedIn = false
        return listOf(TurnEvent.Started(t))
    }

    private fun checkEnd(t: Double): List<TurnEvent> {
        val silence = t - lastSound
        val needed = requiredSilence(text)
        val caught = heardThrough >= lastSound
        if (!(silence >= needed && (caught || silence >= needed + config.catchUpTimeout))) return emptyList()
        return endTurn(t, "")
    }

    /** [by] names what ended a turn that is sent; [end] is where its speech is cut. */
    private fun endTurn(t: Double, why: String, by: String = "silence", end: Double = lastSound): List<TurnEvent> {
        val silence = t - end
        inTurn = false
        loudSince = null
        soundSinceEnd = false
        val said = text
        text = ""
        stats.lengthS = t - turnStart
        stats.silenceS = silence
        if (why.isNotEmpty()) {
            stats.endedBy = why
            return listOf(TurnEvent.Dropped(said, t, why))
        }
        if (Words.list(said).isEmpty() || (Words.onlyBackchannel(said) && (playing || !Words.answersAQuestion(said)))) {
            stats.endedBy = if (said.isEmpty()) "no words" else "backchannel"
            return listOf(TurnEvent.Dropped(said, t, stats.endedBy))
        }
        stats.endedBy = by
        return listOf(TurnEvent.Ended(said, turnStart, end, silence))
    }

    /**
     * The floor starts from a low percentile of the first second's levels, not from -60 dB: road noise in a
     * car sits far above -60 dB, and with a floor that rises 0.5 dB a second every buffer counted as speech
     * for most of a minute. A floor set too high by early speech drops at once on the first quieter buffer.
     */
    private fun seedNoiseFloor(level: Float, duration: Double) {
        if (level < config.digitalSilenceDB) return
        seedLevels += level
        seeded += duration
        if (!floorSeeded) return
        val sorted = seedLevels.sorted()
        noiseFloorDB = sorted[((sorted.size - 1) * config.floorSeedPercentile).toInt()]
        recent.addAll(seedLevels)
        seedLevels.clear()
    }

    /**
     * The floor follows a low percentile of the last few seconds: down at once, up at a few dB a second. On the
     * ferry (17 October) the floor dropped at once to any single quiet buffer and rose 0.5 dB a second, so the
     * engine's waver above it started a turn every few seconds. The margin grows with the waver.
     */
    private fun trackNoiseFloor(level: Float, duration: Double) {
        if (level < config.digitalSilenceDB) return
        if (recentCapacity == 0) recentCapacity = maxOf(1, Math.round(config.floorWindow / duration).toInt())
        recent.addLast(level)
        while (recent.size > recentCapacity) recent.removeFirst()
        val target = percentile(config.floorPercentile)
        noiseFloorDB = if (target < noiseFloorDB) target else minOf(target, noiseFloorDB + config.floorRiseDBPerS * duration.toFloat())
        // The spread is measured only between turns, so speech does not widen it.
        if (inTurn) return
        between.addLast(level)
        while (between.size > recentCapacity) between.removeFirst()
        if (between.size >= recentCapacity) {
            val p90 = between.sorted()[((between.size - 1) * 0.9).toInt()]
            marginDB = maxOf(config.speechMarginDB, p90 - noiseFloorDB + config.spreadMarginDB)
        }
    }

    private fun percentile(p: Double): Float {
        if (recent.isEmpty()) return noiseFloorDB
        val sorted = recent.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }
}

/** One turn's numbers, sent with it so a field test shows why it started and ended. */
data class TurnStats(
    val startedBy: String = "",
    val floorDB: Float = -60f,
    val marginDB: Float = 12f,
    /** The last levels before the turn started, oldest first, 50 ms each. */
    val startLevelsDB: List<Float> = emptyList(),
    var peakDB: Float = -200f,
    var lengthS: Double = 0.0,
    var silenceS: Double = 0.0,
    var endedBy: String = "",
)

/** Word lists and the judgements built on them. */
object Words {
    private val continuation = setOf(
        "and", "or", "but", "so", "because", "then", "the", "a", "an", "to", "of", "in", "on", "at", "for",
        "with", "from", "about", "into", "if", "when", "that", "which", "who", "what", "where", "how", "is",
        "are", "was", "my", "your", "this", "these", "those", "like", "um", "uh", "erm", "hmm", "also",
        "maybe", "just", "can", "could", "should", "would", "will", "please", "i", "we", "you", "it's",
        "let's", "i'm", "there's",
    )
    private val fillers = setOf("um", "uh", "erm", "er", "hmm", "mm", "mhm", "mm-hm", "uh-huh", "ah", "oh")
    private val backchannel = fillers + setOf("yeah", "yes", "yep", "ok", "okay", "right", "sure", "cool", "nice", "got", "it", "i", "see")
    private val answers = setOf("yes", "yeah", "yep", "sure", "ok", "okay", "no", "nope")
    private val cues = setOf("stop", "wait", "pause", "hold", "sorry", "no")

    fun list(text: String): List<String> =
        text.lowercase().split(Regex("\\s+"))
            .map { w -> w.filter { it.isLetterOrDigit() || it == '\'' || it == '-' } }
            .filter { it.isNotEmpty() }

    fun expectsMore(text: String): Boolean = list(text).lastOrNull()?.let { it in continuation } ?: false

    fun onlyBackchannel(text: String): Boolean {
        val words = list(text)
        return words.isNotEmpty() && words.all { it in backchannel }
    }

    fun answersAQuestion(text: String): Boolean = list(text).any { it in answers }

    fun cutsIn(text: String, minWords: Int): Boolean {
        val words = list(text)
        if (words.any { it in cues }) return true
        return words.count { it !in backchannel } >= minWords
    }
}
