// The voice print and the keyword spotter on the phone, through sherpa-onnx (Apache 2.0): TitaNet small for the
// speaker embedding and a 3.3M-parameter zipformer keyword spotter, both offline, both from the app's assets.
// Chosen by Claude Voice's bench of 17 October (claude-voice android/bench).

package com.lomemstudios.voice.sherpa

import android.content.Context
import com.lomemstudios.voice.engines.EngineFactory
import com.lomemstudios.voice.engines.KeywordSpotting
import com.lomemstudios.voice.print.Embedder
import com.lomemstudios.voice.sherpa.kws.SherpaKeywords
import com.lomemstudios.voice.sherpa.print.SherpaEmbedder

/** Where the models sit in the app's assets. The defaults are where Claude Voice Next bundles them. */
data class SherpaModels(
    val titanet: String = "voiceprint/titanet_small.onnx",
    val kwsEncoder: String = "voiceprint/kws/encoder.onnx",
    val kwsDecoder: String = "voiceprint/kws/decoder.onnx",
    val kwsJoiner: String = "voiceprint/kws/joiner.onnx",
    val kwsTokens: String = "voiceprint/kws/tokens.txt",
)

object SherpaEngines {
    /**
     * The engines over the app's assets. [keywordsFile] is the app's own keyword list (its command words, in
     * sherpa-onnx's keywords format). [log] gets one line when a model will not load.
     */
    fun factory(
        context: Context,
        keywordsFile: String,
        log: (String) -> Unit,
        models: SherpaModels = SherpaModels(),
    ): EngineFactory =
        object : EngineFactory {
            override fun embedder(): Embedder = SherpaEmbedder(context, models.titanet, log)
            override fun keywords(): KeywordSpotting? = runCatching { SherpaKeywords(context, models, keywordsFile) }
                .onFailure { log("keyword spotter would not start: ${it.message}") }.getOrNull()
        }
}

internal fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }
