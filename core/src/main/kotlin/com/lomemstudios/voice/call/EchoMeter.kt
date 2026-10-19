// How much of an answer the microphone still hears, measured on the phone while it plays. Plain logic, unit-tested.
//
// The saved turns could not rank echo cancellers (Claude Voice's PROGRESS.md, "Call-path audio"): the canceller has
// to be measured where it runs. While an item plays and the user is not speaking, the app feeds the microphone's
// level every 50 ms; at the item's end the median, against the noise floor at its start, says how far over the
// floor the answer came back. A working canceller leaves a few dB; none, on the speaker, 20 dB and more. The median
// leaves out a short word of the user's that the detector did not take as a turn.

package com.lomemstudios.voice.call

data class EchoReading(val index: String, val medianDB: Float, val floorDB: Float, val frames: Int) {
    val overFloorDB: Float get() = medianDB - floorDB
}

class EchoMeter(
    /** Fewer frames than this (50 ms each) tell nothing: one second. */
    private val minFrames: Int = 20,
) {
    private var index: String? = null
    private var floor = 0f
    private val levels = ArrayList<Float>()
    private val readings = ArrayList<Float>()

    val items: Int get() = readings.size

    /** An item started playing, with the noise floor as it is now. */
    fun start(index: String, floorDB: Float) {
        this.index = index
        floor = floorDB
        levels.clear()
    }

    /** The microphone's level for one frame; counted only while an item plays and the user is not speaking. */
    fun level(db: Float, userSpeaking: Boolean = false) {
        if (index != null && !userSpeaking) levels += db
    }

    /** The item stopped: its reading, or null when nothing played or it was too short to tell. */
    fun stop(): EchoReading? {
        val i = index ?: return null
        index = null
        if (levels.size < minFrames) return null
        val reading = EchoReading(i, median(levels), floor, levels.size)
        readings += reading.overFloorDB
        return reading
    }

    /** The median of every item's reading so far, for one line at the end of the session. */
    fun sessionMedianDB(): Float? = if (readings.isEmpty()) null else median(readings)

    private fun median(xs: List<Float>): Float {
        val s = xs.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }
}
