// The on-device engine behind the voice print. The real one runs on sherpa-onnx in the sherpa module
// (SherpaEngines); NoEngines is the stub for a build without it: nothing is verified.

package com.lomemstudios.voice.engines

import com.lomemstudios.voice.print.Embedder

interface EngineFactory {
    fun embedder(): Embedder
}

/** No engine in this build: an embedder that is never available. */
object NoEngines : EngineFactory {
    override fun embedder(): Embedder = object : Embedder {
        override val available = false
        override fun embed(pcm: ShortArray): FloatArray? = null
    }
}
