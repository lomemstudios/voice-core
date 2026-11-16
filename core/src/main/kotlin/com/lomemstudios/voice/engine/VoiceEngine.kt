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
// A media output (0.2.3): with this phone's microphone the app may want the answers on a device at full quality
// (`mediaOutput`). The route is then media: normal mode, no communication device, the recognition microphone kept on
// the phone (never a headset's), and the app points its players at [outputDevice] with [playUsage] (media).
//
// The microphone is read 50 ms at a time on the "audio" thread. Each frame goes to every tap (FrameFanOut: the
// recognizer pipe and any other consumer, each on its own thread with its own bounded queue) and, with its level, to
// the listener on the main thread, where the app's turn detector runs.
//
// The recorder recovers by itself (0.4.0): one that will not open or start, or fails while reading, is reported
// (MicHealth, through onMicHealth, never thrown) and opened again after a wait that doubles from 0.5 s to 8 s. Only the
// audio thread releases its recorder, after its last read, so stopping never frees a recorder inside read().

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
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
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
    /** The microphone's health changed (0.4.0): see [MicHealth]. */
    fun onMicHealth(health: MicHealth) {}
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
    /** Settings, read at each choice: the id in the outputs (getDevices(GET_DEVICES_OUTPUTS)) of a device the answers
     *  play on as media, with this phone's microphone; null for none. It wins over every call route while listed. */
    private val mediaOutput: () -> Int? = { null },
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
    /** Read by the audio thread, changed on the main thread: each recording has its own number. */
    @Volatile private var audioGeneration = 0
    private val fanOut = FrameFanOut()
    private val micWatch = MicWatch()
    private val backoff = Backoff()

    /** The microphone's health as last reported; null before the first report. */
    val micHealth: MicHealth? get() = micWatch.state

    /** The route in use; null before the first choice. */
    var route: Route? = null
        private set
    /** Call audio: communication mode, the call microphone, and answers played as call audio. */
    val call: Boolean get() = route?.call == true
    private var routeAttempts = 0
    private var aec: AcousticEchoCanceler? = null
    private var ns: NoiseSuppressor? = null

    /** The platform's echo canceller is attached and on. */
    val echoCancellerOn: Boolean get() = runCatching { aec?.enabled == true }.getOrDefault(false)

    /** The microphone the recording really comes from, as the platform routed it; null with no recording. */
    val inputDevice: AudioDeviceInfo? get() = runCatching { record?.routedDevice }.getOrNull()

    /** A consumer of every frame on its own thread (FrameFanOut.tap). */
    fun tap(name: String, capacity: Int, accept: () -> Boolean = { true }, onClose: () -> Unit = {},
            consumer: (ShortArray) -> Unit): FrameFanOut.Tap = fanOut.tap(name, capacity, accept, onClose, consumer)

    /** On a media output: true. */
    val media: Boolean get() = route?.output != null

    /** The usage everything the app plays carries now (CallPlayer). */
    fun playUsage(): Int = CallPlayer.usage(call, media)

    fun attributes(contentType: Int): AudioAttributes = CallPlayer.attributes(call, contentType, media)

    /** The device the app's players point at (setPreferredDevice): the media output; null on the call path, where the
     *  communication device decides, and on the old media path. */
    val outputDevice: AudioDeviceInfo? get() = route?.output?.let { out ->
        audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == out.id }
    }

    /** Opens the session's audio: follows devices, chooses the route and starts the microphone. Main thread. A
     *  microphone that will not open is reported through [VoiceEngineListener.onMicHealth], never thrown. */
    fun start() {
        running = true
        callbacks.add()
        chooseRoute("start")
    }

    /** Stops the microphone and every tap; the audio mode stays until [restoreAudioMode]. Main thread. Safe before
     *  [start] and a second time: it removes only the callbacks a start added. */
    fun stop() {
        running = false
        callbacks.remove()
        stopAudio()
        fanOut.close()
    }

    private fun log(line: String) = listener.onLog(line)

    private fun communicationDevices(): List<AudioOut> =
        audioManager.availableCommunicationDevices.map { AudioOut(it.id, it.type, it.productName?.toString() ?: "") }

    /** Chooses the route now and applies it when it changed, or always with [force]. */
    fun chooseRoute(why: String, force: Boolean = false) {
        if (!running) return
        val media = mediaOutput()?.let { id ->
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { it.id == id }
                ?.let { AudioOut(it.id, it.type, it.productName?.toString() ?: "") }
        }
        val next = CallRoute.choose(communicationDevices(), useBluetooth = useBluetooth(), callAudio = callAudio(),
            preferredId = preferredDevice(), mediaOutput = media)
        val action = RouteSteps.action(route, next, recording = record != null, force = force)
        if (action == RouteAction.KEEP) return
        applyRoute(next)
        route = next
        // The mode as the system has it, not as asked: without MODIFY_AUDIO_SETTINGS setMode is refused silently.
        log("route: ${next.label}, ${if (next.call) "call audio" else if (next.output != null) "media to type ${next.output.type}" else "media"}, mode ${modeName(audioManager.mode)} ($why)")
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
            val to = expected.output?.let { ", answers to type ${it.type}" } ?: ""
            log("recording from ${inputDevice?.productName ?: "unknown"} (type ${inputDevice?.type ?: -1}, media path$to)")
            return
        }
        val routed = inputDevice
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

    /** The two callbacks above, added at most once. Android throws when a listener is removed that was never added
     *  (stop() before start(), or twice: Claude Voice's crash of 6 November) or added a second time. */
    private val callbacks = Callbacks(
        adds = {
            audioManager.registerAudioDeviceCallback(deviceCallback, main)
            audioManager.addOnCommunicationDeviceChangedListener(mainExecutor, communicationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) audioManager.registerAudioRecordingCallback(recordingCallback, main)
        },
        removes = {
            audioManager.unregisterAudioDeviceCallback(deviceCallback)
            audioManager.removeOnCommunicationDeviceChangedListener(communicationListener)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) audioManager.unregisterAudioRecordingCallback(recordingCallback)
        },
    )

    /** Android silences a recording while another app with priority records (Android 10 and later): Taken. It gives
     *  zeros meanwhile and sound again by itself when the other app lets go, which reports Healthy. */
    private val recordingCallback = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
            val session = runCatching { record?.audioSessionId }.getOrNull() ?: return
            val ours = configs.firstOrNull { it.clientAudioSessionId == session } ?: return
            if (ours.isClientSilenced) health(micWatch.taken("Android silenced the recording: another app records"))
        }
    }

    /** Reports a health change to the app, and logs it. Main thread. */
    private fun health(change: MicHealth?) {
        change ?: return
        if (change == MicHealth.Healthy) backoff.reset()
        if (change !is MicHealth.Blank) log("microphone: ${describe(change)}")
        listener.onMicHealth(change)
    }

    private fun describe(h: MicHealth) = when (h) {
        MicHealth.Healthy -> "sound"
        is MicHealth.Blank -> "only zeros for ${h.ms} ms"
        is MicHealth.Dead -> "dead, ${h.why}"
        is MicHealth.Taken -> "taken, ${h.why}"
    }

    /** The recorder failed: report it and open it again after the next wait, unless the recording changed meanwhile. */
    private fun micFailed(change: MicHealth?, line: String) {
        log(line)
        health(change)
        val generation = audioGeneration
        val wait = backoff.next()
        log("opening the microphone again in $wait ms")
        main.postDelayed({
            if (running && generation == audioGeneration && record == null) startAudio()
        }, wait)
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
        val rec = try {
            AudioRecord(source, RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, max(min, CHUNK * 2 * 8))
        } catch (e: Exception) { // no permission, a bad argument on an odd phone
            return micFailed(micWatch.dead("could not open: $e"), "could not open the microphone: $e")
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            return micFailed(micWatch.dead("not initialized"), "the microphone would not initialize")
        }
        attachEffects(rec)
        route?.device?.let { out ->
            val want = CallRoute.expectedInput(out)
            if (want != AudioDeviceInfo.TYPE_BUILTIN_MIC) {
                audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull { it.type == want }?.let { rec.setPreferredDevice(it) }
            }
        }
        // On a media output the microphone is this phone's own, never a wired headset's.
        if (route?.output != null) audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_MIC }?.let { rec.setPreferredDevice(it) }
        val effects = listOfNotNull(aec, ns)
        val started = try {
            rec.startRecording()
            null
        } catch (e: Exception) {
            e
        }
        if (started != null || rec.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
            release(rec, effects)
            aec = null
            ns = null
            return if (started != null) micFailed(micWatch.dead("could not start: $started"), "the microphone would not start: $started")
            else micFailed(micWatch.taken("would not start: another app holds it"), "the microphone would not start: another app holds it")
        }
        record = rec
        val generation = ++audioGeneration
        audioThread = Thread {
            Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
            val buf = ShortArray(CHUNK)
            try {
                while (running && generation == audioGeneration) {
                    val n = rec.read(buf, 0, CHUNK)
                    if (n < 0) {
                        // A dead recorder returns its error at once, every time: stop reading and open a new one.
                        main.post { readFailed(generation, n) }
                        break
                    }
                    if (n == 0) {
                        SystemClock.sleep(5)
                        continue
                    }
                    val chunk = buf.copyOf(n)
                    fanOut.offer(chunk) // each tap drops when its consumer falls behind
                    val level = levelDB(chunk)
                    val zeros = allZero(chunk)
                    val ms = n * 1000L / RATE
                    main.post {
                        listener.onFrame(chunk, level)
                        if (generation == audioGeneration) health(micWatch.frame(zeros, ms))
                    }
                }
            } finally {
                // Only this thread releases its recorder, after its last read.
                release(rec, effects)
            }
        }.apply { name = "audio"; start() }
    }

    /** A read failed with [code] in recording [generation]: its thread has stopped and released the recorder. */
    private fun readFailed(generation: Int, code: Int) {
        if (!running || generation != audioGeneration) return
        record = null
        aec = null
        ns = null
        micFailed(micWatch.dead("read error $code"), "the microphone failed while reading (error $code)")
    }

    private fun release(rec: AudioRecord, effects: List<android.media.audiofx.AudioEffect>) {
        for (e in effects) runCatching { e.release() }
        runCatching { rec.stop() }
        runCatching { rec.release() }
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

    /** Ends the recording. Its thread leaves its loop at the next read and releases the recorder and its effects
     *  itself, so a recorder is never released while a read is inside it. */
    private fun stopAudio() {
        audioGeneration++
        val rec = record ?: return
        record = null
        aec = null
        ns = null
        runCatching { rec.stop() } // ends a blocked read
        audioThread?.join(500)
    }
}

/** Callbacks that are added once and removed once: a second add, or a remove with nothing added, does nothing. */
internal class Callbacks(private val adds: () -> Unit, private val removes: () -> Unit) {
    var added = false
        private set

    fun add() {
        if (added) return
        adds()
        added = true
    }

    fun remove() {
        if (!added) return
        added = false
        removes()
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
