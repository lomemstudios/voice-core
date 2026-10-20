// The target voice's track (plan Phase 1, "The gate can end a turn sooner or keep one from starting, never hold one
// open"): open while the enrolled speaker talks, from pVAD's probability every 10 ms. The turn detector reads it to
// stop another voice holding a turn open (TurnDetector.target). Pure logic, so Claude Voice's Swift TurnCore can port
// it line for line.

package com.lomemstudios.voice.gate

class GateConfig {
    /** A reading at or above this opens the track (Sensitivity: Loose 0.5, Normal 0.7, Strict 0.8). */
    var threshold = 0.7f
    /** The track stays open this long after the last reading at or above the threshold. */
    var hang = 0.3
    /** Readings this far behind the audio are too late to decide on: the open turn falls back to the detector
     *  without the gate (plan "The gate falls back to today's detector"). */
    var maxLag = 0.25
    /** Speech-level audio with a reading below this counts as another voice (`other_s`, Phase 2's trigger). */
    var otherBelow = 0.3f
    /** The silence that ends a turn the gate shortened (sound with the track closed was left out): longer than the
     *  base silence, because pVAD misses the user's own voice now and then (13% of his frames in a quiet room). Chosen
     *  by the mixture replay (claude-voice PROGRESS.md, "1.9"): 1.0 s, with [leaveOutAboveDB] 8 dB, cut 2 of 48 of
     *  his turns in a quiet room where 0.7 s and 0 dB cut 14. */
    var heldOffSilence = 1.0
    /** The gate ends a turn only while the sound it leaves out goes on (heard this recently): another voice still
     *  talking. Once that sound stops, the silence counts from the last sound of any voice, as without the gate, so a
     *  pause of the user's own whose end pVAD missed does not cut the turn. */
    var ongoing = 0.2
    /** ...and has gone on for this long: the first buffers of the user's own voice after a pause come before their
     *  readings (which lag the audio by a buffer or two), and must not end the turn as if another voice talked. */
    var leftOutRun = 0.15
    /** Only sound this far above the speech margin is left out with the track closed: another voice at speech level.
     *  Quieter sound (a breath, the room, the tail of the user's own words) counts as without the gate. */
    var leaveOutAboveDB = 8f
}

class GateTrack(val config: GateConfig = GateConfig()) {
    /** The audio time of the latest reading; none yet before the first. */
    var readThrough = Double.NEGATIVE_INFINITY
        private set
    /** The latest reading's probability. */
    var p = 0f
        private set
    private var lastHigh = Double.NEGATIVE_INFINITY

    /** Whether the track is open as of the latest reading. */
    val open: Boolean get() = lastHigh.isFinite() && readThrough - lastHigh <= config.hang + 1e-9

    /** Where the track closed (the last high reading plus the hang), or would close. */
    val closedAt: Double get() = lastHigh + config.hang

    /** One reading: the probability for the 10 ms of audio ending at [t]. True when it changed whether the track is
     *  open. */
    fun reading(p: Float, t: Double): Boolean {
        val was = open
        this.p = p
        readThrough = t
        if (p >= config.threshold) lastHigh = t
        return open != was
    }

    /** The microphone restarted: the track starts closed, with no readings. */
    fun reset() {
        readThrough = Double.NEGATIVE_INFINITY
        lastHigh = Double.NEGATIVE_INFINITY
        p = 0f
    }
}

/** A turn's numbers for the gate, sent in the end body's `phone.gate` (plan "Trace and turn records"). */
data class GateStats(
    val mode: String = "ends",
    val threshold: Float = 0.7f,
    /** Seconds of the turn with the track open. */
    var openS: Double = 0.0,
    /** Seconds of the turn above the speech margin. */
    var speechS: Double = 0.0,
    /** Seconds of the turn above the speech margin with the reading under GateConfig.otherBelow. */
    var otherS: Double = 0.0,
    /** The turn ended sooner because sound with the track closed no longer held it. */
    var endedByGate: Boolean = false,
    /** Why the gate did not decide this turn: "never opened", "lag"; empty when it did. */
    var fallback: String = "",
)

/**
 * The session's fallback when the track never opens on the user's own turns (plan: "The track never opened on the
 * user's last 5 turns that passed the print check"): the print, recognizer or route then sees something pVAD does not,
 * and the gate goes off until the session restarts.
 */
class GateHealth(private val limit: Int = 5) {
    private var missed = 0
    var off = false
        private set

    /** One turn that passed the print check, and whether its track opened. True when the gate must go off now. */
    fun accepted(opened: Boolean): Boolean {
        if (off) return false
        missed = if (opened) 0 else missed + 1
        if (missed >= limit) off = true
        return off
    }
}
