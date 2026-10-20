// FireRedChat pVAD on ONNX Runtime (ModelManifest.PVAD): every 10 ms of raw 16 kHz audio, the probability that the
// enrolled speaker is talking, given their 192-d SpeechBrain ECAPA vector. The model computes its own mel features;
// its state, mel_buffer 1x80x15 and gru_buffer 2x1x256, is carried from step to step and cleared by reset() when the
// microphone restarts (plan "Threads and latency budget on the phone"). One stream per microphone, on one thread.
//
// Inputs input_audio [1,160], spkemb [1,192], mel_buffer, gru_buffer; outputs linear_out, sigmoid_out (the
// probability), mel_buffer_out, gru_buffer_out (checked with onnxruntime's get_inputs/get_outputs on main).

package com.lomemstudios.voice.pvad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.Buffer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

class Pvad(model: ByteArray, threads: Int = 1) : AutoCloseable {
    companion object {
        const val STEP = 160
        const val EMBEDDING = 192
        private val MEL = longArrayOf(1, 80, 15)
        private val GRU = longArrayOf(2, 1, 256)
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(model, OrtSession.SessionOptions().apply {
        setIntraOpNumThreads(threads)
        setInterOpNumThreads(1)
        setExecutionMode(OrtSession.SessionOptions.ExecutionMode.SEQUENTIAL)
    })

    val inputs: Set<String> get() = session.inputNames
    val outputs: Set<String> get() = session.outputNames

    fun stream(embedding: FloatArray): Stream = Stream(embedding)

    inner class Stream internal constructor(embedding: FloatArray) : AutoCloseable {
        init { require(embedding.size == EMBEDDING) { "pVAD takes a $EMBEDDING-d embedding, not ${embedding.size}" } }

        private val audio = direct(STEP)
        private val mel = direct(80 * 15)
        private val gru = direct(2 * 256)
        private val spk = direct(EMBEDDING).let { b ->
            b.put(embedding)
            rewind(b)
            OnnxTensor.createTensor(env, b, longArrayOf(1, EMBEDDING.toLong()))
        }

        /** One 10 ms step: [STEP] samples of [frame] from [offset], in -1..1. The target speaker's probability. */
        fun step(frame: FloatArray, offset: Int = 0): Float {
            clear(audio)
            audio.put(frame, offset, STEP)
            rewind(audio)
            rewind(mel)
            rewind(gru)
            OnnxTensor.createTensor(env, audio, longArrayOf(1, STEP.toLong())).use { a ->
                OnnxTensor.createTensor(env, mel, MEL).use { m ->
                    OnnxTensor.createTensor(env, gru, GRU).use { g ->
                        session.run(mapOf("input_audio" to a, "spkemb" to spk, "mel_buffer" to m, "gru_buffer" to g)).use { r ->
                            val p = (r.get("sigmoid_out").get() as OnnxTensor).floatBuffer.get(0)
                            clear(mel); mel.put((r.get("mel_buffer_out").get() as OnnxTensor).floatBuffer)
                            clear(gru); gru.put((r.get("gru_buffer_out").get() as OnnxTensor).floatBuffer)
                            return p
                        }
                    }
                }
            }
        }

        /** Clears the carried state, as when the microphone restarts. */
        fun reset() {
            clear(mel); while (mel.hasRemaining()) mel.put(0f)
            clear(gru); while (gru.hasRemaining()) gru.put(0f)
        }

        override fun close() = spk.close()
    }

    override fun close() = session.close()

    // Through Buffer: FloatBuffer's own clear() and rewind() exist only on newer Android releases.
    private fun clear(b: Buffer) { b.clear() }
    private fun rewind(b: Buffer) { b.rewind() }

    private fun direct(n: Int): FloatBuffer = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
}
