// What the phone bench reports about the phone it ran on: the model and chip, the cores and their top clocks, the
// thermal state, and which cores a thread ran on. Read from Android and /proc and /sys, nothing written.

package com.lomemstudios.voice.bench

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import java.io.File

object Device {
    /** "Xiaomi 23078PND5G (marble), SoC QTI SM7475, Android 14 (API 34), arm64-v8a" */
    fun describe(): String {
        val soc = if (Build.VERSION.SDK_INT >= 31) ", SoC ${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else ""
        return "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})$soc, Android ${Build.VERSION.RELEASE} " +
            "(API ${Build.VERSION.SDK_INT}), ${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"}"
    }

    /** "8 cores: cpu0-3 1800 MHz, cpu4-6 2400 MHz, cpu7 2900 MHz" (the top clock of each, where /sys tells). */
    fun cores(): String {
        val n = Runtime.getRuntime().availableProcessors()
        val freqs = (0 until n).map { maxMhz(it) }
        if (freqs.all { it == null }) return "$n cores"
        val groups = mutableListOf<Triple<Int, Int, Int?>>()  // first, last, MHz
        for ((i, f) in freqs.withIndex()) {
            val last = groups.lastOrNull()
            if (last != null && last.third == f && last.second == i - 1) groups[groups.size - 1] = Triple(last.first, i, f)
            else groups += Triple(i, i, f)
        }
        return "$n cores: " + groups.joinToString(", ") { (a, b, f) ->
            (if (a == b) "cpu$a" else "cpu$a-$b") + " " + (f?.let { "$it MHz" } ?: "?")
        }
    }

    private fun maxMhz(cpu: Int): Int? = runCatching {
        File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toInt() / 1000
    }.getOrNull()?.takeIf { it > 0 }  // the emulator reports 0

    /** "thermal none (0), headroom 0.42, battery 31.5 °C" */
    fun thermal(context: Context): String {
        val pm = context.getSystemService(PowerManager::class.java)
        val parts = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= 29) {
            val s = pm.currentThermalStatus
            parts += "thermal ${thermalName(s)} ($s)"
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val h = pm.getThermalHeadroom(10)
            parts += if (h.isNaN()) "headroom unknown" else "headroom %.2f".format(h)
        }
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = battery?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE) ?: Int.MIN_VALUE
        if (tenths != Int.MIN_VALUE) parts += "battery %.1f °C".format(tenths / 10.0)
        return parts.joinToString(", ").ifEmpty { "thermal unknown" }
    }

    fun thermalName(status: Int): String = when (status) {
        0 -> "none"
        1 -> "light"
        2 -> "moderate"
        3 -> "severe"
        4 -> "critical"
        5 -> "emergency"
        6 -> "shutdown"
        else -> "unknown"
    }
}

/** Which cores the calling thread ran on, sampled now and then, and how much CPU time it and the process used. */
class CpuWatch {
    private val tid = android.os.Process.myTid()
    private val seen = sortedMapOf<Int, Int>()
    private val wall0 = System.nanoTime()
    private val thread0 = android.os.Debug.threadCpuTimeNanos()
    private val process0 = android.os.Process.getElapsedCpuTime()

    /** Records the core the thread is on now (field 39 of /proc/self/task/<tid>/stat). */
    fun sample() {
        val core = runCatching {
            val stat = File("/proc/self/task/$tid/stat").readText()
            stat.substringAfterLast(')').trim().split(' ')[36].toInt()
        }.getOrNull() ?: return
        seen[core] = (seen[core] ?: 0) + 1
    }

    /** "thread busy 0.09 of 60.0 s, process 0.12 cores, ran on cpu4 ×40, cpu7 ×20" */
    fun line(): String {
        val wall = (System.nanoTime() - wall0) / 1e9
        val thread = (android.os.Debug.threadCpuTimeNanos() - thread0) / 1e9
        val process = (android.os.Process.getElapsedCpuTime() - process0) / 1000.0
        val on = if (seen.isEmpty()) "cores not readable" else "ran on " + seen.entries.joinToString(", ") { "cpu${it.key} ×${it.value}" }
        return "thread CPU %.2f s of %.1f s wall (%.0f%%), process %.2f cores, %s".format(
            thread, wall, 100 * thread / wall, process / wall, on)
    }
}
