package com.example.util

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.example.data.cloud.CloudSyncManager
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.AppControlEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

private const val TAG = "DeviceUsageTracker"
private const val DAILY_LIMIT_PREFS = "kidsguard_daily_limit_prefs"
private const val KEY_LAST_RESET_DATE_ISO = "last_reset_date_iso"

object DeviceUsageTracker {
  private val _currentForegroundApp = MutableStateFlow<String>("Определение активного окна...")
  val currentForegroundApp: StateFlow<String> = _currentForegroundApp.asStateFlow()

  @Volatile
  private var lastKnownPackage: String? = null

  private val WHITELISTED_SYSTEM_PREFIXES = listOf(
    "com.aistudio.kidsguard",
    "com.example.kidsguard",
    "com.android.systemui",
    "com.android.permissioncontroller",
    "com.google.android.permissioncontroller",
    "com.android.packageinstaller",
    "com.google.android.packageinstaller",
    "com.samsung.android.packageinstaller",
    "com.miui.packageinstaller"
  )

  /**
   * Explicit MDM Core Whitelist:
   * Ensures KidsGuard and critical Android system/UI packages are NEVER blocked or killed
   * when the child's daily usage limit expires.
   */
  fun isWhitelistedCoreMdmPackage(context: Context, packageName: String?): Boolean {
    if (packageName.isNullOrBlank()) return true
    if (packageName == context.packageName) return true
    val lower = packageName.lowercase(Locale.ROOT)
    if (lower.contains("kidsguard")) return true
    if (WHITELISTED_SYSTEM_PREFIXES.any { lower.startsWith(it) }) return true
    if (lower.contains("inputmethod") || lower.contains("keyboard") || lower.contains("latinime")) return true
    return false
  }

  /**
   * Returns today's calendar date in ISO format (e.g. "2025-05-29") based on device clock.
   */
  fun currentDateIso(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
  }

  /**
   * Returns the exact epoch milliseconds of 00:00:00.000 (midnight) for the current day.
   */
  fun getStartOfTodayMidnightMillis(): Long {
    return Calendar.getInstance().apply {
      set(Calendar.HOUR_OF_DAY, 0)
      set(Calendar.MINUTE, 0)
      set(Calendar.SECOND, 0)
      set(Calendar.MILLISECOND, 0)
    }.timeInMillis
  }

  /**
   * Resets accumulated daily usage time (`usedScreenTimeMinutes` and `usedMinutesToday`)
   * to zero at 00:00 (midnight) when a new calendar day starts.
   * Prevents yesterday's and previous days' usage from accumulating into today's statistics.
   */
  suspend fun ensureDailyMidnightReset(context: Context, childId: Long = 0L): Boolean {
    val todayIso = currentDateIso()
    val prefs = context.applicationContext.getSharedPreferences(DAILY_LIMIT_PREFS, Context.MODE_PRIVATE)
    val lastResetDate = prefs.getString(KEY_LAST_RESET_DATE_ISO, null)

    if (lastResetDate != todayIso) {
      val db = KidsGuardDatabase.getDatabase(context.applicationContext)
      val dao = db.kidsGuardDao()
      if (childId > 0L) {
        dao.resetChildUsedScreenTimeToday(childId)
        dao.resetAllAppsUsedMinutesToday(childId)
      }
      dao.resetAllChildrenUsedScreenTimeToday()
      dao.resetAllAppsUsedMinutesTodayGlobal()
      prefs.edit().putString(KEY_LAST_RESET_DATE_ISO, todayIso).apply()
      Log.d(TAG, "Midnight 00:00 reset executed for date $todayIso (childId=$childId)")
      return true
    }
    return false
  }

  fun setForegroundApp(name: String, packageName: String? = null) {
    if (name.isNotBlank()) {
      _currentForegroundApp.value = name
    }
    if (!packageName.isNullOrBlank()) {
      lastKnownPackage = packageName
    }
  }

