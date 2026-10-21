// The microphone and the call path of a voice session, owned in one place (plan step 0.7): an app never opens
// AudioRecord or switches the audio mode itself. Moved from Claude Voice's VoiceService (version 20) unchanged.
//
// A session runs like a call (CallRoute): communication mode, the call microphone, and everything the app plays
// marked as call audio (CallPlayer) through one communication device, so the platform's echo canceller has what
// plays as its reference and the user can talk over an answer on the speaker. The route follows devices as they come
// and go (the car connecting, earbuds put in) without stopping the session: the platform moves the call microphone
// with the device, and the recording restarts only when the call mode itself changes or the microphone did not
// follow. The normal audio mode comes back with restoreAudioMode().
//
// The microphone is read 50 ms at a time on the "audio" thread. Each frame goes to every tap (FrameFanOut: the
// recognizer pipe, later the voice gate, each on its own thread with its own bounded queue) and, with its level, to
// the listener on the main thread, where the app's turn detector runs.

package com.lomemstudios.voice.engine

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Handler
import android.os.Looper
import com.lomemstudios.voice.audio.FrameFanOut
import com.lomemstudios.voice.call.AudioOut
import com.lomemstudios.voice.call.CallPlayer
import com.lomemstudios.voice.call.CallRoute
import com.lomemstudios.voice.call.MicCheck
import com.lomemstudios.voice.call.Route
import com.lomemstudios.voice.call.RouteAction
import com.lomemstudios.voice.call.RouteSteps
import kotlin.math.log10
import kotlin.math.max

/** What the engine tells the app, every call on the main thread. */
interface VoiceEngineListener {
    /** One frame of the microphone (16 kHz mono, 50 ms) and its level in dB below full scale. */
    fun onFrame(pcm: ShortArray, levelDB: Float)
    /** A new route was applied, before the recording restarts for it. */
    fun onRoute(route: Route)
    fun onLog(line: String)
}

