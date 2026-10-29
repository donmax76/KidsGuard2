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
    scope.launch {
      try {
        val now = System.currentTimeMillis()
        val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
        dao.insertOfflineActivityLog(
          OfflineActivityLogEntity(
            childId = childId,
            deviceType = deviceType,
            packageName = packageName,
            appName = appName,
            windowTitle = windowTitle,
            durationSeconds = durationSeconds,
            hourOfDay = currentHourOfDay(now),
            dateIso = currentDateIso(now),
            timestamp = now,
            isSyncedToParent = false
          )
        )
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
    if (childId <= 0L || base64Bitmap.isBlank()) return
    scope.launch {
      try {
        val now = System.currentTimeMillis()
        val dao = KidsGuardDatabase.getDatabase(context).kidsGuardDao()
        dao.insertOfflineScreenshot(
          OfflineScreenshotEntity(
            childId = childId,
            deviceType = deviceType,
            activeAppName = activeAppName,
            statusNote = statusNote,
            base64Bitmap = base64Bitmap,
            timestamp = now,
            isSyncedToParent = false
          )
        )
        dao.trimOfflineScreenshots(childId)
        refreshPendingCounts(context, childId)
      } catch (_: Exception) {}
    }
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
        val unsyncedShots = dao.getUnsyncedScreenshots(childId).take(3) // до 3 скриншотов за один пакет

        if (unsyncedLogs.isEmpty() && unsyncedLocs.isEmpty() && unsyncedShots.isEmpty()) {
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

        val shotsArr = JSONArray()
        for (s in unsyncedShots) {
          shotsArr.put(JSONObject().apply {
            put("deviceType", s.deviceType)
            put("activeAppName", s.activeAppName)
            put("statusNote", s.statusNote)
            put("base64Bitmap", s.base64Bitmap)
            put("timestamp", s.timestamp)
          })
        }

        val packet = JSONObject().apply {
          put("type", "OFFLINE_HISTORY_BUNDLE")
          put("childId", childId)
          put("activityLogs", logsArr)
          put("locationPoints", locsArr)
          put("screenshots", shotsArr)
          put("ts", System.currentTimeMillis())
        }

        val sent = DirectP2pSocketEngine.publishTcpSocketPacket(
          "${DirectP2pSocketEngine.TOPIC_DATA}/$childId",
          packet,
          retain = true
        )
        if (sent) {
          if (unsyncedLogs.isNotEmpty()) {
            dao.markActivityLogsSynced(unsyncedLogs.map { it.id })
          }
          if (unsyncedLocs.isNotEmpty()) {
            dao.markLocationPointsSynced(unsyncedLocs.map { it.id })
          }
          if (unsyncedShots.isNotEmpty()) {
            dao.markScreenshotsSynced(unsyncedShots.map { it.id })
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
          for (i in 0 until logsArr.length()) {
            val obj = logsArr.optJSONObject(i) ?: continue
            val ts = obj.optLong("timestamp", System.currentTimeMillis())
            toInsert.add(
              OfflineActivityLogEntity(
                childId = targetChildId,
                deviceType = obj.optString("deviceType", "PHONE"),
                packageName = obj.optString("packageName", "app"),
                appName = obj.optString("appName", "Приложение"),
                windowTitle = obj.optString("windowTitle", ""),
                durationSeconds = obj.optInt("durationSeconds", 60),
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

        var addedShots = 0
        val shotsArr = msg.optJSONArray("screenshots")
        if (shotsArr != null && shotsArr.length() > 0) {
          for (i in 0 until shotsArr.length()) {
            val obj = shotsArr.optJSONObject(i) ?: continue
            val b64 = obj.optString("base64Bitmap", "")
            if (b64.isBlank()) continue
            val appName = obj.optString("activeAppName", "Экран ребёнка")
            val note = obj.optString("statusNote", "Снимок из оффлайн-архива")
            val ts = obj.optLong("timestamp", System.currentTimeMillis())
            val devType = obj.optString("deviceType", "PHONE")
            dao.insertOfflineScreenshot(
              OfflineScreenshotEntity(
                childId = targetChildId,
                deviceType = devType,
                activeAppName = appName,
                statusNote = note,
                base64Bitmap = b64,
                timestamp = ts,
                isSyncedToParent = true
              )
            )
            ScreenShotManager.addCapturedItem(
              CapturedScreenItem(
                id = ts,
                childId = targetChildId,
                activeAppName = appName,
                timestamp = ts,
                timeFormatted = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ts)),
                statusNote = note,
                base64Bitmap = b64
              )
            )
            addedShots++
          }
          dao.trimOfflineScreenshots(targetChildId)
        }

        val prev = _syncStats.value
        _syncStats.value = prev.copy(
          totalSyncedLogsOnParent = prev.totalSyncedLogsOnParent + addedLogs,
          totalSyncedLocationsOnParent = prev.totalSyncedLocationsOnParent + addedLocs,
          totalSyncedScreenshotsOnParent = prev.totalSyncedScreenshotsOnParent + addedShots,
          lastSyncTimestampMs = System.currentTimeMillis(),
          statusMessage = "Получен оффлайн-архив: +$addedLogs событ., +$addedLocs GPS, +$addedShots снимков"
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
