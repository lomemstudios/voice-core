// Every model a voice-core consumer may run, pinned by SHA-256, with its size, licence and source. The app bundles a
// model in its assets or fetches it on demand (ModelStore); either way the bytes must match the pin here before they
// are used. A new model or version is a change here, noted in the release (CLAUDE.md, "Releases and pinning").

package com.lomemstudios.voice.models

/** One model file as released by its authors, pinned. */
data class ModelSpec(
    /** A short stable name: the key of prints made with it ("titanet_small"), and the log's name for it. */
    val name: String,
    /** What it does, for the app's licence list. */
    val use: String,
    /** The file name the consumer keeps it under. */
    val file: String,
    val sha256: String,
    val bytes: Long,
    /** The licence of the weights, as their source states it. */
    val licence: String,
    /** Where the pinned bytes come from: a URL at a fixed revision or release, and the member of an archive. */
    val source: String,
) {
    /** The first 8 hex digits of the pin: enough to name a print made with this model. */
    val short: String get() = sha256.take(8)
}

object ModelManifest {
    private const val SHERPA = "https://github.com/k2-fsa/sherpa-onnx/releases/download"
    private const val KWS = "$SHERPA/kws-models/sherpa-onnx-kws-zipformer-gigaspeech-3.3M-2024-01-01.tar.bz2"
    private const val KWS_LICENCE = "Apache-2.0"

    val TITANET_SMALL = ModelSpec(
        name = "titanet_small", use = "voice print (speaker embedding)", file = "titanet_small.onnx",
        sha256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e", bytes = 40_257_283,
        licence = "CC-BY-4.0 for TitaNet on Hugging Face; to confirm for the small model on NVIDIA NGC",
        source = "$SHERPA/speaker-recongition-models/nemo_en_titanet_small.onnx")

    val KWS_ENCODER = ModelSpec(
        name = "kws_encoder", use = "keyword spotter, encoder", file = "encoder.onnx",
        sha256 = "1e721676515bcd42a186979733981213c66c80db680e1cc582dfedf3be76e678", bytes = 4_807_159,
        licence = KWS_LICENCE, source = "$KWS encoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val KWS_DECODER = ModelSpec(
        name = "kws_decoder", use = "keyword spotter, decoder", file = "decoder.onnx",
        sha256 = "e40ff43297abe815e8898494c17e71bba2152d9d40fa3eb803f75d0f7533329a", bytes = 277_985,
        licence = KWS_LICENCE, source = "$KWS decoder-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val KWS_JOINER = ModelSpec(
        name = "kws_joiner", use = "keyword spotter, joiner", file = "joiner.onnx",
        sha256 = "eae9da0c7e1e6c6a3f4cc42d167899c388f6c6701b94cb96320e4f55df79624c", bytes = 163_380,
        licence = KWS_LICENCE, source = "$KWS joiner-epoch-12-avg-2-chunk-16-left-64.int8.onnx")
    val KWS_TOKENS = ModelSpec(
        name = "kws_tokens", use = "keyword spotter, tokens", file = "tokens.txt",
        sha256 = "fd2ded4050a55d2b1578870ba8697d02371980217806b7558bd0a5cc60f3ba53", bytes = 5_006,
        licence = KWS_LICENCE, source = "$KWS tokens.txt")

    /** FireRedChat pVAD (int8): the target speaker's voice probability every 10 ms, from raw 16 kHz audio and a
     *  192-d SpeechBrain ECAPA vector (plan Phase 1). Apache-2.0 per its model card and NOTICE. */
    val PVAD = ModelSpec(
        name = "pvad", use = "personal voice activity (FireRedChat pVAD)", file = "pvad.onnx",
        sha256 = "2114fd3c3fa87b560eaf4cad6a6e1a0a73aefba08da05521a27bfe2382ef4bdd", bytes = 3_940_567,
        licence = "Apache-2.0",
        source = "https://huggingface.co/FireRedTeam/FireRedChat-pvad/resolve/74561b17a50fbe9d8f84dacc453f175cb97f567c/pvad.onnx")

    /** GTCRN (Rong et al.), as converted by sherpa-onnx: a 48K-parameter streaming speech denoiser. MIT. */
    val GTCRN = ModelSpec(
        name = "gtcrn_simple", use = "speech denoiser (GTCRN)", file = "gtcrn_simple.onnx",
        sha256 = "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534", bytes = 535_638,
        licence = "MIT", source = "$SHERPA/speech-enhancement-models/gtcrn_simple.onnx")

    val ALL: List<ModelSpec> = listOf(TITANET_SMALL, KWS_ENCODER, KWS_DECODER, KWS_JOINER, KWS_TOKENS, PVAD, GTCRN)

    fun byName(name: String): ModelSpec? = ALL.firstOrNull { it.name == name }
}

/** The libraries that run the models, for the same licence list. */
data class RuntimeSpec(val name: String, val version: String, val licence: String, val source: String)

object Runtimes {
    val ONNX_RUNTIME = RuntimeSpec("ONNX Runtime for Android", "1.22.0", "MIT",
        "Maven Central com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    val SHERPA_ONNX = RuntimeSpec("sherpa-onnx (k2-fsa)", "1.13.8", "Apache-2.0; the ONNX Runtime linked into it, MIT",
        "https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8")
    val ALL = listOf(ONNX_RUNTIME, SHERPA_ONNX)
}
