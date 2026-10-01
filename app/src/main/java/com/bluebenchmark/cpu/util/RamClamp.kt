package com.bluebenchmark.cpu.util

import android.app.ActivityManager
import android.content.Context
import android.os.Process

object RamClamp {
    const val RESERVE_BYTES: Long = 750L * 1024L * 1024L
    const val MIN_ALLOC_MB = 64
    const val PRESET_256 = 256
    const val PRESET_512 = 512
    const val PRESET_1024 = 1024
    private const val LEGACY_PROCESS_MAX_ALLOC_MB = 512

    /** Upper bound for all native matrices combined, after keeping 750MB available to Android. */
    fun maxAllocMb(context: Context): Int {
        return try {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            val usable = info.availMem - RESERVE_BYTES
            val usableMb = if (usable <= 0L) {
                MIN_ALLOC_MB
            } else {
                (usable / (1024L * 1024L))
                    .coerceAtMost(Int.MAX_VALUE.toLong())
                    .toInt()
                    .coerceAtLeast(MIN_ALLOC_MB)
            }
            if (Process.is64Bit()) usableMb else minOf(usableMb, LEGACY_PROCESS_MAX_ALLOC_MB)
        } catch (_: Exception) {
            MIN_ALLOC_MB
        }
    }

    fun clamp(requestMb: Int, context: Context): Int {
        val max = maxAllocMb(context)
        return requestMb.coerceIn(MIN_ALLOC_MB, max)
    }
}
