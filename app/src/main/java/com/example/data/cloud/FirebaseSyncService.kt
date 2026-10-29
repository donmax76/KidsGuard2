package com.example.data.cloud

import android.content.Context
import com.example.BuildConfig
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import com.example.data.model.ContentRuleEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Сервис синхронизации KidsGuard Pro, работающий 100% через прямое P2P-соединение
 * (DirectP2pSocketEngine + RealtimeCloudRelay) и локальную базу данных Room SQLite.
 * Облачные базы данных (Firebase Firestore) полностью отключены и не используются,
 * благодаря чему отсутствуют лимиты квот и задержки посредников.
 */
class FirebaseSyncService(context: Context) {
  private val appContext: Context = context.applicationContext

  // --- CHILD PROFILES (100% P2P) ---

  suspend fun upsertChildProfile(child: ChildProfileEntity) {
    try {
      val isChildDevice = try {
        val cfg = com.example.data.local.KidsGuardDatabase.getDatabase(appContext)
          .kidsGuardDao().getFamilyConfigOnce()
        cfg?.selectedRole == "CHILD" && cfg.thisDeviceChildId == child.id
      } catch (_: Exception) { false }

      RealtimeCloudRelay.publishChildProfile(child, isChildDevice)
    } catch (_: Exception) {}
  }

  suspend fun findChildByPairingCode(code: String): ChildProfileEntity? {
    val clean = code.trim().replace(" ", "")
    val digitsOnly = clean.replace("-", "").uppercase()
    val numericDigits = clean.filter { it.isDigit() }
    val formattedCode = if (numericDigits.length == 6) {
      "${numericDigits.take(3)}-${numericDigits.substring(3)}"
    } else {
      clean
    }

    try {
      for (attempt in 0..14) {
        val relayChildren = RealtimeCloudRelay.pollChildrenFromRelay()
        val matchedRelay = relayChildren.firstOrNull {
          it.pairingCode.replace("-", "").replace(" ", "").uppercase() == digitsOnly ||
            it.id.toString() == digitsOnly
        }
        if (matchedRelay != null) {
          return matchedRelay
        }
        // If there is exactly 1 child profile in the family relay (e.g. "Назрин"), reuse that exact profile
        // and update its pairingCode so reinstalling/re-pairing NEVER creates a second duplicate child!
        if (attempt >= 6 && relayChildren.size == 1 && numericDigits.length == 6) {
          val singleChild = relayChildren.first()
          return singleChild.copy(pairingCode = formattedCode)
        }
        if (attempt == 0 || attempt == 3 || attempt == 7 || attempt == 11) {
          DirectP2pSocketEngine.requestPairingCodeDiscovery(clean)
        }
        delay(250L)
      }
    } catch (_: Exception) {}
    return null
  }

  suspend fun markChildPairedInCloud(childId: Long, pairingCode: String = "", deviceModel: String) {
    val effectiveVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(appContext)
    val fields = mutableMapOf<String, Any>(
      "isPaired" to true,
      "deviceModel" to deviceModel,
      "appVersion" to effectiveVer,
      "updatePending" to false,
      "isChildDevice" to true
    )
    if (pairingCode.isNotBlank()) {
      fields["pairingCode"] = pairingCode
    }
    safeUpdateChildFields(childId, fields)
  }

  suspend fun deleteChildFromCloud(childId: Long) {
    RealtimeCloudRelay.publishDeleteChild(childId)
  }

  fun observeAllChildren(): Flow<List<ChildProfileEntity>> = callbackFlow {
    var lastEmittedSig = 0
    val liveFlowJob = launch {
      RealtimeCloudRelay.liveChildrenFlow.collect { list ->
        val sig = list.hashCode()
        if (list.isNotEmpty() && sig != lastEmittedSig) {
          lastEmittedSig = sig
          trySend(list)
        }
      }
    }

    val pollerJob = launch(Dispatchers.IO) {
      while (isActive) {
        val relayList = RealtimeCloudRelay.pollChildrenFromRelay()
        val sig = relayList.hashCode()
        if (relayList.isNotEmpty() && sig != lastEmittedSig) {
          lastEmittedSig = sig
          trySend(relayList)
        }
        delay(25_000L)
      }
    }

    awaitClose {
      liveFlowJob.cancel()
      pollerJob.cancel()
    }
  }

