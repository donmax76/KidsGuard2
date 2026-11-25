package com.example.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.cloud.CloudSyncManager
import com.example.data.local.KidsGuardDatabase
import com.example.util.DeviceUsageTracker
import com.example.util.ScreenShotManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

private const val TAG = "KidsGuardBgService"
private const val CHANNEL_ID = "kidsguard_protection_channel"
private const val NOTIFICATION_ID = 9001

class KidsGuardBackgroundService : Service() {
  private val serviceScope = CoroutineScope(Dispatchers.Default + Job())
  private var locationManager: LocationManager? = null
  private var bgLocationListener: LocationListener? = null
  private var fusedClient: com.google.android.gms.location.FusedLocationProviderClient? = null
  private var fusedCallback: com.google.android.gms.location.LocationCallback? = null
  private lateinit var cloudSync: CloudSyncManager
  private var activeChildId: Long = 0L
  private var isGpsTrackingStarted = false

  // Окно измерения расхода энергии приложения начинается при отключении зарядки.
  // При подключении или отключении зарядки мгновенно будим сокет и отправляем родителю актуальный заряд и PONG!
  private val powerConnectionReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Intent.ACTION_POWER_CONNECTED -> com.example.util.AppEnergyMeter.reset(applicationContext)
        Intent.ACTION_POWER_DISCONNECTED -> com.example.util.AppEnergyMeter.startWindow(applicationContext)
      }
      wakeSocketAndSendHeartbeatNow("POWER_STATE_CHANGED")
    }
  }

  @Volatile
  private var screenOffSinceMs: Long = 0L

  private val dozeHeartbeatReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      if (intent?.action == ACTION_DOZE_STANDBY_PULSE) {
        if (!DeviceUsageTracker.isInteractive(applicationContext)) {
          scheduleNextDozeStandbyPulse()
          wakeSocketAndSendHeartbeatNow("DOZE_STANDBY_PULSE")
        } else {
          cancelDozeStandbyPulse()
        }
      }
    }
  }

  private fun scheduleNextDozeStandbyPulse() {
    try {
      val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
      val intent = Intent(ACTION_DOZE_STANDBY_PULSE).apply {
        setPackage(packageName)
      }
      val pi = PendingIntent.getBroadcast(
        applicationContext,
        9402,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      val triggerAt = System.currentTimeMillis() + 210_000L // раз в 3.5 минуты только при выключенном экране
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
      } else {
        am.set(android.app.AlarmManager.RTC_WAKEUP, triggerAt, pi)
      }
    } catch (_: Exception) {}
  }

  private fun cancelDozeStandbyPulse() {
    try {
      val am = getSystemService(Context.ALARM_SERVICE) as? android.app.AlarmManager ?: return
      val intent = Intent(ACTION_DOZE_STANDBY_PULSE).apply {
        setPackage(packageName)
      }
      val pi = PendingIntent.getBroadcast(
        applicationContext,
        9402,
        intent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      )
      am.cancel(pi)
    } catch (_: Exception) {}
  }

  // Погас экран — живого просмотра быть не может: сразу гасим стрим и GPS,
  // включаем редкий Doze-пульс (раз в 3.5 мин) и шлём родителю PONG + статус «Телефон не используется • Экран выключен»,
  // чтобы телефон ребёнка ВСЕГДА оставался на связи даже после многих часов бездействия!
  private val screenOffReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Intent.ACTION_SCREEN_OFF -> {
          screenOffSinceMs = System.currentTimeMillis()
          try {
            KidsGuardAccessibilityService.stopLiveStreamingWindow()
          } catch (_: Exception) {}
          stopGpsTracking()
          scheduleNextDozeStandbyPulse()
          wakeSocketAndSendHeartbeatNow("SCREEN_OFF")
        }
        Intent.ACTION_SCREEN_ON,
        Intent.ACTION_USER_PRESENT -> {
          screenOffSinceMs = 0L
          cancelDozeStandbyPulse()
          wakeSocketAndSendHeartbeatNow("SCREEN_ON")
        }
      }
    }
  }

  private fun wakeSocketAndSendHeartbeatNow(reason: String) {
    if (com.example.location.LiveLocationTracker.isEmulator()) return
    try {
      val pm = getSystemService(Context.POWER_SERVICE) as? android.os.PowerManager
      val wl = pm?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "KidsGuard:BgPulseWake")
      wl?.setReferenceCounted(false)
      wl?.acquire(4_500L)
    } catch (_: Exception) {}

    serviceScope.launch(Dispatchers.IO) {
      try {
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD" && bootCfg.permanentHardwareRole != "CHILD" && bootCfg.role != "CHILD") return@launch
        val cid = activeChildId.takeIf { it > 0 }
          ?: cfg?.thisDeviceChildId?.takeIf { it > 0 }
          ?: bootCfg.childId.takeIf { it > 0 }
          ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
          ?: 1L
        activeChildId = cid

        com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
        com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", cid)
        com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()
        val batt = readBatteryLevel()
        if (batt in 1..100) {
          dao.updateChildBattery(cid, batt)
          cloudSync.upsertBatteryLevel(cid, batt)
        }
        com.example.data.cloud.RealtimeCloudRelay.publishChildPong(cid)
        if (!DeviceUsageTracker.isInteractive(applicationContext)) {
          cloudSync.updateActiveAppOnly(cid, buildStandbyScreenOffLabel(batt))
        }
      } catch (_: Exception) {}
    }
  }

  private fun buildStandbyScreenOffLabel(batt: Int): String {
    if (screenOffSinceMs == 0L) screenOffSinceMs = System.currentTimeMillis()
    val idleMin = ((System.currentTimeMillis() - screenOffSinceMs) / 60_000L).coerceAtLeast(0L)
    val idleSuffix = if (idleMin >= 1L) " • не используется $idleMin мин." else ""
    val isCharging = com.example.util.AppEnergyMeter.isCharging(applicationContext)
    return if (isCharging) {
      "🌙 Экран выключен (на зарядке ${if (batt in 1..100) "$batt%" else ""}$idleSuffix)"
    } else {
      "🌙 Телефон не используется • Экран выключен$idleSuffix"
    }
  }

  private fun registerPowerMeter() {
    try {
      val filter = android.content.IntentFilter().apply {
        addAction(Intent.ACTION_POWER_CONNECTED)
        addAction(Intent.ACTION_POWER_DISCONNECTED)
      }
      androidx.core.content.ContextCompat.registerReceiver(
        applicationContext,
        powerConnectionReceiver,
        filter,
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
      )
      if (com.example.util.AppEnergyMeter.isCharging(applicationContext)) {
        com.example.util.AppEnergyMeter.reset(applicationContext)
      } else {
        com.example.util.AppEnergyMeter.startWindow(applicationContext)
      }
    } catch (_: Exception) {
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    isRunning = true
    createNotificationChannel()
    registerPowerMeter()
    registerScreenOffReceiver()
    // Проекция экрана не может быть активна сразу при старте процесса (токен согласия
    // хранится только в памяти). Не объявляем MEDIA_PROJECTION FGS без реальной проекции —
    // иначе система всё время считает приложение «транслирующим экран» и держит его в разряде.
    promoteToForeground(includeMediaProjection = false)

    cloudSync = CloudSyncManager(applicationContext)
    locationManager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    fusedClient = try {
      com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(applicationContext)
    } catch (_: Exception) {
      null
    }

    startProtectionLoops()
  }

  private fun promoteToForeground(includeMediaProjection: Boolean) {
    val notifTitle = if (includeMediaProjection) {
      "KidsGuard • Прямая трансляция экрана и защита"
    } else {
      "KidsGuard • Фоновая защита"
    }
    try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val fgsType = if (includeMediaProjection) {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        } else {
          ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        }
        startForeground(
          NOTIFICATION_ID,
          buildForegroundNotification(notifTitle),
          fgsType
        )
      } else {
        startForeground(NOTIFICATION_ID, buildForegroundNotification(notifTitle))
      }
    } catch (e: Exception) {
      Log.e(TAG, "startForeground fallback: ${e.message}")
      try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          startForeground(
            NOTIFICATION_ID,
            buildForegroundNotification("KidsGuard • Фоновая защита"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
          )
        } else {
          startForeground(NOTIFICATION_ID, buildForegroundNotification("KidsGuard • Фоновая защита"))
        }
      } catch (_: Exception) {}
    }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    val childId = intent?.getLongExtra("CHILD_ID", 0L) ?: 0L
    if (childId > 0) {
      activeChildId = childId
    }
    val enableProj = intent?.getBooleanExtra("ENABLE_PROJECTION_FGS", false) == true
    if (enableProj) {
      promoteToForeground(includeMediaProjection = true)
      val projCode = intent?.getIntExtra("PROJECTION_RESULT_CODE", 0) ?: 0
      val projData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent?.getParcelableExtra("PROJECTION_RESULT_DATA", Intent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent?.getParcelableExtra("PROJECTION_RESULT_DATA")
      }
      val resolvedPair = if (projCode != 0 && projData != null) {
        Pair(projCode, projData)
      } else {
        ScreenShotManager.getPendingProjectionConsent()
      }
      if (resolvedPair != null) {
        ScreenShotManager.startMediaProjection(applicationContext, resolvedPair.first, resolvedPair.second)
      }
    }
    if (!isGpsTrackingStarted && com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
      startGpsTracking(requestImmediateFix = false)
    }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    // Держим только фоновую защиту (без проекции экрана): пользователь свёл приложение
    // из Recents, живой просмотр в этот момент не нужен и не должен жечь батарею.
    try {
      start(applicationContext, activeChildId, enableProjectionFgs = false)
    } catch (_: Exception) {}
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

  private fun startProtectionLoops() {
    serviceScope.launch {
      // 0. Мгновенный старт сетевого движка из BootPrefs (Device Protected Storage) даже до разблокировки экрана после ребута
      val initialBootCfg = com.example.util.BootPrefs.load(applicationContext)
      if (!com.example.location.LiveLocationTracker.isEmulator() &&
          (initialBootCfg.permanentHardwareRole == "CHILD" || initialBootCfg.role == "CHILD")) {
        if (initialBootCfg.childId > 0L) {
          activeChildId = initialBootCfg.childId
        } else if (activeChildId <= 0L) {
          activeChildId = 1L
        }
        com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
        try {
          com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", activeChildId)
          com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()
          com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
        } catch (_: Exception) {}
      }

      // Ждём разблокировки хранилища после рестарта телефона, прежде чем обращаться к Room SQLite
      while (isActive && !isDeviceStorageUnlocked()) {
        delay(2_000L)
      }

      val database = KidsGuardDatabase.getDatabase(applicationContext)
      val dao = database.kidsGuardDao()

      // Resolve activeChildId and run immediately on physical Child devices
      while (isActive) {
        if (com.example.location.LiveLocationTracker.isEmulator()) {
          delay(5000)
          continue
        }
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        val config = try { dao.getFamilyConfigOnce() } catch (_: Exception) { null }
        val isChildRole = config?.selectedRole == "CHILD" ||
          bootCfg.permanentHardwareRole == "CHILD" ||
          bootCfg.role == "CHILD"
        if (!isChildRole) {
          activeChildId = 0L
          LockOverlayService.stop(applicationContext)
          stopSelf()
          return@launch
        }
        val children = try { dao.getAllChildrenOnce() } catch (_: Exception) { emptyList() }
        val resolvedId = when {
          config != null && config.thisDeviceChildId > 0 && children.any { it.id == config.thisDeviceChildId } -> config.thisDeviceChildId
          bootCfg.childId > 0 && (children.isEmpty() || children.any { it.id == bootCfg.childId }) -> bootCfg.childId
          activeChildId > 0 && children.any { it.id == activeChildId } -> activeChildId
          config != null && config.currentActiveChildId > 0 && children.any { it.id == config.currentActiveChildId } -> config.currentActiveChildId
          children.any { it.isPaired } -> children.first { it.isPaired }.id
          children.isNotEmpty() -> children.last().id
          else -> 1L
        }
        if (resolvedId > 0) {
          activeChildId = resolvedId
          try {
            if (config != null && config.thisDeviceChildId != resolvedId) {
              dao.setThisDeviceChildId(resolvedId)
            }
            if (config != null && config.selectedRole != "CHILD" && bootCfg.permanentHardwareRole == "CHILD") {
              dao.setDeviceRole("CHILD")
            }
          } catch (_: Exception) {}
          break
        }
        delay(1500)
      }

      Log.d(TAG, "Background service running for Child ID: $activeChildId")
      com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
      try {
        com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", activeChildId)
      } catch (_: Exception) {}
      com.example.util.CoreEngineHotUpdater.initializeHotCore(applicationContext)

      // Сохраняем минимальный boot-конфиг и кэш кода привязки в Device Protected Storage и RealtimeCloudRelay,
      // чтобы сразу после обновления APK телефон соединялся с родителем в фоне БЕЗ открытия главного окна!
      try {
        val bootCfg = dao.getFamilyConfigOnce()
        val bootChild = dao.getChildByIdOnce(activeChildId) ?: dao.getAllChildrenOnce().firstOrNull()
        val resolvedCode = bootChild?.pairingCode?.trim()?.takeIf { it.length >= 6 }
          ?: com.example.util.BootPrefs.getPairingCode(applicationContext)
        if (resolvedCode.length >= 6) {
          com.example.util.BootPrefs.savePairingCode(applicationContext, resolvedCode)
        }
        if (bootChild != null) {
          com.example.data.cloud.RealtimeCloudRelay.replaceCachedChildProfile(
            if (resolvedCode.length >= 6 && bootChild.pairingCode != resolvedCode) {
              bootChild.copy(pairingCode = resolvedCode, isPaired = true)
            } else {
              bootChild
            }
          )
        }
        com.example.util.BootPrefs.save(
          context = applicationContext,
          role = "CHILD",
          childId = activeChildId,
          allowMobileData = bootChild?.allowMobileData ?: true,
          familyId = bootCfg?.familyId ?: "",
          permanentHardwareRole = "CHILD",
          masterPin = bootCfg?.parentMasterPin
        )
      } catch (_: Exception) {}

      // Мгновенно будим сокет и отправляем PONG + первый кадр экрана родителю при старте сервиса
      try {
        com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()
        com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
      } catch (_: Exception) {}

      // Never auto-lock on service startup unless a fresh authoritative lock was explicitly sent
      val startupAuthLock = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(activeChildId)
      if (startupAuthLock?.first != true) {
        dao.setChildDeviceLocked(activeChildId, false)
        com.example.util.DeviceTotalLockManager.releaseTotalLock(applicationContext)
      }

      // Sync real installed apps from Child's physical phone to Cloud on service start (PRESERVING existing isBlocked & BootPrefs!)
      launch(Dispatchers.IO) {
        try {
          DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
          val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(applicationContext, activeChildId)
          if (realApps.isNotEmpty()) {
            val bootBlockedSet = com.example.util.BootPrefs.getBlockedPackages(applicationContext).map { it.lowercase() }.toSet()
            val bootLimits = com.example.util.BootPrefs.getAppLimitsMap(applicationContext)
            val allDbApps = dao.getAllAppsOnce()
            val anyDbBlocked = allDbApps.filter { it.isBlocked }.map { it.packageName.trim().lowercase() }.toSet()
            val prevApps = dao.getAppsForChildOnce(activeChildId).associateBy { it.packageName.trim().lowercase() }
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
            if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              cloudSync.uploadChildApps(activeChildId, merged)
            }
          }
          DeviceUsageTracker.syncRealUsageStats(applicationContext, activeChildId)
        } catch (_: Exception) {}
      }

      // 1. Observe child profile gpsTrackingEnabled flag and start/stop satellite GPS accordingly
      launch {
        dao.getFamilyConfig().collect { cfg ->
          val boundId = cfg?.thisDeviceChildId?.takeIf { it > 0 }
            ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
            ?: activeChildId
          if (boundId > 0 && boundId != activeChildId) {
            activeChildId = boundId
          }
        }
      }

      launch {
        dao.getChildById(activeChildId).collect { child ->
          if (child != null && child.pairingCode.length >= 6) {
            com.example.util.BootPrefs.savePairingCode(applicationContext, child.pairingCode)
            com.example.data.cloud.RealtimeCloudRelay.replaceCachedChildProfile(child)
          }
          val gpsEnabled = child?.gpsTrackingEnabled ?: false
          com.example.location.LiveLocationTracker.setGpsTrackingAllowed(gpsEnabled)
          launch(Dispatchers.Main) {
            if (gpsEnabled) {
              if (!isGpsTrackingStarted) startGpsTracking(requestImmediateFix = false)
            } else {
              stopGpsTracking()
            }
          }
        }
      }

      // 2. Fallback Foreground App & Block Enforcement Loop (runs frequently ONLY if AccessibilityService is disabled)
      launch(Dispatchers.IO) {
        var lastUploadedAppLabel = ""
        var lastAppUploadTimeMs = 0L
        while (isActive) {
          val isScreenInteractive = DeviceUsageTracker.isInteractive(applicationContext)
          val isLiveStreamActive = KidsGuardAccessibilityService.isLiveStreamingActiveNow()
          val isA11yActive = KidsGuardAccessibilityService.isAccessibilityEnabled()
          val loopDelayMs = when {
            !isScreenInteractive -> 60_000L
            isA11yActive -> 30_000L
            isLiveStreamActive -> 3_000L
            else -> 4_000L
          }
          delay(loopDelayMs)
          try {
            if (!isScreenInteractive || com.example.location.LiveLocationTracker.isEmulator()) continue
            val latestCfg = dao.getFamilyConfigOnce()
            if (latestCfg?.selectedRole != "CHILD") continue
            val boundId = latestCfg.thisDeviceChildId.takeIf { it > 0 } ?: activeChildId
            if (boundId > 0) activeChildId = boundId
            if (activeChildId <= 0) continue

            // Check 00:00 Midnight Reset before evaluating any daily limit
            DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)

            // Если AccessibilityService активен — он сам перехватывает окна по событиям (0 мс) без опроса
            if (isA11yActive) continue

            val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
            val activePkg = DeviceUsageTracker.resolveCurrentForegroundPackage(applicationContext)

            val authLocked = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(activeChildId)?.first
            val isLockedNow = authLocked == true
            val isCoreWhitelisted = DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, activePkg)

            if (!isLockedNow && com.example.util.DeviceTotalLockManager.isCurrentlyLocked) {
              dao.setChildDeviceLocked(activeChildId, false)
              com.example.util.DeviceTotalLockManager.releaseTotalLock(applicationContext)
            } else if (isLockedNow && !com.example.util.DeviceTotalLockManager.isCurrentlyLocked) {
              com.example.util.DeviceTotalLockManager.enforceTotalLock(applicationContext, "Экран заблокирован родителем")
            } else if (!isLockedNow && !activePkg.isNullOrBlank() && !isCoreWhitelisted) {
              val childProfile = dao.getChildByIdOnce(activeChildId)
              val dailyLimit = childProfile?.dailyScreenLimitMinutes ?: 0
              val usedToday = childProfile?.usedScreenTimeMinutes ?: 0
              val appEntity = dao.getAppByPackageOnce(activeChildId, activePkg)
              val isPkgBlocked = (appEntity?.isBlocked == true) ||
                com.example.util.BootPrefs.isPackageBlocked(applicationContext, activePkg)
              val isUnlockedByPin = KidsGuardAccessibilityService.instance?.isPackageTemporarilyUnlockedByPin(activePkg) == true
              if (isPkgBlocked && !isUnlockedByPin) {
                val friendly = appEntity?.appName?.takeIf { it.isNotBlank() }
                  ?: DeviceUsageTracker.getFriendlyAppLabel(applicationContext, activePkg)
                KidsGuardAccessibilityService.killBlockedPackageCompletely(applicationContext, activePkg)
                com.example.ui.screens.ChildLockScreenActivity.launch(
                  context = applicationContext,
                  reason = "Программа «$friendly» заблокирована родителем.\nВведите PIN-код родителя, чтобы открыть программу.",
                  isDeviceTotalLock = false,
                  blockedPackageName = activePkg
                )
              } else if (dailyLimit in 1..usedToday && !isUnlockedByPin) {
                com.example.ui.screens.ChildLockScreenActivity.launch(
                  context = applicationContext,
                  reason = "Дневной лимит экранного времени ($dailyLimit мин) на сегодня исчерпан!",
                  isDeviceTotalLock = false,
                  blockedPackageName = activePkg
                )
              }
            }

            val nowMs = System.currentTimeMillis()
            val isParentWatchingLive = KidsGuardAccessibilityService.isLiveStreamingActiveNow()
            val isParentConnected = com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()
            if (!isLockedNow && isParentWatchingLive) {
              ScreenShotManager.triggerHardwareScreenCapture(appLabel, false) { item ->
                serviceScope.launch(Dispatchers.IO) {
                  if (!item.base64Bitmap.isNullOrBlank()) {
                    cloudSync.uploadScreenSnapshot(activeChildId, appLabel, item.base64Bitmap)
                  }
                }
              }
              lastUploadedAppLabel = appLabel
              lastAppUploadTimeMs = nowMs
            } else if (isParentConnected && appLabel.isNotBlank() && (appLabel != lastUploadedAppLabel || (nowMs - lastAppUploadTimeMs) >= 20_000L)) {
              lastUploadedAppLabel = appLabel
              lastAppUploadTimeMs = nowMs
              ScreenShotManager.triggerHardwareScreenCapture(appLabel, false) { item ->
                serviceScope.launch(Dispatchers.IO) {
                  if (!item.base64Bitmap.isNullOrBlank()) {
                    cloudSync.uploadScreenSnapshot(activeChildId, appLabel, item.base64Bitmap)
                  } else {
                    cloudSync.updateActiveAppOnly(activeChildId, appLabel)
                  }
                }
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Active app sync loop error: ${e.message}")
          }
        }
      }

      // 3. Screen Usage Loop (60 с при включённом экране, 120 с при выключенном — экономия батареи)
      launch {
        var screenOnSeconds = 0
        while (isActive) {
          val isScreenOn = DeviceUsageTracker.isInteractive(applicationContext)
          delay(if (isScreenOn) 60_000L else 120_000L)
          try {
            if (com.example.location.LiveLocationTracker.isEmulator()) continue
            val latestCfg = dao.getFamilyConfigOnce()
            val boundId = latestCfg?.thisDeviceChildId?.takeIf { it > 0 } ?: activeChildId
            if (boundId > 0) activeChildId = boundId
            DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
            if (!isScreenOn) continue
            if (!isGpsTrackingStarted && com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
              launch(Dispatchers.Main) { startGpsTracking(requestImmediateFix = false) }
            }
            if (activeChildId > 0) {
              screenOnSeconds += 60
              if (screenOnSeconds >= 120) {
                screenOnSeconds = 0
                DeviceUsageTracker.syncRealUsageStats(applicationContext, activeChildId)
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Usage sync loop error: ${e.message}")
          }
        }
      }

      // 4. Энергоэффективный цикл синхронизации батареи (БЕЗ периодических WakeLock и без дублирования пульса с AccessibilityService!):
      launch {
        var lastSyncedBattery = -1
        var lastSyncedChildId = -1L
        var lastSyncTimeMs = 0L
        while (isActive) {
          val screenInteractive = DeviceUsageTracker.isInteractive(applicationContext)
          val a11yRunning = KidsGuardAccessibilityService.isAccessibilityEnabled()
          try {
            val latestCfg = dao.getFamilyConfigOnce()
            val boundId = latestCfg?.thisDeviceChildId?.takeIf { it > 0 }
              ?: latestCfg?.currentActiveChildId?.takeIf { it > 0 }
              ?: activeChildId
            if (boundId > 0) activeChildId = boundId
            if (!com.example.location.LiveLocationTracker.isEmulator() && activeChildId > 0) {
              val batteryLevel = readBatteryLevel()
              val now = System.currentTimeMillis()
              val batterySyncInterval = if (screenInteractive) 120_000L else 300_000L
              if ((batteryLevel != lastSyncedBattery && screenInteractive) ||
                  activeChildId != lastSyncedChildId ||
                  (now - lastSyncTimeMs) >= batterySyncInterval) {
                lastSyncedBattery = batteryLevel
                lastSyncedChildId = activeChildId
                lastSyncTimeMs = now
                val apkVer = com.example.util.AppVersionInfo.installedApkVersion(applicationContext)
                dao.updateChildBattery(activeChildId, batteryLevel)
                dao.updateChildAppVersion(activeChildId, apkVer)
                cloudSync.upsertBatteryLevel(activeChildId, batteryLevel)
                if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
                  cloudSync.syncChildAppVersion(activeChildId, apkVer)
                }
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Battery sync error: ${e.message}")
          }
          // Если AccessibilityService активен — он сам ведёт редкий пульс онлайна, поэтому не дублируем сетевые пакеты
          if (!a11yRunning) {
            try {
              com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
              if (activeChildId > 0) {
                com.example.data.cloud.DirectP2pSocketEngine.startEngine("CHILD", activeChildId)
              }
              com.example.data.cloud.DirectP2pSocketEngine.ensureConnectedAndWake()
              if (activeChildId > 0) {
                com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
              }
            } catch (_: Exception) {}
          }
          delay(
            when {
              a11yRunning && screenInteractive -> 120_000L
              a11yRunning -> 240_000L
              screenInteractive -> 35_000L
              else -> 58_000L
            }
          )
        }
      }

      // 5. Listen for remote commands from Cloud reactively (zero SQLite polling loops!)
      launch {
        var currentObservedId = -1L
        var cmdJob: kotlinx.coroutines.Job? = null
        var lastPingCaptureMs = 0L
        dao.getFamilyConfig().collect { currentCfg ->
          val bootCfg = com.example.util.BootPrefs.load(applicationContext)
          if (currentCfg?.selectedRole != "CHILD" && bootCfg.permanentHardwareRole != "CHILD" && bootCfg.role != "CHILD") {
            cmdJob?.cancel()
            LockOverlayService.stop(applicationContext)
            stopSelf()
            return@collect
          }
          val boundId = currentCfg?.thisDeviceChildId?.takeIf { it > 0 }
            ?: bootCfg.childId.takeIf { it > 0 }
            ?: currentCfg?.currentActiveChildId?.takeIf { it > 0 }
            ?: activeChildId
          if (boundId > 0) activeChildId = boundId
          if (!com.example.location.LiveLocationTracker.isEmulator() && activeChildId > 0 && (cmdJob == null || !cmdJob!!.isActive || currentObservedId != activeChildId)) {
            cmdJob?.cancel()
            currentObservedId = activeChildId
            val targetObservedId = activeChildId
            cmdJob = launch(Dispatchers.IO) {
              try {
                cloudSync.observeRemoteCommands(targetObservedId).collect { cmdPair ->
                  if (com.example.location.LiveLocationTracker.isEmulator()) return@collect
                  val latestCfg = dao.getFamilyConfigOnce()
                  val latestBoot = com.example.util.BootPrefs.load(applicationContext)
                  if (latestCfg?.selectedRole != "CHILD" && latestBoot.permanentHardwareRole != "CHILD" && latestBoot.role != "CHILD") {
                    LockOverlayService.stop(applicationContext)
                    stopSelf()
                    return@collect
                  }
                  val latestId = latestCfg?.thisDeviceChildId?.takeIf { it > 0 }
                    ?: latestBoot.childId.takeIf { it > 0 }
                    ?: targetObservedId
                  if (latestId > 0) activeChildId = latestId
                  cmdPair?.let { (cmd, payload) ->
                    Log.d(TAG, "Background received remote command for child $activeChildId: $cmd")
                    when (cmd) {
                      "LOCK" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setLocalAuthoritativeLock(activeChildId, true)
                        dao.setChildDeviceLocked(activeChildId, true)
                        com.example.util.DeviceTotalLockManager.enforceTotalLock(
                          applicationContext,
                          "Экран заблокирован родительским контролем KidsGuard"
                        )
                      }
                      "UNLOCK" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setLocalAuthoritativeLock(activeChildId, false)
                        dao.setChildDeviceLocked(activeChildId, false)
                        com.example.util.DeviceTotalLockManager.releaseTotalLock(applicationContext)
                      }
                      "BLOCK_APP", "UNBLOCK_APP" -> {
                        val isBlocked = cmd == "BLOCK_APP"
                        val pkg = payload.trim()
                        if (pkg.isNotBlank()) {
                          com.example.util.BootPrefs.saveBlockedPackage(applicationContext, pkg, isBlocked)
                          KidsGuardAccessibilityService.instance?.setPackageBlockedImmediate(pkg, isBlocked)
                          dao.setAppBlockedByPackageGlobal(pkg, isBlocked)
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
                                isBlocked = isBlocked
                              )
                            )
                          }
                          cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                          if (isBlocked && DeviceUsageTracker.resolveCurrentForegroundPackage(applicationContext) == pkg) {
                            val label = DeviceUsageTracker.getFriendlyAppLabel(applicationContext, pkg)
                            com.example.ui.screens.ChildLockScreenActivity.launch(
                              context = applicationContext,
                              reason = "Программа «$label» заблокирована родителем.\nВведите PIN-код родителя, чтобы открыть программу.",
                              isDeviceTotalLock = false,
                              blockedPackageName = pkg
                            )
                          }
                        }
                      }
                      "BLOCK_DOMAIN", "UNBLOCK_DOMAIN" -> {
                        val isBlocked = cmd == "BLOCK_DOMAIN"
                        val cleanDomain = com.example.util.BootPrefs.normalizeDomain(payload)
                        if (cleanDomain.isNotBlank() && cleanDomain.contains(".")) {
                          com.example.util.BootPrefs.saveBlockedDomain(applicationContext, cleanDomain, isBlocked)
                          KidsGuardAccessibilityService.instance?.setDomainBlockedImmediate(cleanDomain, isBlocked)
                          if (isBlocked) {
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
                          } else {
                            val matches = dao.getAllContentRulesOnce()
                              .filter { it.ruleType == "DOMAIN" && com.example.util.BootPrefs.normalizeDomain(it.value) == cleanDomain }
                            for (rule in matches) {
                              dao.deleteContentRule(rule)
                            }
                          }
                          cloudSync.syncContentRulesToCloud(dao.getAllContentRulesOnce())
                        }
                      }
                      "LIMIT_APP", "SET_APP_LIMIT" -> {
                        val delim = if (payload.contains("|")) "|" else ":"
                        val pkg = payload.substringBefore(delim).trim()
                        val mins = payload.substringAfter(delim, "0").trim().toIntOrNull() ?: 0
                        if (pkg.isNotBlank()) {
                          com.example.util.BootPrefs.saveAppLimit(applicationContext, pkg, mins)
                          dao.setAppLimitByPackageGlobal(pkg, mins)
                          cloudSync.uploadChildApps(activeChildId, dao.getAppsForChildOnce(activeChildId))
                          com.example.util.DeviceControlDebugger.logEvent(
                            "ЛИМИТ_ПРОГРАММЫ",
                            "Установлен лимит для $pkg: $mins мин/день",
                            "SUCCESS",
                            "CHILD"
                          )
                        }
                      }
                      "LOUD_RING" -> {
                        com.example.util.DeviceControlDebugger.logEvent(
                          "СИРЕНА_ВКЛЮЧЕНА",
                          "Запущена громкая сирена на телефоне ребёнка #$activeChildId по команде родителя",
                          "WARNING",
                          "CHILD"
                        )
                        playLoudAlarm()
                      }
                      "STOP_LOUD_RING" -> {
                        com.example.util.DeviceControlDebugger.logEvent(
                          "СИРЕНА_ОСТАНОВЛЕНА",
                          "Громкая сирена остановлена по команде родителя",
                          "SUCCESS",
                          "CHILD"
                        )
                        com.example.util.SoundAlertManager.stopAlarm(applicationContext)
                      }
                      "SET_LIMIT" -> {
                        payload.toIntOrNull()?.let { mins ->
                          com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                            activeChildId,
                            dailyScreenLimitMinutes = mins
                          )
                          dao.setChildDailyLimit(activeChildId, mins)
                          com.example.util.DeviceControlDebugger.logEvent(
                            "ДНЕВНОЙ_ЛИМИТ",
                            "Изменён дневной лимит экранного времени: $mins мин",
                            "SUCCESS",
                            "CHILD"
                          )
                        }
                      }
                      "STUDY_ON" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                          activeChildId,
                          studyModeActive = true
                        )
                        dao.setChildStudyMode(activeChildId, true)
                        com.example.util.DeviceControlDebugger.logEvent(
                          "РЕЖИМ_УЧЁБЫ",
                          "Режим «Время учёбы» ВКЛЮЧЕН родителем",
                          "SUCCESS",
                          "CHILD"
                        )
                      }
                      "STUDY_OFF" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                          activeChildId,
                          studyModeActive = false
                        )
                        dao.setChildStudyMode(activeChildId, false)
                        com.example.util.DeviceControlDebugger.logEvent(
                          "РЕЖИМ_УЧЁБЫ",
                          "Режим «Время учёбы» ВЫКЛЮЧЕН родителем",
                          "SUCCESS",
                          "CHILD"
                        )
                      }
                      "NET_WIFI_ONLY" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                          activeChildId,
                          allowMobileData = false
                        )
                        dao.setChildAllowMobileData(activeChildId, false)
                        com.example.util.DeviceControlDebugger.logEvent(
                          "РЕЖИМ_СЕТИ",
                          "Переключён сетевой режим ребёнка: ТОЛЬКО Wi-Fi (мобильные данные 4G запрещены для сокетов)",
                          "WARNING",
                          "CHILD"
                        )
                        com.example.data.cloud.DirectP2pSocketEngine.setAllowMobileData(false)
                      }
                      "NET_ALL" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                          activeChildId,
                          allowMobileData = true
                        )
                        dao.setChildAllowMobileData(activeChildId, true)
                        com.example.util.DeviceControlDebugger.logEvent(
                          "РЕЖИМ_СЕТИ",
                          "Переключён сетевой режим ребёнка: Wi-Fi + 4G (все сети разрешены)",
                          "SUCCESS",
                          "CHILD"
                        )
                        com.example.data.cloud.DirectP2pSocketEngine.setAllowMobileData(true)
                      }
                      "PARENT_SESSION_START" -> {
                        // Родитель открыл приложение: выходим из тихого режима и сразу
                        // отдаём актуальный стейт, батарею, статус разрешений и живой снимок экрана!
                        com.example.data.cloud.DirectP2pSocketEngine.setParentSessionActive(true)
                        com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                        val sessBatt = readBatteryLevel()
                        dao.updateChildBattery(activeChildId, sessBatt)
                        cloudSync.upsertBatteryLevel(activeChildId, sessBatt)
                        com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
                        // Мгновенно отправляем 1 стартовый снимок экрана родителю при старте сессии (если экран включён)
                        if (DeviceUsageTracker.isInteractive(applicationContext)) {
                          KidsGuardAccessibilityService.instance?.triggerImmediateCaptureNow()
                        } else {
                          cloudSync.updateActiveAppOnly(activeChildId, buildStandbyScreenOffLabel(sessBatt))
                        }
                        com.example.util.AppEnergyMeter.read(applicationContext)?.let { reading ->
                          com.example.data.cloud.RealtimeCloudRelay.publishPowerStats(
                            activeChildId,
                            reading.appPercent,
                            reading.mahPerHour,
                            reading.percentPerHour
                          )
                        }
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "PARENT_SESSION_STOP" -> {
                        com.example.data.cloud.DirectP2pSocketEngine.setParentSessionActive(false)
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "PING" -> {
                        android.util.Log.d("P2P", "receive PING childId=$activeChildId")
                        com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                        val pingBatt = readBatteryLevel()
                        dao.updateChildBattery(activeChildId, pingBatt)
                        cloudSync.upsertBatteryLevel(activeChildId, pingBatt)
                        com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
                        val nowPingMs = System.currentTimeMillis()
                        val hasInitialSnap = !ScreenShotManager.capturedScreens.value.firstOrNull()?.base64Bitmap.isNullOrBlank()
                        if (DeviceUsageTracker.isInteractive(applicationContext)) {
                          // Экономия батареи: при обычном PING делаем снимок только если ещё нет ни одного кадра или идёт Live-эфир
                          if ((!hasInitialSnap || KidsGuardAccessibilityService.isLiveStreamingActiveNow()) &&
                              (nowPingMs - lastPingCaptureMs >= 15_000L)) {
                            lastPingCaptureMs = nowPingMs
                            KidsGuardAccessibilityService.instance?.triggerImmediateCaptureNow()
                          }
                        } else {
                          cloudSync.updateActiveAppOnly(activeChildId, buildStandbyScreenOffLabel(pingBatt))
                        }
                        com.example.util.AppEnergyMeter.read(applicationContext)?.let { reading ->
                          com.example.data.cloud.RealtimeCloudRelay.publishPowerStats(
                            activeChildId,
                            reading.appPercent,
                            reading.mahPerHour,
                            reading.percentPerHour
                          )
                        }
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "REQUEST_POWER_STATS" -> {
                        com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                        com.example.util.AppEnergyMeter.read(applicationContext)?.let { reading ->
                          com.example.data.cloud.RealtimeCloudRelay.publishPowerStats(
                            activeChildId,
                            reading.appPercent,
                            reading.mahPerHour,
                            reading.percentPerHour
                          )
                        }
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "REQUEST_DEBUG_LOGS" -> {
                        com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                        com.example.data.cloud.RealtimeCloudRelay.publishChildDebugLogs(
                          childId = activeChildId,
                          fullDump = true,
                          triggerReason = "Запрос лога из фонового сервиса"
                        )
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "CLEAR_DEBUG_LOGS" -> {
                        com.example.util.DeviceControlDebugger.clearLogs()
                        com.example.util.DeviceControlDebugger.logEvent(
                          "ЛОГ_РЕБЁНКА_ОЧИЩЕН",
                          "Журнал диагностики на телефоне ребёнка #$activeChildId полностью очищен по команде родителя",
                          "SUCCESS",
                          "CHILD"
                        )
                        com.example.data.cloud.RealtimeCloudRelay.publishChildDebugLogs(
                          childId = activeChildId,
                          fullDump = true,
                          triggerReason = "Подтверждение очистки лога"
                        )
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "SET_REMOTE_LOGGING" -> {
                        val en = payload.trim().equals("ON", ignoreCase = true) || payload.trim() == "1"
                        com.example.util.DeviceControlDebugger.setLoggingEnabled(applicationContext, en)
                        if (en) {
                          com.example.data.cloud.RealtimeCloudRelay.publishChildDebugLogs(
                            childId = activeChildId,
                            fullDump = true,
                            triggerReason = "Включение удалённого логирования"
                          )
                        }
                        cloudSync.clearRemoteCommand(activeChildId)
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
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "SET_MASTER_PIN" -> {
                        val cleanPin = payload.filter { it.isDigit() }
                        if (cleanPin.length in 4..8) {
                          dao.updateMasterPin(cleanPin)
                          dao.updateChildUninstallPin(cleanPin)
                          com.example.util.BootPrefs.saveMasterPin(applicationContext, cleanPin)
                        }
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "SET_PAIRING_CODE" -> {
                        val newCode = payload.trim()
                        if (newCode.length >= 6) {
                          com.example.util.BootPrefs.savePairingCode(applicationContext, newCode)
                          val existing = dao.getChildByIdOnce(activeChildId) ?: dao.getAllChildrenOnce().firstOrNull()
                          if (existing != null) {
                            val updated = existing.copy(pairingCode = newCode, isPaired = true)
                            dao.updateChild(updated)
                            com.example.data.cloud.RealtimeCloudRelay.replaceCachedChildProfile(updated)
                            com.example.data.cloud.RealtimeCloudRelay.publishChildProfile(updated, true)
                            com.example.data.cloud.RealtimeCloudRelay.publishChildPong(updated.id)
                          }
                        }
                        cloudSync.clearRemoteCommand(activeChildId)
                      }
                      "PARENT_ONLINE_HEARTBEAT", "REQUEST_GPS_NOW", "REQUEST_OFFLINE_SYNC", "MAP_VIEWING" -> {
                        com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                        val isImmediateGpsRequest = (cmd == "REQUEST_GPS_NOW" || cmd == "MAP_VIEWING")
                        val freshBatt = readBatteryLevel()
                        dao.updateChildBattery(activeChildId, freshBatt)
                        cloudSync.upsertBatteryLevel(activeChildId, freshBatt)
                        com.example.data.cloud.RealtimeCloudRelay.publishChildPong(activeChildId)
                        if (cmd == "PARENT_ONLINE_HEARTBEAT") {
                          if (!DeviceUsageTracker.isInteractive(applicationContext)) {
                            cloudSync.updateActiveAppOnly(activeChildId, buildStandbyScreenOffLabel(freshBatt))
                          }
                        }
                        if (isImmediateGpsRequest && com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
                          launch(Dispatchers.Main) {
                            startGpsTracking(requestImmediateFix = true)
                          }
                          val freshLoc = com.example.location.LiveLocationTracker.awaitHighAccuracyLocation(applicationContext, 4_000L)
                            ?: com.example.location.LiveLocationTracker.getBestImmediateLocation(applicationContext)
                          if (freshLoc != null) {
                            onNewLocation(freshLoc, forceUpload = true)
                          }
                        }
                        com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(applicationContext, activeChildId)
                      }
                      "GPS_TRACKING_ON" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                          activeChildId,
                          gpsTrackingEnabled = true
                        )
                        com.example.location.LiveLocationTracker.setGpsTrackingAllowed(true)
                        dao.setChildGpsTrackingEnabled(activeChildId, true)
                        com.example.util.DeviceControlDebugger.logEvent(
                          "РЕЖИМ_GPS",
                          "Отслеживание GPS ВКЛЮЧЕНО родителем",
                          "SUCCESS",
                          "CHILD"
                        )
                        launch(Dispatchers.Main) {
                          startGpsTracking(requestImmediateFix = true)
                        }
                      }
                      "GPS_TRACKING_OFF" -> {
                        com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeParentToggle(
                          activeChildId,
                          gpsTrackingEnabled = false
                        )
                        com.example.location.LiveLocationTracker.setGpsTrackingAllowed(false)
                        dao.setChildGpsTrackingEnabled(activeChildId, false)
                        com.example.util.DeviceControlDebugger.logEvent(
                          "РЕЖИМ_GPS",
                          "Отслеживание GPS ВЫКЛЮЧЕНО родителем",
                          "WARNING",
                          "CHILD"
                        )
                        launch(Dispatchers.Main) {
                          stopGpsTracking()
                        }
                      }
                      "REFRESH_APPS" -> {
                        serviceScope.launch(Dispatchers.IO) {
                          DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
                          val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(applicationContext, activeChildId)
                          if (realApps.isNotEmpty()) {
                            val bootBlockedSet = com.example.util.BootPrefs.getBlockedPackages(applicationContext).map { it.lowercase() }.toSet()
                            val bootLimits = com.example.util.BootPrefs.getAppLimitsMap(applicationContext)
                            val allDbApps = dao.getAllAppsOnce()
                            val anyDbBlocked = allDbApps.filter { it.isBlocked }.map { it.packageName.trim().lowercase() }.toSet()
                            val prevApps = dao.getAppsForChildOnce(activeChildId).associateBy { it.packageName.trim().lowercase() }
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
                          cloudSync.clearRemoteCommand(activeChildId)
                        }
                      }
                      "START_LIVE_STREAM" -> {
                        KidsGuardAccessibilityService.activateLiveStreamingWindow(90_000L)
                        val accSvc = KidsGuardAccessibilityService.instance
                        if (accSvc != null) {
                          accSvc.triggerImmediateCaptureNow()
                        } else {
                          val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                          ScreenShotManager.triggerHardwareScreenCapture(appLabel, false) { item ->
                            serviceScope.launch(Dispatchers.IO) {
                              if (!item.base64Bitmap.isNullOrBlank()) {
                                cloudSync.uploadScreenSnapshot(activeChildId, appLabel, item.base64Bitmap)
                              } else {
                                cloudSync.updateActiveAppOnly(activeChildId, appLabel)
                              }
                            }
                          }
                        }
                      }
                      "TAKE_SCREENSHOT" -> {
                        // Одиночный снимок экрана НЕ включает 90-секундный цикл видеопотока (экономия батареи!)
                        val accSvc = KidsGuardAccessibilityService.instance
                        if (accSvc != null) {
                          accSvc.triggerImmediateCaptureNow()
                        } else {
                          val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                          ScreenShotManager.triggerHardwareScreenCapture(appLabel, false) { item ->
                            serviceScope.launch(Dispatchers.IO) {
                              if (!item.base64Bitmap.isNullOrBlank()) {
                                cloudSync.uploadScreenSnapshot(activeChildId, appLabel, item.base64Bitmap)
                              } else {
                                cloudSync.updateActiveAppOnly(activeChildId, appLabel)
                              }
                            }
                          }
                        }
                      }
                      "QUERY_ACTIVE_APP" -> {
                        val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                        cloudSync.updateActiveAppOnly(activeChildId, appLabel)
                        KidsGuardAccessibilityService.instance?.triggerImmediateCaptureNow()
                      }
                      "STOP_LIVE_STREAM" -> {
                        KidsGuardAccessibilityService.stopLiveStreamingWindow()
                      }
                      "UPDATE_APP", "APP_UPDATE" -> {
                        cloudSync.clearRemoteCommand(activeChildId)
                        dao.setChildUpdatePending(activeChildId, true)
                        val targetVer = if (payload.contains("|")) payload.substringBefore("|").trim() else ""
                        val rawUrl = if (payload.contains("|")) payload.substringAfter("|").trim() else payload.trim()
                        val targetUrl = rawUrl.ifBlank { com.example.util.ApkUpdateInstaller.SHARED_APK_URL }
                        com.example.util.ApkUpdateInstaller.downloadAndInstallLatestApk(
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
                Log.e(TAG, "observeRemoteCommands retry: ${e.message}")
              }
            }
          }
        }
      }

      // 4. Phase 4C: Background Chat & Attention Popup Listener on Child device (works even when app UI is swiped away)
      launch(Dispatchers.IO) {
        com.example.data.cloud.RealtimeCloudRelay.incomingChatsFlow.collect { incomingMsg ->
          val cfg = dao.getFamilyConfigOnce()
          if (cfg?.selectedRole != "CHILD") return@collect
          val targetId = cfg.thisDeviceChildId.takeIf { it > 0 } ?: activeChildId
          if (incomingMsg.senderRole.equals("PARENT", ignoreCase = true)) {
            FloatingChatOverlayService.showIncomingMessageOrSos(
              context = applicationContext,
              childId = targetId,
              senderName = incomingMsg.senderName.ifBlank { "Родитель" },
              senderRole = "PARENT",
              messageText = incomingMsg.text,
              isSosAlert = incomingMsg.isAttentionSignal
            )
          }
        }
      }
    }
  }

  private fun stopGpsTracking() {
    try {
      bgLocationListener?.let { listener ->
        locationManager?.removeUpdates(listener)
      }
      fusedCallback?.let { cb ->
        fusedClient?.removeLocationUpdates(cb)
      }
      isGpsTrackingStarted = false
      Log.d(TAG, "GPS tracking stopped (Eco battery-save mode active)")
    } catch (e: Exception) {
      Log.e(TAG, "stopGpsTracking error: ${e.message}")
    }
  }

  @SuppressLint("MissingPermission")
  private fun startGpsTracking(requestImmediateFix: Boolean = false) {
    if (com.example.location.LiveLocationTracker.isEmulator()) return
    if (!com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
      stopGpsTracking()
      return
    }
    val fineGranted = androidx.core.content.ContextCompat.checkSelfPermission(
      this,
      android.Manifest.permission.ACCESS_FINE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED
    val coarseGranted = androidx.core.content.ContextCompat.checkSelfPermission(
      this,
      android.Manifest.permission.ACCESS_COARSE_LOCATION
    ) == android.content.pm.PackageManager.PERMISSION_GRANTED

    if (!fineGranted && !coarseGranted) {
      Log.w(TAG, "Location permission not granted yet")
      return
    }

    // КЛЮЧЕВАЯ ЭКОНОМИЯ БАТАРЕИ: непрерывные обновления координат (requestLocationUpdates) включаем
    // ИСКЛЮЧИТЕЛЬНО пока родитель реально открыл вкладку «Карта» (команда MAP_VIEWING).
    // Для первого фикса при старте или разового запроса REQUEST_GPS_NOW используем ТОЛЬКО однократный
    // запрос getCurrentLocation / lastLocation без бесконечного поиска спутников в помещении!
    val parentOnMap = com.example.data.cloud.DirectP2pSocketEngine.isParentMapViewing()
    val hasInitialFix = lastBgLocation != null
    val shouldRunContinuousUpdates = parentOnMap
    if (!shouldRunContinuousUpdates && isGpsTrackingStarted) {
      stopGpsTracking()
    }

    try {
      val fc = fusedClient
      if (fc != null) {
        try {
          fc.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
              onNewLocation(loc, forceUpload = requestImmediateFix || !hasInitialFix)
            }
          }
          if (requestImmediateFix || parentOnMap || !hasInitialFix) {
            val priority = if (parentOnMap || requestImmediateFix) {
              com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY
            } else {
              com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY
            }
            val oneShotReq = com.google.android.gms.location.CurrentLocationRequest.Builder()
              .setPriority(priority)
              .setMaxUpdateAgeMillis(20_000L)
              .setDurationMillis(5_500L)
              .build()
            fc.getCurrentLocation(oneShotReq, null)
              .addOnSuccessListener { loc ->
                if (loc != null && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
                  onNewLocation(loc, forceUpload = true)
                }
              }
          }
          if (shouldRunContinuousUpdates && !isGpsTrackingStarted) {
            fusedCallback?.let { prev -> try { fc.removeLocationUpdates(prev) } catch (_: Exception) {} }
            val cb = object : com.google.android.gms.location.LocationCallback() {
              override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                if (!com.example.data.cloud.DirectP2pSocketEngine.isParentMapViewing()) {
                  stopGpsTracking()
                  return
                }
                val bestInBatch = result.locations
                  .filter { !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(it.latitude, it.longitude) }
                  .minByOrNull { if (it.accuracy > 0f) it.accuracy else 999f }
                  ?: result.lastLocation
                if (bestInBatch != null) {
                  onNewLocation(bestInBatch)
                }
              }
            }
            fusedCallback = cb
            val req = com.google.android.gms.location.LocationRequest.Builder(
              com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY,
              15_000L
            )
              .setMinUpdateIntervalMillis(10_000L)
              .setMinUpdateDistanceMeters(10f)
              .setWaitForAccurateLocation(false)
              .build()
            fc.requestLocationUpdates(req, cb, android.os.Looper.getMainLooper())
            isGpsTrackingStarted = true
          }
          return
        } catch (_: Exception) {}
      }

      // 2. Fallback LocationManager
      val bestImmediate = com.example.location.LiveLocationTracker.getBestImmediateLocation(this)
      bestImmediate?.let { onNewLocation(it, forceUpload = requestImmediateFix || !hasInitialFix) }
      if (!shouldRunContinuousUpdates) return
      val lm = locationManager ?: return
      val listener = bgLocationListener ?: object : LocationListener {
        override fun onLocationChanged(location: Location) {
          if (!com.example.location.LiveLocationTracker.isGpsTrackingAllowed ||
              !com.example.data.cloud.DirectP2pSocketEngine.isParentMapViewing()) {
            stopGpsTracking()
            return
          }
          onNewLocation(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
      }.also { bgLocationListener = it }

      if (!isGpsTrackingStarted) {
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        val bgProviders = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in bgProviders) {
          try {
            if (lm.allProviders.contains(provider)) {
              lm.requestLocationUpdates(provider, 15_000L, 10f, listener, android.os.Looper.getMainLooper())
            }
          } catch (_: Exception) {}
        }
        isGpsTrackingStarted = true
      }
    } catch (e: Exception) {
      Log.e(TAG, "startGpsTracking error: ${e.message}")
    }
  }

  @Volatile
  private var lastBgLocation: Location? = null

  @Volatile
  private var lastLocationUploadTimeMs: Long = 0L

  private fun onNewLocation(loc: Location, forceUpload: Boolean = false) {
    if (com.example.location.LiveLocationTracker.isEmulator() ||
        com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
      return
    }
    val nowMs = System.currentTimeMillis()
    if (loc.accuracy in 0.1f..80f) {
      com.example.location.LiveLocationTracker.lastKnownHighAccuracyLocation = loc
    }
    val prev = lastBgLocation
    if (prev != null) {
      // Never allow a coarse cell-tower location (>150m error) to overwrite a fine GPS/Wi-Fi fix (<=65m) within 15 minutes
      if (loc.accuracy > 150f && prev.accuracy in 0.1f..65f && (nowMs - lastLocationUploadTimeMs) < 15 * 60_000L) {
        return
      }
      if (!forceUpload) {
        if ((loc.time - prev.time) < 60_000L && loc.accuracy > prev.accuracy + 35f) {
          return
        }
        val movedMeters = try { loc.distanceTo(prev) } catch (_: Exception) { 100f }
        val isMuchMoreAccurate = loc.accuracy > 0f && prev.accuracy > 0f && (prev.accuracy - loc.accuracy) >= 25f
        val heartbeatIntervalMs = if (com.example.data.cloud.DirectP2pSocketEngine.isParentMapViewing()) 20_000L else 120_000L
        if (movedMeters < 15f && !isMuchMoreAccurate && (nowMs - lastLocationUploadTimeMs) < heartbeatIntervalMs) {
          return
        }
      }
    }
    lastBgLocation = loc
    lastLocationUploadTimeMs = nowMs

    serviceScope.launch(Dispatchers.IO) {
      try {
        val database = KidsGuardDatabase.getDatabase(applicationContext)
        val dao = database.kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        val bootCfg = com.example.util.BootPrefs.load(applicationContext)
        if (cfg?.selectedRole != "CHILD" && bootCfg.permanentHardwareRole != "CHILD") return@launch
        val childId = cfg?.thisDeviceChildId?.takeIf { it > 0 }
          ?: bootCfg.childId.takeIf { it > 0 }
          ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
          ?: activeChildId.takeIf { it > 0 }
          ?: 1L
        activeChildId = childId
        val address = com.example.location.LiveLocationTracker.reverseGeocodeExact(applicationContext, loc.latitude, loc.longitude)
        dao.updateChildLocation(childId, loc.latitude, loc.longitude, address, System.currentTimeMillis())
        com.example.data.cloud.OfflineStoreAndForwardManager.recordChildLocationOffline(
          context = applicationContext,
          childId = childId,
          lat = loc.latitude,
          lng = loc.longitude,
          address = address,
          speedKmh = (loc.speed * 3.6f).coerceAtLeast(0f),
          accuracyMeters = if (loc.accuracy > 0f) loc.accuracy else 10f
        )
        cloudSync.updateLocation(childId, loc.latitude, loc.longitude, address)
        Log.d(TAG, "Real Child Location synced: ${loc.latitude}, ${loc.longitude} ($address)")
      } catch (e: Exception) {
        Log.e(TAG, "onNewLocation error: ${e.message}")
      }
    }
  }

  private fun readBatteryLevel(): Int {
    return com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(applicationContext)
  }

  private fun playLoudAlarm() {
    com.example.util.SoundAlertManager.playLoudAttentionSignal(applicationContext)
  }

  private fun bringLockScreenToFront() {
    try {
      com.example.ui.screens.ChildLockScreenActivity.launch(
        applicationContext,
        "Экран заблокирован родительским контролем KidsGuard"
      )
    } catch (_: Exception) {}
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel = NotificationChannel(
        CHANNEL_ID,
        "KidsGuard MDM • Родительский контроль",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Уведомление об активном родительском контроле KidsGuard MDM"
        setShowBadge(true)
      }
      val manager = getSystemService(NotificationManager::class.java)
      manager?.createNotificationChannel(channel)
    }
  }

  private fun buildForegroundNotification(content: String): Notification {
    val pendingIntent = PendingIntent.getActivity(
      this,
      0,
      Intent(this, MainActivity::class.java),
      PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    )

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setContentTitle("KidsGuard MDM • Родительский контроль активен")
      .setContentText(content)
      .setSmallIcon(R.mipmap.ic_launcher)
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .setContentIntent(pendingIntent)
      .build()
  }

  private fun registerScreenOffReceiver() {
    try {
      val filter = android.content.IntentFilter().apply {
        addAction(Intent.ACTION_SCREEN_OFF)
        addAction(Intent.ACTION_SCREEN_ON)
        addAction(Intent.ACTION_USER_PRESENT)
      }
      androidx.core.content.ContextCompat.registerReceiver(
        applicationContext,
        screenOffReceiver,
        filter,
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
      )
      val dozeFilter = android.content.IntentFilter(ACTION_DOZE_STANDBY_PULSE)
      androidx.core.content.ContextCompat.registerReceiver(
        applicationContext,
        dozeHeartbeatReceiver,
        dozeFilter,
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED
      )
      if (!DeviceUsageTracker.isInteractive(applicationContext)) {
        if (screenOffSinceMs == 0L) screenOffSinceMs = System.currentTimeMillis()
        scheduleNextDozeStandbyPulse()
      }
    } catch (_: Exception) {
    }
  }

  override fun onDestroy() {
    isRunning = false
    cancelDozeStandbyPulse()
    // КРИТИЧНО: НЕ отправляем publishChildOffline при остановке/перезапуске фонового сервиса,
    // так как системный KidsGuardAccessibilityService продолжает держать связь 24/7 и тут же перезапустит FGS!
    try {
      unregisterReceiver(powerConnectionReceiver)
    } catch (_: Exception) {
    }
    try {
      unregisterReceiver(screenOffReceiver)
    } catch (_: Exception) {
    }
    try {
      unregisterReceiver(dozeHeartbeatReceiver)
    } catch (_: Exception) {
    }
    super.onDestroy()
  }

  companion object {
    private const val ACTION_DOZE_STANDBY_PULSE = "com.example.ACTION_DOZE_STANDBY_PULSE"

    /** True, пока фоновый сервис жив. Watchdog в AccessibilityService проверяет его. */
    @Volatile
    var isRunning = false

    fun start(context: Context, childId: Long = 0L, enableProjectionFgs: Boolean = false) {      try {
        val intent = Intent(context, KidsGuardBackgroundService::class.java).apply {
          putExtra("CHILD_ID", childId)
          putExtra("ENABLE_PROJECTION_FGS", enableProjectionFgs)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed starting KidsGuardBackgroundService: ${e.message}")
      }
    }

    fun startWithMediaProjection(context: Context, resultCode: Int, data: Intent, childId: Long = 0L) {
      try {
        val intent = Intent(context, KidsGuardBackgroundService::class.java).apply {
          putExtra("CHILD_ID", childId)
          putExtra("ENABLE_PROJECTION_FGS", true)
          putExtra("PROJECTION_RESULT_CODE", resultCode)
          putExtra("PROJECTION_RESULT_DATA", data)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
          context.startForegroundService(intent)
        } else {
          context.startService(intent)
        }
      } catch (e: Exception) {
        Log.e(TAG, "Failed starting MediaProjection FGS: ${e.message}")
      }
    }

    fun stop(context: Context) {
      try {
        val intent = Intent(context, KidsGuardBackgroundService::class.java)
        context.stopService(intent)
      } catch (_: Exception) {}
    }
  }
}
