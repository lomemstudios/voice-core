// The on-device engines behind the voice print and the command words. The real ones run on sherpa-onnx in the
// sherpa module (SherpaEngines); NoEngines is the stub for a build without it: nothing is verified, and commands come
// only from the recognizer's words.

package com.lomemstudios.voice.engines

import com.lomemstudios.voice.print.Embedder

/** Listens to the microphone for a fixed list of command words, offline. */
interface KeywordSpotting {
    /** Feeds 16 kHz audio; the command or wake word heard in it, lower case ("stop", "new topic"), or null. */
    fun feed(pcm: ShortArray): String?
    fun release()
}

interface EngineFactory {
    fun embedder(): Embedder
    fun keywords(): KeywordSpotting?
}

/** No engine in this build: an embedder that is never available and no keyword spotter. */
object NoEngines : EngineFactory {
    override fun embedder(): Embedder = object : Embedder {
        override val available = false
        override fun embed(pcm: ShortArray): FloatArray? = null
    }
    override fun keywords(): KeywordSpotting? = null
}
