// com/ebani/sinage/util/CpuUsageMeter.kt
package com.ebani.sinage.util

import java.io.RandomAccessFile
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class CpuUsageMeter(
    private val periodMs: Long = 1500L
) {
    private val exec = ScheduledThreadPoolExecutor(1).apply { setRemoveOnCancelPolicy(true) }
    private var task: ScheduledFuture<*>? = null

    @Volatile private var prevTotal = 0L
    @Volatile private var prevIdle  = 0L
    @Volatile private var lastPercent = Double.NaN
    @Volatile private var primed = false

    private fun readTotals(): Pair<Long,Long> {
        RandomAccessFile("/proc/stat","r").use { raf ->
            val toks = raf.readLine().trim().split(Regex("\\s+"))
            var idle = 0L; var total = 0L
            for (i in 1 until toks.size) {
                val v = toks[i].toLongOrNull() ?: 0L
                total += v
                if (i == 4 || i == 5) idle += v // idle + iowait
            }
            return total to idle
        }
    }

    /** Do an immediate two-sample read so percent won’t be NaN on first use. */
    fun sampleNow(blockingWaitMs: Long = 120L) {
        val (t0, i0) = readTotals()
        prevTotal = t0; prevIdle = i0
        try { Thread.sleep(blockingWaitMs.coerceAtLeast(50)) } catch (_: Throwable) {}
        val (t1, i1) = readTotals()
        val dT = (t1 - t0).coerceAtLeast(1L).toDouble()
        val dI = (i1 - i0).coerceAtLeast(0L).toDouble()
        lastPercent = ((1.0 - dI / dT) * 100.0).coerceIn(0.0, 100.0)
        primed = true
        prevTotal = t1; prevIdle = i1
    }

    fun start() {
        if (task != null) return
        // make sure we have a first value immediately
        sampleNow()
        task = exec.scheduleWithFixedDelay({
            try {
                val (t, i) = readTotals()
                val dT = (t - prevTotal).coerceAtLeast(1L).toDouble()
                val dI = (i - prevIdle ).coerceAtLeast(0L).toDouble()
                prevTotal = t; prevIdle = i
                lastPercent = ((1.0 - dI / dT) * 100.0).coerceIn(0.0, 100.0)
                primed = true
            } catch (_: Throwable) { /* keep last */ }
        }, periodMs, periodMs, TimeUnit.MILLISECONDS)
    }

    fun stop() { task?.cancel(false); task = null }

    /** Latest % (NaN only if /proc not readable). */
    fun percent(): Pair<Double, Int> =
        lastPercent to Runtime.getRuntime().availableProcessors()

    companion object {
        fun round1(v: Double): Double = ((v * 10.0).roundToInt()) / 10.0
    }
}
