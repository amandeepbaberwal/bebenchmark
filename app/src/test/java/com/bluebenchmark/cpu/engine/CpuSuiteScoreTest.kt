package com.bluebenchmark.cpu.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CpuSuiteScoreTest {
    @Test
    fun higherThroughputProducesHigherCategoryScore() {
        val slower = CpuSuiteScore.categoryScore("Integer", listOf(20_000_000.0))
        val faster = CpuSuiteScore.categoryScore("Integer", listOf(40_000_000.0))

        assertTrue(faster > slower)
    }

    @Test
    fun referenceRateGetsAStableScoreAndHundredTimesReferenceCapsAtMaximum() {
        val reference = CpuSuiteScore.referenceRate("Integer")

        assertEquals(150, CpuSuiteScore.categoryScore("Integer", listOf(reference)))
        assertEquals(1000, CpuSuiteScore.categoryScore("Integer", listOf(reference * 100)))
    }

    @Test
    fun singleAndMultiOverallFormulaDoesNotDependOnScalingCategory() {
        val base = mapOf("Integer" to 510, "Memory" to 620)
        val withScaling = base + ("Scaling" to 900)

        assertEquals(CpuSuiteScore.overallScore(base), CpuSuiteScore.overallScore(withScaling))
    }

    @Test
    fun scoreIsClampedAndEmptyCategoryHasMinimum() {
        assertEquals(1, CpuSuiteScore.categoryScore("unknown", emptyList()))
        assertEquals(1, CpuSuiteScore.categoryScore("Integer", listOf(0.0, Double.NaN)))
        assertEquals(1000, CpuSuiteScore.categoryScore("Integer", listOf(Double.MAX_VALUE)))
    }

    @Test
    fun customProfilesWithDifferentWorkloadsAreNotMixed() {
        val integer = CpuSuiteScore.profileId(SuiteRunConfig(preset = SuitePreset.CUSTOM, selectedCategories = setOf("Integer")))
        val memory = CpuSuiteScore.profileId(SuiteRunConfig(preset = SuitePreset.CUSTOM, selectedCategories = setOf("Memory")))

        assertTrue(integer != memory)
    }

    @Test
    fun memoryBandwidthAndLatencyUseMatchingReferenceUnits() {
        val bandwidth = measurement("Memory", "Sequential read", "GB/s", 1_000_000_000.0)
        val latency = measurement("Memory", "Pointer chase", "ns/access", 10_000_000.0)

        assertEquals(150, CpuSuiteScore.categoryScores(listOf(bandwidth, latency))["Memory"])
    }

    @Test
    fun unavailableMeasurementsDoNotCreateAZeroScoreCategory() {
        val integer = measurement("Integer", "Integer arithmetic", "M ops/s", 80_000_000.0)
        val unavailableSimd = measurement("SIMD", "NEON path unavailable", "unavailable", 0.0)
        val scores = CpuSuiteScore.categoryScores(listOf(integer, unavailableSimd))

        assertTrue(scores.containsKey("Integer"))
        assertFalse(scores.containsKey("SIMD"))
    }

    @Test
    fun radix2FftTransformsAnImpulseToAFlatSpectrum() {
        val real = doubleArrayOf(1.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0)
        val imag = DoubleArray(real.size)

        radix2FftInPlace(real, imag)

        real.forEach { assertEquals(1.0, it, 1e-10) }
        imag.forEach { assertEquals(0.0, it, 1e-10) }
    }

    private fun measurement(category: String, name: String, unit: String, rate: Double) = TestMeasurement(
        category = category,
        name = name,
        value = 1.0,
        unit = unit,
        elapsedNs = 1,
        iterations = 1,
        operations = 1,
        bytesProcessed = 1,
        threadCount = 1,
        frequencyMhz = null,
        temperatureC = null,
        scoreRatePerSecond = rate
    )
}
