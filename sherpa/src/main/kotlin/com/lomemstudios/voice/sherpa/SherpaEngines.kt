// The voice print on the phone, through sherpa-onnx (Apache 2.0): TitaNet small for the speaker embedding, offline,
// from the app's assets. Chosen by Claude Voice's bench of 17 October (claude-voice android/bench).

package com.lomemstudios.voice.sherpa

import android.content.Context
import com.lomemstudios.voice.engines.EngineFactory
import com.lomemstudios.voice.print.Embedder
import com.lomemstudios.voice.sherpa.print.SherpaEmbedder

/** Where the models sit in the app's assets. The default is where Claude Voice Next bundles it. */
data class SherpaModels(
    val titanet: String = "voiceprint/titanet_small.onnx",
)

object SherpaEngines {
    /** The engine over the app's assets. [log] gets one line when a model will not load. */
    fun factory(
        context: Context,
        log: (String) -> Unit,
        models: SherpaModels = SherpaModels(),
    ): EngineFactory =
        object : EngineFactory {
            override fun embedder(): Embedder = SherpaEmbedder(context, models.titanet, log)
        }
}

internal fun floats(pcm: ShortArray) = FloatArray(pcm.size) { pcm[it] / 32768f }
