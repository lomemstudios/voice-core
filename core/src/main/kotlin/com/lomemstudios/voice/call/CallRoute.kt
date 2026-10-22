// Where a session's audio goes, as plain logic so it is unit-tested. A session runs like a call (Claude Voice's
// PROGRESS.md, "Call-path audio"): communication mode, the call microphone and every answer played as call audio,
// through one communication device, so the platform's echo canceller has the answer as its reference and the
// microphone does not hear the answer as a person. The app lists AudioManager.availableCommunicationDevices and
// sets what this chooses with setCommunicationDevice; the platform then opens the matching microphone.
//
// Order: a wired or USB headset (plugged in by hand, so wanted), an LE Audio headset, classic Bluetooth hands-free
// (the car, or earbuds), a hearing aid, then the phone's loudspeaker. Never the earpiece: this is hands-free.
// Bluetooth can be turned off in Settings. A device the user picked (by its id in availableCommunicationDevices) wins
// over that order while it is listed, and the order decides again when it is not; the earpiece is never taken, picked
// or not. With call audio off, the old path: media playback and the recognition
// microphone on the speaker, and headset mode only for Bluetooth, whose microphone opens only on the call link.
//
// A media output (0.2.3, Claude Voice's two audio choices, 22 October): when the app's user keeps this phone's
// microphone and wants the answers on a device at full quality (earbuds as music, A2DP), the app passes that output and
// the route is media to it: normal mode, no communication device, the recognition microphone on the phone, and
// everything played as media pointed at that output (VoiceEngine.outputDevice). Android allows no call link then: a
// device's microphone opens only in call mode, which plays on that same device.

package com.lomemstudios.voice.call

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET

/** A communication device as AudioManager lists it: its id, AudioDeviceInfo type and product name. */
data class AudioOut(val id: Int, val type: Int, val name: String)

/** The chosen device, or null for the media path; [call]: communication mode and the call microphone; [output]: on
 *  the media path, the device answers play on (null: the platform's choice for media). */
data class Route(val device: AudioOut?, val call: Boolean, val output: AudioOut? = null) {
    /** For the screen and the log: "Speaker", or the headset's or car's name. */
    val label: String get() = when {
        output != null -> "${output.name.ifBlank { "Headphones" }} (media)"
        device == null -> "Speaker (media)"
        device.type == TYPE_BUILTIN_SPEAKER -> "Speaker"
        else -> device.name.ifBlank { "Headset" }
    }
    val bluetooth: Boolean get() = device != null && device.type in CallRoute.BLUETOOTH

    fun sameAs(other: Route?): Boolean = other != null && call == other.call && device?.id == other.device?.id &&
        output?.id == other.output?.id
}

object CallRoute {
    private val HEADSETS = listOf(TYPE_WIRED_HEADSET, TYPE_USB_HEADSET)
    val BLUETOOTH = setOf(TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO, TYPE_HEARING_AID)
    private val ORDER = HEADSETS + listOf(TYPE_BLE_HEADSET, TYPE_BLUETOOTH_SCO, TYPE_HEARING_AID, TYPE_BUILTIN_SPEAKER)

    /** [preferredId]: the device the user picked, by its id in [available]; it wins while it is listed, whatever its
     *  type and the Bluetooth setting, except the earpiece. [mediaOutput]: an output the app wants the answers on as
     *  media, with this phone's microphone; it wins over every call route, and the app passes it only when its user
     *  keeps the phone's microphone. */
    fun choose(available: List<AudioOut>, useBluetooth: Boolean, callAudio: Boolean, preferredId: Int? = null,
               mediaOutput: AudioOut? = null): Route {
        if (mediaOutput != null && mediaOutput.type != TYPE_BUILTIN_EARPIECE) return Route(null, call = false, output = mediaOutput)
        val picked = preferredId?.let { id -> available.firstOrNull { it.id == id && it.type != TYPE_BUILTIN_EARPIECE } }
        val allowed = available.filter { it.type in ORDER && (useBluetooth || it.type !in BLUETOOTH) }
        val best = picked ?: allowed.minByOrNull { ORDER.indexOf(it.type) } ?: return Route(null, call = false)
        if (!callAudio && best.type == TYPE_BUILTIN_SPEAKER) return Route(null, call = false)
        return Route(best, call = true)
    }

    /** The microphone the platform should open for [out], to check the recording really comes from it. */
    fun expectedInput(out: AudioOut): Int = when (out.type) {
        TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_WIRED_HEADSET, TYPE_USB_HEADSET -> out.type
        else -> TYPE_BUILTIN_MIC  // the speaker, and hearing aids, which have no microphone
    }
}
