// Which kind of microphone a turn was heard on (0.2.4): the phone's own, earbuds or a headset over Bluetooth, the car's
// hands-free, or a wired or USB headset. The app sends it with each turn, so its
// numbers can be read per kind of microphone. Plain logic over AudioDeviceInfo's types, so it is unit-tested.

package com.lomemstudios.voice.call

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET

enum class MicKind(val wire: String) {
    PHONE("phone"), EARBUDS("earbuds"), CAR("car"), WIRED("wired"), OTHER("other");

    companion object {
        /** The kind of the microphone of [type] (an AudioDeviceInfo type; null when no device is known). Android cannot
         *  tell a car's hands-free from earbuds by type: the app knows it from the Bluetooth device's class ([car]). */
        fun of(type: Int?, car: Boolean = false): MicKind = when (type) {
            null -> OTHER
            TYPE_BUILTIN_MIC -> PHONE
            TYPE_BLUETOOTH_SCO, TYPE_BLE_HEADSET, TYPE_HEARING_AID -> if (car) CAR else EARBUDS
            TYPE_WIRED_HEADSET, TYPE_USB_HEADSET, TYPE_USB_DEVICE -> WIRED
            else -> OTHER
        }

        /** The kind its [wire] name names, or null. */
        fun ofWire(wire: String?): MicKind? = entries.firstOrNull { it.wire == wire }
    }
}
