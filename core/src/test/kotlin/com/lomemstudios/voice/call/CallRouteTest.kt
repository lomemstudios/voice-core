package com.lomemstudios.voice.call

import android.media.AudioDeviceInfo.TYPE_BLE_HEADSET
import android.media.AudioDeviceInfo.TYPE_BLE_SPEAKER
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_EARPIECE
import android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioDeviceInfo.TYPE_HEARING_AID
import android.media.AudioDeviceInfo.TYPE_USB_HEADSET
import android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CallRouteTest {
    // What getAvailableCommunicationDevices lists on the POCO: the earpiece and the speaker, always.
    private val earpiece = AudioOut(1, TYPE_BUILTIN_EARPIECE, "23021RAA2Y")
    private val speaker = AudioOut(2, TYPE_BUILTIN_SPEAKER, "23021RAA2Y")
    private val car = AudioOut(40, TYPE_BLUETOOTH_SCO, "Car Multimedia")
    private val earbuds = AudioOut(41, TYPE_BLUETOOTH_SCO, "Redmi Buds 4")
    private val leBuds = AudioOut(42, TYPE_BLE_HEADSET, "Galaxy Buds3")
    private val wired = AudioOut(43, TYPE_WIRED_HEADSET, "USB-C headset")

    @Test
    fun aMediaOutputRunsAsMediaOverEveryCallRoute() {
        // 0.2.3: this phone's microphone with the answers on the earbuds as music (A2DP, type 8, its own id): no call
        // device, normal mode, even with the earbuds' hands-free link listed and picked.
        val music = AudioOut(51, 8, "Redmi Buds 4")
        val r = CallRoute.choose(listOf(earpiece, speaker, earbuds), useBluetooth = true, callAudio = true,
            preferredId = earbuds.id, mediaOutput = music)
        assertNull(r.device)
        assertFalse(r.call)
        assertEquals(music, r.output)
        assertEquals("Redmi Buds 4 (media)", r.label)
        assertFalse(r.sameAs(Route(null, call = false)))
        // Without one, the call route as before.
        assertEquals(earbuds, CallRoute.choose(listOf(earpiece, speaker, earbuds), useBluetooth = true, callAudio = true).device)
    }

    @Test
    fun nothingConnectedIsTheSpeakerNeverTheEarpiece() {
        val r = CallRoute.choose(listOf(earpiece, speaker), useBluetooth = true, callAudio = true)
        assertEquals(speaker, r.device)
        assertTrue(r.call)
        assertEquals("Speaker", r.label)
    }

    @Test
    fun theCarsHandsFreeWinsOverTheSpeaker() {
        val r = CallRoute.choose(listOf(earpiece, speaker, car), useBluetooth = true, callAudio = true)
        assertEquals(car, r.device)
        assertEquals("Car Multimedia", r.label)
    }

    @Test
    fun aWiredHeadsetWinsOverBluetooth() {
        // Plugged in by hand while the car is connected: the user wants the cable.
        val r = CallRoute.choose(listOf(speaker, car, wired), useBluetooth = true, callAudio = true)
        assertEquals(wired, r.device)
    }

    @Test
    fun leAudioWinsOverClassicBluetooth() {
        val r = CallRoute.choose(listOf(speaker, earbuds, leBuds), useBluetooth = true, callAudio = true)
        assertEquals(leBuds, r.device)
    }

    @Test
    fun bluetoothOffInSettingsStaysOnTheSpeaker() {
        val r = CallRoute.choose(listOf(speaker, car, leBuds), useBluetooth = false, callAudio = true)
        assertEquals(speaker, r.device)
    }

    @Test
    fun callAudioOffPlaysAsMediaOnTheSpeaker() {
        // The old path, kept as a switch for the field test: no communication mode, no device chosen.
        val r = CallRoute.choose(listOf(earpiece, speaker), useBluetooth = true, callAudio = false)
        assertNull(r.device)
        assertFalse(r.call)
    }

    @Test
    fun callAudioOffStillUsesHeadsetModeForBluetooth() {
        // The earbuds' microphone opens only on the call link, so Bluetooth is call audio either way.
        val r = CallRoute.choose(listOf(speaker, earbuds), useBluetooth = true, callAudio = false)
        assertEquals(earbuds, r.device)
        assertTrue(r.call)
    }

    @Test
    fun aSpeakerOnlyBluetoothDeviceIsNotAHeadset() {
        // An LE Audio speaker has no microphone: the phone's speaker and microphone stay together.
        val bleSpeaker = AudioOut(44, TYPE_BLE_SPEAKER, "Soundbar")
        val r = CallRoute.choose(listOf(speaker, bleSpeaker), useBluetooth = true, callAudio = true)
        assertEquals(speaker, r.device)
    }

    @Test
    fun hearingAidsAreBelowHeadsets() {
        val aid = AudioOut(45, TYPE_HEARING_AID, "Aid")
        assertEquals(earbuds, CallRoute.choose(listOf(speaker, aid, earbuds), useBluetooth = true, callAudio = true).device)
        assertEquals(aid, CallRoute.choose(listOf(speaker, aid), useBluetooth = true, callAudio = true).device)
    }

    @Test
    fun noDeviceAtAllFallsBackToMedia() {
        val r = CallRoute.choose(emptyList(), useBluetooth = true, callAudio = true)
        assertNull(r.device)
        assertFalse(r.call)
    }

    @Test
    fun theMicrophoneExpectedForEachOutput() {
        assertEquals(TYPE_BUILTIN_MIC, CallRoute.expectedInput(speaker))
        assertEquals(TYPE_BLUETOOTH_SCO, CallRoute.expectedInput(car))
        assertEquals(TYPE_BLE_HEADSET, CallRoute.expectedInput(leBuds))
        assertEquals(TYPE_WIRED_HEADSET, CallRoute.expectedInput(wired))
        assertEquals(TYPE_USB_HEADSET, CallRoute.expectedInput(AudioOut(46, TYPE_USB_HEADSET, "USB")))
    }

    @Test
    fun aRouteChangeIsOnlyAChangeOfDevice() {
        val onSpeaker = CallRoute.choose(listOf(speaker), useBluetooth = true, callAudio = true)
        val again = CallRoute.choose(listOf(earpiece, speaker), useBluetooth = true, callAudio = true)
        val inCar = CallRoute.choose(listOf(speaker, car), useBluetooth = true, callAudio = true)
        assertTrue(onSpeaker.sameAs(again))
        assertFalse(onSpeaker.sameAs(inCar))
    }

    @Test
    fun aPickedDeviceWinsWhileItIsListed() {
        // Picked over the order: the car while a wired headset is in, the speaker while the earbuds are connected.
        assertEquals(car, CallRoute.choose(listOf(speaker, car, wired), useBluetooth = true, callAudio = true, preferredId = car.id).device)
        assertEquals(speaker, CallRoute.choose(listOf(speaker, earbuds), useBluetooth = true, callAudio = true, preferredId = speaker.id).device)
    }

    @Test
    fun aPickedDeviceWinsOutsideTheOrderAndWithBluetoothOff() {
        val bleSpeaker = AudioOut(44, TYPE_BLE_SPEAKER, "Soundbar")
        assertEquals(bleSpeaker, CallRoute.choose(listOf(speaker, bleSpeaker), useBluetooth = true, callAudio = true, preferredId = bleSpeaker.id).device)
        assertEquals(earbuds, CallRoute.choose(listOf(speaker, earbuds), useBluetooth = false, callAudio = true, preferredId = earbuds.id).device)
    }

    @Test
    fun aPickedDeviceThatIsGoneLeavesTheOrderToDecide() {
        assertEquals(car, CallRoute.choose(listOf(speaker, car), useBluetooth = true, callAudio = true, preferredId = earbuds.id).device)
    }

    @Test
    fun theEarpieceIsNeverTakenEvenWhenPicked() {
        assertEquals(speaker, CallRoute.choose(listOf(earpiece, speaker), useBluetooth = true, callAudio = true, preferredId = earpiece.id).device)
    }
}
