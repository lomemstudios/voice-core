// Whether the voice gate may run in a session, and why not (plan "The gate falls back to today's detector whenever it
// cannot be trusted"). Pure logic; the app gathers the facts.

package com.lomemstudios.voice.gate

enum class GateMode(val wire: String) {
    OFF("off"),
    /** The Phase 1 default once enrolled: turns start as before, and another voice no longer holds one open. */
    ENDS("ends");
    // "Ends and filters" comes only after plan step 1.17 decides the defaults.

    companion object {
        fun of(wire: String?): GateMode = entries.firstOrNull { it.wire == wire } ?: OFF
    }
}

data class GateFacts(
    val mode: GateMode,
    /** The pVAD model is in this build and its bytes matched the pin. */
    val model: Boolean,
    /** The profile holds the ECAPA vector pVAD takes. */
    val print: Boolean,
    /** The phone speed test's pVAD p95 per 50 ms chunk, when it was run. */
    val benchP95Ms: Double?,
    /** The session runs over a Bluetooth headset or the car (SCO). */
    val sco: Boolean,
    /** Settings: use the gate on earbuds and in the car. */
    val onSco: Boolean,
    /** The session's health turned it off (five accepted turns without the track). */
    val healthOff: Boolean = false,
)

object GatePolicy {
    /** The plan's budget for pVAD on a 50 ms chunk (step 1.1: go when p95 <= 15 ms). */
    const val MAX_P95_MS = 15.0

    /** Null when the gate may run, else the cause, for the log's `gate: fallback <cause>` and the Your voice screen. */
    fun offBecause(f: GateFacts): String? = when {
        f.mode == GateMode.OFF -> "off in settings"
        !f.model -> "no pvad model in this build"
        !f.print -> "no voice print for the gate"
        f.benchP95Ms != null && f.benchP95Ms > MAX_P95_MS -> "this phone is too slow (speed test p95 %.1f ms)".format(f.benchP95Ms)
        f.sco && !f.onSco -> "off on earbuds and the car"
        f.healthOff -> "the track never opened on 5 of your turns"
        else -> null
    }
}
