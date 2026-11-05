package com.example.service

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.example.MainActivity
import com.example.data.cloud.CloudSyncManager
import com.example.data.local.KidsGuardDatabase
import com.example.util.ApkUpdateInstaller
import com.example.util.DeviceControlDebugger
import com.example.util.DeviceSecurityPermissionHelper
import com.example.util.DeviceTotalLockManager
import com.example.util.DeviceUsageTracker
import com.example.util.ScreenShotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "KidsGuardA11yService"

class KidsGuardAccessibilityService : AccessibilityService() {

  companion object {
    @Volatile
    var instance: KidsGuardAccessibilityService? = null
      private set

    @Volatile
    var isLiveStreamingToParent: Boolean = false

    @Volatile
    var liveStreamRequestedUntilMs: Long = 0L

    // Защита от «залипшего» просмотра: одна сессия не более 10 минут, плюс
    // аварийное отключение при низком заряде/нагреве (проверка раз в 30 с).
    private const val LIVE_SESSION_MAX_MS = 10 * 60_000L
    @Volatile private var liveSessionStartedMs: Long = 0L
    @Volatile private var lastGuardCheckMs: Long = 0L
    @Volatile private var guardBlocked: Boolean = false

    fun activateLiveStreamingWindow(durationMs: Long = 90_000L) {
      val now = System.currentTimeMillis()
      if (liveSessionStartedMs == 0L) liveSessionStartedMs = now
      if (now - liveSessionStartedMs > LIVE_SESSION_MAX_MS) {
        stopLiveStreamingWindow()
        return
      }
      isLiveStreamingToParent = true
      liveStreamRequestedUntilMs = now + durationMs
      // Сразу подключаем реальную отдачу кадров MediaProjection (до этого она отцеплена
      // и не тратит батарею), а также делаем мгновенный снимок.
      com.example.util.ScreenShotManager.setLiveCaptureActive(true)
      instance?.triggerImmediateCaptureNow()
    }

    fun stopLiveStreamingWindow() {
      isLiveStreamingToParent = false
      liveStreamRequestedUntilMs = 0L
      liveSessionStartedMs = 0L
      guardBlocked = false
      lastGuardCheckMs = 0L
      // Отцепляем зеркало экрана: телефон перестаёт кодировать кадры и греться.
      com.example.util.ScreenShotManager.setLiveCaptureActive(false)
    }

    private fun batteryLowOrHot(): Boolean {
      val ctx = instance?.applicationContext
        ?: com.example.data.cloud.DirectP2pSocketEngine.appContext ?: return false
      return try {
        val bm = ctx.getSystemService(android.os.BatteryManager::class.java)
        val level = bm?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val sticky = ctx.registerReceiver(
          null,
          android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
        )
        val status = sticky?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
          status == android.os.BatteryManager.BATTERY_STATUS_FULL
        if (level in 1..15 && !charging) return true
        if (android.os.Build.VERSION.SDK_INT >= 29) {
          val pm = ctx.getSystemService(android.os.PowerManager::class.java)
          if (pm != null &&
            pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_MODERATE
          ) {
            return true
          }
        }
        false
      } catch (_: Exception) {
        false
      }
    }

    fun isLiveStreamingActiveNow(): Boolean {
      if (!isLiveStreamingToParent) return false
      val now = System.currentTimeMillis()
      if (now > liveStreamRequestedUntilMs) {
        isLiveStreamingToParent = false
        liveSessionStartedMs = 0L
        return false
      }
      if (liveSessionStartedMs > 0L && now - liveSessionStartedMs > LIVE_SESSION_MAX_MS) {
        stopLiveStreamingWindow()
        return false
      }
      if (now - lastGuardCheckMs > 30_000L) {
        lastGuardCheckMs = now
        guardBlocked = batteryLowOrHot()
      }
      if (guardBlocked) {
        stopLiveStreamingWindow()
        return false
      }
      return true
    }

    fun isAccessibilityEnabled(): Boolean = instance != null
  }

  private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
  private var windowManager: WindowManager? = null
  private var overlayView: View? = null

  @Volatile
  private var isDeviceLocked = false

  @Volatile
  private var isChildDeviceRole = false

  @Volatile
  private var blockedPackageNames = setOf<String>()

  @Volatile
  private var appLimitsMap = mapOf<String, Pair<Int, Int>>() // pkg -> (limitMinutes, usedMinutes)

  @Volatile
  private var blockedDomainsList = listOf<String>()

  @Volatile
  private var blockedKeywordsList = listOf<String>()

  @Volatile
  private var blockedCategoriesSet = setOf<String>()

  private var activeChildId: Long = 0L
  private var currentForegroundPackage: String? = null
  private var currentForegroundAppLabel: String = "Главный экран Android"
  private var lastContentPushTimeMs: Long = 0L
  private var lastPushedContentSummary: String = ""

