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
  private val powerConnectionReceiver = object : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
      when (intent?.action) {
        Intent.ACTION_POWER_CONNECTED -> com.example.util.AppEnergyMeter.reset(applicationContext)
        Intent.ACTION_POWER_DISCONNECTED -> com.example.util.AppEnergyMeter.startWindow(applicationContext)
      }
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
    createNotificationChannel()
    registerPowerMeter()
    val persistedLive = ScreenShotManager.restorePersistentLiveStreamState(applicationContext)
    promoteToForeground(includeMediaProjection = persistedLive)

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
    val persistedLive = ScreenShotManager.restorePersistentLiveStreamState(applicationContext)
    val enableProj = intent?.getBooleanExtra("ENABLE_PROJECTION_FGS", false) == true || persistedLive
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
      startGpsTracking(requestImmediateFix = true)
    }
    return START_STICKY
  }

  override fun onTaskRemoved(rootIntent: Intent?) {
    super.onTaskRemoved(rootIntent)
    // Keep foreground protection & persisted live screen streaming active when user closes active windows from Recents
    try {
      val keepLive = ScreenShotManager.restorePersistentLiveStreamState(applicationContext)
      start(applicationContext, activeChildId, enableProjectionFgs = keepLive)
    } catch (_: Exception) {}
  }

  private fun startProtectionLoops() {
    serviceScope.launch {
      val database = KidsGuardDatabase.getDatabase(applicationContext)
      val dao = database.kidsGuardDao()

      // Resolve activeChildId and run immediately on physical Child devices
      while (isActive) {
        if (com.example.location.LiveLocationTracker.isEmulator()) {
          delay(5000)
          continue
        }
        val config = dao.getFamilyConfigOnce()
        val isChildRole = config?.selectedRole == "CHILD"
        if (!isChildRole) {
          activeChildId = 0L
          LockOverlayService.stop(applicationContext)
          stopSelf()
          return@launch
        }
        val children = dao.getAllChildrenOnce()
        val resolvedId = when {
          config != null && config.thisDeviceChildId > 0 && children.any { it.id == config.thisDeviceChildId } -> config.thisDeviceChildId
          activeChildId > 0 && children.any { it.id == activeChildId } -> activeChildId
          config != null && config.currentActiveChildId > 0 && children.any { it.id == config.currentActiveChildId } -> config.currentActiveChildId
          children.isNotEmpty() -> children.last().id
          else -> 1L
        }
        if (resolvedId > 0) {
          activeChildId = resolvedId
          if (config != null && config.thisDeviceChildId != resolvedId) {
            dao.setThisDeviceChildId(resolvedId)
          }
          break
        }
        delay(1500)
      }

      Log.d(TAG, "Background service running for Child ID: $activeChildId")
      com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
      com.example.util.CoreEngineHotUpdater.initializeHotCore(applicationContext)

      // Never auto-lock on service startup unless a fresh authoritative lock was explicitly sent
      val startupAuthLock = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(activeChildId)
      if (startupAuthLock?.first != true) {
        dao.setChildDeviceLocked(activeChildId, false)
        com.example.util.DeviceTotalLockManager.releaseTotalLock(applicationContext)
      }

      // Sync real installed apps from Child's physical phone to Cloud on service start
      launch(Dispatchers.IO) {
        try {
          DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
          val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(applicationContext, activeChildId)
          if (realApps.isNotEmpty()) {
            dao.deleteAppsForChild(activeChildId)
            dao.insertApps(realApps)
            if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              cloudSync.uploadChildApps(activeChildId, realApps)
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
          val gpsEnabled = child?.gpsTrackingEnabled ?: true
          com.example.location.LiveLocationTracker.setGpsTrackingAllowed(gpsEnabled)
          launch(Dispatchers.Main) {
            if (gpsEnabled) {
              if (!isGpsTrackingStarted) startGpsTracking(requestImmediateFix = true)
            } else {
              stopGpsTracking()
            }
          }
        }
      }

      // 2. Active Foreground App, On-Demand Live Screen & App Block Enforcement Loop (Battery-Optimized)
      launch(Dispatchers.IO) {
        var lastUploadedAppLabel = ""
        var lastAppUploadTimeMs = 0L
        while (isActive) {
          val isScreenInteractive = DeviceUsageTracker.isInteractive(applicationContext)
          val isLiveStreamActive = KidsGuardAccessibilityService.isLiveStreamingActiveNow()
          val isA11yActive = KidsGuardAccessibilityService.isAccessibilityEnabled()
          val loopDelayMs = when {
            !isScreenInteractive -> 45_000L
            isLiveStreamActive -> 3_500L
            isA11yActive -> 20_000L // AccessibilityService already intercepts apps instantaneously
            else -> 6_000L
          }
          delay(loopDelayMs)
          try {
            if (!isScreenInteractive || com.example.location.LiveLocationTracker.isEmulator()) continue
            val latestCfg = dao.getFamilyConfigOnce()
            if (latestCfg?.selectedRole != "CHILD") continue
            val boundId = latestCfg.thisDeviceChildId.takeIf { it > 0 } ?: activeChildId
            if (boundId > 0) activeChildId = boundId
            if (activeChildId <= 0) continue

            val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
            val activePkg = DeviceUsageTracker.resolveCurrentForegroundPackage(applicationContext)

            // Check 00:00 Midnight Reset before evaluating any daily limit
            DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)

            // Enforce Phone Lock ONLY when an authoritative lock is active; release immediately if unlocked!
            val authLocked = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(activeChildId)?.first
            val isLockedNow = authLocked == true
            val isCoreWhitelisted = DeviceUsageTracker.isWhitelistedCoreMdmPackage(applicationContext, activePkg)

            if (!isLockedNow && com.example.util.DeviceTotalLockManager.isCurrentlyLocked) {
              dao.setChildDeviceLocked(activeChildId, false)
              com.example.util.DeviceTotalLockManager.releaseTotalLock(applicationContext)
            } else if (isLockedNow && !com.example.util.DeviceTotalLockManager.isCurrentlyLocked) {
              com.example.util.DeviceTotalLockManager.enforceTotalLock(applicationContext, "Экран заблокирован родителем")
            } else if (!isLockedNow && !activePkg.isNullOrBlank() && !isCoreWhitelisted) {
              // Explicitly whitelist KidsGuard & core system UI so KidsGuard NEVER blocks or kills its own MDM service!
              val childProfile = dao.getChildByIdOnce(activeChildId)
              val dailyLimit = childProfile?.dailyScreenLimitMinutes ?: 0
              val usedToday = childProfile?.usedScreenTimeMinutes ?: 0
              val appEntity = dao.getAppByPackageOnce(activeChildId, activePkg)
              if (appEntity?.isBlocked == true) {
                com.example.ui.screens.ChildLockScreenActivity.launch(
                  applicationContext,
                  "Приложение «${appEntity.appName}» заблокировано родительским контролем KidsGuard"
                )
              } else if (dailyLimit in 1..usedToday) {
                com.example.ui.screens.ChildLockScreenActivity.launch(
                  applicationContext,
                  "Дневной лимит экранного времени ($dailyLimit мин) на сегодня исчерпан!"
                )
              }
            }

            // CRITICAL BATTERY FIX: Only capture & stream live screen frames when the Parent is actively watching Live Stream!
            val nowMs = System.currentTimeMillis()
            val isParentWatchingLive = KidsGuardAccessibilityService.isLiveStreamingActiveNow()
            val isParentConnected = com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()
            if (!isLockedNow && isParentWatchingLive && !isA11yActive) {
              ScreenShotManager.triggerHardwareScreenCapture(appLabel, false) { item ->
                serviceScope.launch(Dispatchers.IO) {
                  if (!item.base64Bitmap.isNullOrBlank()) {
                    cloudSync.uploadScreenSnapshot(activeChildId, appLabel, item.base64Bitmap)
                  }
                }
              }
              lastUploadedAppLabel = appLabel
              lastAppUploadTimeMs = nowMs
            } else if (isParentConnected && appLabel.isNotBlank() && (appLabel != lastUploadedAppLabel || (nowMs - lastAppUploadTimeMs) >= 180_000L)) {
              // Parent-presence gated telemetry: only transmit over network when Parent is actively connected
              lastUploadedAppLabel = appLabel
              lastAppUploadTimeMs = nowMs
              cloudSync.updateActiveAppOnly(activeChildId, appLabel)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Active app sync loop error: ${e.message}")
          }
        }
      }

      // 3. Screen Usage Loop (every 45 seconds, zero network calls when screen is off)
      launch {
        var screenOnSeconds = 0
        while (isActive) {
          delay(45_000)
          try {
            if (com.example.location.LiveLocationTracker.isEmulator()) continue
            val latestCfg = dao.getFamilyConfigOnce()
            val boundId = latestCfg?.thisDeviceChildId?.takeIf { it > 0 } ?: activeChildId
            if (boundId > 0) activeChildId = boundId
            if (!isGpsTrackingStarted && com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
              launch(Dispatchers.Main) { startGpsTracking(requestImmediateFix = false) }
            }
            if (activeChildId > 0) {
              val isScreenOn = DeviceUsageTracker.isInteractive(applicationContext)
              // Always check 00:00 midnight reset even if screen is off
              DeviceUsageTracker.ensureDailyMidnightReset(applicationContext, activeChildId)
              if (isScreenOn) {
                screenOnSeconds += 45
                if (screenOnSeconds >= 90) {
                  screenOnSeconds = 0
                  DeviceUsageTracker.syncRealUsageStats(applicationContext, activeChildId)
                }
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Usage sync loop error: ${e.message}")
          }
        }
      }

      // 4. Smart Battery & Telemetry loop (strictly bound to thisDeviceChildId; network gated on Parent presence)
      launch {
        var lastSyncedBattery = -1
        var lastSyncedChildId = -1L
        var lastSyncTimeMs = 0L
        var lastEnergyPublishMs = 0L
        while (isActive) {
          try {
            val latestCfg = dao.getFamilyConfigOnce()
            val boundId = latestCfg?.thisDeviceChildId?.takeIf { it > 0 }
              ?: latestCfg?.currentActiveChildId?.takeIf { it > 0 }
              ?: activeChildId
            if (boundId > 0) activeChildId = boundId
            if (!com.example.location.LiveLocationTracker.isEmulator() && activeChildId > 0) {
              val batteryLevel = readBatteryLevel()
              val now = System.currentTimeMillis()
              if (batteryLevel != lastSyncedBattery || activeChildId != lastSyncedChildId || (now - lastSyncTimeMs) >= 180_000L) {
                lastSyncedBattery = batteryLevel
                lastSyncedChildId = activeChildId
                lastSyncTimeMs = now
                // В профиль ребёнка пишем версию установленного APK, а не «ядра»:
                // иначе родитель видит .22 при старом APK и не предлагает установку.
                val apkVer = com.example.BuildConfig.VERSION_NAME
                dao.updateChildBattery(activeChildId, batteryLevel)
                dao.updateChildAppVersion(activeChildId, apkVer)
                if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
                  cloudSync.upsertBatteryLevel(activeChildId, batteryLevel)
                  cloudSync.syncChildAppVersion(activeChildId, apkVer)
                }
              }
              // Оценка расхода энергии приложения — раз в минуту и только когда родитель
              // на связи (одно чтение системных счётчиков, без истории и без сети зря).
              val energyNow = System.currentTimeMillis()
              if (energyNow - lastEnergyPublishMs >= 60_000L &&
                com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()
              ) {
                lastEnergyPublishMs = energyNow
                com.example.util.AppEnergyMeter.read(applicationContext)?.let { reading ->
                  com.example.data.cloud.RealtimeCloudRelay.publishPowerStats(
                    activeChildId,
                    reading.appPercent,
                    reading.mahPerHour,
                    reading.percentPerHour
                  )
                }
              }
            }
          } catch (e: Exception) {
            Log.e(TAG, "Battery sync error: ${e.message}")
          }
          delay(60_000)
        }
      }

      // 5. Listen for remote commands from Cloud reactively (zero SQLite polling loops!)
      launch {
        var currentObservedId = -1L
        var cmdJob: kotlinx.coroutines.Job? = null
        dao.getFamilyConfig().collect { currentCfg ->
          if (currentCfg?.selectedRole != "CHILD") {
            cmdJob?.cancel()
            LockOverlayService.stop(applicationContext)
            stopSelf()
            return@collect
          }
          val boundId = currentCfg.thisDeviceChildId.takeIf { it > 0 }
            ?: currentCfg.currentActiveChildId.takeIf { it > 0 }
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
                  if (latestCfg?.selectedRole != "CHILD") {
                    LockOverlayService.stop(applicationContext)
                    stopSelf()
                    return@collect
                  }
                  val latestId = latestCfg.thisDeviceChildId.takeIf { it > 0 } ?: targetObservedId
                  if (latestId > 0) activeChildId = latestId
                  cmdPair?.let { (cmd, payload) ->
                    Log.d(TAG, "Background received remote command for child $activeChildId: $cmd")
                    when (cmd) {
                      "LOCK" -> {
                        com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(activeChildId, true)
                        dao.setChildDeviceLocked(activeChildId, true)
                        com.example.util.DeviceTotalLockManager.enforceTotalLock(
                          applicationContext,
                          "Экран заблокирован родительским контролем KidsGuard"
                        )
                      }
                      "UNLOCK" -> {
                        com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(activeChildId, false)
                        dao.setChildDeviceLocked(activeChildId, false)
                        com.example.util.DeviceTotalLockManager.releaseTotalLock(applicationContext)
                      }
                      "BLOCK_APP", "UNBLOCK_APP" -> {
                        val isBlocked = cmd == "BLOCK_APP"
                        val pkg = payload.trim()
                        if (pkg.isNotBlank()) {
                          val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                          if (existing != null) {
                            dao.setAppBlocked(existing.id, isBlocked)
                          } else {
                            // Strictly verify that the package is actually installed on this Child device before inserting
                            val installedSet = com.example.util.DeviceAppsProvider.getInstalledPackageSet(applicationContext)
                            if (pkg.lowercase() in installedSet) {
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
                          }
                          if (isBlocked && DeviceUsageTracker.resolveCurrentForegroundPackage(applicationContext) == pkg) {
                            bringLockScreenToFront()
                          }
                        }
                      }
                      "LIMIT_APP", "SET_APP_LIMIT" -> {
                        val delim = if (payload.contains("|")) "|" else ":"
                        val pkg = payload.substringBefore(delim).trim()
                        val mins = payload.substringAfter(delim, "0").trim().toIntOrNull() ?: 0
                        if (pkg.isNotBlank()) {
                          val existing = dao.getAppByPackageOnce(activeChildId, pkg)
                          if (existing != null) {
                            dao.setAppLimit(existing.id, mins)
                          }
                        }
                      }
                      "LOUD_RING" -> {
                        playLoudAlarm()
                      }
                      "STOP_LOUD_RING" -> {
                        com.example.util.SoundAlertManager.stopAlarm(applicationContext)
                      }
                      "SET_LIMIT" -> {
                        payload.toIntOrNull()?.let { dao.setChildDailyLimit(activeChildId, it) }
                      }
                      "PARENT_ONLINE_HEARTBEAT", "REQUEST_GPS_NOW", "REQUEST_OFFLINE_SYNC" -> {
                        com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(activeChildId)
                        val freshBatt = readBatteryLevel()
                        dao.updateChildBattery(activeChildId, freshBatt)
                        cloudSync.upsertBatteryLevel(activeChildId, freshBatt)
                        if (com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
                          launch(Dispatchers.Main) {
                            startGpsTracking(requestImmediateFix = cmd == "REQUEST_GPS_NOW")
                          }
                          val freshLoc = if (cmd == "REQUEST_GPS_NOW") {
                            com.example.location.LiveLocationTracker.awaitHighAccuracyLocation(applicationContext, 4_000L)
                              ?: com.example.location.LiveLocationTracker.getBestImmediateLocation(applicationContext)
                          } else {
                            com.example.location.LiveLocationTracker.getBestImmediateLocation(applicationContext)
                          }
                          if (freshLoc != null) {
                            onNewLocation(freshLoc, forceUpload = true)
                          }
                        }
                        com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(applicationContext, activeChildId)
                      }
                      "GPS_TRACKING_ON" -> {
                        com.example.location.LiveLocationTracker.setGpsTrackingAllowed(true)
                        dao.setChildGpsTrackingEnabled(activeChildId, true)
                        launch(Dispatchers.Main) {
                          startGpsTracking(requestImmediateFix = true)
                        }
                      }
                      "GPS_TRACKING_OFF" -> {
                        com.example.location.LiveLocationTracker.setGpsTrackingAllowed(false)
                        dao.setChildGpsTrackingEnabled(activeChildId, false)
                        launch(Dispatchers.Main) {
                          stopGpsTracking()
                        }
                      }
                      "REFRESH_APPS" -> {
                        serviceScope.launch(Dispatchers.IO) {
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
                          cloudSync.clearRemoteCommand(activeChildId)
                        }
                      }
                      "START_LIVE_STREAM" -> {
                        KidsGuardAccessibilityService.activateLiveStreamingWindow(90_000L)
                      }
                      "TAKE_SCREENSHOT" -> {
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
                      "QUERY_ACTIVE_APP" -> {
                        val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(applicationContext)
                        cloudSync.updateActiveAppOnly(activeChildId, appLabel)
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
    try {
      // 1. Primary Eco-Smart FusedLocationProviderClient (Balanced power in background, High Accuracy only on explicit on-demand request)
      val fc = fusedClient
      if (fc != null) {
        try {
          fc.lastLocation.addOnSuccessListener { loc ->
            if (loc != null && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
              onNewLocation(loc)
            }
          }
          if (requestImmediateFix) {
            val highAccReq = com.google.android.gms.location.CurrentLocationRequest.Builder()
              .setPriority(com.google.android.gms.location.Priority.PRIORITY_HIGH_ACCURACY)
              .setMaxUpdateAgeMillis(10_000L)
              .setDurationMillis(6_000L)
              .build()
            fc.getCurrentLocation(highAccReq, null)
              .addOnSuccessListener { loc ->
                if (loc != null && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
                  onNewLocation(loc, forceUpload = true)
                }
              }
          }
          if (!isGpsTrackingStarted) {
            fusedCallback?.let { prev -> try { fc.removeLocationUpdates(prev) } catch (_: Exception) {} }
            val cb = object : com.google.android.gms.location.LocationCallback() {
              override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
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
            // Use PRIORITY_BALANCED_POWER_ACCURACY with 60s interval and 20m displacement for continuous tracking
            // so the GNSS radio sleeps when the child is stationary indoors!
            val req = com.google.android.gms.location.LocationRequest.Builder(
              com.google.android.gms.location.Priority.PRIORITY_BALANCED_POWER_ACCURACY,
              60_000L
            )
              .setMinUpdateIntervalMillis(25_000L)
              .setMinUpdateDistanceMeters(20f)
              .setWaitForAccurateLocation(false)
              .build()
            fc.requestLocationUpdates(req, cb, android.os.Looper.getMainLooper())
            isGpsTrackingStarted = true
          }
          return
        } catch (_: Exception) {}
      }

      // 2. Fallback LocationManager ONLY if Google Play Services FusedLocationProviderClient is unavailable
      val lm = locationManager ?: return
      val listener = bgLocationListener ?: object : LocationListener {
        override fun onLocationChanged(location: Location) {
          if (!com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
            stopGpsTracking()
            return
          }
          onNewLocation(location)
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
      }.also { bgLocationListener = it }

      val bestImmediate = com.example.location.LiveLocationTracker.getBestImmediateLocation(this)
      bestImmediate?.let { onNewLocation(it, forceUpload = requestImmediateFix) }

      if (!isGpsTrackingStarted) {
        try { lm.removeUpdates(listener) } catch (_: Exception) {}
        val bgProviders = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        for (provider in bgProviders) {
          try {
            if (lm.allProviders.contains(provider)) {
              lm.requestLocationUpdates(provider, 60_000L, 20f, listener, android.os.Looper.getMainLooper())
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
        // Stationary deduplication: ignore <15m GPS drift within 5 minutes unless new fix is significantly more accurate
        val movedMeters = try { loc.distanceTo(prev) } catch (_: Exception) { 100f }
        val isMuchMoreAccurate = loc.accuracy > 0f && prev.accuracy > 0f && (prev.accuracy - loc.accuracy) >= 25f
        if (movedMeters < 15f && !isMuchMoreAccurate && (nowMs - lastLocationUploadTimeMs) < 300_000L) {
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
        if (cfg?.selectedRole != "CHILD") return@launch
        val childId = cfg.thisDeviceChildId.takeIf { it > 0 }
          ?: cfg.currentActiveChildId.takeIf { it > 0 }
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
        if (forceUpload || com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
          cloudSync.updateLocation(childId, loc.latitude, loc.longitude, address)
          Log.d(TAG, "Real Child Location synced: ${loc.latitude}, ${loc.longitude} ($address)")
        }
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

  override fun onDestroy() {
    try {
      unregisterReceiver(powerConnectionReceiver)
    } catch (_: Exception) {
    }
    super.onDestroy()
  }

  companion object {
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
