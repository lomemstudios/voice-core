// TitaNet small (NVIDIA NeMo, as released by sherpa-onnx) as the voice print's Embedder.

package com.lomemstudios.voice.sherpa.print

import android.content.Context
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractor
import com.k2fsa.sherpa.onnx.SpeakerEmbeddingExtractorConfig
import com.lomemstudios.voice.print.Embedder
import com.lomemstudios.voice.sherpa.floats

internal class SherpaEmbedder(context: Context, model: String, log: (String) -> Unit) : Embedder {
    private val extractor: SpeakerEmbeddingExtractor? = runCatching {
        SpeakerEmbeddingExtractor(context.assets, SpeakerEmbeddingExtractorConfig(model = model, numThreads = 2))
    }.onFailure { log("voice print model would not load: ${it.message}") }.getOrNull()

    override val available get() = extractor != null

    @Synchronized
    override fun embed(pcm: ShortArray): FloatArray? {
        val ex = extractor ?: return null
        val stream = ex.createStream()
        return try {
            stream.acceptWaveform(floats(pcm), 16000)
            stream.inputFinished()
            if (ex.isReady(stream)) ex.compute(stream) else null
        } finally {
            stream.release()
        }
    }
}
