package com.bluebenchmark.cpu.engine

import android.annotation.SuppressLint
import android.content.Context
import com.bluebenchmark.cpu.telemetry.TelemetrySnapshot
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.util.Arrays
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.Deflater
import java.util.zip.Inflater
import java.util.zip.CRC32
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.PI
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

enum class SuitePreset { NORMAL, DEEP, SUSTAINED, CUSTOM, FULL }

data class SuiteRunConfig(
    val preset: SuitePreset = SuitePreset.FULL,
    val singleCore: Boolean = false,
    val sustainedDurationMinutes: Int = 1,
    val selectedCategories: Set<String> = emptySet()
)

data class SuiteProgress(
    val currentTest: String,
    val completedTests: Int,
    val totalTests: Int,
    val elapsedSeconds: Double,
    val sustained: Boolean = false,
    val testProgress: Float = 0f
)

data class TestMeasurement(
    val category: String,
    val name: String,
    val value: Double,
    val unit: String,
    val elapsedNs: Long,
    val iterations: Long,
    val operations: Long,
    val bytesProcessed: Long,
    val threadCount: Int,
    val frequencyMhz: Double?,
    val temperatureC: Double?,
    val scoreRatePerSecond: Double,
    val detail: String = "",
    val latencyP50Ns: Long? = null,
    val latencyP90Ns: Long? = null
)

data class SuiteRunResult(
    val score: Int,
    val categoryScores: Map<String, Int>,
    val measurements: List<TestMeasurement>,
    val elapsedSeconds: Double,
    val peakTemperatureC: Double?,
    val preset: SuitePreset,
    val singleCore: Boolean,
    val rawJson: String
)

/** CPU Suite v4 uses unit-aware reference rates, sustained workloads, and on-device inference. */
object CpuSuiteScore {
    const val VERSION = "cpu-suite-v4"
    private const val MAX_REFERENCE_MULTIPLE = 100.0
    private val referenceRates = mapOf(
        "Integer" to 80_000_000.0, "Floating point" to 20_000_000.0,
        "SIMD" to 100_000_000.0, "Cache" to 10_000_000.0, "Memory" to 1_000_000_000.0,
        "Matrix" to 200_000_000.0, "Cryptography" to 100_000_000.0,
        "Compression" to 50_000_000.0, "DSP" to 50_000_000.0,
        "Real world" to 5_000_000.0, "Scaling" to 8.0, "AI inference" to 1.0
    )

    fun categoryScore(category: String, ratesPerSecond: List<Double>): Int {
        if (ratesPerSecond.isEmpty()) return 1
        val baseline = referenceRate(category)
        val normalized = ratesPerSecond.map { scoreRate(it, baseline) }
        return geometricMean(normalized).roundToInt().coerceIn(1, 1000)
    }

    /** A memory latency rate is accesses/second; bandwidth rates are bytes/second. */
    fun referenceRate(category: String, unit: String, name: String = ""): Double = when {
        (category == "Memory" || category == "Cache") && unit == "ns/access" -> 10_000_000.0
        category == "AI inference" && unit == "inferences/s" && name.contains("classification", ignoreCase = true) -> 30.0
        category == "AI inference" && unit == "inferences/s" && name.contains("detection", ignoreCase = true) -> 2.0
        category == "AI inference" && unit == "inferences/s" && name.contains("segmentation", ignoreCase = true) -> 1.0
        else -> referenceRate(category)
    }

    fun referenceRate(category: String): Double = referenceRates[category] ?: 1_000_000.0

    /** Unavailable workloads are omitted instead of being scored as zero performance. */
    fun categoryScores(measurements: List<TestMeasurement>): Map<String, Int> =
        measurements.groupBy { it.category }.mapNotNull { (category, entries) ->
            val usable = entries.filter { it.scoreRatePerSecond.isFinite() && it.scoreRatePerSecond > 0.0 }
            if (usable.isEmpty()) return@mapNotNull null
            val scores = usable.map { measurement ->
                scoreRate(measurement.scoreRatePerSecond, referenceRate(category, measurement.unit, measurement.name))
            }
            category to geometricMean(scores).roundToInt().coerceIn(1, 1000)
        }.toMap()

    fun profileId(config: SuiteRunConfig): String = when (config.preset) {
        SuitePreset.CUSTOM -> "$VERSION-custom-${config.selectedCategories.map { it.replace(" ", "").lowercase() }.sorted().joinToString("_")}"
        SuitePreset.SUSTAINED -> "$VERSION-sustained-${config.sustainedDurationMinutes.coerceIn(1, 30)}m"
        SuitePreset.FULL -> "$VERSION-full"
        else -> "$VERSION-${config.preset.name.lowercase()}"
    }

    /** Scaling is displayed separately because a single-core run has no scaling measurement. */
    fun overallScore(categoryScores: Map<String, Int>): Int = geometricMean(
        categoryScores.filterKeys { it != "Scaling" }.values.map { it.toDouble() }
    ).roundToInt().coerceIn(1, 1000)

    private fun geometricMean(values: List<Double>): Double = if (values.isEmpty()) 1.0
        else exp(values.map { ln(it.coerceAtLeast(1e-9)) }.average())

