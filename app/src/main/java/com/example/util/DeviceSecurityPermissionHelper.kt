package com.example.util

import android.Manifest
import android.app.Activity
import android.app.AppOpsManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.example.admin.KidsGuardDeviceAdminReceiver

private const val TAG = "SecurityPermission"

object DeviceSecurityPermissionHelper {
  @Volatile
  var isSettingUpPermissions: Boolean = false

  @Volatile
  var autoConfirmUpdatesViaAccessibility: Boolean = false

  fun hasLocationPermission(context: Context): Boolean {
    val fine = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    val coarse = ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
    return fine || coarse
  }

  fun hasBackgroundLocationPermission(context: Context): Boolean {
    if (!hasLocationPermission(context)) return false
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
    } else {
      true
    }
  }

  fun openAppLocationSettings(context: Context) {
    isSettingUpPermissions = true
    try {
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      Toast.makeText(
        context,
        "Выберите «Разрешения» -> «Местоположение» -> «Разрешить в любом режиме»",
        Toast.LENGTH_LONG
      ).show()
    } catch (_: Exception) {}
  }

  fun canInstallUnknownApps(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        context.packageManager.canRequestPackageInstalls()
      } catch (_: Exception) {
        false
      }
    } else {
      true
    }
  }

  fun requestInstallUnknownAppsPermission(context: Context) {
    isSettingUpPermissions = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      try {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
          data = Uri.parse("package:${context.packageName}")
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return
      } catch (e: Exception) {
        Log.e(TAG, "requestInstallUnknownAppsPermission error: ${e.message}")
      }
      try {
        val fallback = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(fallback)
      } catch (_: Exception) {}
    }
  }

  fun isDeviceAdminActive(context: Context): Boolean {
    return try {
      val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      val component = ComponentName(context, KidsGuardDeviceAdminReceiver::class.java)
      dpm?.isAdminActive(component) == true
    } catch (e: Exception) {
      Log.e(TAG, "isDeviceAdminActive error: ${e.message}")
      false
    }
  }

  fun requestDeviceAdmin(context: Context) {
    isSettingUpPermissions = true
    val component = ComponentName(context, KidsGuardDeviceAdminReceiver::class.java)

    try {
      val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, component)
        putExtra(
          DevicePolicyManager.EXTRA_ADD_EXPLANATION,
          "Активируйте администратора устройства KidsGuard для защиты от удаления и аппаратной блокировки."
        )
        if (context !is Activity) {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
      }
      context.startActivity(intent)
      Log.d(TAG, "Device Admin intent launched directly")
      return
    } catch (e: Exception) {
      Log.w(TAG, "Direct ACTION_ADD_DEVICE_ADMIN failed: ${e.message}")
    }

    try {
      val listIntent = Intent().apply {
        setComponent(ComponentName("com.android.settings", "com.android.settings.DeviceAdminSettings"))
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(listIntent)
      return
    } catch (_: Exception) {}

    try {
      val secIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(secIntent)
    } catch (_: Exception) {
      Toast.makeText(context, "Откройте Настройки -> Безопасность -> Администраторы устройства", Toast.LENGTH_LONG).show()
    }
  }

  fun isOverlayPermissionGranted(context: Context): Boolean {
    return Settings.canDrawOverlays(context)
  }

  fun requestOverlayPermission(context: Context) {
    isSettingUpPermissions = true
    try {
      val intent = Intent(
        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
        Uri.parse("package:${context.packageName}")
      ).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (e: Exception) {
      try {
        val generalIntent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(generalIntent)
      } catch (_: Exception) {}
    }
  }

  private const val A11Y_PREFS_NAME = "kidsguard_a11y_persistence_prefs"
  private const val KEY_WAS_A11Y_ENABLED = "was_accessibility_enabled_before_ota"
  private const val KEY_LAST_A11Y_RESTORE_TS = "last_accessibility_restore_ts"

  fun recordAccessibilityStateBeforeOta(context: Context, forceEnabled: Boolean? = null) {
    try {
      val currentlyEnabled = forceEnabled ?: isAccessibilityServiceEnabled(context)
      if (currentlyEnabled) {
        context.applicationContext
          .getSharedPreferences(A11Y_PREFS_NAME, Context.MODE_PRIVATE)
          .edit()
          .putBoolean(KEY_WAS_A11Y_ENABLED, true)
          .putLong(KEY_LAST_A11Y_RESTORE_TS, System.currentTimeMillis())
          .apply()
      }
    } catch (e: Exception) {
      Log.w(TAG, "recordAccessibilityStateBeforeOta error: ${e.message}")
    }
  }

  fun wasAccessibilityEnabledBeforeOta(context: Context): Boolean {
    return try {
      context.applicationContext
        .getSharedPreferences(A11Y_PREFS_NAME, Context.MODE_PRIVATE)
        .getBoolean(KEY_WAS_A11Y_ENABLED, false)
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Automatically restores and rebinds [com.example.service.KidsGuardAccessibilityService]
   * after an in-place APK update (ACTION_MY_PACKAGE_REPLACED) or device boot:
   * 1. Clears any MDM accessibility restrictions via DevicePolicyManager.
   * 2. Restores component entry in Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES & ACCESSIBILITY_ENABLED=1
   *    when WRITE_SECURE_SETTINGS is available (MDM / ADB / Device Owner).
   * 3. Toggles PackageManager component enabled state with DONT_KILL_APP so Android's
   *    AccessibilityManagerService immediately re-binds the service if it dropped during OTA replacement.
   */
  fun restoreAccessibilityServiceAfterOta(context: Context): Boolean {
    val appCtx = context.applicationContext
    val serviceClass = com.example.service.KidsGuardAccessibilityService::class.java
    val componentName = ComponentName(appCtx, serviceClass)
    val flattenedFull = "${appCtx.packageName}/${serviceClass.canonicalName}"
    val flattenedShort = componentName.flattenToShortString()

    // 1. Ensure DevicePolicyManager permits all accessibility services if Device Owner / Admin
    try {
      val dpm = appCtx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
      val adminComp = ComponentName(appCtx, KidsGuardDeviceAdminReceiver::class.java)
      if (dpm != null && dpm.isDeviceOwnerApp(appCtx.packageName)) {
        dpm.setPermittedAccessibilityServices(adminComp, null)
      }
    } catch (e: Exception) {
      Log.d(TAG, "DPM setPermittedAccessibilityServices skipped: ${e.message}")
    }

    // 2. Programmatically restore Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES if WRITE_SECURE_SETTINGS is granted
    try {
      val resolver = appCtx.contentResolver
      val currentRaw = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
      val servicesSet = currentRaw
        .split(":")
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .toMutableSet()

      val alreadyInSecureSettings = servicesSet.any {
        it.equals(flattenedFull, ignoreCase = true) ||
          it.equals(flattenedShort, ignoreCase = true) ||
          it.contains("KidsGuardAccessibilityService")
      }

      if (!alreadyInSecureSettings) {
        servicesSet.add(flattenedFull)
        val joined = servicesSet.joinToString(":")
        Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, joined)
      }
      Settings.Secure.putString(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, "1")
      Log.i(TAG, "Successfully wrote ENABLED_ACCESSIBILITY_SERVICES via WRITE_SECURE_SETTINGS: $flattenedFull")
    } catch (se: SecurityException) {
      Log.d(TAG, "WRITE_SECURE_SETTINGS not granted via ADB (${se.message}); proceeding with PM component rebind.")
    } catch (e: Exception) {
      Log.w(TAG, "Secure settings accessibility restore warning: ${e.message}")
    }

    // 3. Kickstart Android AccessibilityManagerService rebind by toggling ComponentEnabledSetting (DONT_KILL_APP)
    // When an APK is replaced via OTA, AccessibilityManagerService can leave the service unbound even when
    // the setting is enabled; toggling DISABLED -> ENABLED forces AccessibilityManagerService to rebind immediately.
    try {
      val pm = appCtx.packageManager
      if (com.example.service.KidsGuardAccessibilityService.instance == null) {
        pm.setComponentEnabledSetting(
          componentName,
          PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
          PackageManager.DONT_KILL_APP
        )
        try {
          Thread.sleep(120L)
        } catch (_: InterruptedException) {}
      }
      pm.setComponentEnabledSetting(
        componentName,
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
        PackageManager.DONT_KILL_APP
      )
      Log.i(TAG, "Triggered PackageManager component rebind for KidsGuardAccessibilityService")
    } catch (e: Exception) {
      Log.w(TAG, "Component rebind error: ${e.message}")
    }

    val restored = isAccessibilityServiceEnabled(appCtx)
    if (restored) {
      recordAccessibilityStateBeforeOta(appCtx, forceEnabled = true)
    }
    return restored
  }

  fun isAccessibilityServiceEnabled(context: Context): Boolean {
    if (com.example.service.KidsGuardAccessibilityService.instance != null) {
      recordAccessibilityStateBeforeOta(context, forceEnabled = true)
      return true
    }
    return try {
      val expectedServiceName = "${context.packageName}/com.example.service.KidsGuardAccessibilityService"
      val expectedShortName = "${context.packageName}/.service.KidsGuardAccessibilityService"
      val enabledServices = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
      ) ?: return false
      val enabled = enabledServices.contains(expectedServiceName) ||
        enabledServices.contains(expectedShortName) ||
        enabledServices.contains("KidsGuardAccessibilityService")
      if (enabled) {
        recordAccessibilityStateBeforeOta(context, forceEnabled = true)
      }
      enabled
    } catch (_: Exception) {
      false
    }
  }

  fun requestAccessibilityPermission(context: Context) {
    isSettingUpPermissions = true
    try {
      val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (e: Exception) {
      Log.e(TAG, "requestAccessibilityPermission failed: ${e.message}")
    }
  }

  fun isBatteryOptimizationIgnored(context: Context): Boolean {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        pm?.isIgnoringBatteryOptimizations(context.packageName) == true
      } else {
        true
      }
    } catch (_: Exception) {
      false
    }
  }

  fun requestIgnoreBatteryOptimization(context: Context) {
    isSettingUpPermissions = true
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
      try {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
          data = Uri.parse("package:${context.packageName}")
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        return
      } catch (_: Exception) {}

      try {
        val listIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(listIntent)
        return
      } catch (_: Exception) {}

      try {
        val appDetailIntent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
          data = Uri.parse("package:${context.packageName}")
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(appDetailIntent)
      } catch (_: Exception) {}
    }
  }

  fun hasUsageStatsPermission(context: Context): Boolean {
    return try {
      val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
      val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        appOps.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
      } else {
        @Suppress("DEPRECATION")
        appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
      }
      mode == AppOpsManager.MODE_ALLOWED
    } catch (_: Exception) {
      false
    }
  }

  fun requestUsageStatsPermission(context: Context) {
    isSettingUpPermissions = true
    try {
      val intent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
    } catch (_: Exception) {
      try {
        val generalIntent = Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).apply {
          if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(generalIntent)
      } catch (_: Exception) {}
    }
  }

  /**
   * Opens the App Info screen for KidsGuard so Samsung / Android 13-15 users can tap
   * the top-right 3 dots (⋮) -> "Разрешить ограниченные настройки" (Allow restricted settings).
   */
  fun openAppInfoForRestrictedSettings(context: Context) {
    isSettingUpPermissions = true
    try {
      val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
        data = Uri.parse("package:${context.packageName}")
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      Toast.makeText(
        context,
        "В правом верхнем углу нажмите ⋮ (три точки) -> «Разрешить ограниченные настройки»!",
        Toast.LENGTH_LONG
      ).show()
    } catch (e: Exception) {
      Log.e(TAG, "openAppInfoForRestrictedSettings error: ${e.message}")
    }
  }

  /**
   * Opens Samsung Security & Privacy settings so the user can turn off "Автоблокировщик" (Auto Blocker)
   * on Samsung One UI 6.0 / 6.1 / 7.0.
   */
  fun openSamsungAutoBlockerSettings(context: Context) {
    isSettingUpPermissions = true
    try {
      val intent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
        if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(intent)
      Toast.makeText(
        context,
        "В разделе «Безопасность» откройте «Автоблокировщик» (Auto Blocker) и выключите его",
        Toast.LENGTH_LONG
      ).show()
    } catch (_: Exception) {}
  }

  /**
   * Opens standard system App Info screen so the parent can configure permissions transparently.
   */
  fun reinstallViaStoreSessionToUnlockSamsung(context: Context) {
    openAppInfoForRestrictedSettings(context)
  }

  /**
   * Блокировка удаления приложения, если оно является Device Owner (MDM).
   * Без Device Owner Android не позволяет приложению запретить удаление —
   * остаётся только предупреждение администратора устройства.
   */
  fun setUninstallBlocked(context: Context, blocked: Boolean) {
    try {
      val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return
      val admin = ComponentName(context, com.example.admin.KidsGuardDeviceAdminReceiver::class.java)
      if (dpm.isDeviceOwnerApp(context.packageName)) {
        dpm.setUninstallBlocked(admin, context.packageName, blocked)
      }
    } catch (_: Exception) {
    }
  }
}
