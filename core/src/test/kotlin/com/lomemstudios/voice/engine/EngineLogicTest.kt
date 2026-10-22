package com.lomemstudios.voice.engine

import android.media.AudioAttributes
import android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO
import android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC
import android.media.AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
import android.media.AudioManager
import com.lomemstudios.voice.audio.FrameFanOut
import com.lomemstudios.voice.call.AudioOut
import com.lomemstudios.voice.call.CallPlayer
import com.lomemstudios.voice.call.MicCheck
import com.lomemstudios.voice.call.Route
import com.lomemstudios.voice.call.RouteAction
import com.lomemstudios.voice.call.RouteSteps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class EngineLogicTest {
    private val speaker = AudioOut(2, TYPE_BUILTIN_SPEAKER, "phone")
    private val car = AudioOut(40, TYPE_BLUETOOTH_SCO, "Car Multimedia")

    @Test
    fun anotherMediaOutputKeepsTheRecordingAndPlaysAsMedia() {
        val buds = AudioOut(51, 8, "Redmi Buds 4")
        val other = AudioOut(52, 8, "Pixel Buds")
        val onBuds = Route(null, call = false, output = buds)
        assertEquals(RouteAction.KEEP, RouteSteps.action(onBuds, Route(null, call = false, output = buds), recording = true, force = false))
        assertEquals(RouteAction.APPLY, RouteSteps.action(onBuds, Route(null, call = false, output = other), recording = true, force = false))
        assertEquals(RouteAction.APPLY_AND_RESTART, RouteSteps.action(Route(speaker, call = true), onBuds, recording = true, force = false))
        assertEquals(AudioAttributes.USAGE_MEDIA, CallPlayer.usage(call = false, media = true))
        assertEquals(AudioAttributes.USAGE_ASSISTANT, CallPlayer.usage(call = false))
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, CallPlayer.usage(call = true, media = true))
    }

    @Test
    fun theSameRouteWhileRecordingChangesNothing() {
        val r = Route(speaker, call = true)
        assertEquals(RouteAction.KEEP, RouteSteps.action(r, Route(speaker, call = true), recording = true, force = false))
        assertEquals(RouteAction.APPLY, RouteSteps.action(r, Route(speaker, call = true), recording = true, force = true))
    }

    @Test
    fun anotherDeviceInCallModeKeepsTheRecordingAndAModeChangeRestartsIt() {
        val onSpeaker = Route(speaker, call = true)
        assertEquals(RouteAction.APPLY, RouteSteps.action(onSpeaker, Route(car, call = true), recording = true, force = false))
        assertEquals(RouteAction.APPLY_AND_RESTART, RouteSteps.action(onSpeaker, Route(null, call = false), recording = true, force = false))
        assertEquals(RouteAction.APPLY_AND_RESTART, RouteSteps.action(null, onSpeaker, recording = false, force = false))
        assertEquals(RouteAction.APPLY_AND_RESTART, RouteSteps.action(onSpeaker, onSpeaker, recording = false, force = false))
    }

    @Test
    fun aMicrophoneThatDidNotFollowIsRetriedThreeTimes() {
        assertEquals(MicCheck.OK, RouteSteps.micCheck(TYPE_BLUETOOTH_SCO, TYPE_BLUETOOTH_SCO, failed = 2))
        assertEquals(listOf(MicCheck.RETRY, MicCheck.RETRY, MicCheck.RETRY, MicCheck.GIVE_UP),
            (0..3).map { RouteSteps.micCheck(TYPE_BUILTIN_MIC, TYPE_BLUETOOTH_SCO, failed = it) })
        assertEquals(MicCheck.RETRY, RouteSteps.micCheck(null, TYPE_BLUETOOTH_SCO, failed = 0))
    }

    @Test
    fun whatPlaysIsCallAudioOnlyOnTheCallPath() {
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION, CallPlayer.usage(call = true))
        assertEquals(AudioAttributes.USAGE_ASSISTANT, CallPlayer.usage(call = false))
        assertEquals(AudioManager.STREAM_VOICE_CALL, CallPlayer.toneStream(call = true))
        assertEquals(AudioManager.STREAM_MUSIC, CallPlayer.toneStream(call = false))
    }

    @Test
    fun everyTapGetsEveryFrameOnItsOwnThread() {
        val fan = FrameFanOut()
        val got = Collections.synchronizedList(mutableListOf<String>())
        val done = CountDownLatch(6)
        fan.tap("a", 10) { got += "a${it[0]}:${Thread.currentThread().name}"; done.countDown() }
        fan.tap("b", 10) { got += "b${it[0]}:${Thread.currentThread().name}"; done.countDown() }
        for (i in 1..3) fan.offer(shortArrayOf(i.toShort()))
        assertTrue(done.await(2, TimeUnit.SECONDS))
        assertEquals(listOf("a1:a", "a2:a", "a3:a", "b1:b", "b2:b", "b3:b"), got.sorted())
        fan.close()
    }

    @Test
    fun aSlowTapDropsItsOwnFramesAndHoldsUpNoOne() {
        val fan = FrameFanOut()
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val fastDone = CountDownLatch(20)
        val slow = fan.tap("slow", 3) { entered.countDown(); release.await() }
        fan.tap("fast", 40) { fastDone.countDown() }
        fan.offer(shortArrayOf(0))
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        // The microphone's side, on a thread of its own so a blocking offer fails here instead of hanging the test.
        val mic = Thread { repeat(19) { fan.offer(shortArrayOf((it + 1).toShort())) } }.apply { isDaemon = true; start() }
        mic.join(1000)
        assertFalse("the microphone's thread was held up by the slow tap", mic.isAlive)
        assertTrue(fastDone.await(2, TimeUnit.SECONDS))
        // The slow tap holds one frame in its consumer and three in its queue; the other 16 are dropped.
        assertEquals(16, slow.dropped)
        release.countDown()
        fan.close()
    }

    @Test
    fun aTapThatDoesNotAcceptTakesNothingAndAClosedTapEnds() {
        val fan = FrameFanOut()
        var deaf = true
        val heard = Collections.synchronizedList(mutableListOf<Short>())
        val closed = CountDownLatch(1)
        val tap = fan.tap("recognizer", 10, accept = { !deaf }, onClose = { closed.countDown() }) { heard += it[0] }
        fan.offer(shortArrayOf(1))
        deaf = false
        fan.offer(shortArrayOf(2))
        Thread.sleep(100)
        assertEquals(listOf<Short>(2), heard.toList())
        tap.close()
        assertTrue(closed.await(2, TimeUnit.SECONDS))
        fan.offer(shortArrayOf(3))
        Thread.sleep(50)
        assertEquals(listOf<Short>(2), heard.toList())
    }

    @Test
    fun levelsAreDbBelowFullScale() {
        assertEquals(-100f, levelDB(ShortArray(800)), 0.01f)
        assertEquals(0f, levelDB(ShortArray(800) { if (it % 2 == 0) Short.MIN_VALUE else Short.MIN_VALUE }), 0.01f)
    }
}
