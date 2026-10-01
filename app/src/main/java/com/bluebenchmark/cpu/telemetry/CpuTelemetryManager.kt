package com.bluebenchmark.cpu.telemetry

import android.content.Context
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class TelemetrySnapshot(
    val cpuIds: List<Int> = listOf(0),
    val freqsMhz: List<Int> = listOf(0),
    val maxFreqsMhz: List<Int> = listOf(0),
    val cpuBusyPct: List<Int> = listOf(-1),
    val cpuTempC: Float? = null,
    val batteryTempC: Float? = null,
    val throttlingHint: Boolean = false
) {
    val coreCount: Int get() = cpuIds.size
}

/**
 * Non-blocking hardware polling. Layered fallbacks everywhere; unreadable nodes
 * report 0 MHz / null instead of throwing. 1s cadence on Dispatchers.IO.
 */
class CpuTelemetryManager(private val appContext: Context) {
    private data class CpuCounters(val total: Long, val idle: Long)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    private var previousCpuCounters: Map<Int, CpuCounters> = emptyMap()

    private val _state = MutableStateFlow(TelemetrySnapshot(cpuIds = onlineCpuIds()))
    val state: StateFlow<TelemetrySnapshot> = _state.asStateFlow()

    val coreCount: Int get() = _state.value.coreCount

    fun start() {
        if (job?.isActive == true) return
        job = scope.launch {
            while (isActive) {
                try {
                    _state.value = poll()
                } catch (_: Exception) {
                    // Never propagate; keep last known snapshot.
                }
                try {
                    delay(1000L)
                } catch (_: Exception) {
                    break
                }
            }
        }
    }

    fun stop() {
        try {
            job?.cancel()
        } catch (_: Exception) {
        }
        job = null
        scope.cancel()
    }

    private fun onlineCpuIds(): List<Int> {
        val fromSysfs = parseCpuList(readFile("/sys/devices/system/cpu/online"))
        if (fromSysfs.isNotEmpty()) return fromSysfs

        val count = try {
            Runtime.getRuntime().availableProcessors().coerceIn(1, 256)
        } catch (_: Exception) {
            1
        }
        return (0 until count).toList()
    }

    private fun poll(): TelemetrySnapshot {
        val cpuIds = onlineCpuIds()
        val freqs = cpuIds.map { readFreqMhz(it) }
        val maxFreqs = cpuIds.map { readMaxFreqMhz(it) }
        val busy = readCpuBusyPct(cpuIds)
        val cpuTemp = readCpuTemp()
        val battTemp = readBatteryTemp()
        // Warning-only hint (NO auto-abort per user directive): flag if hot.
        val hot = (cpuTemp != null && cpuTemp >= 85f) || (battTemp != null && battTemp >= 48f)
        return TelemetrySnapshot(
            cpuIds = cpuIds,
            freqsMhz = freqs,
            maxFreqsMhz = maxFreqs,
            cpuBusyPct = busy,
            cpuTempC = cpuTemp,
            batteryTempC = battTemp,
            throttlingHint = hot
        )
    }

    /** System-wide busy percentage by CPU, sampled from the previous /proc/stat read. */
    private fun readCpuBusyPct(cpuIds: List<Int>): List<Int> {
        val current = try {
            val result = mutableMapOf<Int, CpuCounters>()
            File("/proc/stat").forEachLine { line ->
                val fields = line.trim().split(Regex("\\s+"))
                val name = fields.firstOrNull() ?: return@forEachLine
                if (!name.startsWith("cpu") || name == "cpu") return@forEachLine
                val id = name.removePrefix("cpu").toIntOrNull() ?: return@forEachLine
                val values = fields.drop(1).mapNotNull { it.toLongOrNull() }
                if (values.size < 4) return@forEachLine
                val total = values.take(8).sum()
                val idle = values[3] + (values.getOrNull(4) ?: 0L)
                result[id] = CpuCounters(total, idle)
            }
            result
        } catch (_: Exception) {
            emptyMap()
        }

        val previous = previousCpuCounters
        previousCpuCounters = current
        return cpuIds.map { id ->
            val before = previous[id]
            val after = current[id]
            if (before == null || after == null) return@map -1
            val totalDelta = after.total - before.total
            val idleDelta = after.idle - before.idle
            if (totalDelta <= 0L) return@map -1
            val busyDelta = (totalDelta - idleDelta).coerceIn(0L, totalDelta)
            ((busyDelta * 100L) / totalDelta).toInt().coerceIn(0, 100)
        }
    }

