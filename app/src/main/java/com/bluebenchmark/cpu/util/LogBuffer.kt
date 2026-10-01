package com.bluebenchmark.cpu.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Thread-safe in-memory ring buffer for the on-screen terminal. */
class LogBuffer(capacity: Int = 2000) {
    private val cap = capacity.coerceIn(100, 10000)
    private val lock = Any()
    private val lines = ArrayDeque<String>()
    private val _flow = MutableStateFlow<List<String>>(emptyList())
    val flow: StateFlow<List<String>> = _flow.asStateFlow()

    private val ts = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)

    fun append(msg: String) {
        synchronized(lock) {
            val stamped = try {
                "[${ts.format(Date())}] $msg"
            } catch (_: Exception) {
                msg
            }
            lines.addLast(stamped)
            while (lines.size > cap) lines.removeFirst()
            _flow.value = lines.toList()
        }
    }

    fun snapshot(): String = synchronized(lock) { lines.joinToString("\n") }

    fun clear() = synchronized(lock) {
        lines.clear()
        _flow.value = emptyList()
    }
}
