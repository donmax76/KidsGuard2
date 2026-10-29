package com.example.util

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugLogEntry(
  val id: Long = System.currentTimeMillis() + (0..999).random(),
  val timestamp: Long = System.currentTimeMillis(),
  val tag: String,
  val action: String,
  val details: String,
  val status: String // SUCCESS, BLOCKED, INTERCEPTED, WARNING, ERROR
)

object DeviceControlDebugger {
  private const val TAG = "KidsGuardDebug"
  private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
  val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

  fun logEvent(action: String, details: String, status: String = "SUCCESS") {
    val entry = DebugLogEntry(
      tag = TAG,
      action = action,
      details = details,
      status = status
    )
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    try {
      android.util.Log.d(TAG, "[$time] [$status] $action: $details")
    } catch (_: Throwable) {
      println("[$TAG] [$time] [$status] $action: $details")
    }

    val current = _logs.value.toMutableList()
    current.add(0, entry)
    if (current.size > 100) {
      _logs.value = current.take(100)
    } else {
      _logs.value = current
    }
  }

  fun clearLogs() {
    _logs.value = emptyList()
  }
}
