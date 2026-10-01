package com.bluebenchmark.cpu

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.bluebenchmark.cpu.data.local.AppDatabase
import com.bluebenchmark.cpu.data.local.BenchmarkRecord
import com.bluebenchmark.cpu.engine.BenchmarkEngine
import com.bluebenchmark.cpu.engine.BenchmarkSuiteRunner
import com.bluebenchmark.cpu.engine.CpuSuiteScore
import com.bluebenchmark.cpu.engine.SuitePreset
import com.bluebenchmark.cpu.engine.SuiteProgress
import com.bluebenchmark.cpu.engine.SuiteRunConfig
import com.bluebenchmark.cpu.engine.SuiteRunResult
import com.bluebenchmark.cpu.service.BenchmarkForegroundService
import com.bluebenchmark.cpu.telemetry.CpuTelemetryManager
import com.bluebenchmark.cpu.telemetry.TelemetrySnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

enum class CoreMode { SINGLE, MULTI }
enum class RunState { IDLE, RUNNING, COMPLETE, CANCELED, ERROR }

class BenchmarkViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.get(app.applicationContext)
    private val prefs = app.getSharedPreferences("bluebench_settings", 0)
    val telemetry = CpuTelemetryManager(app.applicationContext)
    val history = db.dao().observeAll()

    private val _coreMode = MutableStateFlow(
        runCatching { CoreMode.valueOf(prefs.getString("core_mode", CoreMode.SINGLE.name)!!) }.getOrDefault(CoreMode.SINGLE)
    )
    val coreMode: StateFlow<CoreMode> = _coreMode.asStateFlow()
    private val _runState = MutableStateFlow(RunState.IDLE)
    val runState: StateFlow<RunState> = _runState.asStateFlow()
    private val _progress = MutableStateFlow(SuiteProgress("Ready", 0, 0, 0.0))
    val progress: StateFlow<SuiteProgress> = _progress.asStateFlow()
    private val _result = MutableStateFlow<SuiteRunResult?>(null)
    val result: StateFlow<SuiteRunResult?> = _result.asStateFlow()
    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError.asStateFlow()
    private val _useFahrenheit = MutableStateFlow(prefs.getBoolean("fahrenheit", false))
    val useFahrenheit: StateFlow<Boolean> = _useFahrenheit.asStateFlow()
    private val _darkTheme = MutableStateFlow(prefs.getBoolean("dark_theme", false))
    val darkTheme: StateFlow<Boolean> = _darkTheme.asStateFlow()

    private var runJob: Job? = null
    private val cancelRequested = AtomicBoolean(false)

    init { telemetry.start() }

    fun setCoreMode(mode: CoreMode) {
        if (_runState.value == RunState.RUNNING) return
        _coreMode.value = mode
        prefs.edit { putString("core_mode", mode.name) }
    }
    fun setFahrenheit(value: Boolean) {
        _useFahrenheit.value = value
        prefs.edit { putBoolean("fahrenheit", value) }
    }
    fun setDarkTheme(value: Boolean) {
        _darkTheme.value = value
        prefs.edit { putBoolean("dark_theme", value) }
    }

    fun start() {
        if (_runState.value == RunState.RUNNING) return
        runJob?.cancel()
        cancelRequested.set(false)
        _result.value = null
        _lastError.value = null
        _progress.value = SuiteProgress("Preparing CPU tests", 0, 0, 0.0)
        val selectedPreset = SuitePreset.FULL
        val single = _coreMode.value == CoreMode.SINGLE
        val minutes = 1
        _runState.value = RunState.RUNNING
        if (!startService("Preparing ${selectedPreset.name.lowercase()} CPU suite")) {
            _runState.value = RunState.ERROR
            _lastError.value = "Android could not start the benchmark service."
            return
        }
        runJob = viewModelScope.launch(Dispatchers.Default) {
            try {
                val cpus = withContext(Dispatchers.IO) {
                    BenchmarkEngine.allowedCpuIds(telemetry.coreCount).distinct().ifEmpty { listOf(0) }
                }
                val runner = BenchmarkSuiteRunner(
                    context = getApplication<Application>().applicationContext,
                    telemetry = { telemetry.state.value },
                    cancelled = { cancelRequested.get() || !isActive },
                    onProgress = { progress ->
                        _progress.value = progress
                        updateService("${progress.currentTest} · ${"%.0f".format(progress.elapsedSeconds)} s elapsed")
                    }
                )
                val config = SuiteRunConfig(
                    preset = selectedPreset,
                    singleCore = single,
                    sustainedDurationMinutes = minutes,
                    selectedCategories = emptySet()
                )
                val completed = runner.run(config, cpus)
                _result.value = completed
                val snap = telemetry.state.value
                val deviceName = listOfNotNull(Build.MANUFACTURER, Build.MODEL).joinToString(" ")
                withContext(Dispatchers.IO) {
                    db.dao().insert(
                        BenchmarkRecord(
                            timestamp = System.currentTimeMillis(),
                            mode = if (single) "SINGLE_CORE" else "MULTI_CORE",
                            allocatedRamMb = 0,
                            peakTempC = completed.peakTemperatureC?.toFloat() ?: 0f,
                            durationSec = completed.elapsedSeconds,
                            finalGflops = 0.0,
                            finalScore = completed.score,
                            partial = false,
                            rawLog = "",
                            synced = false,
                            rawMetricsJson = completed.rawJson,
                            suitePreset = selectedPreset.name,
                            deviceName = deviceName,
                            scoreVersion = CpuSuiteScore.profileId(config)
                        )
                    )
                }
                _runState.value = RunState.COMPLETE
                updateService("CPU suite complete · score ${completed.score}")
            } catch (_: CancellationException) {
                _runState.value = RunState.CANCELED
            } catch (e: InterruptedException) {
                _runState.value = RunState.CANCELED
            } catch (e: Exception) {
                _runState.value = if (cancelRequested.get()) RunState.CANCELED else RunState.ERROR
                _lastError.value = e.message ?: "The CPU suite stopped unexpectedly."
            } finally {
                stopService()
            }
        }
    }

    fun cancel() {
        if (_runState.value != RunState.RUNNING) return
        cancelRequested.set(true)
        runJob?.cancel()
        updateService("Stopping CPU workers")
    }

    fun deleteAllHistory() {
        if (_runState.value == RunState.RUNNING) return
        viewModelScope.launch(Dispatchers.IO) {
            if (_runState.value == RunState.RUNNING) return@launch
            db.dao().deleteAll()
            _result.value = null
            _lastError.value = null
            _progress.value = SuiteProgress("Ready", 0, 0, 0.0)
            _runState.value = RunState.IDLE
        }
    }

    fun writeExport(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val rows = db.dao().getAllOnce()
                val json = """{"exportVersion":1,"runs":[${rows.joinToString(",") { it.rawMetricsJson }}]}"""
                getApplication<Application>().contentResolver.openOutputStream(uri)?.use {
                    it.write(json.toByteArray(Charsets.UTF_8))
                } ?: error("Cannot open the selected file")
            }
        }
    }

    override fun onCleared() {
        cancelRequested.set(true)
        runJob?.cancel()
        telemetry.stop()
        super.onCleared()
    }

    private fun startService(text: String): Boolean = try {
        val context = getApplication<Application>()
        val intent = Intent(context, BenchmarkForegroundService::class.java)
            .putExtra(BenchmarkForegroundService.EXTRA_TEXT, text)
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        true
    } catch (_: Exception) { false }

    private fun updateService(text: String) {
        try {
            val context = getApplication<Application>()
            val intent = Intent(context, BenchmarkForegroundService::class.java)
                .putExtra(BenchmarkForegroundService.EXTRA_TEXT, text)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent) else context.startService(intent)
        } catch (_: Exception) { }
    }

    private fun stopService() {
        try { getApplication<Application>().stopService(Intent(getApplication(), BenchmarkForegroundService::class.java)) } catch (_: Exception) { }
    }
}
