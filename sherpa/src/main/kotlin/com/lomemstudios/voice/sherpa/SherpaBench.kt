// The sherpa models in the phone speed test (core's PhoneBench): TitaNet small through the very Embedder the app runs
// (SherpaEmbedder, 2 threads), scored on 1 s windows as a command or a short turn is, and GTCRN, sherpa-onnx's
// streaming speech denoiser, fed 50 ms chunks on one thread as a de-noising stage would be.

package com.lomemstudios.voice.sherpa

import android.content.Context
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserGtcrnModelConfig
import com.k2fsa.sherpa.onnx.OfflineSpeechDenoiserModelConfig
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiser
import com.k2fsa.sherpa.onnx.OnlineSpeechDenoiserConfig
import com.lomemstudios.voice.bench.BenchPart
import com.lomemstudios.voice.bench.CpuWatch
import com.lomemstudios.voice.bench.StepStats
import com.lomemstudios.voice.bench.SyntheticAudio
import com.lomemstudios.voice.sherpa.print.SherpaEmbedder

object SherpaBench {
    /** TitaNet small on [windows] windows of 1 s each, from the app's asset [model]. */
    fun titanet(context: Context, model: String = SherpaModels().titanet, windows: Int = 60): BenchPart = object : BenchPart {
        override val name = "titanet"
        override fun run(audio: FloatArray, say: (String) -> Unit) {
            val t0 = System.nanoTime()
            val embedder = SherpaEmbedder(context, model) { say(it) }
            val load = (System.nanoTime() - t0) / 1e6
            if (!embedder.available) { say("not available in this build"); return }
            say("load %.0f ms (2 threads, as the app runs it)".format(load))
            val pcm = SyntheticAudio.pcm16(audio)
            val rate = SyntheticAudio.RATE
            val stats = StepStats(windows)
            val cpu = CpuWatch()
            var dim = 0
            val n = minOf(windows, pcm.size / rate)
            for (i in 0 until n) {
                val e = stats.time { embedder.embed(pcm.copyOfRange(i * rate, (i + 1) * rate)) }
                dim = e?.size ?: dim
                if (i % 5 == 0) cpu.sample()
            }
            say("%d windows of 1 s, %d-d: per window %s".format(n, dim, stats.summary(n.toDouble()).line()))
            say(cpu.line())
        }
    }

    /** GTCRN over the whole clip in 50 ms chunks, from the app's asset [model]. */
    fun gtcrn(context: Context, model: String): BenchPart = object : BenchPart {
        override val name = "gtcrn"
        override fun run(audio: FloatArray, say: (String) -> Unit) {
            val t0 = System.nanoTime()
            val denoiser = OnlineSpeechDenoiser(context.assets, OnlineSpeechDenoiserConfig(
                model = OfflineSpeechDenoiserModelConfig(gtcrn = OfflineSpeechDenoiserGtcrnModelConfig(model = model), numThreads = 1)))
            try {
                say("load %.0f ms (1 thread), frame shift %d samples at %d Hz".format(
                    (System.nanoTime() - t0) / 1e6, denoiser.frameShiftInSamples, denoiser.sampleRate))
                val chunk = 800
                val stats = StepStats(audio.size / chunk)
                val cpu = CpuWatch()
                var out = 0
                var i = 0
                while (i + chunk <= audio.size) {
                    val piece = audio.copyOfRange(i, i + chunk)
                    out += stats.time { denoiser.run(piece, SyntheticAudio.RATE) }.samples.size
                    i += chunk
                    if (stats.count % 20 == 0) cpu.sample()
                }
                val sec = i / SyntheticAudio.RATE.toDouble()
                say("%d chunks of 50 ms (%.0f s, %d samples out): per chunk %s".format(stats.count, sec, out, stats.summary(sec).line()))
                say(cpu.line())
            } finally {
                denoiser.release()
            }
        }
    }
}
