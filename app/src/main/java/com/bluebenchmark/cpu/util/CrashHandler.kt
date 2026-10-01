package com.bluebenchmark.cpu.util

import kotlinx.coroutines.CoroutineExceptionHandler

/** Global crash prevention: route coroutine errors into the terminal, never kill the app. */
object CrashHandler {
    fun handler(log: (String) -> Unit) = CoroutineExceptionHandler { _, e ->
        try {
            log("!! ERROR: ${e::class.simpleName}: ${e.message}")
            e.stackTrace.take(8).forEach { log("    at $it") }
        } catch (_: Exception) {
        }
    }
}
