// The decisions VoiceEngine takes when the route may change and when it checks the microphone after a change, as
// plain logic so they are unit-tested. Unchanged from Claude Voice's VoiceService (version 20).

package com.lomemstudios.voice.call

import android.media.AudioAttributes
import android.media.AudioManager

enum class RouteAction {
    /** The same route, and the microphone records: nothing to do. */
    KEEP,
    /** Set the communication device; the recording goes on, the platform moves the call microphone with it. */
    APPLY,
    /** Set it and restart the recording: there is none yet, or the call mode itself changed. */
    APPLY_AND_RESTART,
}

enum class MicCheck { OK, RETRY, GIVE_UP }

object RouteSteps {
    /** What to do when [next] is chosen while [current] is in use; [force] applies it even when it is the same. */
    fun action(current: Route?, next: Route, recording: Boolean, force: Boolean): RouteAction = when {
        !force && next.sameAs(current) && recording -> RouteAction.KEEP
        !recording || current?.call != next.call -> RouteAction.APPLY_AND_RESTART
        else -> RouteAction.APPLY
    }

    /** After a route change: the recording comes from [routedType] and should come from [wanted]; [failed] checks
     *  failed before this one. A Bluetooth link takes a moment, so it is tried again, [max] times at most. */
    fun micCheck(routedType: Int?, wanted: Int, failed: Int, max: Int = 3): MicCheck = when {
        routedType == wanted -> MicCheck.OK
        failed + 1 <= max -> MicCheck.RETRY
        else -> MicCheck.GIVE_UP
    }
}

/** How everything a session plays is marked, so it goes the session's way: as call audio, which the platform's echo
 *  canceller hears, or as media on the old path. */
object CallPlayer {
    fun usage(call: Boolean): Int = if (call) AudioAttributes.USAGE_VOICE_COMMUNICATION else AudioAttributes.USAGE_ASSISTANT

    /** The stream for a ToneGenerator. */
    fun toneStream(call: Boolean): Int = if (call) AudioManager.STREAM_VOICE_CALL else AudioManager.STREAM_MUSIC

    fun attributes(call: Boolean, contentType: Int): AudioAttributes =
        AudioAttributes.Builder().setUsage(usage(call)).setContentType(contentType).build()
}
