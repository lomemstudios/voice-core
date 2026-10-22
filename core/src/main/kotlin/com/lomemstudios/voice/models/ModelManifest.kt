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

    val TITANET_SMALL = ModelSpec(
        name = "titanet_small", use = "voice print (speaker embedding)", file = "titanet_small.onnx",
        sha256 = "ad4a1802485d8b34c722d2a9d04249662f2ece5d28a7a039063ca22f515a789e", bytes = 40_257_283,
        licence = "Apache-2.0 (the NeMo Toolkit licence, per NGC's titanet_small page; CC BY 4.0 is the large model's)",
        source = "$SHERPA/speaker-recongition-models/nemo_en_titanet_small.onnx")

    /** FireRedChat pVAD (int8): the target speaker's voice probability every 10 ms, from raw 16 kHz audio and a
     *  192-d SpeechBrain ECAPA vector (plan Phase 1). Apache-2.0 per its model card and NOTICE. */
    val PVAD = ModelSpec(
        name = "pvad", use = "personal voice activity (FireRedChat pVAD)", file = "pvad.onnx",
        sha256 = "2114fd3c3fa87b560eaf4cad6a6e1a0a73aefba08da05521a27bfe2382ef4bdd", bytes = 3_940_567,
        licence = "Apache-2.0",
        source = "https://huggingface.co/FireRedTeam/FireRedChat-pvad/resolve/74561b17a50fbe9d8f84dacc453f175cb97f567c/pvad.onnx")

    /** GTCRN (Rong et al.), as converted by sherpa-onnx: a 48K-parameter streaming speech denoiser. MIT; trained on
     *  DNS3, which holds share-alike and non-commercial parts. */
    val GTCRN = ModelSpec(
        name = "gtcrn_simple", use = "speech denoiser (GTCRN)", file = "gtcrn_simple.onnx",
        sha256 = "e77603ac0c23dac3227dd2d7135b3a585cbee2679048aecfa886657d3ae1b534", bytes = 535_638,
        licence = "MIT (trained on DNS Challenge DNS3 data)",
        source = "$SHERPA/speech-enhancement-models/gtcrn_simple.onnx")

    /** SpeechBrain ECAPA (spkrec-ecapa-voxceleb at revision 0f99f2d0ebe89ac095bcc5903c4dd8f72b367286) with its Fbank
     *  front end and mean normalisation, exported by tools/export_ecapa.py (torch 2.7.1 CPU, speechbrain 1.1.2, onnx
     *  1.23.2, opset 17): the 192-d vector pVAD was trained on (plan step 1.2). fp32: the int8 copy missed parity
     *  (cosine 0.955 on some speech, against at least 0.9999999998 for this one on 58 files). Fetched on demand at
     *  enrolment and deleted after it. */
    val ECAPA_SB = ModelSpec(
        name = "ecapa_sb", use = "voice print for the voice gate (SpeechBrain ECAPA)", file = "ecapa_sb.onnx",
        sha256 = "04b8df18dbb48061bc04d8d12a2b93345be1d13f31c708ff59eeecd86e8ccde8", bytes = 84_128_540,
        licence = "Apache-2.0 (SpeechBrain weights; trained on VoxCeleb, \"available to download for research " +
            "purposes\" under CC BY 4.0, copyright with the videos' owners; metadata CC BY-SA 4.0)",
        source = "https://huggingface.co/speechbrain/spkrec-ecapa-voxceleb/tree/0f99f2d0ebe89ac095bcc5903c4dd8f72b367286 " +
            "exported by tools/export_ecapa.py")

    val ALL: List<ModelSpec> = listOf(TITANET_SMALL, PVAD, GTCRN, ECAPA_SB)

    fun byName(name: String): ModelSpec? = ALL.firstOrNull { it.name == name }
}

/** The libraries that run the models, for the same licence list. */
data class RuntimeSpec(val name: String, val version: String, val licence: String, val source: String)

object Runtimes {
    val ONNX_RUNTIME = RuntimeSpec("ONNX Runtime for Android", "1.22.0",
        "MIT, with its ThirdPartyNotices (Eigen MPL-2.0)",
        "Maven Central com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    val SHERPA_ONNX = RuntimeSpec("sherpa-onnx (k2-fsa)", "1.13.8",
        "Apache-2.0; the AAR also holds eSpeak NG and piper-phonemize, GPL-3.0; the ONNX Runtime 1.28.2 linked " +
            "into it, MIT with its ThirdPartyNotices",
        "https://github.com/k2-fsa/sherpa-onnx/releases/tag/v1.13.8")
    val ALL = listOf(ONNX_RUNTIME, SHERPA_ONNX)
}
