// The ECAPA ONNX as the phone runs it (EcapaEncoder over GatePrint.recipe) gives the vector SpeechBrain gives, within
// a cosine of 0.999 (plan step 1.2). Needs VOICE_BENCH_DIR (gate/ecapa/ecapa_sb.onnx, the research enrolment and
// gate/petros_ecapa.f32); skipped without it.

package com.lomemstudios.voice.gate

import com.lomemstudios.voice.bench.BenchDir
import com.lomemstudios.voice.models.ModelManifest
import com.lomemstudios.voice.models.ModelStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EcapaParityTest {
    @Test fun theFirstFiveSecondsGiveSpeechBrainsVector() {
        val model = BenchDir.need("gate/ecapa/ecapa_sb.onnx")
        assertEquals(ModelManifest.ECAPA_SB.sha256, model.inputStream().use { ModelStore.sha256(it) })
        val enrol = BenchDir.wav(BenchDir.need("tse-research/set/enroll.wav"))
        val want = BenchDir.f32(BenchDir.need("gate/petros_ecapa.f32"))
        // The recipe over pieces, as the phone keeps them: the enrolment cut in 3 s pieces.
        val pieces = (0 until enrol.size step 48000).map { enrol.copyOfRange(it, minOf(it + 48000, enrol.size)) }
        val audio = GatePrint.recipe(pieces)
        assertEquals(80000, audio.size)
        val got = EcapaEncoder(model.path).use { it.vector(audio) }
        assertEquals(192, got.size)
        val cos = want.indices.sumOf { (want[it] * got[it]).toDouble() }
        println("ecapa parity: cosine $cos")
        assertTrue("cosine $cos", cos >= 0.999)
    }
}
