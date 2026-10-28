package com.lomemstudios.voice.call

import android.media.AudioDeviceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MicKindTest {
    @Test
    fun eachMicrophoneHasItsKind() {
        assertEquals(MicKind.PHONE, MicKind.of(AudioDeviceInfo.TYPE_BUILTIN_MIC))
        assertEquals(MicKind.EARBUDS, MicKind.of(AudioDeviceInfo.TYPE_BLUETOOTH_SCO))
        assertEquals(MicKind.EARBUDS, MicKind.of(AudioDeviceInfo.TYPE_BLE_HEADSET))
        assertEquals(MicKind.WIRED, MicKind.of(AudioDeviceInfo.TYPE_WIRED_HEADSET))
        assertEquals(MicKind.WIRED, MicKind.of(AudioDeviceInfo.TYPE_USB_HEADSET))
        assertEquals(MicKind.OTHER, MicKind.of(AudioDeviceInfo.TYPE_TELEPHONY))
        assertEquals(MicKind.OTHER, MicKind.of(null))
    }

    @Test
    fun aCarIsKnownOnlyFromWhatTheAppSays() {
        assertEquals(MicKind.CAR, MicKind.of(AudioDeviceInfo.TYPE_BLUETOOTH_SCO, car = true))
        // The phone's own microphone stays the phone's in the car (car mode keeps it).
        assertEquals(MicKind.PHONE, MicKind.of(AudioDeviceInfo.TYPE_BUILTIN_MIC, car = true))
    }

    @Test
    fun theWireNamesGoBothWays() {
        for (k in MicKind.entries) assertEquals(k, MicKind.ofWire(k.wire))
        assertNull(MicKind.ofWire("toaster"))
        assertEquals(listOf("phone", "earbuds", "car", "wired", "other"), MicKind.entries.map { it.wire })
    }
}
