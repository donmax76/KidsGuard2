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
      if (!isLiveStreamingToParent || liveSessionStartedMs == 0L || (now - liveSessionStartedMs > LIVE_SESSION_MAX_MS)) {
        liveSessionStartedMs = now
      }
      guardBlocked = false
      lastGuardCheckMs = now
      isLiveStreamingToParent = true
      liveStreamRequestedUntilMs = now + durationMs
      // Сразу подключаем реальную отдачу кадров MediaProjection и делаем мгновенный снимок.
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
        if (level in 1..5 && !charging) return true
        if (android.os.Build.VERSION.SDK_INT >= 29) {
          val pm = ctx.getSystemService(android.os.PowerManager::class.java)
          if (pm != null &&
            pm.currentThermalStatus >= android.os.PowerManager.THERMAL_STATUS_CRITICAL
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

    /** Текущая открытая программа — для короткого CHILD_PONG родителю. */
    fun currentForegroundApp(): String =
      instance?.currentForegroundAppLabel?.takeIf { it.isNotBlank() } ?: "Главный экран Android"
  }

  private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
  private var windowManager: WindowManager? = null
  private var overlayView: View? = null

  @Volatile
  private var isDeviceLocked = false

  @Volatile
  private var isOverlayForTotalDeviceLock: Boolean = false

  @Volatile
  private var currentBlockedOverlayPackage: String? = null

  @Volatile
  private var lastContentEventProcessedMs: Long = 0L

  @Volatile
  private var lastBlockedAppEjectMs: Long = 0L

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

  fun getCurrentBlockedOverlayPackage(): String? = currentBlockedOverlayPackage

  fun unblockPackageLocallyByPin(packageName: String) {
    val cleanPkg = packageName.trim()
    if (cleanPkg.isNotBlank()) {
      blockedPackageNames = blockedPackageNames - cleanPkg
      appLimitsMap = appLimitsMap - cleanPkg
    }
    if (currentBlockedOverlayPackage == cleanPkg) {
      currentBlockedOverlayPackage = null
    }
    serviceScope.launch(Dispatchers.Main) {
      if (!isDeviceLocked) {
        hideLockOverlay()
      }
    }
  }

  fun prepareForSilentUpdate() {
    serviceScope.launch(Dispatchers.Main) {
      hideLockOverlay()
    }
  }

  private fun ejectBlockedAppToHome(blockedPackage: String? = null) {
    try {
      performGlobalAction(GLOBAL_ACTION_HOME)
    } catch (_: Exception) {}
    try {
      val homeIntent = Intent(Intent.ACTION_MAIN).apply {
        addCategory(Intent.CATEGORY_HOME)
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
      }
      startActivity(homeIntent)
    } catch (_: Exception) {}
    if (!blockedPackage.isNullOrBlank()) {
      serviceScope.launch(Dispatchers.IO) {
        try {
          val am = getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
          am?.killBackgroundProcesses(blockedPackage)
        } catch (_: Exception) {}
      }
    }
  }

  fun enforceTotalLockFromService(reason: String) {
    val wasAlreadyShowingTotal = overlayView != null && isOverlayForTotalDeviceLock
    isDeviceLocked = true
    serviceScope.launch(Dispatchers.Main) {
      showLockOverlay(reason, isDeviceTotalLock = true, blockedPackageName = null)
      if (!wasAlreadyShowingTotal) {
        ejectBlockedAppToHome(null)
      }
    }
  }

  fun enforceAppOrLimitBlockFromService(reason: String, blockedPackage: String? = null) {
    serviceScope.launch(Dispatchers.Main) {
      showLockOverlay(
        reasonText = reason,
        isDeviceTotalLock = false,
        blockedPackageName = blockedPackage
      )
      ejectBlockedAppToHome(blockedPackage)
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
      val bootCfg = com.example.util.BootPrefs.load(applicationContext)
      if (bootCfg.permanentHardwareRole == "CHILD" || bootCfg.role == "CHILD") {
        isChildDeviceRole = true
        if (bootCfg.childId > 0L) activeChildId = bootCfg.childId
      }
      serviceScope.launch(Dispatchers.IO) {
        com.example.util.CoreEngineHotUpdater.initializeHotCore(applicationContext)
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD") {
          isChildDeviceRole = true
          val cid = cfg?.thisDeviceChildId?.takeIf { it > 0 }
            ?: bootCfg.childId.takeIf { it > 0 }
            ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
            ?: 1L
          activeChildId = cid
          val apkVer = com.example.util.AppVersionInfo.installedApkVersion(applicationContext)
          dao.updateChildAppVersion(cid, apkVer)
          try {
            CloudSyncManager(applicationContext).syncChildAppVersion(cid, apkVer)
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
          isLiveStreamingActiveNow() &&
          !com.example.location.LiveLocationTracker.isEmulator()
        if (shouldStream) {
          triggerImmediateCaptureNow()
          delay(1_400L)
        } else {
          // Быстрая проверка включения живого эфира
          delay(1_500L)
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
                  showLockOverlay(
                    reasonText = "Лимит времени на сегодня ($dailyLimit мин) исчерпан!",
                    isDeviceTotalLock = false,
                    blockedPackageName = pkg
                  )
                  ejectBlockedAppToHome(pkg)
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

    // 0. Watchdog выживания: AccessibilityService система почти не убивает, поэтому
    // используем его как сторожа — если фоновый сервис связи упал (разряд, Doze,
    // force-stop части процесса), поднимаем его обратно при следующей проверке.
    serviceScope.launch(Dispatchers.IO) {
      while (isActive) {
        delay(45_000L)
        if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) continue
        try {
          if (!com.example.service.KidsGuardBackgroundService.isRunning) {
            val cid = activeChildId.takeIf { it > 0 }
              ?: dao.getFamilyConfigOnce()?.thisDeviceChildId?.takeIf { it > 0 }
              ?: 1L
            com.example.service.KidsGuardBackgroundService.start(applicationContext, cid)
            Log.w(TAG, "Watchdog: KidsGuardBackgroundService был убит — перезапущен")
          }
        } catch (_: Exception) {}
      }
    }

    // 1. Observe Active Child and Phone Lock status reactively (zero polling loops!)
    serviceScope.launch(Dispatchers.IO) {
      dao.getFamilyConfig().collectLatest { config ->
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        isChildDeviceRole = config?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD"
        if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) {
          isDeviceLocked = false
          launch(Dispatchers.Main) { hideLockOverlay() }
          return@collectLatest
        }
        val childId = when {
          config != null && config.thisDeviceChildId > 0 -> config.thisDeviceChildId
          bootCfg.childId > 0 -> bootCfg.childId
          config != null && config.currentActiveChildId > 0 -> config.currentActiveChildId
          else -> dao.getAllChildrenOnce().firstOrNull()?.id ?: 1L
        }
        if (childId > 0) {
          activeChildId = childId
          dao.getChildById(activeChildId).collectLatest { child ->
            child?.let {
              val authLock = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(activeChildId)
              isDeviceLocked = authLock?.first == true
              com.example.location.LiveLocationTracker.setGpsTrackingAllowed(it.gpsTrackingEnabled)
              launch(Dispatchers.Main) {
                if (isDeviceLocked && isChildDeviceRole) {
                  enforceTotalLockFromService("Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                } else if (isOverlayForTotalDeviceLock) {
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

        // Если текущее открытое приложение только что заблокировали — мгновенно показываем 3-кнопочное окно и закрываем приложение на главный экран
        val activePkg = currentForegroundPackage
        if (isChildDeviceRole && !isDeviceLocked && !activePkg.isNullOrBlank() && blocked.contains(activePkg)) {
          val friendly = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, activePkg)
          launch(Dispatchers.Main) {
            showLockOverlay(
              reasonText = "Приложение «$friendly» заблокировано родительским контролем KidsGuard.",
              isDeviceTotalLock = false,
              blockedPackageName = activePkg
            )
            ejectBlockedAppToHome(activePkg)
          }
        } else if (!isDeviceLocked && !isOverlayForTotalDeviceLock && currentBlockedOverlayPackage != null) {
          // Если заблокированное приложение было разблокировано родителем — сразу закрываем 3-кнопочное окно
          val overlayPkg = currentBlockedOverlayPackage
          if (overlayPkg != null && !blocked.contains(overlayPkg)) {
            launch(Dispatchers.Main) {
              hideLockOverlay()
            }
          }
        }
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
              val bootCfg = com.example.util.BootPrefs.load(applicationContext)
              isChildDeviceRole = latestCfg?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD"
              if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) {
                isDeviceLocked = false
                launch(Dispatchers.Main) { hideLockOverlay() }
                return@collectLatest
              }
              cmdPair?.let { (cmd, payload) ->
                Log.d(TAG, "Accessibility received Cloud command: $cmd (payload=$payload)")
                when (cmd) {
                  "SET_MASTER_PIN" -> {
                    val cleanPin = payload.filter { it.isDigit() }
                    if (cleanPin.length in 4..8) {
                      dao.updateMasterPin(cleanPin)
                      dao.updateChildUninstallPin(cleanPin)
                      com.example.util.BootPrefs.saveMasterPin(applicationContext, cleanPin)
                    }
                  }
                  "LOCK" -> {
                    isDeviceLocked = true
                    com.example.data.cloud.RealtimeCloudRelay.setLocalAuthoritativeLock(activeChildId, true)
                    dao.setChildDeviceLocked(activeChildId, true)
                    launch(Dispatchers.Main) {
                      enforceTotalLockFromService("Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                    }
                  }
                  "UNLOCK" -> {
                    isDeviceLocked = false
                    com.example.data.cloud.RealtimeCloudRelay.setLocalAuthoritativeLock(activeChildId, false)
                    dao.setChildDeviceLocked(activeChildId, false)
                    launch(Dispatchers.Main) {
                      hideLockOverlay()
                      DeviceTotalLockManager.releaseTotalLock(applicationContext)
                    }
                  }
                  "LOUD_RING" -> {
                    com.example.util.SoundAlertManager.playLoudAttentionSignal(applicationContext)
                  }
                  "REQUEST_GPS_NOW", "MAP_VIEWING" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(applicationContext)
                      dao.updateChildBattery(activeChildId, batt)
                      cloudSync.upsertBatteryLevel(activeChildId, batt)
                      if (com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
                        val loc = com.example.location.LiveLocationTracker.awaitHighAccuracyLocation(applicationContext, 4_000L)
                          ?: com.example.location.LiveLocationTracker.getBestImmediateLocation(applicationContext)
                        loc?.let {
                          val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(applicationContext, it.latitude, it.longitude)
                          dao.updateChildLocation(activeChildId, it.latitude, it.longitude, addr, System.currentTimeMillis())
                          cloudSync.updateLocation(activeChildId, it.latitude, it.longitude, addr)
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
                    com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                      activeChildId,
                      gpsTrackingEnabled = true
                    )
                    com.example.location.LiveLocationTracker.setGpsTrackingAllowed(true)
                    serviceScope.launch(Dispatchers.IO) {
                      dao.setChildGpsTrackingEnabled(activeChildId, true)
                    }
                  }
                  "GPS_TRACKING_OFF" -> {
                    com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                      activeChildId,
                      gpsTrackingEnabled = false
                    )
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
                        cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                      }
                      launch(Dispatchers.Main) {
                        if (currentForegroundPackage == pkg) {
                          val friendly = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, pkg)
                          showLockOverlay(
                            reasonText = "Приложение «$friendly» заблокировано родительским контролем KidsGuard.",
                            isDeviceTotalLock = false,
                            blockedPackageName = pkg
                          )
                          ejectBlockedAppToHome(pkg)
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
                        cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                      }
                      launch(Dispatchers.Main) {
                        if (!isDeviceLocked && (currentBlockedOverlayPackage == pkg || !isOverlayForTotalDeviceLock)) {
                          hideLockOverlay()
                        }
                      }
                    }
                  }
                  "LIMIT_APP", "SET_APP_LIMIT" -> {
                    val delim = if (payload.contains("|")) "|" else ":"
                    val pkg = payload.substringBefore(delim).trim()
                    val limitMin = payload.substringAfter(delim, "0").trim().toIntOrNull() ?: 0
                    if (pkg.isNotBlank()) {
                      serviceScope.launch(Dispatchers.IO) {
                        val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                        if (existing != null) {
                          dao.setAppLimit(existing.id, limitMin)
                          cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
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
    // CRITICAL BATTERY & CPU GUARD: Never run Accessibility inspection on a Parent device or Emulator!
    if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) return

    val packageName = event.packageName?.toString() ?: return
    val nowMs = System.currentTimeMillis()

    // 0. Auto-click "Обновить"/"Установить" when OTA update is running inside PackageInstaller
    if (ApkUpdateInstaller.isInstallingUpdate && packageName.contains("packageinstaller", ignoreCase = true)) {
      hideLockOverlay()
      autoConfirmPackageInstallerUpdate(rootInActiveWindow)
      return
    }

    val isSystemOrSelf = packageName == applicationContext.packageName ||
      packageName.startsWith("com.android.systemui") ||
      packageName.contains("inputmethod", ignoreCase = true)

    val isCoreWhitelisted = DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, packageName) ||
      DeviceSecurityPermissionHelper.isSettingUpPermissions ||
      ApkUpdateInstaller.isInstallingUpdate

    // =========================================================================
    // МГНОВЕННАЯ O(1) БЛОКИРОВКА И ЗАКРЫТИЕ ЗАПРЕЩЕННЫХ ПРИЛОЖЕНИЙ:
    // Сначала МГНОВЕННО выводим полноэкранное окно с 3 кнопками (ввести PIN,
    // перейти на главный экран, экстренный вызов), и тут же принудительно
    // закрываем запрещённую программу на системный главный экран (Home Launcher).
    // =========================================================================
    if (!isSystemOrSelf && !isCoreWhitelisted) {
      // 1. Полная блокировка телефона (2 кнопки: PIN и Экстренный вызов SOS)
      if (isDeviceLocked) {
        if (overlayView == null || !isOverlayForTotalDeviceLock) {
          val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
          DeviceControlDebugger.logEvent("БЛОКИРОВКА_ТЕЛЕФОНА", "Перехвачена попытка открыть $friendlyName при заблокированном телефоне", "BLOCKED")
          showLockOverlay(
            reasonText = "Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.",
            isDeviceTotalLock = true,
            blockedPackageName = null
          )
          ejectBlockedAppToHome(packageName)
        }
        return
      }

      // 2. Мгновенное закрытие запрещенного приложения + форма с 3 кнопками (Выйти на главный экран, Ввести PIN, Экстренный вызов)
      if (blockedPackageNames.contains(packageName)) {
        val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
        currentForegroundPackage = packageName
        currentForegroundAppLabel = friendlyName
        // Сначала мгновенно показываем 3-кнопочное окно поверх всего экрана, чтобы ребёнок не видел пустот
        showLockOverlay(
          reasonText = "Приложение «$friendlyName» заблокировано родительским контролем KidsGuard.",
          isDeviceTotalLock = false,
          blockedPackageName = packageName
        )
        // И сразу закрываем само приложение на системный рабочий стол (Home), чтобы оно не оставалось открытым
        if ((nowMs - lastBlockedAppEjectMs) > 350L) {
          lastBlockedAppEjectMs = nowMs
          DeviceControlDebugger.logEvent("ЗАПРЕТ_ПРИЛОЖЕНИЯ", "Мгновенно закрыта заблокированная программа: $friendlyName", "INTERCEPTED")
          ejectBlockedAppToHome(packageName)
        }
        return
      }

      // 3. Проверка дневного лимита времени для приложения (3 кнопки + мгновенное закрытие)
      val limitInfo = appLimitsMap[packageName]
      if (limitInfo != null) {
        val (limit, used) = limitInfo
        if (limit in 1..used) {
          val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
          currentForegroundPackage = packageName
          currentForegroundAppLabel = friendlyName
          showLockOverlay(
            reasonText = "Дневной лимит времени для «$friendlyName» исчерпан ($limit мин).",
            isDeviceTotalLock = false,
            blockedPackageName = packageName
          )
          if ((nowMs - lastBlockedAppEjectMs) > 350L) {
            lastBlockedAppEjectMs = nowMs
            DeviceControlDebugger.logEvent("ЛИМИТ_ВРЕМЕНИ", "Исчерпан лимит для $friendlyName ($used/$limit мин)", "INTERCEPTED")
            ejectBlockedAppToHome(packageName)
          }
          return
        }
      }
    }

    // =========================================================================
    // ЗАЩИТА ОТ ЛАГОВ И НАГРУЗКИ НА ПРОЦЕССОР / ПАМЯТЬ:
    // События TYPE_WINDOW_CONTENT_CHANGED приходят десятками в секунду.
    // Мы НЕ обходим дерево AccessibilityNodeInfo на главном потоке в onAccessibilityEvent!
    // =========================================================================
    val prevPkg = currentForegroundPackage
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
        packageName == prevPkg &&
        packageName != "com.android.settings" &&
        !packageName.contains("packageinstaller", ignoreCase = true) &&
        (nowMs - lastContentEventProcessedMs) < 3_500L) {
      return
    }
    lastContentEventProcessedMs = nowMs

    // Лёгкое отслеживание активного приложения БЕЗ обхода дерева AccessibilityNodeInfo на UI-потоке
    if (!isSystemOrSelf) {
      currentForegroundPackage = packageName
      val friendlyName = if (prevPkg == packageName && currentForegroundAppLabel.isNotBlank()) {
        currentForegroundAppLabel
      } else {
        DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
      }
      currentForegroundAppLabel = friendlyName

      if (prevPkg != packageName || (nowMs - lastContentPushTimeMs > 30_000L)) {
        val eventTitle = event.text.firstOrNull()?.toString()?.trim()?.takeIf {
          it.length in 3..80 && !it.equals(friendlyName, ignoreCase = true)
        }
        val lightweightSummary = if (eventTitle != null) "$friendlyName • $eventTitle" else friendlyName
        DeviceUsageTracker.setForegroundApp(lightweightSummary, packageName)
        if (activeChildId > 0 && (prevPkg != packageName || lightweightSummary != lastPushedContentSummary)) {
          lastContentPushTimeMs = nowMs
          lastPushedContentSummary = lightweightSummary
          val switchedPackage = prevPkg != packageName
          serviceScope.launch(Dispatchers.IO) {
            if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, lightweightSummary)
            }
            if (switchedPackage) {
              com.example.data.cloud.OfflineStoreAndForwardManager.recordChildActivityOffline(
                context = applicationContext,
                childId = activeChildId,
                packageName = packageName,
                appName = friendlyName,
                windowTitle = lightweightSummary,
                durationSeconds = 5,
                deviceType = "PHONE"
              )
            }
          }
        }
      }
    }

    if (isCoreWhitelisted) {
      return
    }

    // 4. Anti-Uninstall / Anti-Disable Protection in System Settings (lightweight check without heavy tree walk)
    if (packageName == "com.android.settings" || packageName.contains("packageinstaller", ignoreCase = true)) {
      val eventText = (event.text.joinToString(" ") + " " + (event.contentDescription?.toString() ?: "")).lowercase()
      val isApkUpdateScreen = eventText.contains("обновить") ||
        eventText.contains("установить") ||
        eventText.contains("установка") ||
        eventText.contains("update") ||
        eventText.contains("install") ||
        eventText.contains("пакет")

      if (isApkUpdateScreen && packageName.contains("packageinstaller", ignoreCase = true)) {
        hideLockOverlay()
        return
      }

      if (eventText.contains("kidsguard")) {
        val isUninstallAttempt = (eventText.contains("удалить") ||
            eventText.contains("стереть") ||
            eventText.contains("остановить") ||
            eventText.contains("uninstall") ||
            eventText.contains("force stop")) &&
            !isApkUpdateScreen

        if (isUninstallAttempt) {
          DeviceControlDebugger.logEvent("ЗАЩИТА_ОТ_УДАЛЕНИЯ", "Попытка сброса в настройках: $eventText", "BLOCKED")
          Log.w(TAG, "Anti-uninstall triggered! Returning home.")
          showLockOverlay(
            reasonText = "Попытка удаления или отключения KidsGuard заблокирована!\nЗащита активна.",
            isDeviceTotalLock = false,
            blockedPackageName = null
          )
          ejectBlockedAppToHome(packageName)
          return
        }
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
          showLockOverlay(
            reasonText = "🌐 Веб-фильтр KidsGuard:\n$blockReason",
            isDeviceTotalLock = false,
            blockedPackageName = packageName
          )
          ejectBlockedAppToHome(packageName)
          return
        }
      }
    }

    if (prevPkg != packageName && !isSystemOrSelf) {
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
  private fun showLockOverlay(
    reasonText: String,
    isDeviceTotalLock: Boolean,
    blockedPackageName: String? = null
  ) {
    if (!Settings.canDrawOverlays(applicationContext)) {
      com.example.ui.screens.ChildLockScreenActivity.launch(
        context = applicationContext,
        reason = reasonText,
        isDeviceTotalLock = isDeviceTotalLock,
        blockedPackageName = blockedPackageName
      )
      return
    }

    // Если тип блокировки изменился (например, было 3-кнопочное окно приложения, а стала 2-кнопочная блокировка телефона или наоборот),
    // либо изменился заблокированный пакет — пересоздаём оверлей с правильным набором кнопок.
    if (overlayView != null) {
      if (isOverlayForTotalDeviceLock == isDeviceTotalLock && currentBlockedOverlayPackage == blockedPackageName) {
        overlayView?.findViewById<TextView>(android.R.id.text1)?.text = reasonText
        return
      } else {
        hideLockOverlay()
      }
    }

    isOverlayForTotalDeviceLock = isDeviceTotalLock
    currentBlockedOverlayPackage = if (isDeviceTotalLock) null else blockedPackageName

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
        blockedPackageName = blockedPackageName,
        onCloseToHome = if (!isDeviceTotalLock) {
          {
            ejectBlockedAppToHome(blockedPackageName)
            hideLockOverlay()
          }
        } else null,
        onUnlocked = {
          if (isDeviceTotalLock) {
            isDeviceLocked = false
            DeviceTotalLockManager.releaseTotalLock(applicationContext)
          }
          hideLockOverlay()
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
      com.example.ui.screens.ChildLockScreenActivity.launch(
        context = applicationContext,
        reason = reasonText,
        isDeviceTotalLock = isDeviceTotalLock,
        blockedPackageName = blockedPackageName
      )
    }
  }

  private fun hideLockOverlay() {
    isOverlayForTotalDeviceLock = false
    currentBlockedOverlayPackage = null
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
