package com.example.data.cloud

import android.content.Context
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.LocationBreadcrumb
import com.example.data.model.OfflineActivityLogEntity
import com.example.data.model.OfflineLocationPointEntity
import com.example.data.model.OfflineScreenshotEntity
import com.example.util.CapturedScreenItem
import com.example.util.ScreenShotManager
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

data class OfflineSyncStats(
  val pendingChildLogsCount: Int = 0,
  val pendingChildLocationsCount: Int = 0,
  val pendingChildScreenshotsCount: Int = 0,
  val totalSyncedLogsOnParent: Int = 0,
  val totalSyncedLocationsOnParent: Int = 0,
  val totalSyncedScreenshotsOnParent: Int = 0,
  val lastSyncTimestampMs: Long = 0L,
  val statusMessage: String = "Оффлайн-кэш Room + SQLite активен"
)

/**
 * Менеджер локального оффлайн-хранения (Room SQLite) и отложенной P2P-переброски (Store-and-Forward).
 *
 * Когда родитель не в эфире (нет интернета или приложение родителя закрыто):
 * 1) Телефон ребёнка сохраняет всю историю гео-данных (FusedLocationProviderClient),
 *    активности приложений по часам и снимков экрана (MediaProjection / Accessibility) в локальную БД Room.
 * 2) Компьютер ребёнка (hosts_blocker.py) сохраняет историю окон, программ и скриншотов в локальную БД SQLite.
 * 3) При появлении связи ребёнок автоматически перебрасывает пакет `OFFLINE_HISTORY_BUNDLE` родителю по P2P.
 */
object OfflineStoreAndForwardManager {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val _syncStats = MutableStateFlow(OfflineSyncStats())
  val syncStats: StateFlow<OfflineSyncStats> = _syncStats.asStateFlow()

  private fun currentHourOfDay(ts: Long = System.currentTimeMillis()): Int {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ts
    return cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
  }

