// The last few seconds of the microphone, kept so a turn's first word is not lost (0.4.0): the turn detector opens a
// turn a quarter second or more into the speech, and the app reads what came before from here when it does.

package com.lomemstudios.voice.audio

/** A ring of the last [seconds] of 16-bit audio. Not thread-safe: fed and read on one thread. */
class PreRoll(private val seconds: Double) {
    private var ring = ShortArray(0)
    private var write = 0
    private var size = 0
    /** The end time of the newest audio, seconds on the caller's clock. */
    var through = 0.0
        private set
    private var rate = 0.0

    /** One frame of [duration] seconds that ends at [t]. */
    fun add(frame: ShortArray, duration: Double, t: Double) {
        if (frame.isEmpty() || duration <= 0 || seconds <= 0) return
        if (ring.isEmpty()) {
            rate = frame.size / duration
            ring = ShortArray(maxOf(1, Math.round(seconds * rate).toInt()))
        }
        for (s in frame) {
            ring[write] = s
            write = (write + 1) % ring.size
        }
        size = minOf(ring.size, size + frame.size)
        through = t
    }

    /** The audio kept, oldest first. */
    fun read(): ShortArray {
        val out = ShortArray(size)
        val from = (write - size + ring.size) % maxOf(1, ring.size)
        for (i in 0 until size) out[i] = ring[(from + i) % ring.size]
        return out
    }

    /** The start time of [read]'s first sample. */
    val from: Double get() = if (rate == 0.0) through else through - size / rate

    fun clear() {
        size = 0
    }
}
