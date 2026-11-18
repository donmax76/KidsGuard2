package com.example.util

import android.content.Context
import com.example.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DebugLogEntry(
  val id: Long = System.nanoTime() xor (0..999999).random().toLong(),
  val timestamp: Long = System.currentTimeMillis(),
  val tag: String,
  val action: String,
  val details: String,
  val status: String, // SUCCESS, BLOCKED, INTERCEPTED, WARNING, ERROR, INFO
  val source: String = "PARENT", // PARENT, CHILD, P2P, VPS, PC, SYSTEM
  val appVersion: String = BuildConfig.VERSION_NAME
)

object DeviceControlDebugger {
  private const val TAG = "KidsGuardDebug"
  private const val PREFS_NAME = "kidsguard_debug_logger_prefs"
  private const val KEY_LOGGING_ENABLED = "logging_enabled"
  private const val KEY_LAST_SYNCED_CHILD_TS = "last_synced_child_log_ts"
  private const val LOG_FILE_NAME = "kidsguard_diagnostic_log.json"
  private const val MAX_LOG_ENTRIES = 500
  private const val MAX_PERSISTED_ENTRIES = 350

  @Volatile
  private var appContext: Context? = null

  @Volatile
  private var lastSyncedChildLogTs: Long = 0L

  private val _isLoggingEnabled = MutableStateFlow(true)
  val isLoggingEnabled: StateFlow<Boolean> = _isLoggingEnabled.asStateFlow()

  private val _logs = MutableStateFlow<List<DebugLogEntry>>(emptyList())
  val logs: StateFlow<List<DebugLogEntry>> = _logs.asStateFlow()

  fun init(context: Context) {
    val ctx = context.applicationContext
    appContext = ctx
    try {
      val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
      val enabled = prefs.getBoolean(KEY_LOGGING_ENABLED, true)
      lastSyncedChildLogTs = prefs.getLong(KEY_LAST_SYNCED_CHILD_TS, 0L)
      _isLoggingEnabled.value = enabled
      loadPersistedLogs(ctx)
    } catch (_: Exception) {}
  }

