package com.bluebenchmark.cpu.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "benchmarks")
data class BenchmarkRecord(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val mode: String = "SINGLE_CORE",
    val allocatedRamMb: Int = 0,
    val peakTempC: Float = 0f,
    val durationSec: Double = 0.0,
    val finalGflops: Double = 0.0,
    val finalScore: Int = 1,
    @ColumnInfo(defaultValue = "0") val partial: Boolean = false,
    val rawLog: String = "",
    val synced: Boolean = false,
    @ColumnInfo(defaultValue = "'{}'") val rawMetricsJson: String = "{}",
    @ColumnInfo(defaultValue = "'NORMAL'") val suitePreset: String = "NORMAL",
    @ColumnInfo(defaultValue = "''") val deviceName: String = "",
    @ColumnInfo(defaultValue = "'cpu-suite-v1'") val scoreVersion: String = "cpu-suite-v1"
)