  /**
   * Resolves the real currently active app on the child's phone using both
   * AccessibilityService window state and UsageStatsManager events.
   */
  fun resolveCurrentForegroundApp(context: Context): String {
    try {
      val pm = context.packageManager
      val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
      val homePkg = pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName

      // 1. Query UsageStatsManager for the latest foreground event in the last 10 minutes
      val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
      if (usm != null) {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10 * 60 * 1000L
        val usageEvents = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTimestamp = 0L

        while (usageEvents != null && usageEvents.hasNextEvent()) {
          usageEvents.getNextEvent(event)
          if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
              event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            val pkg = event.packageName
            if (!pkg.isNullOrBlank() &&
                pkg != context.packageName &&
                !pkg.startsWith("com.android.systemui")) {
              if (event.timeStamp >= latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestPkg = pkg
              }
            }
          }
        }

        if (!latestPkg.isNullOrBlank()) {
          lastKnownPackage = latestPkg
          val label = getFriendlyAppLabel(context, latestPkg, homePkg)
          _currentForegroundApp.value = label
          return label
        }

        // Fallback to most recently used package from queryUsageStats
        val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, endTime - 3600_000L, endTime)
        val recentStat = stats
          ?.filter {
            it.packageName != context.packageName &&
            !it.packageName.startsWith("com.android.systemui") &&
            it.lastTimeUsed > 0
          }
          ?.maxByOrNull { it.lastTimeUsed }

        if (recentStat != null) {
          lastKnownPackage = recentStat.packageName
          val label = getFriendlyAppLabel(context, recentStat.packageName, homePkg)
          _currentForegroundApp.value = label
          return label
        }
      }

