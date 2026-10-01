package com.bluebenchmark.cpu.engine

import android.content.Context
import com.bluebenchmark.cpu.telemetry.TelemetrySnapshot
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.Tensor
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.sin

/** CPU-only inference cases. Model creation and warm-up are outside the measured interval. */
internal class CpuInferenceBenchmarks(private val context: Context) {
    private data class Workload(
        val name: String,
        val asset: String,
        val source: String,
        val seed: Int
    )

    private data class PreparedModel(
        val interpreter: Interpreter,
        val inputs: Array<Any>,
        val outputs: MutableMap<Int, Any>,
        val outputBuffers: List<ByteBuffer>,
        val inputBytes: Int,
        val sha256: String
    )

    private val workloads = listOf(
        Workload(
            "Image classification · MobileNet V1 INT8",
            "mobilenet_v1_1.0_224_quant.tflite",
            "TensorFlow MobileNet V1 quantized model",
            11
        ),
        Workload(
            "Object detection · SSD MobileNet V2 UINT8",
            "ssd_mobilenet_v2_300_uint8.tflite",
            "MLPerf Mobile model set v0.7",
            23
        ),
        Workload(
            "Image segmentation · DeepLabV3+ MobileNetV2 UINT8",
            "deeplabv3_mnv2_ade20k_uint8.tflite",
            "MLPerf Mobile model set v0.7",
            37
        )
    )

    fun run(
        numThreads: Int,
        cpuIds: List<Int>,
        telemetry: () -> TelemetrySnapshot,
        cancelled: () -> Unit,
        onProgress: (index: Int, name: String, fraction: Float) -> Unit
    ): List<TestMeasurement> {
        val measurements = mutableListOf<TestMeasurement>()
        workloads.forEachIndexed { index, workload ->
            cancelled()
            onProgress(index, workload.name, 0f)
            val started = System.nanoTime()
            try {
                val modelFile = cachedModel(workload.asset)
                val prepared = prepare(workload, modelFile, numThreads)
                try {
                    cpuIds.firstOrNull()?.let(BenchmarkEngine::pinCurrentThread)
                    // Two unmeasured inferences settle one-time runtime and kernel initialization.
                    repeat(2) {
                        cancelled()
                        invoke(prepared)
                    }
                    val latencySamples = ArrayList<Long>(16)
                    var totalNs = 0L
                    var inputBytes = 0L
                    var outputChecksum = 0L
                    val measuredStart = System.nanoTime()
                    do {
                        cancelled()
                        val sampleStart = System.nanoTime()
                        outputChecksum = outputChecksum xor invoke(prepared)
                        val elapsed = (System.nanoTime() - sampleStart).coerceAtLeast(1L)
                        latencySamples += elapsed
                        totalNs += elapsed
                        inputBytes += prepared.inputBytes
                        onProgress(index, workload.name, (totalNs.toDouble() / MIN_MEASURED_NS).toFloat().coerceIn(0f, 1f))
                    } while (latencySamples.size < MIN_SAMPLES || System.nanoTime() - measuredStart < MIN_MEASURED_NS)

                    val sorted = latencySamples.sorted()
                    val p50 = percentile(sorted, 0.50)
                    val p90 = percentile(sorted, 0.90)
                    val rate = latencySamples.size.toDouble() / (totalNs / 1e9)
                    val snapshot = telemetry()
                    measurements += TestMeasurement(
                        category = "AI inference",
                        name = workload.name,
                        value = rate,
                        unit = "inferences/s",
                        elapsedNs = totalNs,
                        iterations = latencySamples.size.toLong(),
                        operations = latencySamples.size.toLong(),
                        bytesProcessed = inputBytes,
                        threadCount = numThreads,
                        frequencyMhz = snapshot.freqsMhz.filter { it > 0 }.averageOrNull(),
                        temperatureC = snapshot.cpuTempC?.toDouble(),
                        scoreRatePerSecond = rate,
                        detail = "LiteRT 2.2.0 Interpreter · XNNPACK CPU · ${workload.source} · input ${prepared.inputBytes} B · model SHA-256 ${prepared.sha256} · deterministic synthetic input · output checksum $outputChecksum; accuracy is not scored.",
                        latencyP50Ns = p50,
                        latencyP90Ns = p90
                    )
                    onProgress(index, workload.name, 1f)
                } finally {
                    prepared.interpreter.close()
                }
            } catch (e: InterruptedException) {
                throw e
            } catch (e: RuntimeException) {
                // A vendor runtime can omit an optional operator. Keep the suite usable and mark it.
                if (Thread.currentThread().isInterrupted) throw e
                val snapshot = telemetry()
                measurements += TestMeasurement(
                    category = "AI inference",
                    name = workload.name,
                    value = 0.0,
                    unit = "unavailable",
                    elapsedNs = 0L,
                    iterations = 0L,
                    operations = 0L,
                    bytesProcessed = 0L,
                    threadCount = numThreads,
                    frequencyMhz = snapshot.freqsMhz.filter { it > 0 }.averageOrNull(),
                    temperatureC = snapshot.cpuTempC?.toDouble(),
                    scoreRatePerSecond = 0.0,
                    detail = "This LiteRT CPU model could not run on this device: ${e.javaClass.simpleName}."
                )
                onProgress(index, workload.name, 1f)
            }
        }
        return measurements
    }