class VoiceEngine(
    context: Context,
    /** Settings, read at each choice: Bluetooth headsets and the car may carry the session. */
    private val useBluetooth: () -> Boolean,
    /** Settings, read at each choice: call audio (the platform's echo canceller), else the old media path. */
    private val callAudio: () -> Boolean,
    private val listener: VoiceEngineListener,
    /** Settings, read at each choice: the id in availableCommunicationDevices of the device the user picked, which
     *  wins while it is listed; null for the automatic order. */
    private val preferredDevice: () -> Int? = { null },
) {
    companion object {
        const val RATE = 16000
        /** 50 ms. */
        const val CHUNK = 800
    }

    private val main = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager = context.getSystemService(AudioManager::class.java)
    private val mainExecutor = context.mainExecutor
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var audioThread: Thread? = null
    private var audioGeneration = 0
    private val fanOut = FrameFanOut()

    /** The route in use; null before the first choice. */
    var route: Route? = null
        private set
    /** Call audio: communication mode, the call microphone, and answers played as call audio. */
    val call: Boolean get() = route?.call == true
    private var routeAttempts = 0
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** The platform's echo canceller is attached and on. */
    val echoCancellerOn: Boolean get() = aec?.enabled == true

    /** The microphone the recording really comes from, as the platform routed it; null with no recording. */
    val inputDevice: AudioDeviceInfo? get() = runCatching { record?.routedDevice }.getOrNull()

    /** A consumer of every frame on its own thread (FrameFanOut.tap). */
    fun tap(name: String, capacity: Int, accept: () -> Boolean = { true }, onClose: () -> Unit = {},
            consumer: (ShortArray) -> Unit): FrameFanOut.Tap = fanOut.tap(name, capacity, accept, onClose, consumer)

    /** The usage everything the app plays carries now (CallPlayer). */
    fun playUsage(): Int = CallPlayer.usage(call)

    fun attributes(contentType: Int): AudioAttributes = CallPlayer.attributes(call, contentType)

    /** Opens the session's audio: follows devices, chooses the route and starts the microphone. Main thread. */
    fun start() {
        running = true
        audioManager.registerAudioDeviceCallback(deviceCallback, main)
        audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, communicationListener)
        chooseRoute("start")
    }

    /** Stops the microphone and every tap; the audio mode stays until [restoreAudioMode]. Main thread. */
    fun stop() {
        running = false
        audioManager.unregisterAudioDeviceCallback(deviceCallback)
        audioManager.removeOnCommunicationDeviceChangedListener(communicationListener)
        stopAudio()
        fanOut.close()
    }

    private fun log(line: String) = listener.onLog(line)

    private fun communicationDevices(): List<AudioOut> =
        audioManager.availableCommunicationDevices.map { AudioOut(it.id, it.type, it.productName?.toString() ?: "") }

    /** Chooses the route now and applies it when it changed, or always with [force]. */
    fun chooseRoute(why: String, force: Boolean = false) {
        if (!running) return
        val next = CallRoute.choose(communicationDevices(), useBluetooth = useBluetooth(), callAudio = callAudio(),
            preferredId = preferredDevice())
        val action = RouteSteps.action(route, next, recording = record != null, force = force)
        if (action == RouteAction.KEEP) return
        applyRoute(next)
        route = next
        // The mode as the system has it, not as asked: without MODIFY_AUDIO_SETTINGS setMode is refused silently.
        log("route: ${next.label}, ${if (next.call) "call audio" else "media"}, mode ${modeName(audioManager.mode)} ($why)")
        listener.onRoute(next)
        if (action == RouteAction.APPLY_AND_RESTART) {
            stopAudio()
            startAudio()
        }
        routeAttempts = 0
        main.postDelayed({ verifyRoute(next) }, 1500)
    }

    private fun applyRoute(r: Route) {
        val out = r.device
        if (!r.call || out == null) {
            restoreAudioMode()
            return
        }
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        if (audioManager.mode != AudioManager.MODE_IN_COMMUNICATION)
            log("Android refused communication mode, still ${modeName(audioManager.mode)}: is MODIFY_AUDIO_SETTINGS declared?")
        val info = audioManager.availableCommunicationDevices.firstOrNull { it.id == out.id }
        if (info == null || !audioManager.setCommunicationDevice(info)) log("could not route the call audio to ${r.label}")
    }

    private fun modeName(mode: Int) = when (mode) {
        AudioManager.MODE_NORMAL -> "normal"
        AudioManager.MODE_IN_COMMUNICATION -> "in communication"
        AudioManager.MODE_IN_CALL -> "in call"
        AudioManager.MODE_RINGTONE -> "ringtone"
        else -> mode.toString()
    }

    /** The phone's audio as it was before the session: no communication device of ours, the normal mode. */
    fun restoreAudioMode() {
        audioManager.clearCommunicationDevice()
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = AudioManager.MODE_NORMAL
    }

    /** A Bluetooth link takes a moment to open. If the recording does not come from the route's microphone, the
     *  route is applied again and the recording restarted, three times at most. */
    private fun verifyRoute(expected: Route) {
        if (!running || !expected.sameAs(route)) return
        val out = expected.device ?: run {
            log("recording from ${record?.routedDevice?.productName ?: "unknown"} (media path)")
            return
        }
        val routed = record?.routedDevice
        val want = CallRoute.expectedInput(out)
        val playing = audioManager.communicationDevice
        when (RouteSteps.micCheck(routed?.type, want, routeAttempts)) {
            MicCheck.OK -> {
                log("recording from ${routed?.productName} (type ${routed?.type}); call audio to ${playing?.productName ?: "none"} (type ${playing?.type ?: -1})")
                routeAttempts = 0
            }
            MicCheck.RETRY -> {
                routeAttempts++
                log("recording from ${routed?.productName ?: "unknown"} (type ${routed?.type ?: -1}), not type $want: " +
                    "applying ${expected.label} again, attempt $routeAttempts of 3")
                applyRoute(expected)
                stopAudio()
                startAudio()
                main.postDelayed({ verifyRoute(expected) }, 1500)
            }
            MicCheck.GIVE_UP -> {
                log("the microphone of ${expected.label} would not open after 3 attempts: recording from ${routed?.productName ?: "unknown"}")
                routeAttempts = 0
            }
        }
    }

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) {
            if (!running) return
            val names = added.joinToString { "${it.productName} (type ${it.type})" }
            // A Bluetooth device shows among the communication devices a little after it connects.
            main.postDelayed({ chooseRoute("connected: $names") }, 500)
        }

        override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) {
            if (!running) return
            chooseRoute("gone: ${removed.joinToString { "${it.productName} (type ${it.type})" }}")
        }
    }

    /** Another app (a phone call, a voice note) took the communication device: take it back once it is free. */
    private val communicationListener = AudioManager.OnCommunicationDeviceChangedListener { device ->
        val out = route?.device
        if (running && call && out != null && device?.id != out.id) main.postDelayed({ takeBack() }, 1000)
    }

    private fun takeBack() {
        val out = route?.device ?: return
        if (!running || !call || audioManager.communicationDevice?.id == out.id) return
        // A phone call owns the audio until it ends; ask again after it.
        if (audioManager.mode == AudioManager.MODE_IN_CALL || audioManager.mode == AudioManager.MODE_RINGTONE) {
            main.postDelayed({ takeBack() }, 2000)
            return
        }
        log("the call audio was moved to ${audioManager.communicationDevice?.productName ?: "nothing"}: taking it back")
        chooseRoute("taken back", force = true)
    }

    @SuppressLint("MissingPermission")
    private fun startAudio() {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        // The call microphone follows the communication device and carries the platform's echo canceller; the
        // recognition microphone is the old media path.
        val source = if (call) MediaRecorder.AudioSource.VOICE_COMMUNICATION else MediaRecorder.AudioSource.VOICE_RECOGNITION
        val rec = AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, CHUNK * 2 * 8))
        attachEffects(rec)
        route?.device?.let { out ->
            val want = CallRoute.expectedInput(out)
            if (want != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == want }?.let { rec.setPreferredDevice(it) }
            }
        }
        record = rec
        rec.startRecording()
        val generation = ++audioGeneration
        audioThread = Thread {
            val buf = ShortArray(CHUNK)
            while (running && generation == audioGeneration) {
                val n = rec.read(buf, 0, CHUNK)
                if (n <= 0) continue
                val chunk = buf.copyOf(n)
                fanOut.offer(chunk) // each tap drops when its consumer falls behind
                val level = levelDB(chunk)
                main.post { listener.onFrame(chunk, level) }
            }
        }.apply { name = "audio"; start() }
    }

    /** The phone's echo canceller and noise suppressor on the recording, where the phone has them; logged each time,
     *  with whether each really came on, because a phone can report one available that does nothing. */
    private fun attachEffects(rec: AudioRecord) {
        val aecAvailable = AcousticEchoCanceler.isAvailable()
        val nsAvailable = NoiseSuppressor.isAvailable()
        aec = if (aecAvailable) runCatching { AcousticEchoCanceler.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        ns = if (nsAvailable) runCatching { NoiseSuppressor.create(rec.audioSessionId)?.apply { enabled = true } }.getOrNull() else null
        fun state(available: Boolean, on: Boolean?) = when {
            !available -> "not available"
            on == true -> "on"
            on == false -> "attached but off"
            else -> "available but not attached"
        }
        log("audio effects on the ${if (call) "call" else "recognition"} microphone: echo canceller ${state(aecAvailable, aec?.enabled)}, " +
            "noise suppressor ${state(nsAvailable, ns?.enabled)}")
    }

    private fun stopAudio() {
        audioGeneration++
        val rec = record ?: return
        record = null
        rec.stop() // ends a blocked read
        audioThread?.join(500)
        aec?.release()
        ns?.release()
        aec = null
        ns = null
        rec.release()
    }
}

/** RMS level of 16-bit audio, dB below full scale. */
fun levelDB(samples: ShortArray): Float {
    var sum = 0.0
    for (s in samples) {
        val v = s / 32768.0
        sum += v * v
    }
    return (10 * log10(max(sum / samples.size, 1e-10))).toFloat()
}