  fun observeChild(childId: Long): Flow<ChildProfileEntity?> = callbackFlow {
    var lastEmittedChild: ChildProfileEntity? = null
    val liveJob = launch {
      RealtimeCloudRelay.liveChildrenFlow.collect { list ->
        val found = list.find { it.id == childId }
        if (found != null && found != lastEmittedChild) {
          lastEmittedChild = found
          trySend(found)
        }
      }
    }

    awaitClose {
      liveJob.cancel()
    }
  }

  // --- REAL CHILD APPS SYNC (CHILD -> P2P -> PARENT) ---

  suspend fun uploadChildApps(childId: Long, apps: List<AppControlEntity>) {
    RealtimeCloudRelay.publishChildApps(childId, apps)
  }

  fun observeAppsForChild(childId: Long): Flow<List<AppControlEntity>> = callbackFlow {
    var lastEmittedSig = 0
    val pollerJob = launch(Dispatchers.IO) {
      while (isActive) {
        val apps = RealtimeCloudRelay.getCachedApps(childId)
        val sig = apps.hashCode()
        if (apps.isNotEmpty() && sig != lastEmittedSig) {
          lastEmittedSig = sig
          trySend(apps)
        }
        delay(8_000L)
      }
    }
    awaitClose { pollerJob.cancel() }
  }

  suspend fun updateAppBlocked(childId: Long, packageName: String, isBlocked: Boolean) {
    RealtimeCloudRelay.publishAppUpdate(childId, packageName, isBlocked = isBlocked, limitMinutes = null)
    sendRemoteCommand(childId, if (isBlocked) "BLOCK_APP" else "UNBLOCK_APP", packageName)
  }

  suspend fun updateAppLimit(childId: Long, packageName: String, limitMinutes: Int) {
    RealtimeCloudRelay.publishAppUpdate(childId, packageName, isBlocked = null, limitMinutes = limitMinutes)
    sendRemoteCommand(childId, "LIMIT_APP", "$packageName|$limitMinutes")
  }

  // --- REMOTE COMMANDS (PARENT -> CHILD via P2P in REALTIME) ---