  private fun currentDateIso(ts: Long = System.currentTimeMillis()): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ts))
  }

  fun recordChildActivityOffline(
    context: Context,
    childId: Long,
    packageName: String,
    appName: String,
    windowTitle: String = "",
    durationSeconds: Int = 60,
    deviceType: String = "PHONE"
  ) {
    if (childId <= 0L) return
    val isWeb = packageName.startsWith("web:", ignoreCase = true)
    if (!isWeb) {
      if (durationSeconds <= 5) return
      if (com.example.util.DeviceUsageTracker.isSystemShellOrNoiseEntry(packageName, appName) ||
          com.example.util.DeviceUsageTracker.isSystemShellOrNoiseEntry(packageName, windowTitle)) {
        return
      }
    }
    scope.launch {
      try {
        val now = System.currentTimeMillis()
        val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
        if (dao.existsActivityLogNearTimestamp(childId, packageName, now - 2000L, now + 2000L) > 0) {
          return@launch
        }
        dao.insertOfflineActivityLog(
          OfflineActivityLogEntity(
            childId = childId,
            deviceType = deviceType,
            packageName = packageName,
            appName = appName,
            windowTitle = if (isWeb) windowTitle else "",
            durationSeconds = durationSeconds,
            hourOfDay = currentHourOfDay(now),
            dateIso = currentDateIso(now),
            timestamp = now,
            isSyncedToParent = false
          )
        )
        dao.trimOfflineActivityLogs(childId)
        refreshPendingCounts(context, childId)
        if (DirectP2pSocketEngine.isParentActivelyConnected()) {
          flushUnsyncedHistoryToParent(context, childId)
        }
      } catch (_: Exception) {}
    }
  }

  fun recordChildLocationOffline(
    context: Context,
    childId: Long,
    lat: Double,
    lng: Double,
    address: String,
    speedKmh: Float,
    accuracyMeters: Float
  ) {
    if (childId <= 0L || (lat == 0.0 && lng == 0.0)) return
    scope.launch {
      try {
        val now = System.currentTimeMillis()
        val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
        dao.insertOfflineLocationPoint(
          OfflineLocationPointEntity(
            childId = childId,
            latitude = lat,
            longitude = lng,
            address = address,
            speedKmh = speedKmh,
            accuracyMeters = accuracyMeters,
            timestamp = now,
            isSyncedToParent = false
          )
        )
        dao.trimOfflineLocationPoints(childId)
        refreshPendingCounts(context, childId)
        if (DirectP2pSocketEngine.isParentActivelyConnected()) {
          flushUnsyncedHistoryToParent(context, childId)
        }
      } catch (_: Exception) {}
    }
  }

  fun recordChildScreenshotOffline(
    context: Context,
    childId: Long,
    activeAppName: String,
    statusNote: String,
    base64Bitmap: String,
    deviceType: String = "PHONE"
  ) {
    // Оффлайн-скриншоты экрана НЕ копим: они жгли батарею и память, а старые кадры
    // (часами) всё равно неактуальны. При онлайне кадр идёт напрямую через
    // pushDirectScreenFrame / uploadScreenSnapshot.
    if (childId <= 0L || base64Bitmap.isBlank()) return
  }

  private suspend fun refreshPendingCounts(context: Context, childId: Long) {
    try {
      val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
      val logs = dao.getUnsyncedActivityLogs(childId)
      val locs = dao.getUnsyncedLocationPoints(childId)
      val shots = dao.getUnsyncedScreenshots(childId)
      _syncStats.value = _syncStats.value.copy(
        pendingChildLogsCount = logs.size,
        pendingChildLocationsCount = locs.size,
        pendingChildScreenshotsCount = shots.size
      )
    } catch (_: Exception) {}
  }

  /**
   * Вызывается на устройстве ребёнка: перебрасывает все несинхронизированные записи из локальной БД Room
   * родителю по прямому P2P TCP-сокету и помечает их как синхронизированные.
   */
  fun flushUnsyncedHistoryToParent(context: Context, childId: Long) {
    if (childId <= 0L) return
    val status = DirectP2pSocketEngine.status.value
    if (!status.isLanSocketConnected && !status.isGlobalTcpSocketConnected) {
      return
    }
    scope.launch {
      try {
        val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD") return@launch

        val unsyncedLogs = dao.getUnsyncedActivityLogs(childId)
        val unsyncedLocs = dao.getUnsyncedLocationPoints(childId)
        // Удаляем старые оффлайн-скриншоты, чтобы вчерашние снимки (например, за 22:36)
        // никогда не прилетали родителю под видом текущего живого экрана!
        try { dao.deleteOfflineScreenshotsForChild(childId) } catch (_: Exception) {}

        if (unsyncedLogs.isEmpty() && unsyncedLocs.isEmpty()) {
          return@launch
        }

        val logsArr = JSONArray()
        for (l in unsyncedLogs) {
          logsArr.put(JSONObject().apply {
            put("deviceType", l.deviceType)
            put("packageName", l.packageName)
            put("appName", l.appName)
            put("windowTitle", l.windowTitle)
            put("durationSeconds", l.durationSeconds)
            put("hourOfDay", l.hourOfDay)
            put("dateIso", l.dateIso)
            put("timestamp", l.timestamp)
          })
        }

        val locsArr = JSONArray()
        for (p in unsyncedLocs) {
          locsArr.put(JSONObject().apply {
            put("lat", p.latitude)
            put("lng", p.longitude)
            put("address", p.address)
            put("speed", p.speedKmh.toDouble())
            put("acc", p.accuracyMeters.toDouble())
            put("ts", p.timestamp)
          })
        }

        val packet = JSONObject().apply {
          put("type", "OFFLINE_HISTORY_BUNDLE")
          put("childId", childId)
          put("activityLogs", logsArr)
          put("locationPoints", locsArr)
          put("screenshots", JSONArray())
          put("ts", System.currentTimeMillis())
        }

        val sent = DirectP2pSocketEngine.publishTcpSocketPacket(
          "${DirectP2pSocketEngine.TOPIC_DATA}/$childId",
          packet,
          retain = false
        )
        if (sent) {
          if (unsyncedLogs.isNotEmpty()) {
            dao.markActivityLogsSynced(unsyncedLogs.map { it.id })
          }
          if (unsyncedLocs.isNotEmpty()) {
            dao.markLocationPointsSynced(unsyncedLocs.map { it.id })
          }
          refreshPendingCounts(context, childId)
          _syncStats.value = _syncStats.value.copy(
            lastSyncTimestampMs = System.currentTimeMillis(),
            statusMessage = "Оффлайн-история успешно передана родителю по P2P"
          )
        }
      } catch (_: Exception) {}
    }
  }

  /**
   * Вызывается на устройстве родителя при получении пакета `OFFLINE_HISTORY_BUNDLE` от телефона ребёнка
   * или оффлайн-истории от ПК (`hosts_blocker.py`).
   */
  fun ingestOfflineBundleOnParent(context: Context, msg: JSONObject) {
    scope.launch {
      try {
        val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole == "CHILD") return@launch

        val activeChildren = dao.getAllChildrenOnce()
        if (activeChildren.isEmpty()) return@launch

        val rawCid = msg.optLong("childId", 0L)
        val targetChildId = if (activeChildren.any { it.id == rawCid }) {
          rawCid
        } else {
          cfg?.currentActiveChildId?.takeIf { it > 0L } ?: activeChildren.first().id
        }

        var addedLogs = 0
        val logsArr = msg.optJSONArray("activityLogs")
        if (logsArr != null && logsArr.length() > 0) {
          val toInsert = mutableListOf<OfflineActivityLogEntity>()
          val seenBatchKeys = mutableSetOf<String>()
          for (i in 0 until logsArr.length()) {
            val obj = logsArr.optJSONObject(i) ?: continue
            val ts = obj.optLong("timestamp", System.currentTimeMillis())
            val pkg = obj.optString("packageName", "app").trim()
            val appName = obj.optString("appName", "Приложение").trim()
            val winTitle = obj.optString("windowTitle", "").trim()
            val durSec = obj.optInt("durationSeconds", 60)
            val devType = obj.optString("deviceType", "PHONE")
            val isWeb = pkg.startsWith("web:", ignoreCase = true)

            if (!isWeb && devType == "PHONE") {
              if (durSec <= 5) continue
              if (com.example.util.DeviceUsageTracker.isSystemShellOrNoiseEntry(pkg, appName) ||
                  com.example.util.DeviceUsageTracker.isSystemShellOrNoiseEntry(pkg, winTitle)) {
                continue
              }
            }

            val batchKey = "${targetChildId}_${pkg.lowercase()}_${ts / 2000L}"
            if (!seenBatchKeys.add(batchKey)) continue
            if (dao.existsActivityLogNearTimestamp(targetChildId, pkg, ts - 2000L, ts + 2000L) > 0) {
              continue
            }

            val cleanWinTitle = when {
              isWeb -> winTitle
              winTitle.contains("значок приложения", ignoreCase = true) || winTitle.contains("app icon", ignoreCase = true) -> ""
              devType == "PHONE" -> ""
              else -> winTitle
            }

            toInsert.add(
              OfflineActivityLogEntity(
                childId = targetChildId,
                deviceType = devType,
                packageName = pkg,
                appName = appName,
                windowTitle = cleanWinTitle,
                durationSeconds = durSec,
                hourOfDay = obj.optInt("hourOfDay", currentHourOfDay(ts)).coerceIn(0, 23),
                dateIso = obj.optString("dateIso", currentDateIso(ts)),
                timestamp = ts,
                isSyncedToParent = true
              )
            )
          }
          if (toInsert.isNotEmpty()) {
            dao.insertOfflineActivityLogs(toInsert)
            addedLogs = toInsert.size
          }
        }

        var addedLocs = 0
        val locsArr = msg.optJSONArray("locationPoints")
        if (locsArr != null && locsArr.length() > 0) {
          val toInsertLocs = mutableListOf<OfflineLocationPointEntity>()
          val crumbs = mutableListOf<LocationBreadcrumb>()
          for (i in 0 until locsArr.length()) {
            val obj = locsArr.optJSONObject(i) ?: continue
            val lat = obj.optDouble("lat", 0.0)
            val lng = obj.optDouble("lng", 0.0)
            if (lat == 0.0 && lng == 0.0) continue
            val addr = obj.optString("address", "Точка маршрута GPS")
            val speed = obj.optDouble("speed", 0.0).toFloat()
            val acc = obj.optDouble("acc", 10.0).toFloat()
            val ts = obj.optLong("ts", System.currentTimeMillis())
            toInsertLocs.add(
              OfflineLocationPointEntity(
                childId = targetChildId,
                latitude = lat,
                longitude = lng,
                address = addr,
                speedKmh = speed,
                accuracyMeters = acc,
                timestamp = ts,
                isSyncedToParent = true
              )
            )
            crumbs.add(
              LocationBreadcrumb(
                latitude = lat,
                longitude = lng,
                timestamp = ts,
                title = addr.substringBefore(","),
                speedKmh = speed
              )
            )
          }
          if (toInsertLocs.isNotEmpty()) {
            dao.insertOfflineLocationPoints(toInsertLocs)
            addedLocs = toInsertLocs.size
            val newest = toInsertLocs.maxByOrNull { it.timestamp }
            if (newest != null) {
              dao.updateChildLocation(
                targetChildId,
                newest.latitude,
                newest.longitude,
                newest.address,
                newest.timestamp
              )
            }
            RealtimeCloudRelay.mergeOfflineBreadcrumbs(targetChildId, crumbs)
          }
        }

        // Пункт 6: Никогда не подгружаем старые оффлайн-скриншоты из OFFLINE_HISTORY_BUNDLE в ScreenShotManager,
        // чтобы родитель всегда видел только реальный живой экран онлайн, а не вчерашний снимок (22:36)!
        try {
          dao.deleteOfflineScreenshotsForChild(targetChildId)
          dao.purgeSystemGarbageFromActivityLogs()
          dao.cleanAppIconNoiseInActivityLogs()
          dao.deduplicateOfflineActivityLogs()
          dao.trimOfflineActivityLogs(targetChildId)
          dao.trimOfflineLocationPoints(targetChildId)
        } catch (_: Exception) {}

        val prev = _syncStats.value
        _syncStats.value = prev.copy(
          totalSyncedLogsOnParent = prev.totalSyncedLogsOnParent + addedLogs,
          totalSyncedLocationsOnParent = prev.totalSyncedLocationsOnParent + addedLocs,
          lastSyncTimestampMs = System.currentTimeMillis(),
          statusMessage = "Получен оффлайн-архив: +$addedLogs событ., +$addedLocs GPS"
        )
      } catch (_: Exception) {}
    }
  }

  /**
   * Запрашивает у телефона ребёнка и у ПК немедленную выгрузку накопленной оффлайн-истории.
   */
  fun requestImmediateOfflineSyncFromChild(childId: Long) {
    if (childId > 0L) {
      val cmdJson = JSONObject().apply {
        put("type", "CMD")
        put("childId", childId)
        put("command", "REQUEST_OFFLINE_SYNC")
        put("payload", "ALL")
        put("timestamp", System.currentTimeMillis())
      }
      DirectP2pSocketEngine.publishTcpSocketPacket(
        "${DirectP2pSocketEngine.TOPIC_CMD}/$childId",
        cmdJson,
        retain = false
      )
    }
    com.example.data.pc.PcBlockerClient.requestSync()
    com.example.data.pc.PcBlockerClient.requestPcScreenshot()
    _syncStats.value = _syncStats.value.copy(
      statusMessage = "Отправлен запрос синхронизации оффлайн-истории (Телефон + ПК)..."
    )
  }
}