  fun setLoggingEnabled(context: Context?, enabled: Boolean) {
    val ctx = context?.applicationContext ?: appContext
    if (ctx != null) {
      appContext = ctx
      try {
        ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY_LOGGING_ENABLED, enabled)
          .apply()
      } catch (_: Exception) {}
    }
    val prev = _isLoggingEnabled.value
    _isLoggingEnabled.value = enabled
    if (enabled && !prev) {
      forceLogInternal(
        action = "ЛОГГЕР_ВКЛЮЧЕН",
        details = "Диагностическое логирование включено (версия v${BuildConfig.VERSION_NAME})",
        status = "SUCCESS",
        source = "SYSTEM"
      )
    } else if (!enabled && prev) {
      forceLogInternal(
        action = "ЛОГГЕР_ВЫКЛЮЧЕН",
        details = "Диагностическое логирование приостановлено пользователем (версия v${BuildConfig.VERSION_NAME})",
        status = "WARNING",
        source = "SYSTEM"
      )
    }
  }

  fun logEvent(
    action: String,
    details: String,
    status: String = "SUCCESS",
    source: String = "LOCAL",
    appVersion: String = BuildConfig.VERSION_NAME
  ) {
    if (!_isLoggingEnabled.value) return
    forceLogInternal(action, details, status, source, appVersion)
  }

  /**
   * Записывает полный срез всех текущих параметров устройства (заряд, сеть, сокеты,
   * блокировка, сирена, разрешения, активная роль и версия программы).
   */
  fun logFullDeviceSnapshot(
    context: Context?,
    role: String,
    childId: Long,
    triggerReason: String
  ) {
    if (!_isLoggingEnabled.value) return
    val ctx = context?.applicationContext ?: appContext ?: return
    try {
      val batt = DeviceBatteryUtil.getAccurateBatteryPercentage(ctx)
      val charging = DeviceBatteryUtil.isDeviceCharging(ctx)
      val p2pStatus = com.example.data.cloud.DirectP2pSocketEngine.status.value
      val isLocked = DeviceTotalLockManager.isCurrentlyLocked
      val isSiren = SoundAlertManager.isSirenActive.value
      val a11yOk = DeviceSecurityPermissionHelper.isAccessibilityServiceEnabled(ctx)
      val overlayOk = DeviceSecurityPermissionHelper.isOverlayPermissionGranted(ctx)
      val usageOk = DeviceSecurityPermissionHelper.hasUsageStatsPermission(ctx)
      val cachedChild = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(childId)
      val wifi4gMode = if (cachedChild?.allowMobileData != false) "Wi-Fi+4G" else "Только Wi-Fi"
      val gpsMode = if (cachedChild?.gpsTrackingEnabled != false) "ВКЛ" else "ВЫКЛ"
      val studyMode = if (cachedChild?.studyModeActive == true) "ВКЛ" else "ВЫКЛ"

      val summary = buildString {
        append("Причина=").append(triggerReason)
        append(" | Роль=").append(role)
        append(" (#").append(childId).append(")")
        append(" | Батарея=").append(if (batt in 1..100) "$batt%" else "н/д")
        append(if (charging) " (Зарядка)" else "")
        append(" | Брокер=").append(if (p2pStatus.isGlobalTcpSocketConnected) "ОНЛАЙН" else "ОФЛАЙН")
        append(" | LAN=").append(if (p2pStatus.isLanSocketConnected) "ДА" else "НЕТ")
        append(" | БлокЭкрана=").append(if (isLocked) "ЗАБЛОКИРОВАН" else "СНЯТ")
        append(" | Сирена=").append(if (isSiren) "ИГРАЕТ" else "ВЫКЛ")
        append(" | РежимСети=").append(wifi4gMode)
        append(" | GPS=").append(gpsMode)
        append(" | Учёба=").append(studyMode)
        append(" | Доступы=[A11y=").append(if (a11yOk) "OK" else "НЕТ")
        append(", Окно=").append(if (overlayOk) "OK" else "НЕТ")
        append(", Usage=").append(if (usageOk) "OK" else "НЕТ").append("]")
      }
      forceLogInternal(
        action = "СРЕЗ_ПАРАМЕТРОВ",
        details = summary,
        status = "SUCCESS",
        source = if (role == "CHILD") "CHILD" else "PARENT",
        appVersion = BuildConfig.VERSION_NAME
      )
    } catch (_: Exception) {}
  }

  private fun forceLogInternal(
    action: String,
    details: String,
    status: String,
    source: String,
    appVersion: String = BuildConfig.VERSION_NAME
  ) {
    val now = System.currentTimeMillis()
    val entry = DebugLogEntry(
      timestamp = now,
      tag = TAG,
      action = action,
      details = details,
      status = status,
      source = source,
      appVersion = appVersion.ifBlank { BuildConfig.VERSION_NAME }
    )
    val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(entry.timestamp))
    try {
      android.util.Log.d(TAG, "[$time] [v${entry.appVersion}] [$source] [$status] $action: $details")
    } catch (_: Throwable) {
      println("[$TAG] [$time] [v${entry.appVersion}] [$source] [$status] $action: $details")
    }

    synchronized(this) {
      val current = _logs.value.toMutableList()
      val top = current.firstOrNull()
      if (top != null && top.action == action && top.details == details && kotlin.math.abs(now - top.timestamp) < 500L) {
        return
      }
      current.add(0, entry)
      val trimmed = if (current.size > MAX_LOG_ENTRIES) current.take(MAX_LOG_ENTRIES) else current
      _logs.value = trimmed
      persistLogsAsync(trimmed)
    }
  }

  fun ingestRemoteChildLogs(childId: Long, arr: JSONArray, defaultChildVersion: String = BuildConfig.VERSION_NAME) {
    if (!_isLoggingEnabled.value) return
    synchronized(this) {
      val current = _logs.value.toMutableList()
      var added = 0
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val ts = o.optLong("ts", System.currentTimeMillis())
        val rawAct = o.optString("action", "CHILD_LOG")
        val cleanAct = rawAct.removePrefix("📱 РЕБЁНОК #$childId • ").trim()
        val det = o.optString("details", "")
        val st = o.optString("status", "INFO")
        val ver = o.optString("ver", defaultChildVersion).ifBlank { defaultChildVersion }
        val prefixedAction = "📱 РЕБЁНОК #$childId • $cleanAct"
        val exists = current.any {
          it.action == prefixedAction && it.details == det && kotlin.math.abs(it.timestamp - ts) < 1200L
        }
        if (!exists) {
          current.add(
            DebugLogEntry(
              timestamp = ts,
              tag = TAG,
              action = prefixedAction,
              details = det,
              status = st,
              source = "CHILD",
              appVersion = ver
            )
          )
          added++
        }
      }
      if (added > 0) {
        current.sortByDescending { it.timestamp }
        val trimmed = if (current.size > MAX_LOG_ENTRIES) current.take(MAX_LOG_ENTRIES) else current
        _logs.value = trimmed
        persistLogsAsync(trimmed)
      }
    }
  }

  /**
   * Экспорт логов телефона ребёнка для отправки родителю.
   * Если fullDump == false (авто-догрузка при появлении сети после офлайна),
   * выгружает все записи, которые ещё не были синхронизированы с родителем (новее lastSyncedChildLogTs).
   * Если fullDump == true (по нажатию кнопки «С телефона ребёнка»), выгружает полный срез последних limit записей.
   */
  fun exportUnsyncedOrRecentLogsJsonArray(fullDump: Boolean = true, limit: Int = 120): JSONArray {
    val arr = JSONArray()
    val snapshot = synchronized(this) {
      if (fullDump || lastSyncedChildLogTs <= 0L) {
        _logs.value.take(limit)
      } else {
        val unsynced = _logs.value.filter { it.timestamp > lastSyncedChildLogTs }.take(limit)
        if (unsynced.isNotEmpty()) unsynced else _logs.value.take(25)
      }
    }
    var maxTs = lastSyncedChildLogTs
    for (item in snapshot) {
      if (item.timestamp > maxTs) maxTs = item.timestamp
      arr.put(
        JSONObject().apply {
          put("ts", item.timestamp)
          put("action", item.action)
          put("details", item.details)
          put("status", item.status)
          put("source", item.source)
          put("ver", item.appVersion)
        }
      )
    }
    if (maxTs > lastSyncedChildLogTs) {
      lastSyncedChildLogTs = maxTs
      try {
        appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
          ?.edit()
          ?.putLong(KEY_LAST_SYNCED_CHILD_TS, maxTs)
          ?.apply()
      } catch (_: Exception) {}
    }
    return arr
  }

  fun exportRecentLogsJsonArray(limit: Int = 120): JSONArray {
    return exportUnsyncedOrRecentLogsJsonArray(fullDump = true, limit = limit)
  }

  fun formatAllLogsAsText(): String {
    val snapshot = _logs.value
    if (snapshot.isEmpty()) return "Журнал диагностики KidsGuard пуст."
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    val sb = StringBuilder()
    sb.append("=== ЖУРНАЛ ДИАГНОСТИКИ KIDSGUARD v")
      .append(BuildConfig.VERSION_NAME)
      .append(" (записей: ")
      .append(snapshot.size)
      .append(") ===\n")
    for (entry in snapshot) {
      val t = fmt.format(Date(entry.timestamp))
      val verTag = entry.appVersion.ifBlank { BuildConfig.VERSION_NAME }
      sb.append("[").append(t).append("] ")
        .append("[v").append(verTag).append("] ")
        .append("[").append(entry.status).append("] ")
        .append(entry.action).append(": ")
        .append(entry.details).append("\n")
    }
    return sb.toString()
  }

  /** Полная очистка всех записей на текущем устройстве. */
  fun clearLogs() {
    synchronized(this) {
      _logs.value = emptyList()
      lastSyncedChildLogTs = 0L
      try {
        val ctx = appContext
        if (ctx != null) {
          File(ctx.filesDir, LOG_FILE_NAME).delete()
          ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putLong(KEY_LAST_SYNCED_CHILD_TS, 0L)
            .apply()
        }
      } catch (_: Exception) {}
    }
  }

  /** Очистка только записей Родителя (оставляет записи Ребёнка). */
  fun clearParentOnlyLogs() {
    synchronized(this) {
      val remaining = _logs.value.filter { it.source == "CHILD" || it.action.contains("РЕБЁНОК") }
      _logs.value = remaining
      persistLogsAsync(remaining)
    }
  }

  /** Очистка записей Ребёнка в журнале Родителя. */
  fun clearChildLogsFromParentView() {
    synchronized(this) {
      val remaining = _logs.value.filterNot { it.source == "CHILD" || it.action.contains("РЕБЁНОК") }
      _logs.value = remaining
      persistLogsAsync(remaining)
    }
  }

  private fun persistLogsAsync(entries: List<DebugLogEntry>) {
    val ctx = appContext ?: return
    try {
      val arr = JSONArray()
      for (e in entries.take(MAX_PERSISTED_ENTRIES)) {
        arr.put(
          JSONObject().apply {
            put("id", e.id)
            put("ts", e.timestamp)
            put("action", e.action)
            put("details", e.details)
            put("status", e.status)
            put("source", e.source)
            put("ver", e.appVersion)
          }
        )
      }
      File(ctx.filesDir, LOG_FILE_NAME).writeText(arr.toString())
    } catch (_: Exception) {}
  }

  private fun loadPersistedLogs(ctx: Context) {
    try {
      val file = File(ctx.filesDir, LOG_FILE_NAME)
      if (!file.exists()) return
      val raw = file.readText()
      if (raw.isBlank()) return
      val arr = JSONArray(raw)
      val loaded = mutableListOf<DebugLogEntry>()
      for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        loaded.add(
          DebugLogEntry(
            id = o.optLong("id", System.nanoTime() + i),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            tag = TAG,
            action = o.optString("action", "LOG"),
            details = o.optString("details", ""),
            status = o.optString("status", "INFO"),
            source = o.optString("source", "LOCAL"),
            appVersion = o.optString("ver", BuildConfig.VERSION_NAME).ifBlank { BuildConfig.VERSION_NAME }
          )
        )
      }
      if (loaded.isNotEmpty()) {
        _logs.value = loaded.sortedByDescending { it.timestamp }.take(MAX_LOG_ENTRIES)
      }
    } catch (_: Exception) {}
  }
}