  suspend fun sendRemoteCommand(
    childId: Long,
    commandType: String,
    payload: String = ""
  ) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
    val targetId = if (childId > 0L) childId else 1L
    RealtimeCloudRelay.publishRemoteCommand(targetId, commandType, payload)
  }

  fun observeRemoteCommands(childId: Long): Flow<Pair<String, String>?> = callbackFlow {
    val targetId = if (childId > 0L) childId else 1L
    var lastHandledSig = ""
    val emitDedup: (String, String, Long) -> Unit = { cmd, payload, ts ->
      val sig = "${cmd}_${payload}_$ts"
      if (cmd.isNotBlank() && kotlin.math.abs(System.currentTimeMillis() - ts) < 180_000L && sig != lastHandledSig) {
        lastHandledSig = sig
        trySend(Pair(cmd, payload))
      }
    }

    val p2pCmdJob = launch {
      DirectP2pSocketEngine.incomingCommandsFlow.collect { (cmdChildId, cmd, payload) ->
        val resolvedCmdId = RealtimeCloudRelay.resolveCanonicalChildId(cmdChildId)
        val resolvedTargetId = RealtimeCloudRelay.resolveCanonicalChildId(targetId)
        if (cmdChildId == targetId || cmdChildId == 0L || resolvedCmdId == resolvedTargetId || DirectP2pSocketEngine.thisDeviceBoundChildId > 0L) {
          emitDedup(cmd, payload, System.currentTimeMillis())
        }
      }
    }

    awaitClose {
      p2pCmdJob.cancel()
    }
  }

  suspend fun clearRemoteCommand(childId: Long) {
    if (childId <= 0) return
    RealtimeCloudRelay.publishRemoteCommand(childId, "", "")
  }

  private suspend fun safeUpdateChildFields(childId: Long, fields: Map<String, Any>) {
    val targetId = if (childId > 0) childId else 1L
    RealtimeCloudRelay.publishChildFields(targetId, fields)
  }

  suspend fun updateRemoteLock(childId: Long, locked: Boolean) {
    val targetId = if (childId > 0L) childId else 1L
    RealtimeCloudRelay.publishAuthoritativeLock(targetId, locked)
    safeUpdateChildFields(targetId, mapOf("isDeviceLocked" to locked))
    sendRemoteCommand(targetId, if (locked) "LOCK" else "UNLOCK")
  }

  suspend fun updateStudyMode(childId: Long, active: Boolean) {
    safeUpdateChildFields(childId, mapOf("studyModeActive" to active))
    sendRemoteCommand(childId, if (active) "STUDY_ON" else "STUDY_OFF")
  }

  suspend fun updateAllowMobileData(childId: Long, allow: Boolean) {
    safeUpdateChildFields(childId, mapOf("allowMobileData" to allow))
    sendRemoteCommand(childId, if (allow) "NET_ALL" else "NET_WIFI_ONLY")
  }

  suspend fun updateGpsTrackingEnabled(childId: Long, enabled: Boolean) {
    safeUpdateChildFields(childId, mapOf("gpsTrackingEnabled" to enabled))
    sendRemoteCommand(childId, if (enabled) "GPS_TRACKING_ON" else "GPS_TRACKING_OFF")
  }

  suspend fun updateDailyLimit(childId: Long, limitMinutes: Int) {
    safeUpdateChildFields(childId, mapOf("dailyScreenLimitMinutes" to limitMinutes))
    sendRemoteCommand(childId, "SET_LIMIT", limitMinutes.toString())
  }

  suspend fun updateChildBattery(childId: Long, level: Int) {
    if (com.example.location.LiveLocationTracker.isEmulator()) return
    val isChildDevice = try {
      com.example.data.local.KidsGuardDatabase.getDatabase(appContext)
        .kidsGuardDao().getFamilyConfigOnce()?.selectedRole == "CHILD"
    } catch (_: Exception) { false }
    if (!isChildDevice) return

    val clamped = level.coerceIn(1, 100)
    val modelName = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}".trim()
    val effectiveVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(appContext)
    safeUpdateChildFields(
      childId,
      mapOf(
        "batteryLevel" to clamped,
        "isPaired" to true,
        "deviceModel" to modelName,
        "appVersion" to effectiveVer,
        "updatePending" to false,
        "isChildDevice" to true
      )
    )
  }

  suspend fun updateChildAppVersion(childId: Long, version: String) {
    if (com.example.location.LiveLocationTracker.isEmulator()) return
    val cleanVer = RealtimeCloudRelay.normalizeAppVersion(version)
    safeUpdateChildFields(
      childId,
      mapOf(
        "appVersion" to cleanVer,
        "updatePending" to false,
        "isChildDevice" to true
      )
    )
  }

  companion object {
    @Volatile
    private var lastHistoryLat: Double = 0.0
    @Volatile
    private var lastHistoryLng: Double = 0.0
    @Volatile
    private var lastHistoryTimestamp: Long = 0L

    private fun approxDistanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
      val r = 6371000.0
      val dLat = Math.toRadians(lat2 - lat1)
      val dLon = Math.toRadians(lon2 - lon1)
      val a = kotlin.math.sin(dLat / 2) * kotlin.math.sin(dLat / 2) +
          kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
          kotlin.math.sin(dLon / 2) * kotlin.math.sin(dLon / 2)
      val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
      return r * c
    }
  }

  suspend fun updateLocation(childId: Long, lat: Double, lng: Double, address: String) {
    if (com.example.location.LiveLocationTracker.isEmulator() ||
        com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(lat, lng)) {
      return
    }
    val isChildDevice = try {
      com.example.data.local.KidsGuardDatabase.getDatabase(appContext)
        .kidsGuardDao().getFamilyConfigOnce()?.selectedRole == "CHILD"
    } catch (_: Exception) { false }
    if (!isChildDevice) return

    val cleanAddr = if (com.example.location.LiveLocationTracker.isStaleOrDemoAddress(address)) {
      com.example.location.LiveLocationTracker.reverseGeocodeExact(appContext, lat, lng)
    } else {
      address
    }
    val now = System.currentTimeMillis()
    val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(appContext)
    val effectiveVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(appContext)
    val locFields = mapOf(
      "lastLocationLat" to lat,
      "lastLocationLng" to lng,
      "lastLocationAddress" to cleanAddr,
      "lastLocationTime" to now,
      "batteryLevel" to batt,
      "appVersion" to effectiveVer,
      "updatePending" to false,
      "isChildDevice" to true,
      "isRealGpsFix" to true
    )
    safeUpdateChildFields(childId, locFields)

    // Record 24-hour GPS route history point when moved >= 10m or >= 60 seconds elapsed
    try {
      val movedMeters = if (lastHistoryLat == 0.0 && lastHistoryLng == 0.0) 9999.0
      else approxDistanceMeters(lastHistoryLat, lastHistoryLng, lat, lng)
      val elapsedMs = now - lastHistoryTimestamp
      if (movedMeters >= 10.0 || elapsedMs >= 60_000L) {
        val dtHours = (elapsedMs / 3600000.0).coerceAtLeast(0.001)
        val calcSpeedKmh = if (lastHistoryTimestamp > 0L && movedMeters < 50_000.0 && movedMeters >= 10.0) {
          ((movedMeters / 1000.0) / dtHours).toFloat().coerceIn(0f, 130f)
        } else 0f
        lastHistoryLat = lat
        lastHistoryLng = lng
        lastHistoryTimestamp = now
        val targetId = if (childId > 0) childId else 1L
        RealtimeCloudRelay.publishLocationBreadcrumb(
          childId = targetId,
          lat = lat,
          lng = lng,
          address = cleanAddr,
          speedKmh = calcSpeedKmh,
          timestamp = now
        )
      }
    } catch (_: Exception) {}
  }

  fun observeLocationHistory24h(childId: Long): Flow<List<com.example.data.model.LocationBreadcrumb>> = callbackFlow {
    var lastEmittedSig = 0
    val pollerJob = launch(Dispatchers.IO) {
      while (isActive) {
        val list = RealtimeCloudRelay.getCachedHistory24h(childId)
        val sig = list.hashCode()
        if (sig != lastEmittedSig) {
          lastEmittedSig = sig
          trySend(list)
        }
        delay(15_000L)
      }
    }
    awaitClose { pollerJob.cancel() }
  }

  // --- CHAT & ALERTS (P2P) ---

  suspend fun sendChatMessage(message: ChatMessageEntity) {
    RealtimeCloudRelay.publishChatMessage(message)
    if (message.isAttentionSignal) {
      sendRemoteCommand(message.childId, "LOUD_RING", message.text)
    }
  }

  fun observeChatMessages(childId: Long): Flow<List<ChatMessageEntity>> = callbackFlow {
    var lastEmittedSig = 0
    val pollerJob = launch(Dispatchers.IO) {
      while (isActive) {
        val list = RealtimeCloudRelay.getCachedChats(childId)
        val sig = list.hashCode()
        if (list.isNotEmpty() && sig != lastEmittedSig) {
          lastEmittedSig = sig
          trySend(list)
        }
        delay(15_000L)
      }
    }
    awaitClose { pollerJob.cancel() }
  }

  suspend fun sendAlert(alert: AlertNotificationEntity) {
    RealtimeCloudRelay.publishAlert(alert)
  }

  fun observeAlerts(): Flow<List<AlertNotificationEntity>> = callbackFlow {
    var lastEmittedSig = 0
    val pollerJob = launch(Dispatchers.IO) {
      while (isActive) {
        val list = RealtimeCloudRelay.getCachedAlerts()
        val sig = list.hashCode()
        if (list.isNotEmpty() && sig != lastEmittedSig) {
          lastEmittedSig = sig
          trySend(list)
        }
        delay(20_000L)
      }
    }
    awaitClose { pollerJob.cancel() }
  }

  // --- LIVE SCREEN MONITORING & SCREENSHOT SYNC OVER P2P (STRICT PER-CHILD ISOLATION) ---
  suspend fun updateActiveAppOnly(childId: Long, activeAppName: String) {
    if (activeAppName.isBlank() || com.example.location.LiveLocationTracker.isEmulator()) return
    val isChildDevice = try {
      com.example.data.local.KidsGuardDatabase.getDatabase(appContext)
        .kidsGuardDao().getFamilyConfigOnce()?.selectedRole == "CHILD"
    } catch (_: Exception) { false }
    if (!isChildDevice) return
    val targetId = if (childId > 0L) childId else 1L
    RealtimeCloudRelay.publishActiveAppOnly(targetId, activeAppName)
  }

  suspend fun uploadScreenSnapshot(childId: Long, activeAppName: String, base64Bitmap: String) {
    if (com.example.location.LiveLocationTracker.isEmulator() || base64Bitmap.isBlank()) return
    val isChildDevice = try {
      com.example.data.local.KidsGuardDatabase.getDatabase(appContext)
        .kidsGuardDao().getFamilyConfigOnce()?.selectedRole == "CHILD"
    } catch (_: Exception) { false }
    if (!isChildDevice) return
    val targetId = if (childId > 0L) childId else 1L

    // Instant delivery via P2P LAN Socket + Global P2P Binary TCP Socket
    RealtimeCloudRelay.publishScreenSnapshot(targetId, activeAppName, base64Bitmap)
  }

  fun observeScreenSnapshot(childId: Long): Flow<Pair<String, String>?> = callbackFlow {
    val targetId = if (childId > 0L) childId else 1L
    var lastEmittedFrameTs = 0L
    var lastEmittedAppName = ""

    val directJob = launch {
      DirectP2pSocketEngine.framesByChild.collect { _ ->
        val frame = DirectP2pSocketEngine.getLatestDirectFrameForChild(targetId)
        if (frame != null && (frame.first.isNotBlank() || frame.second.isNotBlank())) {
          if (frame.third != lastEmittedFrameTs || frame.first != lastEmittedAppName) {
            lastEmittedFrameTs = frame.third
            lastEmittedAppName = frame.first
            trySend(Pair(frame.first, frame.second))
          }
        }
      }
    }
    awaitClose {
      directJob.cancel()
    }
  }

  suspend fun pollLatestScreenSnapshot(childId: Long): Triple<String, String, Long>? {
    val targetId = if (childId > 0L) childId else 1L
    return RealtimeCloudRelay.pollLatestScreenSnapshot(targetId)
  }

  // --- REMOTE OTA APP UPDATE CHECK & NOTIFICATION (P2P) ---
  suspend fun triggerRemoteAppUpdate(childId: Long, latestVersion: String, downloadUrl: String) {
    safeUpdateChildFields(childId, mapOf("updatePending" to true))
    sendRemoteCommand(childId, "APP_UPDATE", "$latestVersion|$downloadUrl")
  }

  // --- WEB FILTER DOMAIN BLACKLIST & CATEGORY RULES P2P SYNC ---
  suspend fun syncContentRulesToCloud(rules: List<ContentRuleEntity>) = kotlinx.coroutines.withContext(kotlinx.coroutines.NonCancellable) {
    try {
      RealtimeCloudRelay.publishContentRules(rules)
    } catch (_: Exception) {}
  }

  fun observeContentRulesFromCloud(): Flow<List<ContentRuleEntity>> = callbackFlow {
    val rulesJob = launch {
      RealtimeCloudRelay.contentRulesFlow.collect { rules ->
        if (rules.isNotEmpty()) {
          trySend(rules)
        }
      }
    }
    awaitClose {
      rulesJob.cancel()
    }
  }
}
