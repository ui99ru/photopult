package ru.ui99.photopult.util

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One log line kept in memory for the on-device debug screen. */
data class LogEntry(
    val timeMillis: Long,
    val level: Char,
    val message: String,
)

/**
 * Central logging for the connection cycle.
 *
 * Everything goes to logcat under a single tag so a tester can grab a full trace with
 * `adb logcat -s Photopult`, and is also mirrored into an in-memory ring buffer that the hidden
 * debug screen renders — for when adb isn't at hand. Especially important for Nearby, whose
 * lifecycle and errors are otherwise invisible.
 */
object PhotopultLog {
    const val TAG = "Photopult"
    private const val MAX_ENTRIES = 300

    private val _entries = MutableStateFlow<List<LogEntry>>(emptyList())
    val entries: StateFlow<List<LogEntry>> = _entries.asStateFlow()

    fun d(message: String) = record('D', message) { Log.d(TAG, message) }
    fun i(message: String) = record('I', message) { Log.i(TAG, message) }
    fun w(message: String) = record('W', message) { Log.w(TAG, message) }

    fun e(message: String, throwable: Throwable? = null) =
        record('E', if (throwable != null) "$message: ${throwable.message}" else message) {
            Log.e(TAG, message, throwable)
        }

    fun clear() {
        _entries.value = emptyList()
    }

    @Synchronized
    private fun record(level: Char, message: String, emit: () -> Unit) {
        emit()
        val entry = LogEntry(System.currentTimeMillis(), level, message)
        val current = _entries.value
        val trimmed = if (current.size >= MAX_ENTRIES) {
            current.subList(current.size - MAX_ENTRIES + 1, current.size)
        } else {
            current
        }
        _entries.value = trimmed + entry
    }
}