    private fun scoreRate(rate: Double, baseline: Double): Double {
        val safeRate = rate.takeIf { it.isFinite() && it > 0.0 } ?: 0.0
        val relativeRate = safeRate / baseline
        return (1000.0 * ln(1.0 + relativeRate) / ln(1.0 + MAX_REFERENCE_MULTIPLE)).coerceIn(1.0, 1000.0)
    }
}

internal fun radix2FftInPlace(real: DoubleArray, imag: DoubleArray) {
    require(real.size == imag.size && real.isNotEmpty() && (real.size and (real.size - 1)) == 0) {
        "FFT input must use equal, power-of-two arrays"
    }
    val size = real.size
    var reversed = 0
    for (index in 1 until size) {
        var bit = size shr 1
        while (reversed and bit != 0) {
            reversed = reversed xor bit
            bit = bit shr 1
        }
        reversed = reversed xor bit
        if (index < reversed) {
            val realValue = real[index]
            real[index] = real[reversed]
            real[reversed] = realValue
            val imagValue = imag[index]
            imag[index] = imag[reversed]
            imag[reversed] = imagValue
        }
    }

    var length = 2
    while (length <= size) {
        val half = length / 2
        val angle = -2.0 * PI / length
        val stepReal = cos(angle)
        val stepImag = sin(angle)
        var base = 0
        while (base < size) {
            var twiddleReal = 1.0
            var twiddleImag = 0.0
            for (j in 0 until half) {
                val even = base + j
                val odd = even + half
                val oddReal = real[odd]
                val oddImag = imag[odd]
                val tr = twiddleReal * oddReal - twiddleImag * oddImag
                val ti = twiddleReal * oddImag + twiddleImag * oddReal
                val evenReal = real[even]
                val evenImag = imag[even]
                real[even] = evenReal + tr
                imag[even] = evenImag + ti
                real[odd] = evenReal - tr
                imag[odd] = evenImag - ti
                val nextReal = twiddleReal * stepReal - twiddleImag * stepImag
                twiddleImag = twiddleReal * stepImag + twiddleImag * stepReal
                twiddleReal = nextReal
            }
            base += length
        }
        length = length shl 1
    }
}

