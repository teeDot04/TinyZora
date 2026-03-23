package com.telo.tinyzora.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

data class LogEntry(
    val id: String = UUID.randomUUID().toString(),
    val message: String
)

object ConsoleLogger {
    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    fun init() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, exception ->
            e("UncaughtException", "Crash in ${thread.name}: ${exception.message}\n${exception.stackTraceToString()}")
            defaultHandler?.uncaughtException(thread, exception)
        }
    }

    fun d(tag: String, message: String) {
        android.util.Log.d(tag, message)
        appendLog("D/$tag: $message")
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        android.util.Log.e(tag, message, throwable)
        val trace = throwable?.stackTraceToString() ?: ""
        val truncatedTrace = if (trace.length > 2000) trace.take(2000) + "\n...[truncated]" else trace
        appendLog("E/$tag: $message\n$truncatedTrace")
    }

    fun i(tag: String, message: String) {
        android.util.Log.i(tag, message)
        appendLog("I/$tag: $message")
    }

    private fun appendLog(msg: String) {
        val time = dateFormat.format(Date())
        val formatted = "[$time] $msg"
        val current = _logs.value.toMutableList()
        current.add(0, LogEntry(message = formatted)) // Prepend so newest is at the top
        if (current.size > 200) {
            current.removeAt(current.size - 1)
        }
        _logs.value = current
    }

    fun clear() {
        _logs.value = emptyList()
    }
}