    private fun prepare(workload: Workload, modelFile: File, numThreads: Int): PreparedModel {
        val options = Interpreter.Options()
            .setNumThreads(numThreads.coerceAtLeast(1))
            .setUseXNNPACK(true)
        val interpreter = Interpreter(modelFile, options)
        try {
            interpreter.allocateTensors()
            val inputs = Array<Any>(interpreter.inputTensorCount) { index ->
                makeInput(interpreter.getInputTensor(index), workload.seed + index)
            }
            val outputs = mutableMapOf<Int, Any>()
            val outputBuffers = (0 until interpreter.outputTensorCount).map { index ->
                val tensor = interpreter.getOutputTensor(index)
                ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder()).also {
                    outputs[index] = it
                }
            }
            return PreparedModel(
                interpreter = interpreter,
                inputs = inputs,
                outputs = outputs,
                outputBuffers = outputBuffers,
                inputBytes = (0 until interpreter.inputTensorCount).sumOf { interpreter.getInputTensor(it).numBytes() },
                sha256 = modelFile.sha256()
            )
        } catch (e: Exception) {
            interpreter.close()
            throw e
        }
    }

    private fun invoke(model: PreparedModel): Long {
        model.inputs.filterIsInstance<ByteBuffer>().forEach { it.rewind() }
        model.outputBuffers.forEach { it.clear() }
        model.interpreter.runForMultipleInputsOutputs(model.inputs, model.outputs)
        var checksum = 0L
        model.outputBuffers.forEachIndexed { index, buffer ->
            if (buffer.capacity() > 0) checksum = checksum xor (buffer.get(0).toLong() shl (index % 8))
        }
        return checksum
    }

    private fun makeInput(tensor: Tensor, seed: Int): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(tensor.numBytes()).order(ByteOrder.nativeOrder())
        when (tensor.dataType()) {
            DataType.UINT8 -> for (i in 0 until tensor.numBytes()) buffer.put(((i * 31 + seed * 17) and 0xff).toByte())
            DataType.INT8 -> for (i in 0 until tensor.numBytes()) buffer.put((((i * 31 + seed * 17) and 0xff) - 128).toByte())
            DataType.FLOAT32 -> {
                val values = buffer.asFloatBuffer()
                repeat(tensor.numElements()) { i -> values.put((sin((i + seed) * 0.017) * 0.5).toFloat()) }
            }
            DataType.INT32 -> {
                val values = buffer.asIntBuffer()
                repeat(tensor.numElements()) { i -> values.put(1 + (i * 17 + seed * 31) % 30000) }
            }
            else -> error("Unsupported CPU inference input type ${tensor.dataType()}")
        }
        buffer.rewind()
        return buffer
    }

    private fun cachedModel(name: String): File {
        val directory = File(context.cacheDir, "cpu-inference-models")
        if (!directory.exists() && !directory.mkdirs()) error("Unable to prepare the local AI model cache")
        val destination = File(directory, name)
        if (!destination.isFile || destination.length() == 0L) {
            context.assets.open("models/$name").use { input -> destination.outputStream().use(input::copyTo) }
        }
        return destination
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().buffered().use { input ->
            val block = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(block)
                if (count < 0) break
                digest.update(block, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(Locale.ROOT, it) }
    }

    private fun percentile(sorted: List<Long>, fraction: Double): Long {
        if (sorted.isEmpty()) return 0L
        val index = (ceil(sorted.size * fraction).toInt() - 1).coerceIn(0, sorted.lastIndex)
        return sorted[index]
    }

    private fun List<Int>.averageOrNull(): Double? = filter { it > 0 }.takeIf { it.isNotEmpty() }?.average()

    companion object {
        const val WORKLOAD_COUNT = 3
        private const val MIN_SAMPLES = 5
        private const val MIN_MEASURED_NS = 1_000_000_000L
    }
}
