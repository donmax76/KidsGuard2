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
  private const val MAX_LOG_ENTRIES = 140
  private const val MAX_PERSISTED_ENTRIES = 120
  private const val RESERVED_CHILD_LOG_QUOTA = 60
  private const val DISK_FLUSH_INTERVAL_MS = 30_000L

  // Шумные и частые фоновые события, которые НЕ должны засорять журнал и расходовать память/батарею
  private val IGNORED_NOISY_ACTIONS = setOf(
    "СКРИНШОТ_ЭКРАНА",
    "КАДР_ЭКРАНА_ОТПРАВЛЕН",
    "КАДР_ЭКРАНА_ПРИНЯТ",
    "ДУБЛЬ_БРОКЕРА_ОТБРОШЕН",
    "ДУБЛЬ_КОМАНДЫ_ОТБРОШЕНА",
    "СТАРАЯ_КОМАНДА_ОТБРОШЕНА",
    "ЭКРАННОЕ_ВРЕМЯ",
    "РАСХОД_ЭНЕРГИИ",
    "БАТАРЕЯ_ОБНОВЛЕНА",
    "СКРИНШОТ_СИСТЕМНЫЙ_КОД",
    "VPS_ПОВТОР_ПОДКЛЮЧЕНИЯ",
    "ЧУЖОЙ_ПРОФИЛЬ_ОТБРОШЕН"
  )

  // События с ограничением частоты (не чаще 1 раза в 60 секунд)
  private val RATE_LIMITED_ACTIONS = setOf(
    "P2P_БРОКЕР_ПОДКЛЮЧЕН",
    "СТАТУС_ОНЛАЙН",
    "СТАТУС_ОФФЛАЙН",
    "СЕТЬ_ВОССТАНОВЛЕНА",
    "СЕТЬ_ПОТЕРЯНА",
    "СРЕЗ_ПАРАМЕТРОВ",
    "VPS_СОЕДИНЕНИЕ_ЗАКРЫТО",
    "НЕСОВПАДЕНИЕ_КОДА"
  )

  private val lastActionLogTimeMs = java.util.concurrent.ConcurrentHashMap<String, Long>()

  @Volatile
  private var lastDiskFlushTimeMs: Long = 0L

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
        details = "Диагностическое логирование включено (лимит $MAX_LOG_ENTRIES записей, v${BuildConfig.VERSION_NAME})",
        status = "SUCCESS",
        source = "SYSTEM",
        forceFlushDisk = true
      )
    } else if (!enabled && prev) {
      forceLogInternal(
        action = "ЛОГГЕР_ВЫКЛЮЧЕН",
        details = "Диагностическое логирование приостановлено пользователем (v${BuildConfig.VERSION_NAME})",
        status = "WARNING",
        source = "SYSTEM",
        forceFlushDisk = true
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
    val cleanAction = action.removePrefix("📱").substringAfter("• ").trim()
    if (action in IGNORED_NOISY_ACTIONS || cleanAction in IGNORED_NOISY_ACTIONS) return
    val now = System.currentTimeMillis()
    if (action in RATE_LIMITED_ACTIONS || cleanAction in RATE_LIMITED_ACTIONS) {
      val prevTs = lastActionLogTimeMs[cleanAction] ?: 0L
      if (now - prevTs < 60_000L) return
      lastActionLogTimeMs[cleanAction] = now
    }
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
    val now = System.currentTimeMillis()
    val prevSnapTs = lastActionLogTimeMs["СРЕЗ_ПАРАМЕТРОВ_$role"] ?: 0L
    if (now - prevSnapTs < 45_000L && !triggerReason.contains("Запрос", ignoreCase = true)) return
    lastActionLogTimeMs["СРЕЗ_ПАРАМЕТРОВ_$role"] = now
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
    appVersion: String = BuildConfig.VERSION_NAME,
    forceFlushDisk: Boolean = false
  ) {
    val now = System.currentTimeMillis()
    val entry = DebugLogEntry(
      timestamp = now,
      tag = TAG,
      action = action,
      details = details.take(240),
      status = status,
      source = source,
      appVersion = appVersion.ifBlank { BuildConfig.VERSION_NAME }
    )

    synchronized(this) {
      val current = _logs.value.toMutableList()
      // Дедупликация одинаковых событий за последние 15 секунд, чтобы не копить повторы
      val duplicateIdx = current.indexOfFirst {
        it.action == action && (it.details == entry.details || kotlin.math.abs(now - it.timestamp) < 3_000L)
      }
      if (duplicateIdx >= 0 && kotlin.math.abs(now - current[duplicateIdx].timestamp) < 15_000L) {
        return
      }
      current.add(0, entry)
      val trimmed = trimPreservingChildQuota(current, MAX_LOG_ENTRIES)
      _logs.value = trimmed
      if (forceFlushDisk || (now - lastDiskFlushTimeMs >= DISK_FLUSH_INTERVAL_MS)) {
        lastDiskFlushTimeMs = now
        persistLogsAsync(trimmed)
      }
    }
  }

  private fun trimPreservingChildQuota(entries: List<DebugLogEntry>, maxTotal: Int): List<DebugLogEntry> {
    if (entries.size <= maxTotal) return entries.sortedByDescending { it.timestamp }
    val sorted = entries.sortedByDescending { it.timestamp }
    val childLogs = sorted.filter { it.source == "CHILD" || it.action.contains("РЕБЁНОК") }.take(RESERVED_CHILD_LOG_QUOTA)
    val otherLimit = (maxTotal - childLogs.size).coerceAtLeast(maxTotal / 2)
    val otherLogs = sorted.filterNot { it.source == "CHILD" || it.action.contains("РЕБЁНОК") }.take(otherLimit)
    return (childLogs + otherLogs).sortedByDescending { it.timestamp }.take(maxTotal)
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
        val cleanAct = rawAct.removePrefix("📱").substringAfter("• ").trim().ifBlank { rawAct }
        if (cleanAct in IGNORED_NOISY_ACTIONS) continue
        val det = o.optString("details", "").take(240)
        val st = o.optString("status", "INFO")
        val ver = o.optString("ver", defaultChildVersion).ifBlank { defaultChildVersion }
        val prefixedAction = "📱 РЕБЁНОК #$childId • $cleanAct"
        val exists = current.any {
          it.action == prefixedAction && (it.details == det || kotlin.math.abs(it.timestamp - ts) < 2_500L)
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
        val trimmed = trimPreservingChildQuota(current, MAX_LOG_ENTRIES)
        _logs.value = trimmed
        lastDiskFlushTimeMs = System.currentTimeMillis()
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
        val rawAct = o.optString("action", "LOG")
        val cleanAct = rawAct.removePrefix("📱").substringAfter("• ").trim()
        if (rawAct in IGNORED_NOISY_ACTIONS || cleanAct in IGNORED_NOISY_ACTIONS) continue
        loaded.add(
          DebugLogEntry(
            id = o.optLong("id", System.nanoTime() + i),
            timestamp = o.optLong("ts", System.currentTimeMillis()),
            tag = TAG,
            action = rawAct,
            details = o.optString("details", "").take(240),
            status = o.optString("status", "INFO"),
            source = o.optString("source", "LOCAL"),
            appVersion = o.optString("ver", BuildConfig.VERSION_NAME).ifBlank { BuildConfig.VERSION_NAME }
          )
        )
      }
      val trimmed = trimPreservingChildQuota(loaded, MAX_LOG_ENTRIES)
      _logs.value = trimmed
      if (loaded.size > MAX_PERSISTED_ENTRIES) {
        persistLogsAsync(trimmed)
      }
    } catch (_: Exception) {}
  }
}
