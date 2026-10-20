// How long each step of a model took, summed up as the phone bench and the gate's perf line report it: mean, p50,
// p95, worst, and the real-time factor (compute time over the audio's length). Plain logic, unit-tested.

package com.lomemstudios.voice.bench

class StepStats(capacity: Int = 1024) {
    private var nanos = LongArray(capacity)
    var count = 0
        private set

    fun add(ns: Long) {
        if (count == nanos.size) nanos = nanos.copyOf(nanos.size * 2)
        nanos[count++] = ns
    }

    /** Times [block] and adds it; returns what the block returned. */
    inline fun <T> time(block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        add(System.nanoTime() - t0)
        return r
    }

    fun summary(audioSeconds: Double): Summary {
        require(count > 0) { "no steps" }
        val ms = DoubleArray(count) { nanos[it] / 1e6 }.also { it.sort() }
        val total = ms.sum()
        return Summary(count, total / count, percentile(ms, 0.50), percentile(ms, 0.95), ms.last(), total / 1000.0 / audioSeconds, total)
    }

    data class Summary(val count: Int, val meanMs: Double, val p50Ms: Double, val p95Ms: Double, val worstMs: Double,
                       val rtf: Double, val totalMs: Double) {
        /** "mean 0.92 ms, p50 0.88, p95 1.20, worst 9.10, RTF 0.0920" */
        fun line(): String = "mean %s ms, p50 %s, p95 %s, worst %s, RTF %.4f".format(
            ms(meanMs), ms(p50Ms), ms(p95Ms), ms(worstMs), rtf)

        private fun ms(v: Double) = if (v < 10) "%.2f".format(v) else "%.1f".format(v)
    }

    companion object {
        /** Linear between the closest ranks, as numpy's default percentile. [sorted] ascending. */
        fun percentile(sorted: DoubleArray, q: Double): Double {
            if (sorted.size == 1) return sorted[0]
            val pos = q * (sorted.size - 1)
            val lo = pos.toInt()
            val hi = minOf(lo + 1, sorted.size - 1)
            return sorted[lo] + (sorted[hi] - sorted[lo]) * (pos - lo)
        }
    }
}
