package com.bluebenchmark.cpu.engine

/** Result marshalled from JNI. errorCode 0 = ok, negative = graceful failure (never a crash). */
data class NativeResult(
    val gflops: Double,
    val elapsedSec: Double,
    val errorCode: Int,
    val errorMsg: String = ""
)

object BenchmarkEngine {
    @Volatile
    private var loaded = false
    @Volatile
    var lastError: String? = null
        private set

    @Synchronized
    fun ensureLoaded(): Boolean {
        if (loaded) return true
        return try {
            System.loadLibrary("benchmark_engine")
            loaded = true
            true
        } catch (e: UnsatisfiedLinkError) {
            lastError = "Native lib missing: ${e.message}"
            false
        } catch (e: Exception) {
            lastError = "Native load failed: ${e.message}"
            false
        }
    }

    /**
     * @param ramBytes native malloc target (clamped by caller).
     * @param threads 1 = single-core, N = multi-core.
     * @param cancelPtr native atomic flag address (0 = no cancellation).
     */
    fun run(ramBytes: Long, threads: Int, cancelPtr: Long): NativeResult {
        if (!ensureLoaded()) {
            return NativeResult(0.0, 0.0, -100, lastError ?: "lib load failed")
        }
        return try {
            val g = nativeRun(ramBytes, threads, cancelPtr)
            if (g < -0.5) {
                // Native graceful codes: -1 alloc fail, -2 cancelled, -3 invalid args.
                NativeResult(0.0, 0.0, g.toInt(), nativeErrorString(g.toInt()))
            } else {
                NativeResult(g, 0.0, 0)
            }
        } catch (e: Exception) {
            NativeResult(0.0, 0.0, -99, "JNI exception: ${e.message}")
        }
    }

    fun createCancelFlag(): Long = try {
        if (!ensureLoaded()) 0L else nativeCreateFlag()
    } catch (_: Exception) {
        0L
    }

    fun setCancelled(ptr: Long) {
        try {
            if (ptr != 0L && loaded) nativeSetCancelled(ptr)
        } catch (_: Exception) {
        }
    }

    fun freeFlag(ptr: Long) {
        try {
            if (ptr != 0L && loaded) nativeFreeFlag(ptr)
        } catch (_: Exception) {
        }
    }

    /** CPUs Android currently permits this process to run on (its cpuset). */
    fun allowedCpuIds(fallbackCount: Int): List<Int> {
        val fallback = (0 until fallbackCount.coerceAtLeast(1)).toList()
        if (!ensureLoaded()) return fallback
        return try {
            nativeAllowedCpuIds().toList().ifEmpty { fallback }
        } catch (_: UnsatisfiedLinkError) {
            fallback
        } catch (_: Exception) {
            fallback
        }
    }

    /** Best-effort affinity for the calling worker thread. */
    fun pinCurrentThread(cpuId: Int): Boolean = try {
        ensureLoaded() && nativePinCurrentThread(cpuId)
    } catch (_: Exception) {
        false
    }

    /** Returns elapsed seconds and a checksum for a deterministic RGBA workload. */
    fun imageBenchmark(pixelCount: Int, passes: Int, neon: Boolean): DoubleArray {
        if (!ensureLoaded()) return doubleArrayOf(0.0, 0.0)
        return try {
            nativeImageBenchmark(pixelCount.coerceIn(1, 4_194_304), passes.coerceIn(1, 64), neon)
        } catch (_: Exception) {
            doubleArrayOf(0.0, 0.0)
        }
    }

    private fun nativeErrorString(code: Int): String = when (code) {
        -1 -> "Native malloc failed (OOM guarded)"
        -2 -> "Cancelled by user"
        -3 -> "Invalid args"
        -4 -> "Native engine could not allocate worker resources"
        else -> "Native error $code"
    }

    private external fun nativeRun(ramBytes: Long, threads: Int, cancelPtr: Long): Double
    private external fun nativeCreateFlag(): Long
    private external fun nativeAllowedCpuIds(): IntArray
    private external fun nativeSetCancelled(ptr: Long)
    private external fun nativeFreeFlag(ptr: Long)
    private external fun nativePinCurrentThread(cpuId: Int): Boolean
    private external fun nativeImageBenchmark(pixelCount: Int, passes: Int, neon: Boolean): DoubleArray
}