    /** Parses Linux CPU-list syntax, e.g. "0-3,6,8-9" or "0 1 2 3". */
    private fun parseCpuList(raw: String?): List<Int> {
        if (raw.isNullOrBlank()) return emptyList()
        return try {
            val ids = sortedSetOf<Int>()
            for (token in raw.trim().split(Regex("[,\\s]+"))) {
                if (token.isBlank()) continue
                val bounds = token.split('-', limit = 2)
                val first = bounds[0].toIntOrNull() ?: continue
                val last = if (bounds.size == 2) bounds[1].toIntOrNull() ?: continue else first
                if (first < 0 || last < first || last - first > 1023) continue
                for (cpu in first..last) ids.add(cpu)
            }
            ids.toList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun readFile(path: String): String? {
        return try {
            val f = File(path)
            if (!f.exists() || !f.canRead()) return null
            f.bufferedReader().use { it.readLine()?.trim() }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFreqMhz(core: Int): Int {
        // Primary: per-core cpufreq node (kHz -> MHz).
        val primaries = listOf(
            "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_cur_freq",
            "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_cur_freq"
        )
        for (p in primaries) {
            val v = readFile(p)?.toLongOrNull()
            if (v != null && v > 0) return (v / 1000L).toInt()
        }
        // Fallback: use only the policy that lists this CPU as a member. Using
        // the first readable policy for every CPU makes heterogeneous devices
        // report one cluster's frequency for the whole chip.
        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val policies = try {
            policyRoot.listFiles { f -> f.isDirectory && f.name.startsWith("policy") }
                ?.sortedBy { it.name.removePrefix("policy").toIntOrNull() ?: Int.MAX_VALUE }
                ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        for (policy in policies) {
            val members = parseCpuList(
                readFile("${policy.absolutePath}/related_cpus")
                    ?: readFile("${policy.absolutePath}/affected_cpus")
            )
            if (core !in members) continue
            for (name in listOf("scaling_cur_freq", "cpuinfo_cur_freq")) {
                val v = readFile("${policy.absolutePath}/$name")?.toLongOrNull()
                if (v != null && v > 0) return (v / 1000L).toInt()
            }
        }
        return 0
    }

    private fun readMaxFreqMhz(core: Int): Int {
        val direct = listOf(
            "/sys/devices/system/cpu/cpu$core/cpufreq/cpuinfo_max_freq",
            "/sys/devices/system/cpu/cpu$core/cpufreq/scaling_max_freq"
        )
        direct.forEach { path ->
            val value = readFile(path)?.toLongOrNull()
            if (value != null && value > 0) return (value / 1000L).toInt()
        }
        val policyRoot = File("/sys/devices/system/cpu/cpufreq")
        val policies = try {
            policyRoot.listFiles { f -> f.isDirectory && f.name.startsWith("policy") } ?: emptyArray()
        } catch (_: Exception) { emptyArray() }
        for (policy in policies) {
            val members = parseCpuList(
                readFile("${policy.absolutePath}/related_cpus")
                    ?: readFile("${policy.absolutePath}/affected_cpus")
            )
            if (core !in members) continue
            for (name in listOf("cpuinfo_max_freq", "scaling_max_freq")) {
                val value = readFile("${policy.absolutePath}/$name")?.toLongOrNull()
                if (value != null && value > 0) return (value / 1000L).toInt()
            }
        }
        return 0
    }

    private fun readCpuTemp(): Float? {
        // Scan thermal zones for CPU/SoC sensors (millidegree C -> C).
        try {
            val dir = File("/sys/class/thermal")
            val zones = try {
                dir.listFiles { f -> f.name.startsWith("thermal_zone") }?.sortedBy { it.name }
            } catch (_: Exception) {
                null
            } ?: emptyList()
            var cpuCandidate: Float? = null
            for (z in zones) {
                try {
                    val type = readFile("${z.absolutePath}/type")?.lowercase() ?: continue
                    val raw = readFile("${z.absolutePath}/temp")?.toLongOrNull() ?: continue
                    if (raw <= 0 || raw > 200000) continue
                    val c = raw / 1000f
                    if (type.contains("cpu") || type.contains("soc") || type.contains("msm") ||
                        type.contains("mtk") || type.contains("exynos") || type.contains("tensor") ||
                        type.contains("big") || type.contains("little") || type.contains("cluster")
                    ) {
                        cpuCandidate = maxOf(cpuCandidate ?: c, c)
                    }
                } catch (_: Exception) {
                    continue
                }
            }
            if (cpuCandidate != null) return cpuCandidate
        } catch (_: Exception) {
        }
        // A battery sensor is not a CPU temperature measurement.
        return null
    }

    private fun readBatteryTemp(): Float? {
        return try {
            // Sticky battery broadcast; tenth-of-a-degree C.
            val intent = appContext.registerReceiver(null, IntentFilter("android.intent.action.BATTERY_CHANGED"))
            val tenth = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
            if (tenth > -1) tenth / 10f else null
        } catch (_: Exception) {
            null
        }
    }
}
