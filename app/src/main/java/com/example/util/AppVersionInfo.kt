package com.example.util

import android.content.Context

/**
 * Версия именно установленного APK (манифеста), а не горячего ядра.
 *
 * `BuildConfig.VERSION_NAME` внутри подменённого DEX показывает версию ЯДРА
 * (например, 3.3.35 Pro.24), хотя установленный APK может быть 3.3.35 Pro.22.
 * PackageManager всегда возвращает версию APK — по ней и надо сравнивать обновления.
 */
object AppVersionInfo {
  fun installedApkVersion(context: Context): String {
    return try {
      val info = context.packageManager.getPackageInfo(context.packageName, 0)
      info.versionName?.takeIf { it.isNotBlank() } ?: com.example.BuildConfig.VERSION_NAME
    } catch (_: Exception) {
      com.example.BuildConfig.VERSION_NAME
    }
  }
}
