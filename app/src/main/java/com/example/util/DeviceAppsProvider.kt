package com.example.util

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import com.example.data.model.AppControlEntity

object DeviceAppsProvider {

  /**
   * Returns the set of launchable package names actually installed on THIS physical device.
   */
  fun getInstalledPackageSet(context: Context): Set<String> {
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
      addCategory(Intent.CATEGORY_LAUNCHER)
    }
    val resolveList = try {
      pm.queryIntentActivities(mainIntent, 0)
    } catch (_: Exception) {
      emptyList()
    }
    return resolveList
      .mapNotNull { it.activityInfo?.packageName?.trim()?.lowercase() }
      .filter { it.isNotBlank() }
      .toSet()
  }

  /**
   * STRICT SEGREGATION: Scans installed launchable apps ONLY when this device is operating in CHILD role.
   * If called on a PARENT device, immediately returns emptyList() so the Parent's local PackageManager
   * is NEVER queried or mixed into the Child's app list!
   */
  fun getInstalledLaunchableApps(context: Context, childId: Long): List<AppControlEntity> {
    val engineRole = com.example.data.cloud.DirectP2pSocketEngine.currentRole
    if (engineRole == "PARENT") {
      return emptyList()
    }
    val pm = context.packageManager
    val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
      addCategory(Intent.CATEGORY_LAUNCHER)
    }

    val resolveList = try {
      pm.queryIntentActivities(mainIntent, 0)
    } catch (_: Exception) {
      emptyList()
    }

    val currentPackage = context.packageName.lowercase().trim()
    val result = mutableListOf<AppControlEntity>()
    val seenPackages = HashSet<String>()
    val seenLabels = HashSet<String>()

    val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
    val homePkg = try {
      pm.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
    } catch (_: Exception) {
      null
    }

    for (resolveInfo in resolveList) {
      val pkgName = resolveInfo.activityInfo?.packageName?.trim() ?: continue
      val pkgKey = pkgName.lowercase()
      if (pkgKey.isBlank() ||
          pkgKey == currentPackage ||
          pkgKey.contains("kidsguard") ||
          pkgName == homePkg ||
          pkgKey.contains("launcher") ||
          pkgKey == "com.miui.home" ||
          pkgKey == "com.android.settings" ||
          !seenPackages.add(pkgKey)
      ) continue

      val label = try {
        resolveInfo.loadLabel(pm).toString().trim().ifBlank { pkgName }
      } catch (_: Exception) {
        pkgName
      }
      val labelKey = label.lowercase()
      if (labelKey.contains("kidsguard") ||
          labelKey.contains("родительский контроль") ||
          !seenLabels.add(labelKey)
      ) continue

      val appInfo = resolveInfo.activityInfo.applicationInfo
      val category = categorizeApp(pkgName, appInfo)
      val iconType = getIconType(pkgName, category)

      result.add(
        AppControlEntity(
          childId = childId,
          packageName = pkgName,
          appName = label,
          iconType = iconType,
          category = category,
          dailyLimitMinutes = 0,
          usedMinutesToday = 0,
          isBlocked = false,
          isEducational = category == "Образование"
        )
      )
    }

    return result
      .distinctBy { it.packageName.lowercase().trim() }
      .sortedBy { it.appName.lowercase() }
  }

  private fun categorizeApp(packageName: String, appInfo: ApplicationInfo): String {
    val pkg = packageName.lowercase()
    return when {
      pkg.contains("game") || pkg.contains("play.games") ||
          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo.category == ApplicationInfo.CATEGORY_GAME) -> "Игры"

      pkg.contains("youtube") || pkg.contains("video") || pkg.contains("netflix") || pkg.contains("kinopoisk") ||
          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (appInfo.category == ApplicationInfo.CATEGORY_VIDEO || appInfo.category == ApplicationInfo.CATEGORY_AUDIO)) -> "Видео"

      pkg.contains("telegram") || pkg.contains("whatsapp") || pkg.contains("vk") || pkg.contains("tiktok") || pkg.contains("instagram") ||
          (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && appInfo.category == ApplicationInfo.CATEGORY_SOCIAL) -> "Соцсети"

      pkg.contains("duolingo") || pkg.contains("school") || pkg.contains("learn") || pkg.contains("dnevnik") || pkg.contains("edu") -> "Образование"

      pkg.contains("chrome") || pkg.contains("browser") || pkg.contains("firefox") || pkg.contains("opera") -> "Браузер"

      else -> "Инструменты"
    }
  }

  private fun getIconType(packageName: String, category: String): String {
    val pkg = packageName.lowercase()
    return when {
      pkg.contains("youtube") -> "youtube"
      pkg.contains("telegram") -> "telegram"
      pkg.contains("tiktok") -> "tiktok"
      pkg.contains("chrome") -> "chrome"
      pkg.contains("roblox") -> "roblox"
      pkg.contains("duolingo") -> "duolingo"
      category == "Игры" -> "games"
      category == "Соцсети" -> "social"
      category == "Видео" -> "video"
      category == "Образование" -> "education"
      else -> "generic"
    }
  }

  private fun getDefaultFallbackApps(childId: Long): List<AppControlEntity> {
    return listOf(
      AppControlEntity(childId = childId, packageName = "com.android.chrome", appName = "Google Chrome", iconType = "chrome", category = "Браузер", dailyLimitMinutes = 60),
      AppControlEntity(childId = childId, packageName = "com.google.android.youtube", appName = "YouTube", iconType = "youtube", category = "Видео", dailyLimitMinutes = 45),
      AppControlEntity(childId = childId, packageName = "org.telegram.messenger", appName = "Telegram", iconType = "telegram", category = "Соцсети", dailyLimitMinutes = 60),
      AppControlEntity(childId = childId, packageName = "com.google.android.apps.maps", appName = "Карты", iconType = "generic", category = "Инструменты", dailyLimitMinutes = 0)
    )
  }
}
