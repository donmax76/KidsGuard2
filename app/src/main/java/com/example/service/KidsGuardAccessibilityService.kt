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

    /**
     * Полностью останавливает аудио/видеопоток запрещённой программы (0 мс)
     * и многократно завершает её фоновые процессы, чтобы она не могла играть в фоне или в карусели Недавних.
     */
    fun killBlockedPackageCompletely(context: Context, packageName: String) {
      val cleanPkg = packageName.trim()
      if (cleanPkg.isBlank()) return
      try {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? android.media.AudioManager
        if (audioManager != null) {
          try {
            val downPause = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            val upPause = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_PAUSE)
            val downStop = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_STOP)
            val upStop = android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_MEDIA_STOP)
            audioManager.dispatchMediaKeyEvent(downPause)
            audioManager.dispatchMediaKeyEvent(upPause)
            audioManager.dispatchMediaKeyEvent(downStop)
            audioManager.dispatchMediaKeyEvent(upStop)
          } catch (_: Exception) {}
          try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
              val req = android.media.AudioFocusRequest.Builder(android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE).build()
              audioManager.requestAudioFocus(req)
              android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                try { audioManager.abandonAudioFocusRequest(req) } catch (_: Exception) {}
              }, 350L)
            } else {
              @Suppress("DEPRECATION")
              audioManager.requestAudioFocus(null, android.media.AudioManager.STREAM_MUSIC, android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
            }
          } catch (_: Exception) {}
        }
      } catch (_: Exception) {}

      try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
        am?.killBackgroundProcesses(cleanPkg)
        CoroutineScope(Dispatchers.IO).launch {
          for (waitMs in listOf(80L, 180L, 350L, 700L)) {
            delay(waitMs)
            try { am?.killBackgroundProcesses(cleanPkg) } catch (_: Exception) {}
          }
        }
      } catch (_: Exception) {}
    }
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
  private var lastRecentsCheckMs: Long = 0L

  @Volatile
  private var lastRecentsSwipeMs: Long = 0L

  @Volatile
  private var lastPingCaptureA11yMs: Long = 0L

  @Volatile
  private var lastBlockedAppEjectMs: Long = 0L

  @Volatile
  private var isContentEventsEnabledInServiceInfo: Boolean = true

  @Volatile
  private var lastBrowserUrlScanMs: Long = 0L

  @Volatile
  private var lastLoggedBrowserDomain: String = ""

  @Volatile
  private var lastLoggedBrowserDomainMs: Long = 0L

  private var otaAutoClickWatcherJob: kotlinx.coroutines.Job? = null

  @Volatile
  private var isChildDeviceRole = false

  @Volatile
  private var blockedPackageNames = setOf<String>()

  // Карта (lowercase название программы -> packageName) для мгновенного распознавания карточек в списке Недавних окон
  @Volatile
  private var blockedAppLabelsToPkg = mapOf<String, String>()

  // Набор заблокированных пакетов, которые ребёнок пытался открыть и которые могут оставаться в карусели Недавних окон
  private val dirtyRecentsBlockedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

  // Временный пропуск после ввода PIN-кода родителя для конкретного пакета (pkg -> validUntilMs)
  private val temporaryPinUnlockedPackages = java.util.concurrent.ConcurrentHashMap<String, Long>()

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

  private var isScreenPowerReceiverRegistered = false

  private val screenAndPowerWakeReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) return
      when (intent?.action) {
        Intent.ACTION_SCREEN_OFF -> {
          stopLiveStreamingWindow()
          acquireShortWakeLockAndPulse(reason = "SCREEN_OFF", captureScreenAfter = false)
        }
        Intent.ACTION_SCREEN_ON,
        Intent.ACTION_USER_PRESENT -> {
          acquireShortWakeLockAndPulse(reason = "SCREEN_ON", captureScreenAfter = true)
        }
        Intent.ACTION_POWER_CONNECTED,
        Intent.ACTION_POWER_DISCONNECTED -> {
          acquireShortWakeLockAndPulse(reason = "POWER_CHANGED", captureScreenAfter = DeviceUsageTracker.isInteractive(applicationContext))
        }
      }
    }
  }

  private fun registerScreenAndPowerWakeReceiver() {
    if (isScreenPowerReceiverRegistered) return
    try {
      val filter = android.content.IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
      }
      androidx.core.content.ContextCompat.registerReceiver(
        applicationContext,
        screenAndPowerWakeReceiver,
        filter,
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
      )
      isScreenPowerReceiverRegistered = true
    } catch (_: Exception) {}
  }

  private fun acquireShortWakeLockAndPulse(reason: String, captureScreenAfter: Boolean) {
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
      val wl = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KidsGuard:A11yPulseWake")
      wl?.setReferenceCounted(false)
      wl?.acquire(4_500L)
    } catch (_: Exception) {}

    serviceScope.launch(Dispatchers.IO) {
      try {
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD" && bootCfg.permanentHardwareRole != "CHILD") return@launch
        val cid = activeChildId.takeIf { it > 0 }
          ?: cfg?.thisDeviceChildId?.takeIf { it > 0 }
          ?: bootCfg.childId.takeIf { it > 0 }
          ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
          ?: 1L
        activeChildId = cid

        com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
        com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", cid)
        com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()

        val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(applicationContext)
        if (batt in 1..100) {
          dao.updateChildBattery(cid, batt)
          CloudSyncManager(applicationContext).upsertBatteryLevel(cid, batt)
        }
        com.example.data.cloud.RealtimeCloudRelay.publishChildPong(cid)

        val isInteractive = DeviceUsageTracker.isInteractive(applicationContext)
        if (!isInteractive) {
          val isCharging = com.example.util.AppEnergyMeter.isCharging(applicationContext)
          val statusTitle = if (isCharging) {
            "Экран выключен (на зарядке • ${if (batt in 1..100) "$batt%" else "зарядка"})"
          } else {
            "Экран выключен (телефон заблокирован)"
          }
          CloudSyncManager(applicationContext).updateActiveAppOnly(cid, statusTitle)
        } else if (captureScreenAfter) {
          delay(550L)
          triggerImmediateCaptureNow()
        }
      } catch (_: Exception) {}
    }
  }

  private fun isDeviceStorageUnlocked(): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
        val um = getSystemService(Context.USER_SERVICE) as? android.os.UserManager
        um?.isUserUnlocked != false
      } else true
    } catch (_: Exception) {
      true
    }
  }

  override fun onCreate() {
    super.onCreate()
    instance = this
    com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
    windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    // Мгновенно (0 мс) загружаем список заблокированных программ и лимитов из защищённого хранилища ещё до старта Room
    val bootBlocked = com.example.util.BootPrefs.getBlockedPackages(applicationContext)
      .map { it.trim().lowercase() }
      .toSet()
    blockedPackageNames = bootBlocked
    val bootDomains = com.example.util.BootPrefs.getBlockedDomains(applicationContext)
    if (bootDomains.isNotEmpty()) {
      blockedDomainsList = bootDomains.toList()
    }
    val bootLimits = com.example.util.BootPrefs.getAppLimitsMap(applicationContext)
    if (bootLimits.isNotEmpty() && appLimitsMap.isEmpty()) {
      appLimitsMap = bootLimits.mapValues { (_, lim) -> Pair(lim, 0) }
    }
    rebuildBlockedLabelsIndex()

    val bootCfg = com.example.util.BootPrefs.load(applicationContext)
    if (bootCfg.permanentHardwareRole == "CHILD" ||
        bootCfg.role == "CHILD" ||
        (bootCfg.permanentHardwareRole != "PARENT" && (bootBlocked.isNotEmpty() || bootCfg.pairingCode.length >= 6))) {
      isChildDeviceRole = true
      activeChildId = bootCfg.childId.takeIf { it > 0L } ?: 1L
      if (!com.example.location.LiveLocationTracker.isEmulator()) {
        try {
          com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", activeChildId)
        } catch (_: Exception) {}
      }
    }
    registerScreenAndPowerWakeReceiver()
    observeSecurityAndCloud()
    startMinuteUsageTicking()
  }

  private fun rebuildBlockedLabelsIndex() {
    val map = mutableMapOf<String, String>()
    for (pkg in blockedPackageNames) {
      val cleanPkg = pkg.trim()
      if (cleanPkg.isBlank()) continue
      val label = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, cleanPkg).trim().lowercase()
      if (label.length >= 2) {
        map[label] = cleanPkg
      }
      val lowPkg = cleanPkg.lowercase()
      when {
        lowPkg == "com.android.vending" || lowPkg.contains("vending") -> {
          map["google play"] = cleanPkg
          map["play маркет"] = cleanPkg
          map["play market"] = cleanPkg
          map["плей маркет"] = cleanPkg
          map["google play маркет"] = cleanPkg
        }
        lowPkg.contains("musically") || lowPkg.contains("trill") || lowPkg.contains("tiktok") -> {
          map["tiktok"] = cleanPkg
          map["тикток"] = cleanPkg
          map["тик ток"] = cleanPkg
        }
        lowPkg.contains("instagram") -> {
          map["instagram"] = cleanPkg
          map["инстаграм"] = cleanPkg
        }
        lowPkg.contains("youtube") -> {
          map["youtube"] = cleanPkg
          map["ютуб"] = cleanPkg
        }
        lowPkg.contains("telegram") -> {
          map["telegram"] = cleanPkg
          map["телеграм"] = cleanPkg
        }
        lowPkg.contains("whatsapp") -> {
          map["whatsapp"] = cleanPkg
          map["ватсап"] = cleanPkg
        }
      }
    }
    blockedAppLabelsToPkg = map
  }

  fun setDomainBlockedImmediate(domain: String, isBlocked: Boolean) {
    val cleanDomain = com.example.util.BootPrefs.normalizeDomain(domain)
    if (cleanDomain.isBlank() || !cleanDomain.contains(".")) return
    com.example.util.BootPrefs.saveBlockedDomain(applicationContext, cleanDomain, isBlocked)
    blockedDomainsList = if (isBlocked) {
      (blockedDomainsList + cleanDomain).distinct()
    } else {
      blockedDomainsList.filter { it != cleanDomain }
    }
    if (isBlocked && isChildDeviceRole) {
      val activePkg = currentForegroundPackage.orEmpty()
      if (isUniversalBrowserPackage(activePkg) &&
          (lastLoggedBrowserDomain == cleanDomain || lastLoggedBrowserDomain.endsWith(".$cleanDomain"))) {
        serviceScope.launch(Dispatchers.Main) {
          showLockOverlay(
            reasonText = "🌐 Сайт заблокирован родителем:\n$cleanDomain\n\nДоступ к этому сайту запрещён во всех браузерах.",
            isDeviceTotalLock = false,
            blockedPackageName = activePkg
          )
          ejectBlockedAppToHome(activePkg)
        }
      }
    }
  }

  fun grantTemporaryPinUnlockForPackage(packageName: String, durationMs: Long = 30 * 60_000L) {
    val cleanPkg = packageName.trim()
    if (cleanPkg.isBlank()) return
    temporaryPinUnlockedPackages[cleanPkg] = System.currentTimeMillis() + durationMs
    dirtyRecentsBlockedPackages.remove(cleanPkg)
    if (currentBlockedOverlayPackage == cleanPkg) {
      currentBlockedOverlayPackage = null
    }
    serviceScope.launch(Dispatchers.Main) {
      if (!isDeviceLocked && !isOverlayForTotalDeviceLock) {
        hideLockOverlay()
      }
    }
  }

  fun isPackageTemporarilyUnlockedByPin(packageName: String): Boolean {
    val until = temporaryPinUnlockedPackages[packageName.trim()] ?: return false
    if (System.currentTimeMillis() <= until) return true
    temporaryPinUnlockedPackages.remove(packageName.trim())
    return false
  }

  fun dismissOverlayFromGatekeeperActivity() {
    serviceScope.launch(Dispatchers.Main) {
      if (!isDeviceLocked) {
        hideLockOverlay()
      }
    }
  }

  fun getCurrentBlockedOverlayPackage(): String? = currentBlockedOverlayPackage

  fun setPackageBlockedImmediate(packageName: String, isBlocked: Boolean) {
    val cleanPkg = packageName.trim()
    if (cleanPkg.isBlank() || DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, cleanPkg)) return
    com.example.util.BootPrefs.saveBlockedPackage(applicationContext, cleanPkg, isBlocked)
    if (isBlocked) {
      temporaryPinUnlockedPackages.remove(cleanPkg)
      blockedPackageNames = blockedPackageNames + cleanPkg
      rebuildBlockedLabelsIndex()
      dirtyRecentsBlockedPackages[cleanPkg] = System.currentTimeMillis()
      killBlockedPackageCompletely(applicationContext, cleanPkg)
      val activePkg = currentForegroundPackage
      val rootPkg = try { rootInActiveWindow?.packageName?.toString() } catch (_: Exception) { null }
      if (isChildDeviceRole && (activePkg == cleanPkg || rootPkg == cleanPkg)) {
        val friendly = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, cleanPkg)
        serviceScope.launch(Dispatchers.Main) {
          interceptAndGateBlockedApp(
            packageName = cleanPkg,
            reasonText = "Программа «$friendly» заблокирована родителем.\nВведите PIN-код родителя, чтобы открыть программу."
          )
        }
      }
    } else {
      blockedPackageNames = blockedPackageNames - cleanPkg
      rebuildBlockedLabelsIndex()
      dirtyRecentsBlockedPackages.remove(cleanPkg)
      temporaryPinUnlockedPackages.remove(cleanPkg)
      if (currentBlockedOverlayPackage == cleanPkg) {
        currentBlockedOverlayPackage = null
      }
      serviceScope.launch(Dispatchers.Main) {
        if (!isDeviceLocked && !isOverlayForTotalDeviceLock) {
          hideLockOverlay()
          DeviceTotalLockManager.activeLockActivityCloser?.invoke()
        }
      }
    }
  }

  fun unblockPackageLocallyByPin(packageName: String) {
    val cleanPkg = packageName.trim()
    if (cleanPkg.isNotBlank()) {
      grantTemporaryPinUnlockForPackage(cleanPkg)
    }
    if (currentBlockedOverlayPackage == cleanPkg) {
      currentBlockedOverlayPackage = null
    }
    serviceScope.launch(Dispatchers.Main) {
      if (!isDeviceLocked) {
        hideLockOverlay()
        DeviceTotalLockManager.activeLockActivityCloser?.invoke()
      }
    }
  }

  fun prepareForSilentUpdate() {
    serviceScope.launch(Dispatchers.Main) {
      hideLockOverlay()
      startOtaAutoClickWatcher(15_000L)
    }
  }

  /**
   * Запускает системное окно подтверждения установки PackageInstaller напрямую из привилегированного
   * контекста активной AccessibilityService (в обход ограничения Android 10–15 Background Activity Launch,
   * которое блокирует запуск Activity из BroadcastReceiver при закрытом приложении KidsGuard)
   * и включает 15-секундный активный автокликер кнопки «Обновить» / «Установить».
   */
  fun launchInstallerIntentFromAccessibility(installIntent: Intent): Boolean {
    return try {
      ApkUpdateInstaller.isInstallingUpdate = true
      serviceScope.launch(Dispatchers.Main) {
        hideLockOverlay()
        DeviceTotalLockManager.activeLockActivityCloser?.invoke()
      }
      installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
      startActivity(installIntent)
      startOtaAutoClickWatcher(15_000L)
      Log.i(TAG, "Launched PackageInstaller confirmation directly from AccessibilityService context")
      true
    } catch (e: Exception) {
      Log.e(TAG, "launchInstallerIntentFromAccessibility failed: ${e.message}", e)
      false
    }
  }

  fun startOtaAutoClickWatcher(durationMs: Long = 15_000L) {
    otaAutoClickWatcherJob?.cancel()
    otaAutoClickWatcherJob = serviceScope.launch(Dispatchers.Main) {
      val deadline = System.currentTimeMillis() + durationMs
      while (isActive && ApkUpdateInstaller.isInstallingUpdate && System.currentTimeMillis() < deadline) {
        hideLockOverlay()
        try {
          autoConfirmPackageInstallerUpdate(rootInActiveWindow)
          for (win in windows) {
            val wRoot = try { win.root } catch (_: Exception) { null } ?: continue
            val wPkg = wRoot.packageName?.toString().orEmpty()
            if (wPkg.contains("packageinstaller", ignoreCase = true) ||
                wPkg.contains("securitycenter", ignoreCase = true) ||
                wPkg.contains("apkinstaller", ignoreCase = true)) {
              autoConfirmPackageInstallerUpdate(wRoot)
            }
          }
        } catch (_: Exception) {}
        delay(220L)
      }
    }
  }

  private fun ejectBlockedAppToHome(blockedPackage: String? = null) {
    val nowMs = System.currentTimeMillis()
    if (!blockedPackage.isNullOrBlank()) {
      killBlockedPackageCompletely(applicationContext, blockedPackage)
    }
    // Защита от мигания и цикла переходов окон после рестарта (особенно для Google Play com.android.vending):
    // отправляем одиночное действие GLOBAL_ACTION_HOME с дебаунсом 550 мс, без одновременного спама
    // BACK + HOME + startActivity(CATEGORY_HOME) + RECENTS, который вызывал мерцание экрана!
    if ((nowMs - lastBlockedAppEjectMs) < 550L) return
    lastBlockedAppEjectMs = nowMs
    try {
      val movedHome = performGlobalAction(GLOBAL_ACTION_HOME)
      if (!movedHome) {
        val homeIntent = Intent(Intent.ACTION_MAIN).apply {
          addCategory(Intent.CATEGORY_HOME)
          flags = Intent.FLAG_ACTIVITY_NEW_TASK or
            Intent.FLAG_ACTIVITY_CLEAR_TOP or
            Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
            Intent.FLAG_ACTIVITY_NO_ANIMATION
        }
        startActivity(homeIntent)
      }
    } catch (_: Exception) {}
  }

  /**
   * Проверяет, соответствует ли текст или описание узла точному заголовку заблокированной программы
   * (без опасного substring-поиска contains(), который мог зацепить соседние разрешённые программы или весь список карусели).
   */
  private fun isExactBlockedRecentsCardLabel(rawCandidate: String, blockedLabel: String): Boolean {
    if (rawCandidate.isBlank() || blockedLabel.length < 2) return false
    if (rawCandidate == blockedLabel) return true
    // Оболочки Android (OneUI / MIUI / Pixel Launcher) часто формируют contentDescription карточки
    // в формате: "TikTok, недавнее приложение" или "Google Play, закрыть"
    if (rawCandidate.startsWith("$blockedLabel,") ||
        rawCandidate.startsWith("$blockedLabel ") ||
        rawCandidate.startsWith("$blockedLabel.") ||
        rawCandidate.startsWith("$blockedLabel:")) {
      return true
    }
    return false
  }

  /**
   * Мгновенно (0 мс) перекрывает экран непрозрачным PIN-шлюзом ПЕРЕД сворачиванием,
   * чтобы запрещённая программа не показывала даже свой логотип на 1 секунду,
   * и без единого мигания переводит телефон на главный экран под оверлеем.
   */
  private fun interceptAndGateBlockedApp(packageName: String, reasonText: String) {
    val cleanPkg = packageName.trim()
    // 1. ПЕРВЫМ ДЕЛОМ (0 мс) синхронно накрываем экран непрозрачным системным оверлеем блокировки!
    showLockOverlay(
      reasonText = reasonText,
      isDeviceTotalLock = false,
      blockedPackageName = cleanPkg.ifBlank { null }
    )
    // 2. Останавливаем процесс и мягко сворачиваем программу на рабочий стол под непрозрачным оверлеем (без вызова карусели!)
    ejectBlockedAppToHome(cleanPkg.ifBlank { null })
    if (cleanPkg.isNotBlank() && !Settings.canDrawOverlays(applicationContext)) {
      com.example.ui.screens.ChildLockScreenActivity.launch(
        context = this,
        reason = reasonText,
        isDeviceTotalLock = false,
        blockedPackageName = cleanPkg
      )
    }
  }

  fun enforceTotalLockFromService(reason: String) {
    val wasAlreadyShowingTotal = overlayView != null && isOverlayForTotalDeviceLock
    isDeviceLocked = true
    serviceScope.launch(Dispatchers.Main) {
      if (!wasAlreadyShowingTotal) {
        ejectBlockedAppToHome(null)
      }
      showLockOverlay(reason, isDeviceTotalLock = true, blockedPackageName = null)
    }
  }

  fun enforceAppOrLimitBlockFromService(reason: String, blockedPackage: String? = null) {
    serviceScope.launch(Dispatchers.Main) {
      interceptAndGateBlockedApp(
        packageName = blockedPackage.orEmpty(),
        reasonText = reason
      )
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
    // Программно фиксируем флаги AccessibilityService после рестарта телефона:
    // мгновенный перехват окон (FLAG_RETRIEVE_INTERACTIVE_WINDOWS) БЕЗ спама FLAG_INCLUDE_NOT_IMPORTANT_VIEWS!
    try {
      val info = serviceInfo
      if (info != null) {
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
          AccessibilityEvent.TYPE_WINDOWS_CHANGED or
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.flags = (info.flags or
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS) and
          android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS.inv()
        info.notificationTimeout = 90L
        serviceInfo = info
      }
    } catch (_: Exception) {}

    if (!com.example.location.LiveLocationTracker.isEmulator()) {
      val bootBlocked = com.example.util.BootPrefs.getBlockedPackages(applicationContext)
      if (bootBlocked.isNotEmpty()) {
        blockedPackageNames = blockedPackageNames + bootBlocked
        rebuildBlockedLabelsIndex()
      }
      val bootCfg = com.example.util.BootPrefs.load(applicationContext)
      if (bootCfg.permanentHardwareRole == "CHILD" ||
          bootCfg.role == "CHILD" ||
          (bootCfg.permanentHardwareRole != "PARENT" && (bootBlocked.isNotEmpty() || bootCfg.pairingCode.length >= 6))) {
        isChildDeviceRole = true
        activeChildId = bootCfg.childId.takeIf { it > 0L } ?: 1L
        com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
        try {
          com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", activeChildId)
          com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()
        } catch (_: Exception) {}
      }

      serviceScope.launch(Dispatchers.IO) {
        try {
          com.example.util.CoreEngineHotUpdater.initializeHotCore(applicationContext)
        } catch (_: Exception) {}
        if (isChildDeviceRole && activeChildId > 0L) {
          try {
            com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
          } catch (_: Exception) {}
        }
        // Ждём разблокировки экрана после перезагрузки телефона (Direct Boot), чтобы Room не упал с исключением
        while (isActive && !isDeviceStorageUnlocked()) {
          delay(1_500L)
        }
        try {
          val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
          val cfg = dao.getFamilyConfigOnce()
          val allChildren = dao.getAllChildrenOnce()
          val pairedChild = allChildren.firstOrNull { it.isPaired && it.pairingCode.trim().length >= 6 }
          val isChild = cfg?.selectedRole == "CHILD" ||
            bootCfg.permanentHardwareRole == "CHILD" ||
            bootCfg.role == "CHILD" ||
            (cfg?.selectedRole != "PARENT" && bootCfg.permanentHardwareRole != "PARENT" && pairedChild != null)
          if (isChild) {
            isChildDeviceRole = true
            val cid = cfg?.thisDeviceChildId?.takeIf { it > 0 }
              ?: bootCfg.childId.takeIf { it > 0 }
              ?: pairedChild?.id
              ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
              ?: allChildren.firstOrNull()?.id
              ?: 1L
            activeChildId = cid

            val childProfile = allChildren.firstOrNull { it.id == cid && it.pairingCode.trim().length >= 6 }
              ?: pairedChild
              ?: dao.getChildByIdOnce(cid)
              ?: allChildren.firstOrNull()
            val resolvedCode = childProfile?.pairingCode?.trim()?.takeIf { it.length >= 6 }
              ?: com.example.util.BootPrefs.getPairingCode(applicationContext)
            if (resolvedCode.length >= 6) {
              com.example.util.BootPrefs.savePairingCode(applicationContext, resolvedCode)
            }
            com.example.util.BootPrefs.save(
              context = applicationContext,
              role = "CHILD",
              childId = cid,
              allowMobileData = childProfile?.allowMobileData ?: true,
              familyId = cfg?.familyId ?: "family_default",
              permanentHardwareRole = "CHILD",
              masterPin = cfg?.parentMasterPin,
              pairingCode = resolvedCode
            )
            if (childProfile != null) {
              com.example.data.cloud.RealtimeCloudRelay.replaceCachedChildProfile(
                if (resolvedCode.length >= 6 && childProfile.pairingCode != resolvedCode) {
                  childProfile.copy(pairingCode = resolvedCode, isPaired = true)
                } else {
                  childProfile
                }
              )
            }

            com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
            com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", cid)
            com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()

            val apkVer = com.example.util.AppVersionInfo.installedApkVersion(applicationContext)
            dao.updateChildAppVersion(cid, apkVer)
            try {
              CloudSyncManager(applicationContext).syncChildAppVersion(cid, apkVer)
            } catch (_: Exception) {}
            KidsGuardBackgroundService.start(applicationContext, cid)

            // Мгновенно отправляем PONG со статусом всех разрешений и 1 снимок экрана (если экран включён),
            // чтобы родитель сразу видел картинку экрана после перезагрузки или обновления!
            com.example.data.cloud.RealtimeCloudRelay.publishChildPong(cid)
            if (DeviceUsageTracker.isInteractive(applicationContext)) {
              delay(1_300L)
              triggerImmediateCaptureNow()
            }
          }
        } catch (e: Exception) {
          Log.e(TAG, "onServiceConnected DB init error: ${e.message}")
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
    // ЗАЩИТА NFC / GOOGLE PAY (PCI-DSS):
    // Никогда не вызываем takeScreenshot / MediaProjection и не читаем узлы окна, когда открыт Google Кошелёк или окно NFC-оплаты!
    if (DeviceUsageTracker.isNfcOrPaymentPackage(currentForegroundPackage)) return
    serviceScope.launch(Dispatchers.IO) {
      try {
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        val cfg = if (isDeviceStorageUnlocked()) {
          try {
            KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao().getFamilyConfigOnce()
          } catch (_: Exception) {
            null
          }
        } else null
        if (!isChildDeviceRole && cfg?.selectedRole != "CHILD" && bootCfg.permanentHardwareRole != "CHILD" && bootCfg.role != "CHILD") return@launch
        val cid = cfg?.thisDeviceChildId?.takeIf { it > 0 }
          ?: bootCfg.childId.takeIf { it > 0 }
          ?: activeChildId.takeIf { it > 0 }
          ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
          ?: 1L
        activeChildId = cid
        if (DeviceUsageTracker.isNfcOrPaymentPackage(currentForegroundPackage)) return@launch
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
        val persistOffline = !isLiveStreamingActiveNow() && isDeviceStorageUnlocked()
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
          val frameDelay = if (ScreenShotManager.isMediaProjectionDeliveringFreshFrames()) 900L else 1_550L
          delay(frameDelay)
        } else {
          // Экономия батареи: когда прямой эфир выключен, спим 4.5 секунды без нагрузки на процессор
          delay(4_500L)
        }
      }
    }

    // ПРИМЕЧАНИЕ ПО ЭНЕРГОСБЕРЕЖЕНИЮ:
    // Опросные циклы (180 мс / 350 мс) на Dispatchers.Main с вызовом rootInActiveWindow и windows
    // полностью удалены! Перехват всех запрещённых программ и карусели Недавних приложений работает
    // строго событийно (0 мс) в onAccessibilityEvent из оперативной памяти (BootPrefs.cachedBlockedSet),
    // благодаря чему телефон не греется и не расходует заряд батареи в фоне.

    // 2. Minute Usage Ticking + Offline Store-and-Forward Recording & Flushing
    serviceScope.launch(Dispatchers.IO) {
      while (isActive && !isDeviceStorageUnlocked()) {
        delay(2_000L)
      }
      val database = KidsGuardDatabase.getDatabase(applicationContext)
      val dao = database.kidsGuardDao()
      var secondsInForeground = 0

      while (isActive) {
        if (!isChildDeviceRole) {
          delay(30_000L)
          val bootCfg = com.example.util.BootPrefs.load(applicationContext)
          val cfgCheck = try { dao.getFamilyConfigOnce() } catch (_: Exception) { null }
          isChildDeviceRole = cfgCheck?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD" || bootCfg.role == "CHILD"
          continue
        }
        val isScreenOn = DeviceUsageTracker.isInteractive(applicationContext)
        delay(if (isScreenOn) 30_000L else 60_000L)
        if (!isScreenOn || com.example.location.LiveLocationTracker.isEmulator()) continue
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        val cfg = try { dao.getFamilyConfigOnce() } catch (_: Exception) { null }
        isChildDeviceRole = cfg?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD" || bootCfg.role == "CHILD"
        if (!isChildDeviceRole) continue

        if (activeChildId <= 0) {
          activeChildId = when {
            cfg != null && cfg.thisDeviceChildId > 0 -> cfg.thisDeviceChildId
            bootCfg.childId > 0 -> bootCfg.childId
            cfg != null && cfg.currentActiveChildId > 0 -> cfg.currentActiveChildId
            else -> try { dao.getAllChildrenOnce().firstOrNull()?.id ?: 1L } catch (_: Exception) { 1L }
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
          DeviceUsageTracker.isSystemShellOrNoiseEntry(pkg, currentForegroundAppLabel)

        if (!isLauncherOrSystem && pkg != null && !isDeviceLocked && activeChildId > 0) {
          secondsInForeground += 30
          com.example.data.cloud.OfflineStoreAndForwardManager.recordChildActivityOffline(
            context = applicationContext,
            childId = activeChildId,
            packageName = pkg,
            appName = currentForegroundAppLabel,
            windowTitle = "",
            durationSeconds = 30,
            deviceType = "PHONE"
          )

          if (secondsInForeground >= 60) {
            secondsInForeground = 0
            DeviceUsageTracker.recordAppSessionTime(applicationContext, activeChildId, pkg, 1)

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
              if (dailyLimit in 1..newUsed && !isPackageTemporarilyUnlockedByPin(pkg)) {
                launch(Dispatchers.Main) {
                  interceptAndGateBlockedApp(
                    packageName = pkg,
                    reasonText = "Лимит времени на сегодня ($dailyLimit мин) исчерпан!\nВведите PIN-код родителя, чтобы продолжить."
                  )
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

    // 0. Энергосберегающий Watchdog выживания + фоновый пульс онлайна БЕЗ захвата PARTIAL_WAKE_LOCK (32 с / 55 с):
    // Работает даже сразу после перезагрузки телефона (из BootPrefs), без открытия MainActivity!
    serviceScope.launch(Dispatchers.IO) {
      var lastWatchdogCheckMs = 0L
      var lastScreenOffStatusPushMs = 0L
      while (isActive) {
        val isScreenOn = DeviceUsageTracker.isInteractive(applicationContext)
        delay(if (isScreenOn) 32_000L else 55_000L)
        if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) continue
        val nowMs = System.currentTimeMillis()
        try {
          val bootCfg = com.example.util.BootPrefs.load(applicationContext)
          val cid = activeChildId.takeIf { it > 0 }
            ?: bootCfg.childId.takeIf { it > 0 }
            ?: (if (isDeviceStorageUnlocked()) {
              try {
                KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao().getFamilyConfigOnce()?.thisDeviceChildId?.takeIf { it > 0 }
              } catch (_: Exception) { null }
            } else null)
            ?: 1L
          activeChildId = cid
          // Гарантируем, что движок связи запущен даже без открытия окна приложения KidsGuard
          com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
          com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", cid)
          // Поддерживаем P2P/MQTT сокеты и шлём лёгкий пульс онлайна + батарею + статус 8 разрешений родителю
          com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()
          com.example.data.cloud.RealtimeCloudRelay.publishChildPong(cid)

          // При выключенном экране обновляем заряд батареи не чаще раза в 3 минуты без лишней записи в SQLite
          if (!isScreenOn && (nowMs - lastScreenOffStatusPushMs) >= 180_000L) {
            lastScreenOffStatusPushMs = nowMs
            val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(applicationContext)
            if (batt in 1..100) {
              cloudSync.upsertBatteryLevel(cid, batt)
            }
          }

          if ((nowMs - lastWatchdogCheckMs) >= 45_000L) {
            lastWatchdogCheckMs = nowMs
            if (!com.example.service.KidsGuardBackgroundService.isRunning) {
              com.example.service.KidsGuardBackgroundService.start(applicationContext, cid)
              Log.w(TAG, "Watchdog: KidsGuardBackgroundService был убит — перезапущен")
            }
          }
        } catch (_: Exception) {}
      }
    }

    // 1. Observe Active Child and Phone Lock status reactively (Direct-Boot-safe with retry!)
    serviceScope.launch(Dispatchers.IO) {
      while (isActive && !isDeviceStorageUnlocked()) {
        delay(1_500L)
      }
      while (isActive) {
        try {
          val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
          dao.getFamilyConfig().collectLatest { config ->
            val bootCfg = com.example.util.BootPrefs.load(applicationContext)
            isChildDeviceRole = config?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD" || bootCfg.role == "CHILD"
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
              if (config != null && config.selectedRole == "CHILD") {
                com.example.util.BootPrefs.save(applicationContext, "CHILD", childId)
              }
              dao.getChildById(activeChildId).collectLatest { child ->
                child?.let {
                  if (it.pairingCode.length >= 6) {
                    com.example.util.BootPrefs.savePairingCode(applicationContext, it.pairingCode)
                    com.example.data.cloud.RealtimeCloudRelay.replaceCachedChildProfile(it)
                  }
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
        } catch (e: Exception) {
          Log.w(TAG, "FamilyConfig observer retry: ${e.message}")
          delay(3_000L)
        }
      }
    }

    // 2. Observe Blocked Apps AND Daily Time Limits across ALL local rows + BootPrefs (Direct-Boot-safe!)
    serviceScope.launch(Dispatchers.IO) {
      while (isActive && !isDeviceStorageUnlocked()) {
        delay(1_500L)
      }
      while (isActive) {
        try {
          val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
          dao.getAllApps().collectLatest { allApps ->
            val bootBlocked = com.example.util.BootPrefs.getBlockedPackages(applicationContext)
              .map { it.trim() }
              .filter { it.isNotBlank() && !DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, it) }
              .toSet()
            val dbBlocked = allApps
              .filter { it.isBlocked && !DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, it.packageName) }
              .map { it.packageName.trim() }
              .filter { it.isNotBlank() }
              .toSet()
            for (pkg in dbBlocked) {
              com.example.util.BootPrefs.saveBlockedPackage(applicationContext, pkg, true)
            }
            // Если в BootPrefs программа заблокирована, а после рестарта/обновления в Room она сбросилась в false — восстанавливаем в Room!
            for (pkg in bootBlocked) {
              if (pkg !in dbBlocked) {
                dao.setAppBlockedByPackageGlobal(pkg, true)
              }
            }
            val blocked = bootBlocked + dbBlocked
            blockedPackageNames = blocked
            rebuildBlockedLabelsIndex()

            val bootLimits = com.example.util.BootPrefs.getAppLimitsMap(applicationContext)
            val childApps = if (activeChildId > 0) allApps.filter { it.childId == activeChildId }.ifEmpty { allApps } else allApps
            val limits = mutableMapOf<String, Pair<Int, Int>>()
            for (a in childApps) {
              val effectiveLimit = if (a.dailyLimitMinutes > 0) a.dailyLimitMinutes else (bootLimits[a.packageName] ?: 0)
              if (effectiveLimit > 0 && !DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, a.packageName)) {
                limits[a.packageName] = Pair(effectiveLimit, a.usedMinutesToday)
                if (a.dailyLimitMinutes != effectiveLimit) {
                  dao.setAppLimitByPackageGlobal(a.packageName, effectiveLimit)
                }
              }
            }
            appLimitsMap = limits
            Log.d(TAG, "Loaded rules for child $activeChildId: blocked=${blocked.size}, withLimits=${limits.size}")

            val activePkg = currentForegroundPackage
            val rootPkg = try { rootInActiveWindow?.packageName?.toString()?.trim() } catch (_: Exception) { null }
            val targetBlockedNow = listOfNotNull(activePkg, rootPkg).firstOrNull {
              (blocked.contains(it) || com.example.util.BootPrefs.isPackageBlocked(applicationContext, it)) &&
                !isPackageTemporarilyUnlockedByPin(it)
            }
            if (isChildDeviceRole && !isDeviceLocked && !targetBlockedNow.isNullOrBlank()) {
              val friendly = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, targetBlockedNow)
              launch(Dispatchers.Main) {
                interceptAndGateBlockedApp(
                  packageName = targetBlockedNow,
                  reasonText = "Программа «$friendly» заблокирована родителем.\nВведите PIN-код родителя, чтобы открыть программу."
                )
              }
            } else if (!isDeviceLocked && !isOverlayForTotalDeviceLock && currentBlockedOverlayPackage != null) {
              val overlayPkg = currentBlockedOverlayPackage
              if (overlayPkg != null && !blocked.contains(overlayPkg) && !com.example.util.BootPrefs.isPackageBlocked(applicationContext, overlayPkg)) {
                launch(Dispatchers.Main) {
                  hideLockOverlay()
                  DeviceTotalLockManager.activeLockActivityCloser?.invoke()
                }
              }
            }
          }
        } catch (e: Exception) {
          Log.w(TAG, "BlockedApps observer retry: ${e.message}")
          delay(3_000L)
        }
      }
    }

    // 2B. Observe Web Filter / Domain Blacklist & Category Rules (Direct-Boot-safe!)
    serviceScope.launch(Dispatchers.IO) {
      while (isActive && !isDeviceStorageUnlocked()) {
        delay(2_000L)
      }
      while (isActive) {
        try {
          val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
          dao.getAllContentRules().collectLatest { rules ->
            val bootDomains = com.example.util.BootPrefs.getBlockedDomains(applicationContext)
            val dbDomains = rules.filter { it.ruleType == "DOMAIN" && it.isBlocked }
              .map { com.example.util.BootPrefs.normalizeDomain(it.value) }
              .filter { it.isNotBlank() && it.contains(".") }
              .toSet()
            for (d in dbDomains) {
              com.example.util.BootPrefs.saveBlockedDomain(applicationContext, d, true)
            }
            blockedDomainsList = (bootDomains + dbDomains).toList()
            blockedKeywordsList = rules.filter { it.ruleType == "KEYWORD" && it.isBlocked }
              .map { it.value.trim().lowercase() }
              .filter { it.isNotBlank() }
            blockedCategoriesSet = rules.filter { it.ruleType == "CATEGORY" && it.isBlocked }
              .map { it.value.uppercase() }
              .toSet()
          }
        } catch (_: Exception) {
          delay(5_000L)
        }
      }
    }

    // 3. DIRECT CLOUD COMMAND LISTENER (ONLY executes on CHILD device!)
    serviceScope.launch(Dispatchers.IO) {
      while (isActive) {
        if (isChildDeviceRole && activeChildId > 0) {
          try {
            cloudSync.observeRemoteCommands(activeChildId).collectLatest { cmdPair ->
              val bootCfg = com.example.util.BootPrefs.load(applicationContext)
              val dao = if (isDeviceStorageUnlocked()) {
                try { KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao() } catch (_: Exception) { null }
              } else null
              val latestCfg = try { dao?.getFamilyConfigOnce() } catch (_: Exception) { null }
              isChildDeviceRole = latestCfg?.selectedRole == "CHILD" || bootCfg.permanentHardwareRole == "CHILD" || bootCfg.role == "CHILD"
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
                      com.example.util.BootPrefs.saveMasterPin(applicationContext, cleanPin)
                      dao?.updateMasterPin(cleanPin)
                      dao?.updateChildUninstallPin(cleanPin)
                    }
                  }
                  "SET_PAIRING_CODE" -> {
                    val newCode = payload.trim()
                    if (newCode.length >= 6) {
                      com.example.util.BootPrefs.savePairingCode(applicationContext, newCode)
                      serviceScope.launch(Dispatchers.IO) {
                        val existing = dao?.getChildByIdOnce(activeChildId) ?: dao?.getAllChildrenOnce()?.firstOrNull()
                        if (existing != null && dao != null) {
                          val updated = existing.copy(pairingCode = newCode, isPaired = true)
                          dao.updateChild(updated)
                          com.example.data.cloud.RealtimeCloudRelay.replaceCachedChildProfile(updated)
                          com.example.data.cloud.RealtimeCloudRelay.publishChildProfile(updated, true)
                          com.example.data.cloud.RealtimeCloudRelay.publishChildPong(updated.id)
                        } else {
                          com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
                        }
                      }
                    }
                  }
                  "LOCK" -> {
                    isDeviceLocked = true
                    com.example.data.cloud.RealtimeCloudRelay.setLocalAuthoritativeLock(activeChildId, true)
                    dao?.setChildDeviceLocked(activeChildId, true)
                    launch(Dispatchers.Main) {
                      enforceTotalLockFromService("Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                    }
                  }
                  "UNLOCK" -> {
                    isDeviceLocked = false
                    com.example.data.cloud.RealtimeCloudRelay.setLocalAuthoritativeLock(activeChildId, false)
                    dao?.setChildDeviceLocked(activeChildId, false)
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
                      dao?.updateChildBattery(activeChildId, batt)
                      cloudSync.upsertBatteryLevel(activeChildId, batt)
                      if (com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
                        val loc = com.example.location.LiveLocationTracker.awaitHighAccuracyLocation(applicationContext, 4_000L)
                          ?: com.example.location.LiveLocationTracker.getBestImmediateLocation(applicationContext)
                        loc?.let {
                          val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(applicationContext, it.latitude, it.longitude)
                          dao?.updateChildLocation(activeChildId, it.latitude, it.longitude, addr, System.currentTimeMillis())
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
                      dao?.setChildGpsTrackingEnabled(activeChildId, true)
                    }
                  }
                  "GPS_TRACKING_OFF" -> {
                    com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                      activeChildId,
                      gpsTrackingEnabled = false
                    )
                    com.example.location.LiveLocationTracker.setGpsTrackingAllowed(false)
                    serviceScope.launch(Dispatchers.IO) {
                      dao?.setChildGpsTrackingEnabled(activeChildId, false)
                    }
                  }
                  "BLOCK_APP" -> {
                    val pkg = payload.trim()
                    if (pkg.isNotBlank()) {
                      setPackageBlockedImmediate(pkg, true)
                      serviceScope.launch(Dispatchers.IO) {
                        if (dao != null) {
                          dao.setAppBlockedByPackageGlobal(pkg, true)
                          val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                          if (existing == null) {
                            val label = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, pkg)
                            dao.insertApp(
                              com.example.data.model.AppControlEntity(
                                childId = activeChildId,
                                packageName = pkg,
                                appName = label,
                                iconType = "generic",
                                category = "Приложения",
                                dailyLimitMinutes = 0,
                                usedMinutesToday = 0,
                                isBlocked = true
                              )
                            )
                          }
                          cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                        }
                      }
                    }
                  }
                  "UNBLOCK_APP" -> {
                    val pkg = payload.trim()
                    if (pkg.isNotBlank()) {
                      setPackageBlockedImmediate(pkg, false)
                      serviceScope.launch(Dispatchers.IO) {
                        if (dao != null) {
                          dao.setAppBlockedByPackageGlobal(pkg, false)
                          cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                        }
                      }
                    }
                  }
                  "BLOCK_DOMAIN" -> {
                    val cleanDomain = com.example.util.BootPrefs.normalizeDomain(payload)
                    if (cleanDomain.isNotBlank() && cleanDomain.contains(".")) {
                      setDomainBlockedImmediate(cleanDomain, true)
                      serviceScope.launch(Dispatchers.IO) {
                        if (dao != null) {
                          val existing = dao.getAllContentRulesOnce()
                            .firstOrNull { it.ruleType == "DOMAIN" && com.example.util.BootPrefs.normalizeDomain(it.value) == cleanDomain }
                          if (existing == null) {
                            dao.insertContentRule(
                              com.example.data.model.ContentRuleEntity(
                                ruleType = "DOMAIN",
                                value = cleanDomain,
                                displayName = "🌐 $cleanDomain",
                                isBlocked = true
                              )
                            )
                          } else if (!existing.isBlocked) {
                            dao.updateContentRule(existing.copy(isBlocked = true))
                          }
                          cloudSync.syncContentRulesToCloud(dao.getAllContentRulesOnce())
                        }
                      }
                    }
                  }
                  "UNBLOCK_DOMAIN" -> {
                    val cleanDomain = com.example.util.BootPrefs.normalizeDomain(payload)
                    if (cleanDomain.isNotBlank()) {
                      setDomainBlockedImmediate(cleanDomain, false)
                      serviceScope.launch(Dispatchers.IO) {
                        if (dao != null) {
                          val matches = dao.getAllContentRulesOnce()
                            .filter { it.ruleType == "DOMAIN" && com.example.util.BootPrefs.normalizeDomain(it.value) == cleanDomain }
                          for (rule in matches) {
                            dao.deleteContentRule(rule)
                          }
                          cloudSync.syncContentRulesToCloud(dao.getAllContentRulesOnce())
                        }
                      }
                    }
                  }
                  "LIMIT_APP", "SET_APP_LIMIT" -> {
                    val delim = if (payload.contains("|")) "|" else ":"
                    val pkg = payload.substringBefore(delim).trim()
                    val limitMin = payload.substringAfter(delim, "0").trim().toIntOrNull() ?: 0
                    if (pkg.isNotBlank()) {
                      com.example.util.BootPrefs.saveAppLimit(applicationContext, pkg, limitMin)
                      serviceScope.launch(Dispatchers.IO) {
                        if (dao != null) {
                          dao.setAppLimitByPackageGlobal(pkg, limitMin)
                          cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                        }
                      }
                    }
                  }
                  "REFRESH_APPS" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      if (dao != null) {
                        DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
                        val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(applicationContext, activeChildId)
                        if (realApps.isNotEmpty()) {
                          val bootBlockedSet = com.example.util.BootPrefs.getBlockedPackages(applicationContext)
                            .map { it.lowercase() }
                            .toSet()
                          val bootLimits = com.example.util.BootPrefs.getAppLimitsMap(applicationContext)
                          val allDbApps = dao.getAllAppsOnce()
                          val prevApps = dao.getAppsForChildOnce(activeChildId).associateBy { it.packageName.trim().lowercase() }
                          val anyDbBlocked = allDbApps.filter { it.isBlocked }.map { it.packageName.trim().lowercase() }.toSet()
                          val merged = realApps.map { app ->
                            val key = app.packageName.trim().lowercase()
                            val prev = prevApps[key]
                            val mustBeBlocked = (prev?.isBlocked == true) || (key in bootBlockedSet) || (key in anyDbBlocked)
                            if (mustBeBlocked) {
                              com.example.util.BootPrefs.saveBlockedPackage(applicationContext, app.packageName, true)
                            }
                            val effectiveLimit = if ((prev?.dailyLimitMinutes ?: 0) > 0) {
                              prev!!.dailyLimitMinutes
                            } else {
                              bootLimits[app.packageName] ?: 0
                            }
                            if (prev != null) {
                              app.copy(
                                isBlocked = mustBeBlocked,
                                dailyLimitMinutes = effectiveLimit,
                                usedMinutesToday = prev.usedMinutesToday
                              )
                            } else {
                              app.copy(
                                isBlocked = mustBeBlocked,
                                dailyLimitMinutes = effectiveLimit
                              )
                            }
                          }
                          dao.replaceChildAppsStrictlyFromRemote(activeChildId, merged)
                          cloudSync.uploadChildApps(activeChildId, merged)
                        }
                        DeviceUsageTracker.syncRealUsageStats(applicationContext, activeChildId)
                      }
                    }
                  }
                  "STOP_LOUD_RING" -> {
                    com.example.util.SoundAlertManager.stopAlarm(applicationContext)
                  }
                  "PING", "PARENT_SESSION_START", "PARENT_ONLINE_HEARTBEAT" -> {
                    com.example.data.cloud.DirectP2pSocketEngine.setParentSessionActive(true)
                    com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                    serviceScope.launch(Dispatchers.IO) {
                      val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(applicationContext)
                      if (batt in 1..100) {
                        dao?.updateChildBattery(activeChildId, batt)
                        cloudSync.upsertBatteryLevel(activeChildId, batt)
                      }
                      com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
                      val isScreenInteractive = DeviceUsageTracker.isInteractive(applicationContext)
                      val nowPingMs = System.currentTimeMillis()
                      if (isScreenInteractive) {
                        if (cmd == "PARENT_SESSION_START" || (nowPingMs - lastPingCaptureA11yMs >= 8_000L)) {
                          lastPingCaptureA11yMs = nowPingMs
                          triggerImmediateCaptureNow()
                        }
                      } else {
                        val isCharging = com.example.util.AppEnergyMeter.isCharging(applicationContext)
                        val offLabel = if (isCharging) {
                          "Экран выключен (на зарядке • ${if (batt in 1..100) "$batt%" else "зарядка"})"
                        } else {
                          "Экран выключен (телефон заблокирован)"
                        }
                        cloudSync.updateActiveAppOnly(activeChildId, offLabel)
                      }
                    }
                  }
                  "CONFIGURE_VPS" -> {
                    try {
                      val j = org.json.JSONObject(payload)
                      com.example.data.cloud.DirectP2pSocketEngine.configureVpsRelay(
                        enabled = j.optBoolean("enabled", false),
                        host = j.optString("host", ""),
                        port = j.optInt("port", 443),
                        password = j.optString("password", "1234"),
                        roomCode = j.optString("roomCode", "101-701")
                      )
                    } catch (_: Exception) {}
                  }
                  "START_LIVE_STREAM" -> {
                    activateLiveStreamingWindow(90_000L)
                    triggerImmediateCaptureNow()
                  }
                  "TAKE_SCREENSHOT" -> {
                    // Одиночный снимок экрана НЕ включает 90-секундный цикл видеопотока (экономия батареи!)
                    triggerImmediateCaptureNow()
                  }
                  "QUERY_ACTIVE_APP" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      val baseApp = currentForegroundAppLabel.ifBlank {
                        DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                      }
                      CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, baseApp)
                    }
                    triggerImmediateCaptureNow()
                  }
                  "STOP_LIVE_STREAM" -> {
                    stopLiveStreamingWindow()
                  }
                  "UPDATE_APP", "APP_UPDATE" -> {
                    serviceScope.launch(Dispatchers.IO) {
                      cloudSync.clearRemoteCommand(activeChildId)
                      dao?.setChildUpdatePending(activeChildId, true)
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

  /**
   * Смахивает ТОЛЬКО карточку запрещённой программы в карусели Недавних окон (Recents / Overview),
   * оставляя саму карусель открытой, чтобы ребёнок мог свободно закрывать другие приложения и чистить память.
   */
  private fun dismissSingleRecentsBlockedCard(matchedNode: AccessibilityNodeInfo, blockedPkg: String) {
    val nowMs = System.currentTimeMillis()
    // 1. Сначала пробуем штатное действие ACTION_DISMISS (API 21+) на самой карточке или её контейнере TaskView
    try {
      var cur: AccessibilityNodeInfo? = matchedNode
      var depth = 0
      while (cur != null && depth < 6) {
        val actions = cur.actionList
        if (actions != null && actions.any { it.id == AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id }) {
          if (cur.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_DISMISS.id)) {
            Log.d(TAG, "Dismissed Recents card for $blockedPkg via ACTION_DISMISS")
            return
          }
        }
        cur = cur.parent
        depth++
      }
    } catch (_: Exception) {}

    // 2. Если оболочка (OneUI / MIUI / HyperOS / Quickstep) не поддерживает ACTION_DISMISS на карточке —
    // находим границы (Rect) именно этой карточки TaskView и смахиваем ТОЛЬКО её быстрым жестом dispatchGesture!
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && (nowMs - lastRecentsSwipeMs) >= 950L) {
      lastRecentsSwipeMs = nowMs
      try {
        val dm = resources.displayMetrics
        val sw = dm.widthPixels.coerceAtLeast(720)
        val sh = dm.heightPixels.coerceAtLeast(1280)

        var bestRect = android.graphics.Rect()
        matchedNode.getBoundsInScreen(bestRect)

        var cur: AccessibilityNodeInfo? = matchedNode
        var depth = 0
        while (cur != null && depth < 6) {
          val r = android.graphics.Rect()
          cur.getBoundsInScreen(r)
          val w = r.width()
          val h = r.height()
          // Карточка TaskView занимает заметную часть экрана, но не весь экран карусели RecentsView
          if (w in (sw * 18 / 100)..(sw * 95 / 100) && h in (sh * 12 / 100)..(sh * 85 / 100)) {
            bestRect = r
          }
          val cls = cur.className?.toString()?.lowercase().orEmpty()
          val vid = cur.viewIdResourceName?.lowercase().orEmpty()
          if (cls.contains("taskview") || vid.contains("task_view") || vid.contains("snapshot")) {
            if (w > 100 && h > 100) {
              bestRect = r
              break
            }
          }
          cur = cur.parent
          depth++
        }

        val path = android.graphics.Path()
        // Если карточка узкая (< 52% ширины экрана — вертикальная сетка из 2 колонок в MIUI/HyperOS),
        // смахиваем её горизонтально к ближайшему краю экрана.
        // Иначе (стандартная горизонтальная карусель карточек) — смахиваем карточку строго ВВЕРХ!
        if (bestRect.width() in 100..(sw * 52 / 100)) {
          val startX = bestRect.centerX().coerceIn(80, sw - 80).toFloat()
          val startY = bestRect.centerY().coerceIn(150, sh - 150).toFloat()
          val endX = if (bestRect.centerX() < sw / 2) 20f else (sw - 20f)
          path.moveTo(startX, startY)
          path.lineTo(endX, startY)
        } else {
          val startX = bestRect.centerX().takeIf { it in 80..(sw - 80) }?.toFloat() ?: (sw / 2f)
          val rawStartY = if (bestRect.height() > 150) {
            bestRect.centerY() + (bestRect.height() / 4)
          } else {
            (sh * 58 / 100)
          }
          val startY = rawStartY.coerceIn(sh * 25 / 100, sh * 82 / 100).toFloat()
          val endY = 50f
          path.moveTo(startX, startY)
          path.lineTo(startX, endY)
        }

        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0L, 120L)
        val gesture = android.accessibilityservice.GestureDescription.Builder().addStroke(stroke).build()
        dispatchGesture(gesture, null, null)
        Log.d(TAG, "Swiped away only blocked Recents card for $blockedPkg at bounds=$bestRect")
      } catch (e: Exception) {
        Log.e(TAG, "Recents single card swipe error: ${e.message}")
      }
    }
  }

  private fun isRecentsHostPackage(pkg: String): Boolean {
    val lowPkg = pkg.lowercase()
    return lowPkg.startsWith("com.android.systemui") ||
      lowPkg.contains("launcher") ||
      lowPkg == "com.miui.home" ||
      lowPkg.contains("quickstep") ||
      lowPkg.contains("trebuchet") ||
      lowPkg.contains("lawnchair") ||
      lowPkg.contains("globallauncher") ||
      lowPkg.contains("hilauncher") ||
      lowPkg.contains("xoslauncher")
  }

  fun isUniversalBrowserPackage(pkg: String): Boolean {
    val low = pkg.lowercase()
    if (low.isBlank()) return false
    return low.contains("chrome") ||
      low.contains("browser") ||
      low.contains("sbrowser") ||
      low.contains("firefox") ||
      low.contains("mozilla") ||
      low.contains("opera") ||
      low.contains("yandex") ||
      low.contains("brave") ||
      low.contains("emmx") ||
      low.contains("duckduckgo") ||
      low.contains("vivaldi") ||
      low.contains("ucmobile") ||
      low.contains("kiwibrowser")
  }

  /**
   * Энергосберегающий динамический фильтр событий AccessibilityService:
   * В обычных программах и играх отключает поток TYPE_WINDOW_CONTENT_CHANGED (оставляя мгновенный перехват окон
   * TYPE_WINDOW_STATE_CHANGED | TYPE_WINDOWS_CHANGED), благодаря чему телефон ребёнка НЕ тратит процессор и батарею!
   * Когда ребёнок открывает браузер, системные настройки или карусель Недавних — автоматически включает
   * TYPE_WINDOW_CONTENT_CHANGED для чтения URL сайтов и защиты настроек.
   */
  private fun updateDynamicEventFilterForPackage(pkg: String) {
    val low = pkg.lowercase()
    val needsContentEvents = isUniversalBrowserPackage(low) ||
      isRecentsHostPackage(low) ||
      low == "com.android.settings" ||
      low.contains("packageinstaller") ||
      low.contains("securitycenter") ||
      low.contains("apkinstaller")

    if (needsContentEvents == isContentEventsEnabledInServiceInfo) return
    isContentEventsEnabledInServiceInfo = needsContentEvents
    try {
      val info = serviceInfo ?: return
      info.eventTypes = if (needsContentEvents) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
          AccessibilityEvent.TYPE_WINDOWS_CHANGED or
          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
      } else {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
          AccessibilityEvent.TYPE_WINDOWS_CHANGED
      }
      info.notificationTimeout = if (needsContentEvents) 120L else 180L
      serviceInfo = info
    } catch (_: Exception) {}
  }

  /**
   * Событийный перехватчик режима разделения экрана / плавающих окон и карусели «Список открытых программ» (Recents / Overview).
   * - В режиме разделения экрана / плавающего окна: мгновенно закрывает запрещённую программу и выводит PIN-шлюз.
   * - В карусели Недавних окон (Recents): НЕ блокирует карусель пин-кодом и НЕ закрывает разрешённые программы,
   *   а точечно смахивает только карточку с точным заголовком запрещённой программы!
   */
  private fun inspectAndBlockRecentsOrMultiWindow(
    event: AccessibilityEvent,
    activePkg: String,
    nowMs: Long
  ): Boolean {
    if (blockedPackageNames.isEmpty() && appLimitsMap.isEmpty()) return false
    if (ApkUpdateInstaller.isInstallingUpdate) return false

    val eventType = event.eventType
    // 1. Проверка Multi-Window / Split-Screen / Плавающих окон (только при смене окон!)
    if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
        eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
      // Если оверлей блокировки уже открыт или только что (менее 850 мс назад) свернули запрещённое окно,
      // не перезапускаем цикл перехвата по остаточным окнам анимации закрытия (защита от мигания Google Play после рестарта!)
      if (overlayView != null || (nowMs - lastBlockedAppEjectMs) < 850L) {
        return overlayView != null && !isOverlayForTotalDeviceLock
      }
      try {
        for (win in windows) {
          val wPkg = win.root?.packageName?.toString()?.trim().orEmpty()
          if (wPkg.isBlank() ||
              wPkg == applicationContext.packageName ||
              wPkg.startsWith("com.android.systemui") ||
              wPkg.contains("inputmethod", ignoreCase = true) ||
              DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, wPkg) ||
              isPackageTemporarilyUnlockedByPin(wPkg)) {
            continue
          }
          val limitPair = appLimitsMap[wPkg]
          val isLimitExhausted = limitPair != null && limitPair.first in 1..limitPair.second
          val isWinBlocked = blockedPackageNames.contains(wPkg) ||
            com.example.util.BootPrefs.isPackageBlocked(applicationContext, wPkg)
          if (isWinBlocked || isLimitExhausted) {
            val friendly = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, wPkg)
            val reason = if (isLimitExhausted && limitPair != null) {
              "Дневной лимит времени для «$friendly» исчерпан (${limitPair.first} мин).\nВведите PIN-код родителя, чтобы открыть программу."
            } else {
              "Программа «$friendly» заблокирована родителем.\nВведите PIN-код родителя, чтобы открыть программу."
            }
            interceptAndGateBlockedApp(wPkg, reason)
            return true
          }
        }
      } catch (_: Exception) {}
    }

    // 2. Точечная очистка карточки запрещённой программы в карусели Недавних приложений (Recents / Overview)
    // БЕЗ закрытия разрешённых программ и без зависания карусели!
    if (!isRecentsHostPackage(activePkg)) return false

    if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
        eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
      if (nowMs - lastRecentsCheckMs < 380L) return false
    }
    lastRecentsCheckMs = nowMs

    val clsName = event.className?.toString()?.lowercase().orEmpty()
    var hasRecentsMarker = clsName.contains("recent") ||
      clsName.contains("overview") ||
      clsName.contains("taskview") ||
      clsName.contains("taskstack") ||
      clsName.contains("quickstep") ||
      clsName.contains("multiwindow") ||
      clsName.contains("recentsactivity")

    val recentsMarkerWords = listOf(
      "закрыть все", "очистить всё", "очистить все", "очистить", "close all", "clear all",
      "разделить экран", "всплывающее окно", "плавающее окно", "split screen", "pop-up view",
      "список недавних", "недавние приложения", "recent apps", "нет недавних"
    )

    val labelsMap = blockedAppLabelsToPkg
    var matchedBlockedPkg: String? = null
    var matchedBlockedNode: AccessibilityNodeInfo? = null

    try {
      val root = rootInActiveWindow
      if (root != null) {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < 160) {
          val node = queue.removeFirst()
          visited++
          val viewId = node.viewIdResourceName?.lowercase().orEmpty()
          val nodeCls = node.className?.toString()?.lowercase().orEmpty()
          val txt = node.text?.toString()?.trim()?.lowercase().orEmpty()
          val desc = node.contentDescription?.toString()?.trim()?.lowercase().orEmpty()

          if (!hasRecentsMarker) {
            if (viewId.contains("recent") ||
                viewId.contains("overview") ||
                viewId.contains("task_view") ||
                viewId.contains("clear_all") ||
                viewId.contains("recents_view") ||
                viewId.contains("snapshot") ||
                viewId.contains("dismiss_task") ||
                nodeCls.contains("recents") ||
                nodeCls.contains("taskview") ||
                nodeCls.contains("overview") ||
                recentsMarkerWords.any { (txt.isNotBlank() && txt.contains(it)) || (desc.isNotBlank() && desc.contains(it)) }) {
              hasRecentsMarker = true
            }
          }

          // Строгое сопоставление ТОЛЬКО точного заголовка карточки заблокированной программы!
          if (matchedBlockedPkg == null && labelsMap.isNotEmpty() && (txt.isNotBlank() || desc.isNotBlank())) {
            for ((blockedLabel, bPkg) in labelsMap) {
              if (isPackageTemporarilyUnlockedByPin(bPkg)) continue
              if (isExactBlockedRecentsCardLabel(txt, blockedLabel) ||
                  isExactBlockedRecentsCardLabel(desc, blockedLabel)) {
                matchedBlockedPkg = bPkg
                matchedBlockedNode = node
                break
              }
            }
          }

          val count = node.childCount
          for (i in 0 until count) {
            node.getChild(i)?.let { queue.add(it) }
          }
        }
      }
    } catch (_: Exception) {}

    if (!hasRecentsMarker) return false

    // Смахиваем ТОЛЬКО найденную карточку с точным заголовком заблокированной программы!
    // Никаких слепых свайпов по центру экрана — разрешённые программы в карусели остаются нетронутыми.
    val targetBlockedPkg = matchedBlockedPkg
    val targetNode = matchedBlockedNode
    if (targetBlockedPkg != null && targetNode != null) {
      dirtyRecentsBlockedPackages.remove(targetBlockedPkg)
      killBlockedPackageCompletely(applicationContext, targetBlockedPkg)
      dismissSingleRecentsBlockedCard(targetNode, targetBlockedPkg)
      val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, targetBlockedPkg)
      DeviceControlDebugger.logEvent(
        "ОЧИСТКА_КАРТОЧКИ_В_КАРУСЕЛИ",
        "Карточка запрещённой программы «$friendlyName» точечно убрана из карусели (другие программы не затронуты)",
        "INTERCEPTED"
      )
    }

    return false
  }

  override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    if (event == null) return
    // CRITICAL BATTERY & CPU GUARD: Never run Accessibility inspection on a Parent device or Emulator!
    if (!isChildDeviceRole || com.example.location.LiveLocationTracker.isEmulator()) return

    val eventPkg = event.packageName?.toString()?.trim().orEmpty()
    val eventClassName = event.className?.toString()?.trim().orEmpty()

    // =========================================================================
    // ЗАЩИТА БЕСКОНТАКТНОЙ ОПЛАТЫ NFC / GOOGLE PAY / GOOGLE КОШЕЛЁК / MIR PAY:
    // Когда ребёнок открывает Google Кошелёк или прикладывает телефон к NFC-терминалу:
    // 1) НЕ вызываем rootInActiveWindow / windows и НЕ делаем скриншотов (чтобы антифрод Google Pay
    //    не отклонил транзакцию из-за чтения защищённого окна FLAG_SECURE).
    // 2) Автоматически убираем плавающий кружок чата (FloatingChatOverlayService) и любые незаблокированные оверлеи,
    //    так как по стандарту PCI-DSS Google Pay блокирует NFC-оплату при любом активном SYSTEM_ALERT_WINDOW.
    // =========================================================================
    if (DeviceUsageTracker.isNfcOrPaymentPackage(eventPkg, eventClassName)) {
      currentForegroundPackage = eventPkg
      currentForegroundAppLabel = "Google Кошелёк (NFC-оплата)"
      FloatingChatOverlayService.dismissOverlay(applicationContext)
      if (!isDeviceLocked && overlayView != null) {
        hideLockOverlay()
      }
      return
    }

    // Экономия батареи: запрашиваем rootInActiveWindow по IPC ТОЛЬКО при смене окна или если eventPkg пуст!
    val needRootCheck = eventPkg.isBlank() ||
      event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
      event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED
    val rootPkg = if (needRootCheck) {
      try { rootInActiveWindow?.packageName?.toString()?.trim().orEmpty() } catch (_: Exception) { "" }
    } else ""
    if (DeviceUsageTracker.isNfcOrPaymentPackage(rootPkg, eventClassName)) {
      currentForegroundPackage = rootPkg
      currentForegroundAppLabel = "Google Кошелёк (NFC-оплата)"
      FloatingChatOverlayService.dismissOverlay(applicationContext)
      if (!isDeviceLocked && overlayView != null) {
        hideLockOverlay()
      }
      return
    }
    val packageName = when {
      rootPkg.isNotBlank() && (blockedPackageNames.contains(rootPkg) || com.example.util.BootPrefs.isPackageBlocked(applicationContext, rootPkg)) -> rootPkg
      eventPkg.isNotBlank() -> eventPkg
      rootPkg.isNotBlank() -> rootPkg
      else -> return
    }
    val nowMs = System.currentTimeMillis()

    // 0. Auto-click "Обновить"/"Установить" when OTA update is running inside PackageInstaller
    val isInstallerPkg = packageName.contains("packageinstaller", ignoreCase = true) ||
      packageName.contains("miui.securitycenter", ignoreCase = true) ||
      packageName.contains("apkinstaller", ignoreCase = true)
    if (ApkUpdateInstaller.isInstallingUpdate && isInstallerPkg) {
      hideLockOverlay()
      autoConfirmPackageInstallerUpdate(rootInActiveWindow)
      return
    }

    val isSystemOrSelf = packageName == applicationContext.packageName ||
      DeviceUsageTracker.isSystemShellOrNoiseEntry(packageName)

    // КРИТИЧНО: isSettingUpPermissions НИКОГДА не добавляется в isCoreWhitelisted,
    // иначе при фоновом вызове настройки разрешений блокировка TikTok/Instagram/YouTube отключалась!
    val isCoreWhitelisted = DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, packageName) ||
      (ApkUpdateInstaller.isInstallingUpdate && isInstallerPkg)

    // Динамически включаем/выключаем TYPE_WINDOW_CONTENT_CHANGED для максимальной экономии батареи
    updateDynamicEventFilterForPackage(packageName)

    // =========================================================================
    // ПЕРЕХВАТ СПИСКА ОТКРЫТЫХ ОКОН (RECENTS / OVERVIEW) И РАЗДЕЛЕНИЯ ЭКРАНА:
    // В карусели Недавних смахивает только карточку с точным заголовком заблокированной программы!
    // =========================================================================
    if (!isCoreWhitelisted && inspectAndBlockRecentsOrMultiWindow(event, packageName, nowMs)) {
      return
    }

    // =========================================================================
    // МГНОВЕННЫЙ ПРЕДПУСКОВОЙ PIN-ШЛЮЗ (0 мс):
    // 1) СНАЧАЛА (0 мс) синхронно накрываем экран непрозрачным системным оверлеем блокировки,
    //    чтобы запрещённая программа не успела показать даже свой логотип на 1 секунду!
    // 2) Останавливаем аудио/видео и мягко сворачиваем программу на главный экран под оверлеем.
    // =========================================================================
    if (!isSystemOrSelf && !isCoreWhitelisted) {
      // 1. Полная блокировка телефона (2 кнопки: PIN и Экстренный вызов SOS)
      if (isDeviceLocked) {
        ejectBlockedAppToHome(packageName)
        if (overlayView == null || !isOverlayForTotalDeviceLock) {
          val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
          DeviceControlDebugger.logEvent("БЛОКИРОВКА_ТЕЛЕФОНА", "Перехвачена попытка открыть $friendlyName при заблокированном телефоне", "BLOCKED")
          showLockOverlay(
            reasonText = "Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.",
            isDeviceTotalLock = true,
            blockedPackageName = null
          )
        }
        return
      }

      // 2. Мгновенное закрытие запрещенного приложения (0 мс) + PIN-шлюз
      val isBlockedByParent = blockedPackageNames.contains(packageName) ||
        com.example.util.BootPrefs.isPackageBlocked(applicationContext, packageName)
      if (isBlockedByParent && !isPackageTemporarilyUnlockedByPin(packageName)) {
        if (!blockedPackageNames.contains(packageName)) {
          blockedPackageNames = blockedPackageNames + packageName
          rebuildBlockedLabelsIndex()
        }
        val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
        currentForegroundPackage = packageName
        currentForegroundAppLabel = friendlyName
        DeviceControlDebugger.logEvent("ЗАПРЕТ_ПРИЛОЖЕНИЯ", "Перехвачен запуск заблокированной программы: $friendlyName", "INTERCEPTED")
        interceptAndGateBlockedApp(
          packageName = packageName,
          reasonText = "Программа «$friendlyName» заблокирована родителем.\nВведите PIN-код родителя, чтобы открыть программу."
        )
        return
      }

      // 3. Проверка дневного лимита времени для приложения
      val limitInfo = appLimitsMap[packageName]
      if (limitInfo != null && !isPackageTemporarilyUnlockedByPin(packageName)) {
        val (limit, used) = limitInfo
        if (limit in 1..used) {
          val friendlyName = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
          currentForegroundPackage = packageName
          currentForegroundAppLabel = friendlyName
          DeviceControlDebugger.logEvent("ЛИМИТ_ВРЕМЕНИ", "Исчерпан лимит для $friendlyName ($used/$limit мин)", "INTERCEPTED")
          interceptAndGateBlockedApp(
            packageName = packageName,
            reasonText = "Дневной лимит времени для «$friendlyName» исчерпан ($limit мин).\nВведите PIN-код родителя, чтобы открыть программу."
          )
          return
        }
      }
    }

    // =========================================================================
    // ЗАЩИТА ОТ ЛАГОВ И НАГРУЗКИ НА ПРОЦЕССОР / ПАМЯТЬ:
    // События TYPE_WINDOW_CONTENT_CHANGED приходят только в браузерах/настройках/карусели.
    // В браузерах проверяем адресную строку не чаще 1 раза в 950 мс.
    // =========================================================================
    val prevPkg = currentForegroundPackage
    val isBrowserApp = isUniversalBrowserPackage(packageName)
    val contentThrottleMs = if (isBrowserApp) 950L else 3_500L
    if (event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
        packageName == prevPkg &&
        packageName != "com.android.settings" &&
        !packageName.contains("packageinstaller", ignoreCase = true) &&
        (nowMs - lastContentEventProcessedMs) < contentThrottleMs) {
      return
    }
    lastContentEventProcessedMs = nowMs

    // Лёгкое отслеживание активного приложения БЕЗ обхода дерева AccessibilityNodeInfo на UI-потоке
    if (!isSystemOrSelf) {
      val friendlyName = if (prevPkg == packageName && currentForegroundAppLabel.isNotBlank()) {
        currentForegroundAppLabel
      } else {
        DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
      }
      if (!DeviceUsageTracker.isSystemShellOrNoiseEntry(packageName, friendlyName)) {
        currentForegroundPackage = packageName
        currentForegroundAppLabel = friendlyName

        if (prevPkg != packageName || (nowMs - lastContentPushTimeMs > 20_000L)) {
          val eventTitle = DeviceUsageTracker.sanitizeWindowEventTitle(
            event.text.firstOrNull()?.toString(),
            friendlyName
          )
          val lightweightSummary = if (eventTitle != null && isBrowserApp) "$friendlyName • $eventTitle" else friendlyName
          DeviceUsageTracker.setForegroundApp(lightweightSummary, packageName)
          if (activeChildId > 0 && (prevPkg != packageName || lightweightSummary != lastPushedContentSummary)) {
            lastContentPushTimeMs = nowMs
            lastPushedContentSummary = lightweightSummary
            val switchedPackage = prevPkg != packageName
            serviceScope.launch(Dispatchers.IO) {
              CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, lightweightSummary)
              if (switchedPackage && (nowMs - lastPingCaptureA11yMs >= 5_000L)) {
                val parentWatching = com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected() ||
                  isLiveStreamingActiveNow() ||
                  ScreenShotManager.capturedScreens.value.firstOrNull()?.base64Bitmap.isNullOrBlank()
                if (parentWatching) {
                  lastPingCaptureA11yMs = nowMs
                  delay(350L)
                  triggerImmediateCaptureNow()
                }
              }
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

    // 5. Универсальный перехватчик сайтов во ВСЕХ браузерах (Chrome, Mi Browser, Samsung, Yandex, Opera, Firefox, Brave, Edge, DuckDuckGo):
    // - Извлекает посещённый домен из адресной строки любого браузера
    // - Мгновенно блокирует сайт, если его домен заблокирован родителем
    // - Записывает каждый посещённый сайт с точной датой и временем (DateTime) в лог сайтов и активности для родителя
    if (isBrowserApp) {
      val extractedSite = extractBrowserUrlOrDomainFromWindow(event, nowMs)
      val detectedDomain = extractedSite?.first.orEmpty()
      val detectedFullUrl = extractedSite?.second.orEmpty()
      val eventText = (
        detectedDomain + " " + detectedFullUrl + " " +
        event.text.joinToString(" ") + " " +
        (event.contentDescription?.toString() ?: "")
      ).lowercase()

      if (eventText.isNotBlank()) {
        val allBlockedDomains = (blockedDomainsList + com.example.util.BootPrefs.getBlockedDomains(applicationContext)).distinct()
        val matchedDomain = allBlockedDomains.firstOrNull { dom ->
          dom.isNotBlank() && (
            detectedDomain == dom ||
            detectedDomain.endsWith(".$dom") ||
            eventText.contains(dom)
          )
        }
        val matchedKeyword = blockedKeywordsList.firstOrNull { it.isNotBlank() && eventText.contains(it) }
        val matchedCategory = when {
          "ADULT" in blockedCategoriesSet && listOf("porn", "xxx", "pornhub", "xvideos", "onlyfans", "порно", "секс 18+").any { eventText.contains(it) } -> "Контент 18+"
          "GAMBLING" in blockedCategoriesSet && listOf("1xbet", "fonbet", "casino", "казино", "vulkan", "stake.com").any { eventText.contains(it) } -> "Азартные игры и казино"
          "VIOLENCE" in blockedCategoriesSet && listOf("даркнет", "шок-контент", "оружие купить").any { eventText.contains(it) } -> "Насилие и оружие"
          "DRUGS" in blockedCategoriesSet && listOf("вейп купить", "снюс", "закладки").any { eventText.contains(it) } -> "Наркотики и вейпы"
          else -> null
        }
        val blockReason = when {
          matchedDomain != null -> "Сайт $matchedDomain заблокирован родителем"
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

        // Логируем посещённый ребёнком сайт (независимо от браузера) с точной датой и временем и отправляем родителю!
        if (detectedDomain.length >= 4 && detectedDomain.contains(".")) {
          val isNewDomainOrExpired = detectedDomain != lastLoggedBrowserDomain ||
            (nowMs - lastLoggedBrowserDomainMs) >= 25_000L
          if (isNewDomainOrExpired && activeChildId > 0) {
            lastLoggedBrowserDomain = detectedDomain
            lastLoggedBrowserDomainMs = nowMs
            val browserLabel = currentForegroundAppLabel.ifBlank {
              DeviceUsageTracker.getFriendlyAppLabel(applicationContext, packageName)
            }
            val webSummary = "$browserLabel • 🌐 $detectedDomain"
            lastPushedContentSummary = webSummary
            DeviceUsageTracker.setForegroundApp(webSummary, packageName)
            serviceScope.launch(Dispatchers.IO) {
              CloudSyncManager(applicationContext).updateActiveAppOnly(activeChildId, webSummary)
              if (isDeviceStorageUnlocked()) {
                com.example.data.cloud.OfflineStoreAndForwardManager.recordChildActivityOffline(
                  context = applicationContext,
                  childId = activeChildId,
                  packageName = "web:$detectedDomain",
                  appName = "🌐 $detectedDomain",
                  windowTitle = "🌐 $detectedDomain • $browserLabel" +
                    (if (detectedFullUrl.isNotBlank() && detectedFullUrl != detectedDomain) " ($detectedFullUrl)" else ""),
                  durationSeconds = 10,
                  deviceType = "PHONE"
                )
                if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
                  com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(
                    context = applicationContext,
                    childId = activeChildId
                  )
                }
              }
            }
          }
        }
      }
    }

    if (prevPkg != packageName && !isSystemOrSelf) {
      DeviceControlDebugger.logEvent("ОТКРЫТИЕ_ПРОГРАММЫ", "Активное окно: $currentForegroundAppLabel", "SUCCESS")
    }
  }

  /**
   * Извлекает текущий открытый сайт (домен + URL) из адресной строки любого браузера:
   * Chrome, Xiaomi Mi Browser, Samsung Internet, Яндекс Браузер, Opera, Firefox, Brave, Edge, DuckDuckGo, Vivaldi, UC.
   */
  private fun extractBrowserUrlOrDomainFromWindow(event: AccessibilityEvent, nowMs: Long): Pair<String, String>? {
    val domainRegex = Regex("""(?:https?://)?(?:www\.)?([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)+)(?:/[^\s]*)?""", RegexOption.IGNORE_CASE)
    // 1. Сначала проверяем текст самого события (0 затрат IPC)
    for (t in event.text) {
      val candidate = t?.toString()?.trim().orEmpty()
      if (candidate.length in 4..240 && candidate.contains(".") && !candidate.contains(" ")) {
        val m = domainRegex.find(candidate)
        val host = m?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
        if (isValidExtractedDomain(host)) {
          return Pair(host, candidate.take(140))
        }
      }
    }

    if (nowMs - lastBrowserUrlScanMs < 900L) return null
    lastBrowserUrlScanMs = nowMs

    // 2. Быстрый поиск узла адресной строки браузера (не более 55 узлов в глубину)
    val addressBarIdHints = listOf(
      "url_bar", "url_field", "search_box_text", "mozac_browser_toolbar_url_view",
      "address_bar", "omnibox", "location_bar", "bro_omnibox", "custom_tab_toolbar",
      "url_edit", "toolbar_url", "address_edit"
    )
    try {
      val root = rootInActiveWindow ?: return null
      val queue = ArrayDeque<AccessibilityNodeInfo>()
      queue.add(root)
      var visited = 0
      while (queue.isNotEmpty() && visited < 55) {
        val node = queue.removeFirst()
        visited++
        val vid = node.viewIdResourceName?.lowercase().orEmpty()
        val txt = node.text?.toString()?.trim().orEmpty()
        if (txt.length in 4..260 && txt.contains(".")) {
          val isUrlView = addressBarIdHints.any { vid.contains(it) } || !txt.contains(" ")
          if (isUrlView) {
            val m = domainRegex.find(txt)
            val host = m?.groupValues?.getOrNull(1)?.lowercase()?.removePrefix("www.").orEmpty()
            if (isValidExtractedDomain(host)) {
              return Pair(host, txt.take(140))
            }
          }
        }
        val count = node.childCount
        for (i in 0 until count) {
          node.getChild(i)?.let { queue.add(it) }
        }
      }
    } catch (_: Exception) {}
    return null
  }

  private fun isValidExtractedDomain(host: String): Boolean {
    if (host.length < 4 || !host.contains(".")) return false
    val tld = host.substringAfterLast('.', "")
    if (tld.length !in 2..10 || tld.any { !it.isLetter() }) return false
    if (host.endsWith(".apk") || host.endsWith(".png") || host.endsWith(".jpg") || host.endsWith(".mp4")) return false
    return true
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

    // ИСПРАВЛЕНИЕ МЕРЦАНИЯ ЭКРАНА БЛОКИРОВКИ ПОСЛЕ РЕСТАРТА:
    // Если оверлей уже висит на экране в том же режиме (блокировка программы или полная блокировка телефона),
    // НИКОГДА не вызываем removeViewImmediate + addView и не пересоздаём окно —
    // просто обновляем текст на месте (0 мс, 0 миганий между рабочим столом и окном блокировки)!
    if (overlayView != null) {
      if (isOverlayForTotalDeviceLock == isDeviceTotalLock) {
        currentBlockedOverlayPackage = if (isDeviceTotalLock) null else blockedPackageName
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
            DeviceTotalLockManager.activeLockActivityCloser?.invoke()
          }
        } else null,
        onUnlocked = {
          if (isDeviceTotalLock) {
            isDeviceLocked = false
            DeviceTotalLockManager.releaseTotalLock(applicationContext)
            hideLockOverlay()
          } else {
            val targetPkg = blockedPackageName?.trim().orEmpty()
            hideLockOverlay()
            DeviceTotalLockManager.activeLockActivityCloser?.invoke()
            if (targetPkg.isNotBlank()) {
              grantTemporaryPinUnlockForPackage(targetPkg)
              try {
                val launchIntent = packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                if (launchIntent != null) {
                  startActivity(launchIntent)
                }
              } catch (_: Exception) {}
            }
          }
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
    if (isScreenPowerReceiverRegistered) {
      try {
        applicationContext.unregisterReceiver(screenAndPowerWakeReceiver)
      } catch (_: Exception) {}
      isScreenPowerReceiverRegistered = false
    }
    if (instance === this) {
      instance = null
    }
    hideLockOverlay()
    super.onDestroy()
  }
}
