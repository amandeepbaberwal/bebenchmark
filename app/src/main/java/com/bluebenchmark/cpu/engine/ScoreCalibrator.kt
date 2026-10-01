package com.bluebenchmark.cpu.engine

import kotlin.math.ln

object ScoreCalibrator {
    const val SINGLE_PEAK_GFLOPS = 350.0
    const val MULTI_PEAK_GFLOPS = 2500.0
    const val MATRIX_COUNT = 3

    /** Log-linear map of GFLOPs -> 1..1000. Always clamped. */
    fun score(gflops: Double, singleCore: Boolean): Int {
        return try {
            if (gflops.isNaN() || gflops <= 0.0) return 1
            val peak = if (singleCore) SINGLE_PEAK_GFLOPS else MULTI_PEAK_GFLOPS
            val v = 1000.0 * (ln(1.0 + gflops) / ln(1.0 + peak))
            v.toInt().coerceIn(1, 1000)
        } catch (_: Exception) {
            1
        }
    }

    /** Matrix dimension when the total A, B and C buffers share M bytes. */
    fun matrixDim(ramBytes: Long): Int {
        if (ramBytes <= 0) return 0
        return try {
            kotlin.math.sqrt(ramBytes / (8.0 * MATRIX_COUNT)).toInt().coerceAtLeast(0)
        } catch (_: Exception) {
            0
        }
    }
}