      // 2. Check lastKnownPackage from AccessibilityService
      val a11yPkg = lastKnownPackage
      if (!a11yPkg.isNullOrBlank()) {
        val label = getFriendlyAppLabel(context, a11yPkg, homePkg)
        _currentForegroundApp.value = label
        return label
      }
    } catch (e: Exception) {
      Log.e(TAG, "resolveCurrentForegroundApp error: ${e.message}")
    }

    val current = _currentForegroundApp.value
    return if (current.isBlank() || current == "Определение активного окна...") {
      "Главный экран Android"
    } else {
      current
    }
  }

  /**
   * Returns the raw packageName of the currently active foreground app on the Child device.
   */
  fun resolveCurrentForegroundPackage(context: Context): String? {
    try {
      val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
      if (usm != null) {
        val endTime = System.currentTimeMillis()
        val startTime = endTime - 10 * 60 * 1000L
        val usageEvents = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()
        var latestPkg: String? = null
        var latestTimestamp = 0L

        while (usageEvents != null && usageEvents.hasNextEvent()) {
          usageEvents.getNextEvent(event)
          if (event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
              event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND) {
            val pkg = event.packageName
            if (!pkg.isNullOrBlank() &&
                pkg != context.packageName &&
                !pkg.startsWith("com.android.systemui")) {
              if (event.timeStamp >= latestTimestamp) {
                latestTimestamp = event.timeStamp
                latestPkg = pkg
              }
            }
          }
        }
        if (!latestPkg.isNullOrBlank()) {
          lastKnownPackage = latestPkg
          return latestPkg
        }
      }
    } catch (_: Exception) {}
    return lastKnownPackage
  }

  private val labelCache = java.util.concurrent.ConcurrentHashMap<String, String>()

  fun getFriendlyAppLabel(context: Context, packageName: String, homePkg: String? = null): String {
    if (packageName == homePkg || packageName.contains("launcher", ignoreCase = true)) {
      return "Главный экран (Лаунчер)"
    }
    labelCache[packageName]?.let { return it }
    val resolved = try {
      val pm = context.packageManager
      val ai = pm.getApplicationInfo(packageName, 0)
      val label = pm.getApplicationLabel(ai).toString()
      if (label.isNotBlank()) label else packageName
    } catch (_: Exception) {
      packageName.substringAfterLast('.').replaceFirstChar { it.uppercase() }
    }
    labelCache[packageName] = resolved
    return resolved
  }

  fun isInteractive(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return pm?.isInteractive ?: true
  }

  suspend fun recordAppSessionTime(context: Context, childId: Long, packageName: String, minutesToAdd: Int) {
    if (minutesToAdd <= 0 || childId <= 0) return
    withContext(Dispatchers.IO) {
      try {
        ensureDailyMidnightReset(context, childId)
        if (isWhitelistedCoreMdmPackage(context, packageName)) return@withContext

        val database = KidsGuardDatabase.getDatabase(context)
        val dao = database.kidsGuardDao()
        // CRITICAL (Phase 4B): Never insert or mutate local apps on the Parent's phone!
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD") return@withContext
        val cloudSync = CloudSyncManager(context)

        // 1. Update or insert app entity
        val app = dao.getAppByPackageOnce(childId, packageName)
        if (app != null) {
          val newUsage = app.usedMinutesToday + minutesToAdd
          dao.updateApp(app.copy(usedMinutesToday = newUsage))
        } else {
          val friendlyName = getFriendlyAppLabel(context, packageName)
          val newApp = AppControlEntity(
            childId = childId,
            packageName = packageName,
            appName = friendlyName,
            iconType = "generic",
            category = "Приложения",
            dailyLimitMinutes = 0,
            usedMinutesToday = minutesToAdd,
            isBlocked = false
          )
          dao.insertApp(newApp)
        }

        // 2. Increment child's screen time strictly for today
        val child = dao.getChildByIdOnce(childId)
        if (child != null) {
          val updatedMinutes = child.usedScreenTimeMinutes + minutesToAdd
          val updatedChild = child.copy(usedScreenTimeMinutes = updatedMinutes)
          dao.updateChild(updatedChild)
          // Battery-saving gate: only push real-time packets when Parent is actively connected
          if (DirectP2pSocketEngine.isParentActivelyConnected()) {
            cloudSync.upsertChildProfile(updatedChild)
          }
          Log.d(TAG, "Screen time updated: +$minutesToAdd min. Total for child ${child.name}: $updatedMinutes min")
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error recording app usage: ${e.message}")
      }
    }
  }

  suspend fun recordScreenOnTime(context: Context, childId: Long, minutesToAdd: Int) {
    if (minutesToAdd <= 0 || childId <= 0) return
    withContext(Dispatchers.IO) {
      try {
        ensureDailyMidnightReset(context, childId)
        val database = KidsGuardDatabase.getDatabase(context)
        val dao = database.kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD") return@withContext
        val cloudSync = CloudSyncManager(context)

        val child = dao.getChildByIdOnce(childId)
        if (child != null) {
          val updated = child.copy(usedScreenTimeMinutes = child.usedScreenTimeMinutes + minutesToAdd)
          dao.updateChild(updated)
          if (DirectP2pSocketEngine.isParentActivelyConnected()) {
            cloudSync.upsertChildProfile(updated)
          }
          Log.d(TAG, "Screen-On minute recorded: Total ${updated.usedScreenTimeMinutes} min")
        }
      } catch (e: Exception) {
        Log.e(TAG, "recordScreenOnTime error: ${e.message}")
      }
    }
  }

  private fun isSystemOrLauncherPackage(pkg: String, currentPkg: String, homePkg: String?): Boolean {
    if (pkg.isBlank()) return true
    if (pkg == currentPkg || pkg == homePkg) return true
    val lower = pkg.lowercase()
    return lower.startsWith("com.android.systemui") ||
        lower.contains("launcher") ||
        lower == "com.miui.home" ||
        lower == "com.sec.android.app.launcher" ||
        lower == "com.google.android.apps.nexuslauncher" ||
        lower == "com.android.settings" ||
        lower.contains("packageinstaller") ||
        lower.contains("permissioncontroller") ||
        lower == "com.miui.securitycenter"
  }

  suspend fun syncRealUsageStats(context: Context, childId: Long) {
    if (childId <= 0) return
    withContext(Dispatchers.IO) {
      try {
        ensureDailyMidnightReset(context, childId)
        val database = KidsGuardDatabase.getDatabase(context)
        val dao = database.kidsGuardDao()
        // CRITICAL (Phase 4B): Never scan local UsageStatsManager or insert local apps on the Parent's phone!
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "CHILD") return@withContext

        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager ?: return@withContext
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePkg = try {
          pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        } catch (_: Exception) {
          null
        }
        val currentPkg = context.packageName

        val startTime = getStartOfTodayMidnightMillis()
        val endTime = System.currentTimeMillis()

        // Compute exact foreground time strictly from today's UsageEvents (since 00:00)
        // so yesterday's 24h bucket or launcher idle time never inflates today's counters.
        val fgDurationMsByPkg = mutableMapOf<String, Long>()
        val activeStartByPkg = mutableMapOf<String, Long>()
        val usageEvents = usm.queryEvents(startTime, endTime)
        val event = UsageEvents.Event()

        while (usageEvents != null && usageEvents.hasNextEvent()) {
          usageEvents.getNextEvent(event)
          val pkg = event.packageName ?: continue
          if (isSystemOrLauncherPackage(pkg, currentPkg, homePkg) || isWhitelistedCoreMdmPackage(context, pkg)) continue
          val eventTs = event.timeStamp.coerceIn(startTime, endTime)

          when (event.eventType) {
            UsageEvents.Event.ACTIVITY_RESUMED,
            UsageEvents.Event.MOVE_TO_FOREGROUND -> {
              activeStartByPkg[pkg] = eventTs
            }
            UsageEvents.Event.ACTIVITY_PAUSED,
            UsageEvents.Event.MOVE_TO_BACKGROUND,
            UsageEvents.Event.ACTIVITY_STOPPED -> {
              val resumeTs = activeStartByPkg.remove(pkg)
              if (resumeTs != null && eventTs > resumeTs) {
                val delta = (eventTs - resumeTs).coerceAtMost(2 * 3600_000L)
                fgDurationMsByPkg[pkg] = (fgDurationMsByPkg[pkg] ?: 0L) + delta
              }
            }
          }
        }

        val cloudSync = CloudSyncManager(context)
        val existingApps = dao.getAppsForChildOnce(childId)
        var totalScreenMinutes = 0

        val visitedPackages = mutableSetOf<String>()
        for (app in existingApps) {
          visitedPackages.add(app.packageName)
          val ms = if (isSystemOrLauncherPackage(app.packageName, currentPkg, homePkg) ||
              isWhitelistedCoreMdmPackage(context, app.packageName)) {
            0L
          } else {
            fgDurationMsByPkg[app.packageName] ?: 0L
          }
          val minutes = (ms / 60_000L).toInt()
          totalScreenMinutes += minutes
          if (app.usedMinutesToday != minutes) {
            dao.updateApp(app.copy(usedMinutesToday = minutes))
          }
        }

        for ((pkg, ms) in fgDurationMsByPkg) {
          if (pkg in visitedPackages) continue
          val minutes = (ms / 60_000L).toInt()
          if (minutes > 0) {
            totalScreenMinutes += minutes
            val friendlyName = getFriendlyAppLabel(context, pkg, homePkg)
            dao.insertApp(
              AppControlEntity(
                childId = childId,
                packageName = pkg,
                appName = friendlyName,
                iconType = "generic",
                category = "Приложения",
                dailyLimitMinutes = 0,
                usedMinutesToday = minutes,
                isBlocked = false
              )
            )
          }
        }

        val child = dao.getChildByIdOnce(childId)
        if (child != null && child.usedScreenTimeMinutes != totalScreenMinutes) {
          val updated = child.copy(usedScreenTimeMinutes = totalScreenMinutes)
          dao.updateChild(updated)
          if (DirectP2pSocketEngine.isParentActivelyConnected()) {
            cloudSync.upsertChildProfile(updated)
          }
        }
        Log.d(TAG, "Synced exact real usage stats since midnight. Total: $totalScreenMinutes mins")
      } catch (e: Exception) {
        Log.e(TAG, "syncRealUsageStats error: ${e.message}")
      }
    }
  }
}