  // Кэш описания экрана для частых запросов «снимок сейчас»: обход дерева
  // AccessibilityNodeInfo — дорогая операция, а в живом просмотре запросы идут каждые 1.5–2 с.
  @Volatile
  private var cachedCaptureSummary: String = ""

  @Volatile
  private var cachedCaptureSummaryMs: Long = 0L

  override fun onCreate() {
    super.onCreate()
    instance = this
    com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
    windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    observeSecurityAndCloud()
    startMinuteUsageTicking()
  }

  fun prepareForSilentUpdate() {
    serviceScope.launch(Dispatchers.Main) {
      hideLockOverlay()
    }
  }

  fun enforceTotalLockFromService(reason: String) {
    val wasAlreadyShowing = overlayView != null
    isDeviceLocked = true
    serviceScope.launch(Dispatchers.Main) {
      if (!wasAlreadyShowing) {
        try {
          performGlobalAction(GLOBAL_ACTION_HOME)
        } catch (_: Exception) {}
      }
      showLockOverlay(reason, isDeviceTotalLock = true)
    }
  }

  fun releaseTotalLockFromService() {
    isDeviceLocked = false
    serviceScope.launch(Dispatchers.Main) {
      hideLockOverlay()
    }
  }

  override fun onServiceConnected() {
    super.onServiceConnected()
    instance = this
    if (!com.example.location.LiveLocationTracker.isEmulator()) {
      serviceScope.launch(Dispatchers.IO) {
        com.example.util.CoreEngineHotUpdater.initializeHotCore(applicationContext)
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole == "CHILD") {
          isChildDeviceRole = true
          val cid = cfg.thisDeviceChildId.takeIf { it > 0 }
            ?: cfg.currentActiveChildId.takeIf { it > 0 }
            ?: 1L
          activeChildId = cid
          val effectiveVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(applicationContext)
          dao.updateChildAppVersion(cid, effectiveVer)
          try {
            CloudSyncManager(applicationContext).syncChildAppVersion(cid, effectiveVer)
          } catch (_: Exception) {}
          KidsGuardBackgroundService.start(applicationContext, cid)
        }
      }
    }
    Log.d(TAG, "KidsGuardAccessibilityService connected in transparent MDM mode")
  }

  private fun extractVisibleScreenContent(friendlyAppName: String): String {
    val lines = mutableListOf<String>()
    val skipWords = setOf(
      "главная", "shorts", "подписки", "вы", "библиотека", "поделиться", "скачать",
      "комментарии", "нравится", "не нравится", "подписаться", "ещё", "поиск",
      "home", "subscriptions", "library", "share", "remix", "ремикс"
    )
    try {
      val root = rootInActiveWindow
      if (root != null) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 70 && lines.size < 8) {
          val node = queue.removeFirst()
          visited++
          val rawText = node.text?.toString()?.trim().orEmpty()
          val rawDesc = node.contentDescription?.toString()?.trim().orEmpty()
          for (candidate in listOf(rawText, rawDesc)) {
            if (candidate.length in 4..120 &&
                !candidate.equals(friendlyAppName, ignoreCase = true) &&
                candidate.lowercase() !in skipWords &&
                !candidate.matches(Regex("^[0-9:\\s./+-]+$")) &&
                lines.none { it.equals(candidate, ignoreCase = true) }) {
              lines.add(candidate.replace("\n", " ").trim())
            }
          }
          val count = node.childCount
          for (i in 0 until count) {
            node.getChild(i)?.let { queue.add(it) }
          }
        }
      }
    } catch (_: Exception) {}

    return if (lines.isNotEmpty()) {
      val headline = lines.first()
      val details = lines.drop(1).take(5).joinToString(" | ")
      if (details.isNotBlank()) {
        "$friendlyAppName • $headline || $details"
      } else {
        "$friendlyAppName • $headline"
      }
    } else {
      friendlyAppName
    }
  }

  fun triggerImmediateCaptureNow() {
    if (com.example.location.LiveLocationTracker.isEmulator()) return
    serviceScope.launch(Dispatchers.IO) {
      try {
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD") return@launch
        val cid = cfg.thisDeviceChildId.takeIf { it > 0 }
          ?: cfg.currentActiveChildId.takeIf { it > 0 }
          ?: 1L
        activeChildId = cid
        val baseApp = currentForegroundAppLabel.ifBlank {
          DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
        }
        val nowSummaryMs = System.currentTimeMillis()
        val richSummary = if (cachedCaptureSummary.isNotBlank() && nowSummaryMs - cachedCaptureSummaryMs < 3_000L) {
          cachedCaptureSummary
        } else {
          extractVisibleScreenContent(baseApp).also {
            cachedCaptureSummary = it
            cachedCaptureSummaryMs = nowSummaryMs
          }
        }
        val sync = CloudSyncManager(applicationContext)
        // Во время непрерывного просмотра НЕ пишем каждый кадр в SQLite —
        // иначе база и диск телефона молотятся каждые 2–3 секунды, а батарея тает.
        val persistOffline = !isLiveStreamingActiveNow()
        ScreenShotManager.triggerHardwareScreenCapture(richSummary) { item ->
          serviceScope.launch(Dispatchers.IO) {
            if (!item.base64Bitmap.isNullOrBlank()) {
              sync.uploadScreenSnapshot(cid, richSummary, item.base64Bitmap)
              if (persistOffline) {
                com.example.data.cloud.OfflineStoreAndForwardManager.recordChildScreenshotOffline(
                  context = applicationContext,
                  childId = cid,
                  activeAppName = baseApp,
                  statusNote = richSummary,
                  base64Bitmap = item.base64Bitmap,
                  deviceType = "PHONE"
                )
              }
            } else {
              sync.updateActiveAppOnly(cid, richSummary)
            }
          }
        }
      } catch (_: Exception) {}
    }
  }

  private fun startMinuteUsageTicking() {
    // 1. Live Video Frame Streaming Loop (ONLY streams frames when Parent has requested Live Stream!)
    serviceScope.launch(Dispatchers.IO) {
      while (isActive) {
        val shouldStream = isChildDeviceRole &&
          activeChildId > 0 &&
          !isDeviceLocked &&
          isLiveStreamingActiveNow() &&
          DeviceUsageTracker.isInteractive(applicationContext) &&
          !com.example.location.LiveLocationTracker.isEmulator()
        if (shouldStream) {
          // Если MediaProjection уже отдаёт кадры напрямую (pushDirectScreenFrame), повторный
          // дорогой AccessibilityService.takeScreenshot каждые 2.5 с не нужен — только активное окно.
          if (com.example.util.ScreenShotManager.isMediaProjectionActive.value) {
            serviceScope.launch(Dispatchers.IO) {
              try {
                val app = currentForegroundAppLabel.ifBlank {
                  DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                }
                CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, app)
              } catch (_: Exception) {}
            }
            delay(10_000L)
          } else {
            triggerImmediateCaptureNow()
            delay(2_500L)
          }
        } else {
          // Rest quietly when Parent is not actively watching Live Stream
          delay(12_000L)
        }
      }
    }

    // 2. Minute Usage Ticking + Offline Store-and-Forward Recording & Flushing
    serviceScope.launch(Dispatchers.IO) {
      val database = KidsGuardDatabase.getDatabase(applicationContext)
      val dao = database.kidsGuardDao()
      var secondsInForeground = 0

      while (isActive) {
        if (!isChildDeviceRole) {
          delay(30_000L)
          val cfgCheck = dao.getFamilyConfigOnce()
          isChildDeviceRole = cfgCheck?.selectedRole == "CHILD"
          continue
        }
        val isScreenOn = DeviceUsageTracker.isInteractive(applicationContext)
        // 30 с вместо 15: вдвое меньше пробуждений CPU/Room, точность учёта времени
        // для родителя остаётся достаточной.
        delay(if (isScreenOn) 30_000L else 60_000L)
        if (!isScreenOn || com.example.location.LiveLocationTracker.isEmulator()) continue
        val cfg = dao.getFamilyConfigOnce()
        isChildDeviceRole = cfg?.selectedRole == "CHILD"
        if (!isChildDeviceRole) continue

        if (activeChildId <= 0) {
          activeChildId = when {
            cfg != null && cfg.thisDeviceChildId > 0 -> cfg.thisDeviceChildId
            cfg != null && cfg.currentActiveChildId > 0 -> cfg.currentActiveChildId
            else -> dao.getAllChildrenOnce().firstOrNull()?.id ?: 1L
          }
        }

        // Always enforce 00:00 midnight reset so daily limits and usage counters reset at midnight
        val didResetMidnight = DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
        if (didResetMidnight) {
          appLimitsMap = appLimitsMap.mapValues { (_, pair) -> Pair(pair.first, 0) }
        }

        val pkg = currentForegroundPackage
        val isLauncherOrSystem = pkg == null ||
          DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, pkg) ||
          pkg.contains("launcher", ignoreCase = true) ||
          pkg == "com.miui.home" ||
          pkg == "com.android.settings"

        if (!isLauncherOrSystem && pkg != null && !isDeviceLocked && activeChildId > 0) {
          secondsInForeground += 30
          // Record 30s activity slice in offline Room DB for 24-hour Recharts hourly chart & offline sync
          com.example.data.cloud.OfflineStoreAndForwardManager.recordChildActivityOffline(
            context = applicationContext,
            childId = activeChildId,
            packageName = pkg,
            appName = currentForegroundAppLabel,
            windowTitle = lastPushedContentSummary.ifBlank { currentForegroundAppLabel },
            durationSeconds = 30,
            deviceType = "PHONE"
          )

          if (secondsInForeground >= 60) {
            secondsInForeground = 0
            DeviceUsageTracker.recordAppSessionTime(applicationContext, activeChildId, pkg, 1)
            DeviceControlDebugger.logEvent("ЭКРАННОЕ_ВРЕМЯ", "+1 мин для $currentForegroundAppLabel", "SUCCESS")

            // Flush any cached offline history (apps, GPS, screenshots) to parent over P2P ONLY if Parent is actively connected
            if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(
                context = applicationContext,
                childId = activeChildId
              )
            }

            val limitInfo = appLimitsMap[pkg]
            if (limitInfo != null && !DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, pkg)) {
              val (dailyLimit, used) = limitInfo
              val newUsed = used + 1
              appLimitsMap = appLimitsMap + (pkg to Pair(dailyLimit, newUsed))
              if (dailyLimit in 1..newUsed) {
                launch(Dispatchers.Main) {
                  performGlobalAction(GLOBAL_ACTION_HOME)
                  showLockOverlay("Лимит времени на сегодня ($dailyLimit мин) исчерпан!", isDeviceTotalLock = false)
                }
              }
            }
          }
        }
      }
    }
  }

  private fun observeSecurityAndCloud() {
    val cloudSync = CloudSyncManager(applicationContext)
    val database = KidsGuardDatabase.getDatabase(applicationContext)
    val dao = database.kidsGuardDao()

    // 1. Observe Active Child and Phone Lock status reactively (zero polling loops!)
    serviceScope.launch(Dispatchers.IO) {
      dao.getFamilyConfig().collectLatest { config ->
        isChildDeviceRole = config?.selectedRole == "CHILD"
        if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) {
          isDeviceLocked = false
          launch(Dispatchers.Main) { hideLockOverlay() }
          return@collectLatest
        }
        val childId = when {
          config != null && config.thisDeviceChildId > 0 -> config.thisDeviceChildId
          config != null && config.currentActiveChildId > 0 -> config.currentActiveChildId
          else -> dao.getAllChildrenOnce().firstOrNull()?.id ?: 1L
        }
        if (childId > 0) {
          activeChildId = childId
          dao.getChildById(activeChildId).collectLatest { child ->
            child?.let {
              val authLock = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(activeChildId)
              isDeviceLocked = authLock?.first == true
              // Именно через setGpsTrackingAllowed: он не только меняет флаг,
              // но и СРАЗУ останавливает FusedLocation/LocationManager у ребёнка.
              com.example.location.LiveLocationTracker.setGpsTrackingAllowed(it.gpsTrackingEnabled)
              launch(Dispatchers.Main) {
                if (isDeviceLocked && isChildDeviceRole) {
                  enforceTotalLockFromService("Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                } else {
                  hideLockOverlay()
                }
              }
            }
          }
        }
      }
    }

    // 2. Observe Blocked Apps AND Daily Time Limits for THIS child only from local DB (never blocking KidsGuard itself)
    serviceScope.launch(Dispatchers.IO) {
      dao.getAllApps().collectLatest { allApps ->
        val childApps = if (activeChildId > 0) allApps.filter { it.childId == activeChildId } else allApps
        val blocked = childApps
          .filter { it.isBlocked && !DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, it.packageName) }
          .map { it.packageName }
          .toSet()
        blockedPackageNames = blocked

        val limits = mutableMapOf<String, Pair<Int, Int>>()
        for (a in childApps) {
          if (a.dailyLimitMinutes > 0 && !DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, a.packageName)) {
            limits[a.packageName] = Pair(a.dailyLimitMinutes, a.usedMinutesToday)
          }
        }
        appLimitsMap = limits
        Log.d(TAG, "Loaded rules for child $activeChildId: blocked=${blocked.size}, withLimits=${limits.size}")
      }
    }

    // 2B. Observe Web Filter / Domain Blacklist & Category Rules
    serviceScope.launch(Dispatchers.IO) {
      dao.getAllContentRules().collectLatest { rules ->
        blockedDomainsList = rules.filter { it.ruleType == "DOMAIN" && it.isBlocked }
          .map {
            it.value.trim().lowercase()
              .removePrefix("https://")
              .removePrefix("http://")
              .removePrefix("www.")
              .trimEnd('/')
          }
          .filter { it.isNotBlank() }
        blockedKeywordsList = rules.filter { it.ruleType == "KEYWORD" && it.isBlocked }
          .map { it.value.trim().lowercase() }
          .filter { it.isNotBlank() }
        blockedCategoriesSet = rules.filter { it.ruleType == "CATEGORY" && it.isBlocked }
          .map { it.value.uppercase() }
          .toSet()
      }
    }

    // 3. DIRECT CLOUD COMMAND LISTENER (ONLY executes on CHILD device!)
    serviceScope.launch(Dispatchers.IO) {
      while (isActive) {
        if (isChildDeviceRole && activeChildId > 0) {
          try {
            cloudSync.observeRemoteCommands(activeChildId).collectLatest { cmdPair ->
              val latestCfg = dao.getFamilyConfigOnce()
              isChildDeviceRole = latestCfg?.selectedRole == "CHILD"
              if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) {
                isDeviceLocked = false
                launch(Dispatchers.Main) { hideLockOverlay() }
                return@collectLatest
              }
              cmdPair?.let { (cmd, payload) ->
                Log.d(TAG, "Accessibility received Cloud command: $cmd (payload=$payload)")
                when (cmd) {
                  "LOCK" -> {
                    isDeviceLocked = true
                    com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(activeChildId, true)
                    dao.setChildDeviceLocked(activeChildId, true)
                    launch(Dispatchers.Main) {
                      enforceTotalLockFromService("Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                    }
                  }
                  "UNLOCK" -> {
                    isDeviceLocked = false
                    com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(activeChildId, false)
                    dao.setChildDeviceLocked(activeChildId, false)
                    launch(Dispatchers.Main) {
                      hideLockOverlay()
                      DeviceTotalLockManager.releaseTotalLock(applicationContext)
                    }
                  }
                  "LOUD_RING" -> {
                    com.example.util.SoundAlertManager.playLoudAttentionSignal(applicationContext)
                  }
                  "REQUEST_GPS_NOW" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(applicationContext)
                      dao.updateChildBattery(activeChildId, batt)
                      cloudSync.upsertBatteryLevel(activeChildId, batt)
                      if (com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
                        com.example.location.LiveLocationTracker.getBestImmediateLocation(applicationContext)?.let { loc ->
                          val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(applicationContext, loc.latitude, loc.longitude)
                          dao.updateChildLocation(activeChildId, loc.latitude, loc.longitude, addr, System.currentTimeMillis())
                          cloudSync.updateLocation(activeChildId, loc.latitude, loc.longitude, addr)
                        }
                      }
                      com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(
                        context = applicationContext,
                        childId = activeChildId
                      )
                    }
                  }
                  "REQUEST_OFFLINE_SYNC" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(
                        context = applicationContext,
                        childId = activeChildId
                      )
                    }
                  }
                  "GPS_TRACKING_ON" -> {
                    com.example.location.LiveLocationTracker.setGpsTrackingAllowed(true)
                    serviceScope.launch(Dispatchers.IO) {
                      dao.setChildGpsTrackingEnabled(activeChildId, true)
                    }
                  }
                  "GPS_TRACKING_OFF" -> {
                    com.example.location.LiveLocationTracker.setGpsTrackingAllowed(false)
                    serviceScope.launch(Dispatchers.IO) {
                      dao.setChildGpsTrackingEnabled(activeChildId, false)
                    }
                  }
                  "BLOCK_APP" -> {
                    val pkg = payload.trim()
                    if (pkg.isNotBlank()) {
                      blockedPackageNames = blockedPackageNames + pkg
                      serviceScope.launch(Dispatchers.IO) {
                        val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                        if (existing != null) {
                          dao.setAppBlocked(existing.id, true)
                        }
                      }
                      launch(Dispatchers.Main) {
                        if (currentForegroundPackage == pkg) {
                          performGlobalAction(GLOBAL_ACTION_HOME)
                          showLockOverlay("Приложение «$currentForegroundAppLabel» заблокировано родительским контролем KidsGuard.", isDeviceTotalLock = false)
                        }
                      }
                    }
                  }
                  "UNBLOCK_APP" -> {
                    val pkg = payload.trim()
                    if (pkg.isNotBlank()) {
                      blockedPackageNames = blockedPackageNames - pkg
                      serviceScope.launch(Dispatchers.IO) {
                        val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                        if (existing != null) {
                          dao.setAppBlocked(existing.id, false)
                        }
                      }
                      launch(Dispatchers.Main) {
                        hideLockOverlay()
                      }
                    }
                  }
                  "LIMIT_APP", "SET_APP_LIMIT" -> {
                    val parts = payload.split("|")
                    val pkg = parts.getOrNull(0)?.trim().orEmpty()
                    val limitMin = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                    if (pkg.isNotBlank()) {
                      serviceScope.launch(Dispatchers.IO) {
                        val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                        if (existing != null) {
                          dao.setAppLimit(existing.id, limitMin)
                        }
                      }
                    }
                  }
                  "REFRESH_APPS" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
                      val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(applicationContext, activeChildId)
                      if (realApps.isNotEmpty()) {
                        val prevApps = dao.getAppsForChildOnce(activeChildId).associateBy { it.packageName.trim().lowercase() }
                        val merged = realApps.map { app ->
                          val prev = prevApps[app.packageName.trim().lowercase()]
                          if (prev != null) {
                            app.copy(
                              isBlocked = prev.isBlocked,
                              dailyLimitMinutes = prev.dailyLimitMinutes,
                              usedMinutesToday = app.usedMinutesToday
                            )
                          } else app
                        }
                        dao.replaceChildAppsStrictlyFromRemote(activeChildId, merged)
                        cloudSync.uploadChildApps(activeChildId, merged)
                      }
                      DeviceUsageTracker.syncRealUsageStats(applicationContext, activeChildId)
                    }
                  }
                  "START_LIVE_STREAM" -> {
                    activateLiveStreamingWindow(90_000L)
                  }
                  "TAKE_SCREENSHOT" -> {
                    triggerImmediateCaptureNow()
                  }
                  "QUERY_ACTIVE_APP" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      val baseApp = currentForegroundAppLabel.ifBlank {
                        DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                      }
                      CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, baseApp)
                    }
                  }
                  "STOP_LIVE_STREAM" -> {
                    stopLiveStreamingWindow()
                  }
                  "UPDATE_APP", "APP_UPDATE" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      cloudSync.clearRemoteCommand(activeChildId)
                      dao.setChildUpdatePending(activeChildId, true)
                    }
                    prepareForSilentUpdate()
                    val targetVer = if (payload.contains("|")) payload.substringBefore("|").trim() else ""
                    val rawUrl = if (payload.contains("|")) payload.substringAfter("|").trim() else payload.trim()
                    val targetUrl = rawUrl.ifBlank { ApkUpdateInstaller.SHARED_APK_URL }
                    ApkUpdateInstaller.downloadAndInstallLatestApk(
                      context = applicationContext,
                      customUrl = targetUrl,
                      isSilentChildUpdate = true,
                      targetChildId = activeChildId,
                      targetVersionOverride = targetVer
                    )
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Cloud observer retry: ${e.message}")
            delay(5000)
          }
        } else {
          delay(15_000L)
        }
      }
    }
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    // CRITICAL BATTERY GUARD: Never run Accessibility node inspection on a Parent device or Emulator!
    if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) return

    val packageName = event.packageName?.toString() ?: return

    // Track active foreground application package, watched video/page title & push if changed
    val prevPkg = currentForegroundPackage
    if (packageName != applicationContext.packageName &&
        !packageName.startsWith("com.android.systemui") &&
        !packageName.contains("inputmethod", ignoreCase = true)) {
      currentForegroundPackage = packageName
      val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
      currentForegroundAppLabel = friendlyName
      val nowMs = System.currentTimeMillis()
      // Battery optimization: only walk Accessibility node tree on actual app switch or every 25s
      val shouldExtractContent = prevPkg != packageName || (nowMs - lastContentPushTimeMs > 25_000L)
      if (shouldExtractContent) {
        val richSummary = extractVisibleScreenContent(friendlyName)
        DeviceUsageTracker.setForegroundApp(richSummary.substringBefore(" ||"), packageName)
        if (activeChildId > 0 && (prevPkg != packageName || richSummary != lastPushedContentSummary)) {
          lastContentPushTimeMs = nowMs
          lastPushedContentSummary = richSummary
          serviceScope.launch(Dispatchers.IO) {
            if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, richSummary)
            }
            if (prevPkg != packageName) {
              com.example.data.cloud.OfflineStoreAndForwardManager.recordChildActivityOffline(
                context = applicationContext,
                childId = activeChildId,
                packageName = packageName,
                appName = friendlyName,
                windowTitle = richSummary,
                durationSeconds = 5,
                deviceType = "PHONE"
              )
            }
          }
        }
      }
    }

    // 0. Auto-click "Обновить"/"Установить" when OTA update is running inside PackageInstaller
    if (ApkUpdateInstaller.isInstallingUpdate && packageName.contains("packageinstaller", ignoreCase = true)) {
      hideLockOverlay()
      autoConfirmPackageInstallerUpdate(rootInActiveWindow)
      return
    }

    // ABSOLUTE EXEMPTION: Never block KidsGuard itself, core system UI, permission controller, or OTA updates!
    if (DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, packageName) ||
        DeviceSecurityPermissionHelper.isSettingUpPermissions ||
        ApkUpdateInstaller.isInstallingUpdate) {
      return
    }

    // 1. Anti-Uninstall / Anti-Disable Protection in System Settings (with full-window check so in-place APK updates are NEVER blocked!)
    if (packageName == "com.android.settings" || packageName.contains("packageinstaller", ignoreCase = true)) {
      val eventText = event.text.joinToString(" ").lowercase()
      val fullWindowText = (eventText + " " + extractVisibleScreenContent("")).lowercase()
      val isApkUpdateScreen = fullWindowText.contains("обновить") ||
        fullWindowText.contains("установить") ||
        fullWindowText.contains("установка") ||
        fullWindowText.contains("update") ||
        fullWindowText.contains("install") ||
        fullWindowText.contains("пакет")

      if (isApkUpdateScreen && packageName.contains("packageinstaller", ignoreCase = true)) {
        hideLockOverlay()
        return
      }

      val isUninstallAttempt = (fullWindowText.contains("удалить") ||
          fullWindowText.contains("стереть") ||
          fullWindowText.contains("остановить") ||
          fullWindowText.contains("uninstall") ||
          fullWindowText.contains("force stop")) &&
          !isApkUpdateScreen &&
          fullWindowText.contains("kidsguard")

      if (isUninstallAttempt) {
        DeviceControlDebugger.logEvent("ЗАЩИТА_ОТ_УДАЛЕНИЯ", "Попытка сброса в настройках: $eventText", "BLOCKED")
        Log.w(TAG, "Anti-uninstall triggered! Returning home.")
        performGlobalAction(GLOBAL_ACTION_HOME)
        showLockOverlay("Попытка удаления или отключения KidsGuard заблокирована!\nЗащита активна.", isDeviceTotalLock = false)
        return
      }
    }

    // 2. Full Device Lockout: Intercept any app when phone is locked (without repeated GLOBAL_ACTION_HOME flickering when overlay is already visible!)
    if (isDeviceLocked) {
      if (overlayView == null) {
        DeviceControlDebugger.logEvent("БЛОКИРОВКА_ТЕЛЕФОНА", "Перехвачена попытка открыть $currentForegroundAppLabel при заблокированном телефоне", "BLOCKED")
        performGlobalAction(GLOBAL_ACTION_HOME)
        showLockOverlay("Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.", isDeviceTotalLock = true)
      }
      return
    }

    // 3. Blocked App Enforcer (Instant ejection)
    if (blockedPackageNames.contains(packageName)) {
      DeviceControlDebugger.logEvent("ЗАПРЕТ_ПРИЛОЖЕНИЯ", "Запуск заблокированной программы: $currentForegroundAppLabel", "INTERCEPTED")
      Log.w(TAG, "Blocked application launched: $packageName -> Ejecting to Home immediately")
      performGlobalAction(GLOBAL_ACTION_HOME)
      showLockOverlay("Приложение «$currentForegroundAppLabel» заблокировано родительским контролем KidsGuard.", isDeviceTotalLock = false)
      return
    }

    // 4. Daily Time Limit Enforcer
    val limitInfo = appLimitsMap[packageName]
    if (limitInfo != null) {
      val (limit, used) = limitInfo
      if (limit in 1..used) {
        DeviceControlDebugger.logEvent("ЛИМИТ_ВРЕМЕНИ", "Исчерпан лимит для $currentForegroundAppLabel ($used/$limit мин)", "INTERCEPTED")
        performGlobalAction(GLOBAL_ACTION_HOME)
        showLockOverlay("Дневной лимит времени для «$currentForegroundAppLabel» исчерпан ($limit мин).", isDeviceTotalLock = false)
        return
      }
    }

    // 5. Real-time Browser Domain Blacklist & Category Web Filter Enforcer
    val isBrowserApp = packageName.contains("chrome", ignoreCase = true) ||
      packageName.contains("browser", ignoreCase = true) ||
      packageName.contains("sbrowser", ignoreCase = true) ||
      packageName.contains("firefox", ignoreCase = true) ||
      packageName.contains("opera", ignoreCase = true) ||
      packageName.contains("yandex", ignoreCase = true) ||
      packageName.contains("brave", ignoreCase = true) ||
      packageName.contains("emmx", ignoreCase = true)
    if (isBrowserApp) {
      val eventText = (event.text.joinToString(" ") + " " + (event.contentDescription?.toString() ?: "")).lowercase()
      if (eventText.isNotBlank()) {
        val matchedDomain = blockedDomainsList.firstOrNull { eventText.contains(it) }
        val matchedKeyword = blockedKeywordsList.firstOrNull { eventText.contains(it) }
        val matchedCategory = when {
          "ADULT" in blockedCategoriesSet && listOf("porn", "xxx", "pornhub", "xvideos", "onlyfans", "порно", "секс 18+").any { eventText.contains(it) } -> "Контент 18+"
          "GAMBLING" in blockedCategoriesSet && listOf("1xbet", "fonbet", "casino", "казино", "vulkan", "stake.com").any { eventText.contains(it) } -> "Азартные игры и казино"
          "VIOLENCE" in blockedCategoriesSet && listOf("даркнет", "шок-контент", "оружие купить").any { eventText.contains(it) } -> "Насилие и оружие"
          "DRUGS" in blockedCategoriesSet && listOf("вейп купить", "снюс", "закладки").any { eventText.contains(it) } -> "Наркотики и вейпы"
          else -> null
        }
        val blockReason = when {
          matchedDomain != null -> "Сайт $matchedDomain находится в черном списке доменов"
          matchedKeyword != null -> "Запрещенный поисковый запрос: «$matchedKeyword»"
          matchedCategory != null -> "Блокировка категории: $matchedCategory"
          else -> null
        }
        if (blockReason != null) {
          DeviceControlDebugger.logEvent("ВЕБ_ФИЛЬТР", blockReason, "BLOCKED")
          performGlobalAction(GLOBAL_ACTION_BACK)
          performGlobalAction(GLOBAL_ACTION_HOME)
          showLockOverlay("🌐 Веб-фильтр KidsGuard:\n$blockReason", isDeviceTotalLock = false)
          return
        }
      }
    }

    if (prevPkg != packageName && packageName != applicationContext.packageName && !packageName.startsWith("com.android.systemui")) {
      DeviceControlDebugger.logEvent("ОТКРЫТИЕ_ПРОГРАММЫ", "Активное окно: $currentForegroundAppLabel", "SUCCESS")
    }
  }

  override fun onInterrupt() {}

  private fun autoConfirmPackageInstallerUpdate(root: AccessibilityNodeInfo?) {
    if (root == null) return
    val targetLabels = setOf(
      "обновить", "установить", "продолжить", "готово", "открыть",
      "update", "install", "continue", "done", "open", "разрешить"
    )
    try {
      val queue = ArrayDeque<AccessibilityNodeInfo>()
      queue.add(root)
      var visited = 0
      while (queue.isNotEmpty() && visited < 120) {
        val node = queue.removeFirst()
        visited++
        val txt = node.text?.toString()?.trim()?.lowercase().orEmpty()
        val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()
        if (txt in targetLabels || desc in targetLabels) {
          if (node.isClickable && node.isEnabled) {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            Log.d(TAG, "Auto-clicked PackageInstaller button: '$txt'")
            return
          }
          var parent = node.parent
          while (parent != null) {
            if (parent.isClickable && parent.isEnabled) {
              parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
              Log.d(TAG, "Auto-clicked parent of PackageInstaller button: '$txt'")
              return
            }
            parent = parent.parent
          }
        }
        for (i in 0 until node.childCount) {
          node.getChild(i)?.let { queue.add(it) }
        }
      }
    } catch (_: Exception) {}
  }

  @SuppressLint("InflateParams")
  private fun showLockOverlay(reasonText: String, isDeviceTotalLock: Boolean) {
    if (!Settings.canDrawOverlays(applicationContext)) {
      if (isDeviceTotalLock) {
        com.example.ui.screens.ChildLockScreenActivity.launch(applicationContext, reasonText)
      }
      return
    }

    if (overlayView != null) {
      overlayView?.findViewById<TextView>(android.R.id.text1)?.text = reasonText
      return
    }

    try {
      val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
      }

      val flags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        layoutType,
        flags,
        PixelFormat.OPAQUE
      ).apply {
        gravity = Gravity.CENTER
      }

      val layout = com.example.util.UnifiedLockScreenViewBuilder.buildLockView(
        context = this,
        reasonText = reasonText,
        isDeviceTotalLock = isDeviceTotalLock,
        onCloseToHome = if (!isDeviceTotalLock) {
          {
            performGlobalAction(GLOBAL_ACTION_HOME)
            hideLockOverlay()
          }
        } else null,
        onUnlocked = {
          isDeviceLocked = false
          hideLockOverlay()
          DeviceTotalLockManager.releaseTotalLock(applicationContext)
        }
      )

      overlayView = layout
      windowManager?.addView(overlayView, params)
      // Не держим экран включённым бесконечно (это сильно сажает батарею, пока телефон
      // лежит заблокированным). Через 45 секунд разрешаем экрану погаснуть — сам оверлей
      // остаётся и снова покажется при включении экрана.
      android.os.Handler(mainLooper).postDelayed({
        try {
          val p = overlayView?.layoutParams as? WindowManager.LayoutParams
          if (p != null) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            windowManager?.updateViewLayout(overlayView, p)
          }
        } catch (_: Exception) {}
      }, 45_000L)
    } catch (e: Exception) {
      Log.e(TAG, "Error displaying overlay: ${e.message}")
      if (isDeviceTotalLock) {
        com.example.ui.screens.ChildLockScreenActivity.launch(applicationContext, reasonText)
      }
    }
  }

  private fun hideLockOverlay() {
    overlayView?.let {
      try {
        windowManager?.removeView(it)
      } catch (_: Exception) {}
      overlayView = null
    }
  }

  private fun bringAppToFront() {
    val intent = Intent(this, MainActivity::class.java).apply {
      addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
    startActivity(intent)
  }

  override fun onDestroy() {
    if (instance === this) {
      instance = null
    }
    hideLockOverlay()
    super.onDestroy()
  }
}
