// The microphone's health as the engine sees it (0.4.0), so an app needs no watchdogs of its own for a recorder that
// will not open, dies, is taken by another app, or gives only exact zeros. MicWatch and Backoff are the decisions,
// kept apart from AudioRecord so they are tested on the JVM.

package com.lomemstudios.voice.engine

/** What [VoiceEngineListener.onMicHealth] reports. */
sealed class MicHealth {
    /** Sound comes from the microphone: at the start, and again after any of the others. */
    data object Healthy : MicHealth()
    /** Only exact zeros for [ms] so far: reported at [MicWatch.blankAfterMs], then every [MicWatch.blankEveryMs]
     *  while it lasts. Zeros are also the platform's own silence (an answer on the call path, another app
     *  recording), which only the app can tell apart; the engine does not reopen on them. */
    data class Blank(val ms: Long) : MicHealth()
    /** The recorder would not open, or failed while reading. The engine opens it again with a growing wait. */
    data class Dead(val why: String) : MicHealth()
    /** Another app holds the microphone (a phone call, a voice note): it would not start, or Android silenced it.
     *  The engine tries again with a growing wait when it would not start; a silenced one comes back by itself. */
    data class Taken(val why: String) : MicHealth()
}

/** Turns frames and failures into [MicHealth] changes: each call returns the event to report, or null. */
class MicWatch(val blankAfterMs: Long = 300, val blankEveryMs: Long = 1000) {
    /** The last reported state; null before the first. */
    var state: MicHealth? = null
        private set
    private var zeroMs = 0L
    private var nextBlankAt = 0L

    /** One frame of [ms] milliseconds; [allZero] when every sample is exactly zero. */
    fun frame(allZero: Boolean, ms: Long): MicHealth? {
        if (!allZero) {
            zeroMs = 0
            if (state == MicHealth.Healthy) return null
            return MicHealth.Healthy.also { state = it }
        }
        zeroMs += ms
        val s = state
        if (s is MicHealth.Dead || s is MicHealth.Taken) return null
        if (s is MicHealth.Blank) {
            if (zeroMs < nextBlankAt) return null
            nextBlankAt += blankEveryMs
            return MicHealth.Blank(zeroMs).also { state = it }
        }
        if (zeroMs < blankAfterMs) return null
        nextBlankAt = zeroMs + blankEveryMs
        return MicHealth.Blank(zeroMs).also { state = it }
    }

    fun dead(why: String): MicHealth? = change(MicHealth.Dead(why))

    fun taken(why: String): MicHealth? = change(MicHealth.Taken(why))

    private fun change(to: MicHealth): MicHealth? {
        zeroMs = 0
        if (state?.javaClass == to.javaClass) return null
        state = to
        return to
    }
}

/** The waits between attempts to open the microphone again: [firstMs], doubling up to [maxMs], until [reset]. */
class Backoff(private val firstMs: Long = 500, private val maxMs: Long = 8000) {
    private var nextMs = firstMs

    fun next(): Long = nextMs.also { nextMs = minOf(maxMs, nextMs * 2) }

    fun reset() {
        nextMs = firstMs
    }
}

/** Whether every sample is exactly zero: the platform's silence or a dead recorder, never a real microphone. */
fun allZero(samples: ShortArray): Boolean {
    for (s in samples) if (s.toInt() != 0) return false
    return samples.isNotEmpty()
}
