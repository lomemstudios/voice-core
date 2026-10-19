// The 3.3M-parameter zipformer keyword spotter (GigaSpeech, Apache 2.0) over the app's own keyword list.

package com.lomemstudios.voice.sherpa.kws

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.lomemstudios.voice.engines.KeywordSpotting
import com.lomemstudios.voice.sherpa.SherpaModels
import com.lomemstudios.voice.sherpa.floats

internal class SherpaKeywords(context: Context, models: SherpaModels, keywordsFile: String) : KeywordSpotting {
    private val spotter = KeywordSpotter(context.assets, KeywordSpotterConfig(
        featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
        modelConfig = OnlineModelConfig(
            transducer = OnlineTransducerModelConfig(
                encoder = models.kwsEncoder, decoder = models.kwsDecoder,
                joiner = models.kwsJoiner),
            tokens = models.kwsTokens, numThreads = 1),
        keywordsFile = keywordsFile))
    private val stream: OnlineStream = spotter.createStream()

    override fun feed(pcm: ShortArray): String? {
        stream.acceptWaveform(floats(pcm), 16000)
        var found: String? = null
        while (spotter.isReady(stream)) {
            spotter.decode(stream)
            val k = spotter.getResult(stream).keyword
            if (k.isNotBlank()) {
                found = k.lowercase().replace('_', ' ')
                spotter.reset(stream)
            }
        }
        return found
    }

    override fun release() {
        stream.release()
        spotter.release()
    }
}