/** Fixed-work, deterministic CPU workloads. Startup/warm-up is excluded from measured iterations. */
class BenchmarkSuiteRunner(
    private val context: Context,
    private val telemetry: () -> TelemetrySnapshot,
    private val cancelled: () -> Boolean,
    private val onProgress: (SuiteProgress) -> Unit
) {
    private val runStartedNs = System.nanoTime()
    private data class Spec(
        val category: String,
        val name: String,
        val totalUnits: Int,
        val operationsPerUnit: Long,
        val bytesPerUnit: Long = 0,
        val latency: Boolean = false,
        val prepare: (units: Int, worker: Int, workers: Int, check: () -> Unit) -> (() -> Long)
    )

    private data class Sample(val elapsedNs: Long, val operations: Long, val bytes: Long, val checksum: Long)
    private var workerCpuIds: List<Int> = emptyList()
    private val sink = AtomicLong(0)
    fun run(config: SuiteRunConfig, cpuIds: List<Int>): SuiteRunResult {
        val workers = if (config.singleCore) 1 else cpuIds.size.coerceIn(1, 16)
        workerCpuIds = cpuIds.take(workers)
        val fullSuite = config.preset == SuitePreset.FULL
        val deep = config.preset == SuitePreset.DEEP || fullSuite
        val sustainedOnly = config.preset == SuitePreset.SUSTAINED
        val runSustained = sustainedOnly || fullSuite
        val selected = config.selectedCategories
        val all = config.preset != SuitePreset.CUSTOM && !sustainedOnly
        val specs = buildList {
            if (!sustainedOnly && (all || "Integer" in selected)) add(integerSpec(deep))
            if (!sustainedOnly && (all || "Floating point" in selected)) {
                add(floatSpec(deep))
                add(floatFunctionsSpec(deep))
            }
            if (!sustainedOnly && (all || "Memory" in selected)) {
                add(memorySpec(deep))
                add(pointerSpec(64 * 1024, workers, if (deep) 4_000_000 else 2_000_000, "Pointer chase · 64 KB"))
                if (deep) listOf(4, 16, 64, 256, 1024, 4096, 8192).forEach { kb ->
                    add(pointerSpec(kb * 1024, workers, 4_000_000, "Random access · ${kb} KB", category = "Cache"))
                }
            }
            if (!sustainedOnly && (all || "Matrix" in selected)) add(matrixSpec(if (deep) 256 else 128, workers))
            if (!sustainedOnly && (all || "Cryptography" in selected)) {
                add(cryptoSpec("SHA-1", deep))
                add(cryptoSpec("SHA-256", deep))
                add(cryptoSpec("SHA-512", deep))
                add(crc32Spec(deep))
                add(aesSpec(16, deep))
                add(aesSpec(32, deep))
            }
            if (!sustainedOnly && (all || "Compression" in selected)) {
                add(compressionSpec(deep))
                add(decompressionSpec(deep))
            }
            if (!sustainedOnly && (all || "DSP" in selected)) add(fftSpec(workers))
            if (!sustainedOnly && (all || "Real world" in selected)) add(realWorldSpec(deep, workers))
        }

        val includeImage = !sustainedOnly && (all || "SIMD" in selected)
        val includeScaling = !sustainedOnly && config.preset != SuitePreset.CUSTOM && !config.singleCore
        val includeAi = fullSuite
        val totalTests = specs.size + (if (includeImage) 1 else 0) + (if (includeScaling) 1 else 0) +
            (if (includeAi) CpuInferenceBenchmarks.WORKLOAD_COUNT else 0) + (if (runSustained) 1 else 0)
        var completed = 0
        val measurements = mutableListOf<TestMeasurement>()
        var peakTemp: Double? = null
        val executor = Executors.newFixedThreadPool(workers) { runnable ->
            Thread(runnable, "BlueBench-CPU").apply { isDaemon = true }
        }
        try {
            for (spec in specs) {
                checkCancelled()
                report(spec.name, completed, totalTests)
                val measured = measure(spec, workers, executor) { fraction ->
                    report(spec.name, completed, totalTests, fraction = fraction)
                }
                val snap = telemetry()
                snap.cpuTempC?.toDouble()?.let { peakTemp = max(peakTemp ?: it, it) }
                val category = spec.category
                measurements += TestMeasurement(
                    category = category,
                    name = spec.name,
                    value = measured.value,
                    unit = measured.unit,
                    elapsedNs = measured.elapsedNs,
                    iterations = measured.iterations,
                    operations = measured.operations,
                    bytesProcessed = measured.bytes,
                    threadCount = if (spec.category == "SIMD" || spec.latency) 1 else workers,
                    frequencyMhz = snap.freqsMhz.filter { it > 0 }.averageOrNull(),
                    temperatureC = snap.cpuTempC?.toDouble(),
                    scoreRatePerSecond = measured.rate,
                    detail = measured.detail,
                    latencyP50Ns = measured.latencyP50Ns,
                    latencyP90Ns = measured.latencyP90Ns
                )
                completed++
                report(spec.name, completed, totalTests, fraction = 1f)
            }
            if (includeImage) {
                report("RGBA grayscale · scalar / NEON", completed, totalTests)
                measurements += runNativeImage(executor)
                val imageSnap = telemetry()
                imageSnap.cpuTempC?.toDouble()?.let { peakTemp = max(peakTemp ?: it, it) }
                completed++
                report("SIMD image processing", completed, totalTests, fraction = 1f)
            }
            if (includeScaling) {
                report("Multi-core scaling", completed, totalTests)
                measurements += runScaling(cpuIds, workers)
                completed++
                report("Multi-core scaling", completed, totalTests, fraction = 1f)
            }
            if (includeAi) {
                val ai = CpuInferenceBenchmarks(context).run(
                    numThreads = workers,
                    cpuIds = workerCpuIds,
                    telemetry = telemetry,
                    cancelled = ::checkCancelled,
                    onProgress = { index, name, fraction ->
                        report(name, completed + index, totalTests, fraction = fraction)
                    }
                )
                measurements += ai
                completed += CpuInferenceBenchmarks.WORKLOAD_COUNT
                report("On-device AI inference", completed, totalTests, fraction = 1f)
            }
            if (runSustained) {
                val minutes = config.sustainedDurationMinutes.coerceIn(1, 30)
                val stressSpecs = listOf(integerSpec(false), floatSpec(false), matrixSpec(128, workers))
                stressSpecs.forEach { measure(it, workers, executor, samples = 1, warmup = true, minimumDurationNs = 0L, minimumSamples = 1) }
                val sustainedStart = System.nanoTime()
                val until = sustainedStart + TimeUnit.MINUTES.toNanos(minutes.toLong())
                report("Sustained CPU performance", completed, totalTests, sustained = true)
                var nextReport = sustainedStart + TimeUnit.SECONDS.toNanos(15)
                var windowStart = sustainedStart
                val peakRates = mutableMapOf<String, Double>()
                var windowOps = mutableMapOf<String, Long>()
                var windowNs = mutableMapOf<String, Long>()
                var windowBytes = mutableMapOf<String, Long>()
                var windowCounts = mutableMapOf<String, Int>()
                while (System.nanoTime() < until) {
                    checkCancelled()
                    for (spec in stressSpecs) {
                        if (System.nanoTime() >= until) break
                        val sample = measure(spec, workers, executor, samples = 1, warmup = false,
                            minimumDurationNs = 0L, minimumSamples = 1)
                        windowOps[spec.category] = (windowOps[spec.category] ?: 0L) + sample.operations
                        windowNs[spec.category] = (windowNs[spec.category] ?: 0L) + sample.elapsedNs
                        windowBytes[spec.category] = (windowBytes[spec.category] ?: 0L) + sample.bytes
                        windowCounts[spec.category] = (windowCounts[spec.category] ?: 0) + 1
                    }
                    val now = System.nanoTime()
                    if (now >= nextReport || now >= until) {
                        checkCancelled()
                        val snap = telemetry()
                        snap.cpuTempC?.toDouble()?.let { peakTemp = max(peakTemp ?: it, it) }
                        val windowSeconds = ((now - windowStart) / 1e9).coerceAtLeast(1e-9)
                        stressSpecs.map { it.category }.distinct().forEach { category ->
                            val ops = windowOps[category] ?: 0L
                            val ns = windowNs[category] ?: 0L
                            if (ops <= 0L || ns <= 0L) return@forEach
                            val rate = ops.toDouble() / (ns / 1e9)
                            val previousPeak = peakRates[category] ?: rate
                            peakRates[category] = max(previousPeak, rate)
                            val matrix = category == "Matrix"
                            val unit = if (matrix) "GFLOP/s" else "M ops/s"
                            val displayRate = if (matrix) rate / 1e9 else rate / 1e6
                            val pctPeak = (rate / peakRates.getValue(category) * 100).toInt().coerceIn(0, 100)
                            measurements += TestMeasurement(
                                category = category, name = "Sustained $category · ${"%.0f".format((now - sustainedStart) / 1e9)} s",
                                value = displayRate, unit = unit, elapsedNs = ns,
                                iterations = (windowCounts[category] ?: 0).toLong(), operations = ops,
                                bytesProcessed = windowBytes[category] ?: 0L, threadCount = workers,
                                frequencyMhz = snap.freqsMhz.filter { it > 0 }.averageOrNull(),
                                temperatureC = snap.cpuTempC?.toDouble(), scoreRatePerSecond = rate,
                                detail = "${"%.1f".format(windowSeconds)} s window · $pctPeak% of observed peak"
                            )
                        }
                        windowStart = now
                        windowOps = mutableMapOf()
                        windowNs = mutableMapOf()
                        windowBytes = mutableMapOf()
                        windowCounts = mutableMapOf()
                        nextReport = now + TimeUnit.SECONDS.toNanos(15)
                        report("Sustained CPU performance", completed, totalTests, sustained = true,
                            fraction = ((now - sustainedStart).toDouble() / (until - sustainedStart)).toFloat().coerceIn(0f, 1f))
                    }
                }
                completed++
                report("Sustained CPU performance", completed, totalTests, sustained = true, fraction = 1f)
            }
        } finally {
            executor.shutdownNow()
            executor.awaitTermination(2, TimeUnit.SECONDS)
        }
        checkCancelled()
        val categoryScores = calculateCategoryScores(measurements)
        val overall = CpuSuiteScore.overallScore(categoryScores)
        val elapsed = (System.nanoTime() - runStartedNs) / 1_000_000_000.0
        val json = encodeRawResults(config, workers, measurements, categoryScores, overall, elapsed, peakTemp)
        return SuiteRunResult(overall, categoryScores, measurements, elapsed, peakTemp, config.preset, config.singleCore, json)
    }

    private data class Measured(
        val value: Double,
        val unit: String,
        val elapsedNs: Long,
        val iterations: Long,
        val operations: Long,
        val bytes: Long,
        val rate: Double,
        val detail: String = "",
        val latencyP50Ns: Long? = null,
        val latencyP90Ns: Long? = null
    )

    private fun measure(spec: Spec, workers: Int, executor: java.util.concurrent.ExecutorService,
                        samples: Int = 3, warmup: Boolean = true,
                        minimumDurationNs: Long = 1_000_000_000L,
                        minimumSamples: Int = 5,
                        onProgress: (Float) -> Unit = {}): Measured {
        val sampleWorkers = if (spec.latency) 1 else workers
        if (warmup) runSample(spec, sampleWorkers, executor, max(1, spec.totalUnits / 10))
        var totalNs = 0L
        var totalOps = 0L
        var totalBytes = 0L
        var totalChecksum = 0L
        var sampleCount = 0
        val latencySamples = mutableListOf<Double>()
        val requiredSamples = max(samples, minimumSamples.coerceAtLeast(1))
        do {
            checkCancelled()
            val sample = runSample(spec, sampleWorkers, executor, spec.totalUnits)
            checkCancelled()
            totalNs += sample.elapsedNs
            totalOps += sample.operations
            totalBytes += sample.bytes
            totalChecksum += sample.checksum
            if (spec.latency && sample.operations > 0) {
                latencySamples += sample.elapsedNs.toDouble() / sample.operations
            }
            sink.addAndGet(sample.checksum)
            sampleCount++
            onProgress(if (minimumDurationNs <= 0L) 1f else (totalNs.toDouble() / minimumDurationNs).toFloat().coerceIn(0f, 1f))
        } while (totalNs < minimumDurationNs || sampleCount < requiredSamples)
        val rate = totalOps.toDouble() / (totalNs.coerceAtLeast(1) / 1e9)
        if (spec.latency) {
            val sorted = latencySamples.sorted()
            val p50 = sorted.getOrElse(sorted.size / 2) { totalNs.toDouble() / totalOps.coerceAtLeast(1) }
            val p90 = sorted.getOrElse((sorted.size * .9).toInt().coerceAtMost(sorted.lastIndex)) { p50 }
            val nsAccess = p50
            return Measured(nsAccess, "ns/access", totalNs, sampleCount.toLong(), totalOps, totalBytes,
                1e9 / nsAccess.coerceAtLeast(1e-9), latencyP50Ns = p50.roundToLong(), latencyP90Ns = p90.roundToLong())
        }
        if (spec.bytesPerUnit > 0) {
            val gbps = totalBytes.toDouble() / (totalNs.coerceAtLeast(1) / 1e9) / 1e9
            val bytesPerSecond = totalBytes.toDouble() / (totalNs.coerceAtLeast(1) / 1e9)
            val detail = if (spec.category == "Compression" && spec.name.contains("compression", ignoreCase = true) && totalChecksum > 0) {
                "compression ratio ${"%.2f".format(totalBytes.toDouble() / totalChecksum)}×"
            } else ""
            return Measured(gbps, "GB/s", totalNs, sampleCount.toLong(), totalOps, totalBytes, bytesPerSecond, detail)
        }
        val unit = if (spec.category == "Matrix") "GFLOP/s" else "M ops/s"
        val value = if (spec.category == "Matrix") rate / 1e9 else rate / 1e6
        return Measured(value, unit, totalNs, sampleCount.toLong(), totalOps, totalBytes, rate)
    }

    private fun runSample(spec: Spec, workers: Int, executor: java.util.concurrent.ExecutorService,
                          units: Int): Sample {
        checkCancelled()
        val unitCounts = IntArray(workers) { units / workers + if (it < units % workers) 1 else 0 }
        val plans = unitCounts.mapIndexed { worker, count ->
            spec.prepare(count, worker, workers, ::checkCancelled)
        }
        val ready = CountDownLatch(workers)
        val startGate = CountDownLatch(1)
        val futures = plans.mapIndexed { worker, plan -> executor.submit<Long> {
            workerCpuIds.getOrNull(worker)?.let { BenchmarkEngine.pinCurrentThread(it) }
            ready.countDown()
            startGate.await()
            plan.invoke()
        } }
        ready.await()
        val measuredStart = System.nanoTime()
        startGate.countDown()
        var checksum = 0L
        try {
            futures.forEach { checksum += it.get() }
        } catch (e: Exception) {
            futures.forEach { it.cancel(true) }
            checkCancelled()
            throw e
        }
        val elapsed = (System.nanoTime() - measuredStart).coerceAtLeast(1)
        val actualUnits = unitCounts.sum().toLong()
        return Sample(elapsed, actualUnits * spec.operationsPerUnit,
            actualUnits * spec.bytesPerUnit, checksum)
    }

    private fun integerSpec(deep: Boolean) = Spec("Integer", "Integer arithmetic · bitwise · branches",
        if (deep) 16_000_000 else 8_000_000, 8) { units, worker, _, check ->
        return@Spec {
            var x = 0x1234abcdL + worker
            var branch = 0L
            for (i in 0 until units) {
                if ((i and 4095) == 0) check()
                x = (x + i + 17) xor (x shl 7)
                x = x * 2862933555777941757L + 3037000493L
                branch += if (((i * 1103515245 + 12345) and 7) < 4) x else x ushr 3
            }
            x xor branch
        }
    }

    private fun floatSpec(deep: Boolean) = Spec("Floating point", "FP32 / FP64 arithmetic",
        if (deep) 8_000_000 else 4_000_000, 10) { units, worker, _, check ->
        return@Spec {
            var a = 0.125f + worker
            var b = 0.875f
            var d = 0.125 + worker
            var e = 0.875
            for (i in 0 until units) {
                if ((i and 4095) == 0) check()
                a = a * 1.0000001f + b
                b = b * 0.9999999f + a * 0.000001f
                d = d * 1.0000000001 + e
                e = e * 0.9999999999 + d * 0.0000001
            }
            (a.toRawBits().toLong() shl 32) xor d.toBits() xor b.toRawBits().toLong() xor e.toBits()
        }
    }

    private fun floatFunctionsSpec(deep: Boolean) = Spec("Floating point", "FP math functions · sqrt / sin / cos / log / exp",
        if (deep) 1_000_000 else 500_000, 5) { units, worker, _, check ->
        return@Spec {
            var sum = 0.0
            repeat(units) { i ->
                if ((i and 1023) == 0) check()
                val x = ((i + worker * 19) % 997 + 1) / 997.0
                sum += sqrt(x) + sin(x) + cos(x) + ln(x + 1.0) + kotlin.math.exp(x * .01)
            }
            sum.toBits()
        }
    }

    private fun memorySpec(deep: Boolean) = Spec("Memory", "Sequential read + write",
        if (deep) 512 else 256, 1, 512L * 1024) { units, worker, _, check ->
        val bytes = ByteArray(256 * 1024)
        val dst = ByteArray(bytes.size)
        for (i in bytes.indices step 4096) bytes[i] = (i + worker).toByte()
        return@Spec {
            var checksum = 0L
            repeat(units) { n ->
                if ((n and 15) == 0) check()
                System.arraycopy(bytes, 0, dst, 0, bytes.size)
                checksum += dst[(n * 4093) and (dst.size - 1)].toLong()
            }
            checksum
        }
    }

    private fun pointerSpec(requestedBytes: Int, workers: Int, totalAccesses: Int, label: String, category: String = "Memory"): Spec {
        val bytes = max(4096, min(requestedBytes, max(4096, 16 * 1024 * 1024 / workers)))
        val size = Integer.highestOneBit((bytes / 4).coerceAtLeast(1024)).coerceAtLeast(1024)
        val actualLabel = if (bytes < requestedBytes) "$label · ${bytes / 1024} KB per worker" else label
        return Spec(category, actualLabel, totalAccesses, 1, latency = true) { units, worker, _, check ->
            val next = IntArray(size)
            for (i in 0 until size) next[i] = (i * 1_103_515_245 + 12_345) and (size - 1)
            return@Spec {
                var at = (worker * 7919 + 17) and (size - 1)
                repeat(units) { n ->
                    if ((n and 8191) == 0) check()
                    at = next[at]
                }
                at.toLong()
            }
        }
    }

    private fun matrixSpec(size: Int, workers: Int) = Spec("Matrix", "FP32 matrix multiply · ${size}×$size",
        size, 2L * size * size) { rows, worker, threadCount, check ->
        val a = FloatArray(size * size) { i -> ((i * 17 + 7) % 101) / 101f }
        val b = FloatArray(size * size) { i -> ((i * 29 + 11) % 97) / 97f }
        val c = FloatArray(size * size)
        val startRow = (size / threadCount) * worker + min(worker, size % threadCount)
        return@Spec {
            var checksum = 0L
            for (r in startRow until startRow + rows) {
                check()
                val row = r * size
                for (col in 0 until size) {
                    var value = 0f
                    for (k in 0 until size) value += a[row + k] * b[k * size + col]
                    c[row + col] = value
                }
                checksum += c[row + (r % size)].toBits()
            }
            checksum
        }
    }

    private fun cryptoSpec(name: String, deep: Boolean) = Spec("Cryptography", "$name hashing",
        if (deep) 48 else 24, 64 * 1024L, 64L * 1024) { units, worker, _, check ->
        val input = ByteArray(64 * 1024) { ((it * 31 + worker) and 255).toByte() }
        val digest = MessageDigest.getInstance(name)
        return@Spec {
            var sum = 0L
            repeat(units) { n ->
                check()
                digest.update(input)
                val result = digest.digest()
                sum += result[n % result.size].toLong()
            }
            sum
        }
    }

    private fun crc32Spec(deep: Boolean) = Spec("Cryptography", "CRC32 checksum", if (deep) 48 else 24, 64 * 1024L, 64L * 1024) { units, worker, _, check ->
        val input = ByteArray(64 * 1024) { ((it * 23 + worker) and 255).toByte() }
        val crc = CRC32()
        return@Spec {
            var checksum = 0L
            repeat(units) {
                check()
                crc.reset()
                crc.update(input)
                checksum += crc.value
            }
            checksum
        }
    }

    @SuppressLint("GetInstance")
    private fun aesSpec(keyBytes: Int, deep: Boolean) = Spec("Cryptography", "AES-${keyBytes * 8} encryption",
        if (deep) 48 else 24, 64 * 1024L, 64L * 1024) { units, worker, _, check ->
        val input = ByteArray(64 * 1024) { ((it * 13 + worker) and 255).toByte() }
        // ECB is deliberately used here to isolate raw AES block throughput on fixed, synthetic data.
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(ByteArray(keyBytes) { (it * 7 + worker).toByte() }, "AES"))
        return@Spec {
            var sum = 0L
            repeat(units) { n ->
                check()
                val encrypted = cipher.doFinal(input)
                sum += encrypted[n % encrypted.size].toLong()
            }
            sum
        }
    }

    private fun compressionSpec(deep: Boolean) = Spec("Compression", "Deflate compression",
        if (deep) 48 else 24, 64 * 1024L, 64L * 1024) { units, worker, _, check ->
        val input = ByteArray(64 * 1024) { ((it / 7 + worker * 13) and 255).toByte() }
        val output = ByteArray(input.size + 1024)
        val deflater = Deflater(1)
        return@Spec {
            try {
                var compressed = 0L
                repeat(units) { n ->
                    check()
                    deflater.reset()
                    deflater.setInput(input)
                    deflater.finish()
                    var count = 0
                    while (!deflater.finished()) {
                        if ((count and 7) == 0) check()
                        count += deflater.deflate(output)
                    }
                    compressed += count
                }
                compressed
            } finally {
                deflater.end()
            }
        }
    }

    private fun decompressionSpec(deep: Boolean) = Spec("Compression", "Deflate decompression",
        if (deep) 48 else 24, 64 * 1024L, 64L * 1024) { units, worker, _, check ->
        val input = ByteArray(64 * 1024) { ((it / 7 + worker * 13) and 255).toByte() }
        val compressedBuffer = ByteArray(input.size + 1024)
        val deflater = Deflater(1)
        deflater.setInput(input)
        deflater.finish()
        var compressedSize = 0
        while (!deflater.finished() && compressedSize < compressedBuffer.size) {
            val count = deflater.deflate(compressedBuffer, compressedSize, compressedBuffer.size - compressedSize)
            if (count == 0) break
            compressedSize += count
        }
        deflater.end()
        require(compressedSize > 0) { "Unable to prepare deterministic Deflate input" }
        val inflater = Inflater()
        val output = ByteArray(input.size)
        return@Spec {
            try {
                var outputBytes = 0L
                repeat(units) {
                    check()
                    inflater.reset()
                    inflater.setInput(compressedBuffer, 0, compressedSize)
                    var count = 0
                    while (!inflater.finished()) {
                        if ((count and 7) == 0) check()
                        val produced = inflater.inflate(output)
                        if (produced == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                            error("Deflate input ended before the stream completed")
                        }
                        count += produced
                    }
                    outputBytes += count
                }
                outputBytes
            } finally {
                inflater.end()
            }
        }
    }

    private fun fftSpec(workers: Int) = Spec("DSP", "Radix-2 FFT · 1,024-point complex",
        max(8, 128 / workers), 51_200) { units, worker, _, check ->
        val size = 1024
        val baseReal = DoubleArray(size) { i -> sinDet(i + worker) }
        val baseImag = DoubleArray(size) { i -> sinDet(i + worker + 31) * 0.5 }
        val real = DoubleArray(size)
        val imag = DoubleArray(size)
        return@Spec {
            var checksum = 0L
            repeat(units) { n ->
                check()
                System.arraycopy(baseReal, 0, real, 0, size)
                System.arraycopy(baseImag, 0, imag, 0, size)
                radix2FftInPlace(real, imag)
                checksum += real[(n * 31) and (size - 1)].toBits() xor imag[(n * 17) and (size - 1)].toBits()
            }
            checksum
        }
    }

    private fun realWorldSpec(deep: Boolean, workers: Int) = Spec("Real world", "JSON parsing + sorting",
        max(8, (if (deep) 512 else 256) / workers), 1024) { units, worker, _, check ->
        val payload = """{"device":"android","cores":8,"values":[3,7,11,19,23,29,31,37],"ok":true,"worker":$worker}"""
        val values = IntArray(1024)
        return@Spec {
            var checksum = 0L
            repeat(units) { n ->
                check()
                val parsed = org.json.JSONObject(payload).getJSONArray("values")
                for (i in 0 until parsed.length()) values[i] = parsed.getInt(i)
                values[0] = (values[0] xor n)
                Arrays.sort(values)
                checksum += values[(n * 17) and (values.size - 1)].toLong()
            }
            checksum
        }
    }

    private fun runNativeImage(executor: java.util.concurrent.ExecutorService): List<TestMeasurement> {
        val pixels = 512 * 512
        val passes = 20
        val out = mutableListOf<TestMeasurement>()
        fun run(variant: Boolean, passCount: Int): DoubleArray = executor.submit<DoubleArray> {
            workerCpuIds.firstOrNull()?.let { BenchmarkEngine.pinCurrentThread(it) }
            BenchmarkEngine.imageBenchmark(pixels, passCount, variant)
        }.get()
        for (variant in listOf(false, true)) {
            checkCancelled()
            repeat(2) { run(variant, 2) }
            var elapsedSeconds = 0.0
            var sampleCount = 0
            var supported = true
            while (elapsedSeconds < 1.0 || sampleCount < 5) {
                checkCancelled()
                val result = run(variant, passes)
                if (result.size < 2 || result[0] <= 0.0) {
                    supported = false
                    break
                }
                elapsedSeconds += result[0]
                if (variant && (result.size < 3 || result[2] < .5)) {
                    supported = false
                    break
                }
                sampleCount++
            }
            if (!supported) {
                out += TestMeasurement("SIMD", if (variant) "NEON path unavailable" else "Native grayscale unavailable",
                    0.0, "unavailable", 0L, 0L, 0L, 0L, 1, null, null, 0.0,
                    if (variant) "This CPU does not report runtime NEON support; no fallback timing is counted."
                    else "The native image benchmark could not start on this device.")
                continue
            }
            if (elapsedSeconds <= 0.0) continue
            val measuredPasses = sampleCount.toLong() * passes
            val pxPerSecond = pixels.toDouble() * measuredPasses / elapsedSeconds
            out += TestMeasurement("SIMD", if (variant) "RGBA grayscale · NEON" else "RGBA grayscale · scalar",
                pxPerSecond / 1e6, "MPixels/s", (elapsedSeconds * 1e9).toLong(), measuredPasses,
                pixels.toLong() * measuredPasses, pixels.toLong() * 4 * measuredPasses, 1,
                telemetry().freqsMhz.filter { it > 0 }.averageOrNull(), telemetry().cpuTempC?.toDouble(),
                pxPerSecond, if (variant) "NEON available on ARM; otherwise native scalar fallback" else "Native scalar reference")
        }
        val scalar = out.firstOrNull { "scalar" in it.name }?.scoreRatePerSecond
        return out.map { if ("NEON" in it.name && scalar != null && scalar > 0) it.copy(detail = "${it.detail}; speedup ${"%.2f".format(it.scoreRatePerSecond / scalar)}×") else it }
    }

    private fun runScaling(cpuIds: List<Int>, workers: Int): List<TestMeasurement> {
        val rates = mutableListOf<Pair<Int, Measured>>()
        for (threadCount in listOf(1, 2, 4, workers).distinct().filter { it <= workers }) {
            checkCancelled()
            val spec = integerSpec(deep = false)
            val pool = Executors.newFixedThreadPool(threadCount)
            try {
                val result = measure(spec, threadCount, pool, samples = 1, warmup = false)
                rates += threadCount to result
            } finally {
                pool.shutdownNow()
                pool.awaitTermination(2, TimeUnit.SECONDS)
            }
        }
        val single = rates.firstOrNull { it.first == 1 }?.second?.rate ?: return emptyList()
        return rates.map { (count, measured) ->
            val ratio = measured.rate / single.coerceAtLeast(1.0)
            val snapshot = telemetry()
            val label = "$count thread" + if (count == 1) "" else "s"
            val efficiency = "efficiency " + "%.0f".format(ratio / count * 100) + "% · " +
                "%.2f".format(measured.rate / count / 1e6) + " M ops/s/thread"
            TestMeasurement("Scaling", label, ratio, "×",
                measured.elapsedNs, measured.iterations, measured.operations, measured.bytes, count,
                snapshot.freqsMhz.filter { it > 0 }.averageOrNull(), snapshot.cpuTempC?.toDouble(), ratio, efficiency)
        }
    }

    private fun calculateCategoryScores(metrics: List<TestMeasurement>): Map<String, Int> {
        // Keep sustained windows in raw history; repeated windows must not overweight the peak score.
        return CpuSuiteScore.categoryScores(metrics.filterNot { it.name.startsWith("Sustained ") })
    }

    private fun encodeRawResults(config: SuiteRunConfig, workers: Int, metrics: List<TestMeasurement>,
                                 scores: Map<String, Int>, score: Int, elapsed: Double, peakTemp: Double?): String {
        val root = JSONObject()
            .put("schema", 1)
            .put("scoreVersion", CpuSuiteScore.profileId(config))
            .put("preset", config.preset.name)
            .put("coreMode", if (config.singleCore) "SINGLE_CORE" else "MULTI_CORE")
            .put("threadCount", workers)
            .put("elapsedSeconds", elapsed)
            .put("score", score)
            .put("categoryScores", JSONObject(scores.mapValues { it.value }))
            .put("peakTemperatureC", peakTemp ?: JSONObject.NULL)
            .put("measurements", JSONArray().apply {
                metrics.forEach { m -> put(JSONObject()
                    .put("category", m.category).put("name", m.name)
                    .put("value", m.value).put("unit", m.unit)
                    .put("elapsedNs", m.elapsedNs).put("iterations", m.iterations)
                    .put("operations", m.operations).put("bytesProcessed", m.bytesProcessed)
                    .put("threadCount", m.threadCount)
                    .put("throughputPerSecond", m.scoreRatePerSecond)
                    .put("scoreReferenceRatePerSecond", CpuSuiteScore.referenceRate(m.category, m.unit, m.name))
                    .put("throughputUnit", when (m.unit) {
                        "GB/s" -> "bytes/s"
                        "ns/access" -> "accesses/s"
                        "MPixels/s" -> "pixels/s"
                        "inferences/s" -> "inferences/s"
                        "GFLOP/s" -> "FLOP/s"
                        "×" -> "ratio"
                        else -> "ops/s"
                    })
                    .put("frequencyMhz", m.frequencyMhz ?: JSONObject.NULL)
                    .put("temperatureC", m.temperatureC ?: JSONObject.NULL)
                    .put("latencyP50Ns", m.latencyP50Ns ?: JSONObject.NULL)
                    .put("latencyP90Ns", m.latencyP90Ns ?: JSONObject.NULL)
                    .put("powerWatts", JSONObject.NULL).put("cycles", JSONObject.NULL)
                    .put("instructions", JSONObject.NULL).put("ipc", JSONObject.NULL)
                    .put("cacheHits", JSONObject.NULL).put("cacheMisses", JSONObject.NULL)
                    .put("coreAffinity", if (config.singleCore) "best effort · one allowed CPU" else "best effort · allowed CPUs")
                    .put("detail", m.detail)) }
            })
        return root.toString()
    }

    private fun report(name: String, done: Int, total: Int, fraction: Float = 0f, sustained: Boolean = false) {
        onProgress(SuiteProgress(name, done, total,
            (System.nanoTime() - runStartedNs) / 1_000_000_000.0, sustained, fraction.coerceIn(0f, 1f)))
    }

    private fun checkCancelled() {
        if (cancelled()) throw InterruptedException("Benchmark cancelled")
    }

    private fun List<Int>.averageOrNull(): Double? = filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()
    private fun geometricMean(values: List<Double>): Double {
        if (values.isEmpty()) return 1.0
        return exp(values.map { ln(it.coerceAtLeast(1e-9)) }.average())
    }

    private fun sinDet(value: Int): Double = ((value * 37 % 997) - 498) / 499.0
}
