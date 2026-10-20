// pVAD as the voice gate's scorer (gate.TargetScorer): one session and one stream, for one microphone.

package com.lomemstudios.voice.pvad

import com.lomemstudios.voice.gate.TargetScorer

class PvadScorer(model: ByteArray, embedding: FloatArray, threads: Int = 1) : TargetScorer {
    private val pvad = Pvad(model, threads)
    private val stream = try { pvad.stream(embedding) } catch (e: Throwable) { pvad.close(); throw e }

    override fun step(audio: FloatArray, offset: Int): Float = stream.step(audio, offset)

    override fun reset() = stream.reset()

    override fun close() {
        stream.close()
        pvad.close()
    }
}
