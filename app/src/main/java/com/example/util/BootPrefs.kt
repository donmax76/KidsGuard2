package com.example.util

import android.content.Context

/**
 * Минимальный boot-конфиг в Device Protected Storage. При LOCKED_BOOT_COMPLETED
 * (до ввода ПИН-кода) Room ещё недоступен, поэтому роль/childId/режим сети должны
 * читаться из незашифрованного хранилища, чтобы фоновый сервис и тихий сокет
 * стартовали сразу после зарядки/перезагрузки.
 */
object BootPrefs {
  private const val NAME = "kidsguard_boot"
  private const val KEY_ROLE = "role"
  private const val KEY_PERMANENT_ROLE = "permanentHardwareRole"
  private const val KEY_CHILD_ID = "childId"
  private const val KEY_ALLOW_MOBILE = "allowMobileData"
  private const val KEY_FAMILY_ID = "familyId"
  private const val KEY_MASTER_PIN = "masterPin"
  private const val KEY_PAIRING_CODE = "pairingCode"

  data class BootConfig(
    val role: String = "",
    val permanentHardwareRole: String = "",
    val childId: Long = 0L,
    val allowMobileData: Boolean = true,
    val familyId: String = "",
    val masterPin: String = "1234",
    val pairingCode: String = ""
  )

  private fun prefs(context: Context) =
    context.applicationContext.createDeviceProtectedStorageContext().getSharedPreferences(NAME, Context.MODE_PRIVATE)

  fun save(
    context: Context,
    role: String,
    childId: Long,
    allowMobileData: Boolean = true,
    familyId: String = "family_default",
    permanentHardwareRole: String? = null,
    masterPin: String? = null,
    pairingCode: String? = null
  ) {
    try {
      val p = prefs(context)
      val existingPerm = p.getString(KEY_PERMANENT_ROLE, "").orEmpty()
      val resolvedPerm = when {
        !permanentHardwareRole.isNullOrBlank() -> permanentHardwareRole
        existingPerm.isNotBlank() -> existingPerm
        role == "CHILD" || role == "PARENT" -> role
        else -> ""
      }
      val editor = p.edit()
        .putString(KEY_ROLE, role)
        .putLong(KEY_CHILD_ID, childId)
        .putBoolean(KEY_ALLOW_MOBILE, allowMobileData)
        .putString(KEY_FAMILY_ID, familyId)
      if (resolvedPerm.isNotBlank()) {
        editor.putString(KEY_PERMANENT_ROLE, resolvedPerm)
      }
      if (!masterPin.isNullOrBlank()) {
        editor.putString(KEY_MASTER_PIN, masterPin)
      }
      if (!pairingCode.isNullOrBlank()) {
        editor.putString(KEY_PAIRING_CODE, pairingCode.trim())
      }
      editor.commit()
    } catch (_: Exception) {
    }
  }

  fun savePermanentHardwareRole(
    context: Context,
    hardwareRole: String,
    childId: Long = 0L,
    pairingCode: String? = null
  ) {
    try {
      val editor = prefs(context).edit()
        .putString(KEY_PERMANENT_ROLE, hardwareRole)
        .putString(KEY_ROLE, hardwareRole)
      if (childId > 0L) {
        editor.putLong(KEY_CHILD_ID, childId)
      }
      if (!pairingCode.isNullOrBlank()) {
        editor.putString(KEY_PAIRING_CODE, pairingCode.trim())
      }
      editor.commit()
    } catch (_: Exception) {}
  }

  fun savePairingCode(context: Context, pairingCode: String) {
    val clean = pairingCode.trim()
    if (clean.isBlank()) return
    try {
      prefs(context).edit().putString(KEY_PAIRING_CODE, clean).commit()
    } catch (_: Exception) {}
  }

  fun getPairingCode(context: Context?): String {
    if (context == null) return ""
    return try {
      prefs(context).getString(KEY_PAIRING_CODE, "")?.trim().orEmpty()
    } catch (_: Exception) {
      ""
    }
  }

  fun saveMasterPin(context: Context, pin: String) {
    if (pin.isBlank()) return
    try {
      prefs(context).edit().putString(KEY_MASTER_PIN, pin.trim()).commit()
    } catch (_: Exception) {}
  }

  fun getMasterPin(context: Context): String {
    return try {
      prefs(context).getString(KEY_MASTER_PIN, "1234")?.takeIf { it.isNotBlank() } ?: "1234"
    } catch (_: Exception) {
      "1234"
    }
  }

  private const val KEY_BLOCKED_PACKAGES = "blockedPackagesSet"
  private const val KEY_BLOCKED_PACKAGES_CSV = "blockedPackagesCsv"
  private const val KEY_BLOCKED_DOMAINS_CSV = "blockedDomainsCsv"
  private const val KEY_APP_LIMITS_JSON = "appLimitsJson"
  private const val KEY_POLICY_UPDATED_AT = "policyUpdatedAtMs"
  private const val KEY_AUTO_UPDATE_GRANTED = "autoUpdateGranted"
  private const val KEY_LIVE_STREAM_GRANTED = "liveStreamGranted"
  private const val KEY_USAGE_DATE_ISO = "usageDateIso"

  @Volatile
  private var cachedBlockedSet: Set<String>? = null

  @Volatile
  private var cachedBlockedDomainsSet: Set<String>? = null

  fun normalizeDomain(raw: String?): String {
    return raw.orEmpty()
      .trim()
      .lowercase()
      .removePrefix("https://")
      .removePrefix("http://")
      .removePrefix("www.")
      .substringBefore("/")
      .substringBefore("?")
      .substringBefore("#")
      .trimEnd('.')
      .trim()
  }

  fun saveBlockedDomain(context: Context?, domain: String, isBlocked: Boolean) {
    if (context == null) return
    val cleanDomain = normalizeDomain(domain)
    if (cleanDomain.isBlank() || !cleanDomain.contains(".")) return
    try {
      val current = getBlockedDomains(context).toMutableSet()
      val changed = if (isBlocked) current.add(cleanDomain) else current.remove(cleanDomain)
      cachedBlockedDomainsSet = current.toSet()
      if (changed) {
        prefs(context).edit()
          .putString(KEY_BLOCKED_DOMAINS_CSV, current.joinToString("|"))
          .commit()
      }
    } catch (_: Exception) {}
  }

  fun getBlockedDomains(context: Context?): Set<String> {
    cachedBlockedDomainsSet?.let { return it }
    if (context == null) return emptySet()
    return try {
      val set = prefs(context).getString(KEY_BLOCKED_DOMAINS_CSV, "")
        .orEmpty()
        .split("|")
        .map { normalizeDomain(it) }
        .filter { it.isNotBlank() && it.contains(".") }
        .toSet()
      cachedBlockedDomainsSet = set
      set
    } catch (_: Exception) {
      emptySet()
    }
  }

  fun setAutoUpdateGranted(context: Context?, granted: Boolean) {
    if (context == null) return
    try {
      prefs(context).edit().putBoolean(KEY_AUTO_UPDATE_GRANTED, granted).commit()
    } catch (_: Exception) {}
  }

  fun isAutoUpdateGranted(context: Context?): Boolean {
    if (context == null) return false
    return try {
      prefs(context).getBoolean(KEY_AUTO_UPDATE_GRANTED, false)
    } catch (_: Exception) {
      false
    }
  }

  fun setLiveStreamGranted(context: Context?, granted: Boolean) {
    if (context == null) return
    try {
      prefs(context).edit().putBoolean(KEY_LIVE_STREAM_GRANTED, granted).commit()
    } catch (_: Exception) {}
  }

  fun isLiveStreamGranted(context: Context?): Boolean {
    if (context == null) return false
    return try {
      prefs(context).getBoolean(KEY_LIVE_STREAM_GRANTED, false)
    } catch (_: Exception) {
      false
    }
  }

  fun getUsageDateIso(context: Context?): String {
    if (context == null) return ""
    return try {
      prefs(context).getString(KEY_USAGE_DATE_ISO, "")?.trim().orEmpty()
    } catch (_: Exception) {
      ""
    }
  }

  fun setUsageDateIso(context: Context?, dateIso: String) {
    if (context == null || dateIso.isBlank()) return
    try {
      prefs(context).edit().putString(KEY_USAGE_DATE_ISO, dateIso.trim()).commit()
    } catch (_: Exception) {}
  }

  fun saveAppLimit(context: Context?, packageName: String, limitMinutes: Int) {
    if (context == null) return
    val cleanPkg = packageName.trim().lowercase()
    if (cleanPkg.isBlank() || cleanPkg.contains("kidsguard")) return
    try {
      val current = getAppLimitsMap(context).toMutableMap()
      if (limitMinutes > 0) {
        current[cleanPkg] = limitMinutes
      } else {
        current.remove(cleanPkg)
      }
      val json = org.json.JSONObject()
      for ((k, v) in current) {
        json.put(k, v)
      }
      prefs(context).edit().putString(KEY_APP_LIMITS_JSON, json.toString()).commit()
    } catch (_: Exception) {}
  }

  fun getAppLimitsMap(context: Context?): Map<String, Int> {
    if (context == null) return emptyMap()
    return try {
      val raw = prefs(context).getString(KEY_APP_LIMITS_JSON, "")?.trim().orEmpty()
      if (raw.isBlank()) return emptyMap()
      val json = org.json.JSONObject(raw)
      val map = mutableMapOf<String, Int>()
      val keys = json.keys()
      while (keys.hasNext()) {
        val k = keys.next().trim().lowercase()
        val v = json.optInt(k, 0)
        if (k.isNotBlank() && !k.contains("kidsguard") && v > 0) {
          map[k] = v
        }
      }
      map
    } catch (_: Exception) {
      emptyMap()
    }
  }

  fun getPolicyUpdatedAtMs(context: Context?): Long {
    if (context == null) return 0L
    return try {
      prefs(context).getLong(KEY_POLICY_UPDATED_AT, 0L)
    } catch (_: Exception) {
      0L
    }
  }

  /**
   * Применяет полный авторитетный чёрный список и лимиты от родителей (PARENT_POLICY_SYNC).
   * Сохраняется в DeviceProtectedStorage (в надёжной CSV-строке и в ОЗУ) и никогда не теряется при рестарте телефона.
   */
  fun applyAuthoritativeParentPolicy(
    context: Context?,
    blockedPackages: Set<String>,
    limitsMap: Map<String, Int>,
    policyTimestampMs: Long = System.currentTimeMillis()
  ) {
    if (context == null) return
    try {
      val cleanBlocked = blockedPackages
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && !it.contains("kidsguard") }
        .toSet()
      cachedBlockedSet = cleanBlocked
      val json = org.json.JSONObject()
      for ((k, v) in limitsMap) {
        val cleanK = k.trim().lowercase()
        if (cleanK.isNotBlank() && !cleanK.contains("kidsguard") && v > 0) {
          json.put(cleanK, v)
        }
      }
      prefs(context).edit()
        .remove(KEY_BLOCKED_PACKAGES)
        .putString(KEY_BLOCKED_PACKAGES_CSV, cleanBlocked.joinToString("|"))
        .putString(KEY_APP_LIMITS_JSON, json.toString())
        .putLong(KEY_POLICY_UPDATED_AT, policyTimestampMs)
        .commit()
    } catch (_: Exception) {}
  }

  fun saveBlockedPackage(context: Context?, packageName: String, isBlocked: Boolean) {
    if (context == null) return
    val cleanPkg = packageName.trim().lowercase()
    if (cleanPkg.isBlank() || cleanPkg.contains("kidsguard")) return
    try {
      val current = getBlockedPackages(context).toMutableSet()
      val changed = if (isBlocked) current.add(cleanPkg) else current.remove(cleanPkg)
      cachedBlockedSet = current.toSet()
      if (changed) {
        prefs(context).edit()
          .remove(KEY_BLOCKED_PACKAGES)
          .putString(KEY_BLOCKED_PACKAGES_CSV, current.joinToString("|"))
          .putLong(KEY_POLICY_UPDATED_AT, System.currentTimeMillis())
          .commit()
      }
    } catch (_: Exception) {}
  }

  fun getBlockedPackages(context: Context?): Set<String> {
    cachedBlockedSet?.let { return it }
    if (context == null) return emptySet()
    return try {
      val p = prefs(context)
      val csvSet = p.getString(KEY_BLOCKED_PACKAGES_CSV, "")
        .orEmpty()
        .split("|")
        .map { it.trim().lowercase() }
        .filter { it.isNotBlank() && !it.contains("kidsguard") }
        .toSet()
      val legacySet = try {
        p.getStringSet(KEY_BLOCKED_PACKAGES, emptySet())
          ?.map { it.trim().lowercase() }
          ?.filter { it.isNotBlank() && !it.contains("kidsguard") }
          ?.toSet()
          ?: emptySet()
      } catch (_: Exception) {
        emptySet()
      }
      val merged = csvSet + legacySet
      cachedBlockedSet = merged
      if (legacySet.isNotEmpty() && csvSet != merged) {
        p.edit()
          .remove(KEY_BLOCKED_PACKAGES)
          .putString(KEY_BLOCKED_PACKAGES_CSV, merged.joinToString("|"))
          .commit()
      }
      merged
    } catch (_: Exception) {
      emptySet()
    }
  }

  fun isPackageBlocked(context: Context?, packageName: String?): Boolean {
    val clean = packageName?.trim()?.lowercase().orEmpty()
    if (clean.isBlank() || clean.contains("kidsguard")) return false
    return getBlockedPackages(context).contains(clean)
  }

  fun clearAllBlockedPackages(context: Context?) {
    cachedBlockedSet = emptySet()
    if (context == null) return
    try {
      prefs(context).edit()
        .remove(KEY_BLOCKED_PACKAGES)
        .remove(KEY_BLOCKED_PACKAGES_CSV)
        .commit()
    } catch (_: Exception) {}
  }

  fun load(context: Context): BootConfig {
    return try {
      val p = prefs(context)
      val r = p.getString(KEY_ROLE, "").orEmpty()
      val perm = p.getString(KEY_PERMANENT_ROLE, "").orEmpty().ifBlank { r }
      BootConfig(
        role = r,
        permanentHardwareRole = perm,
        childId = p.getLong(KEY_CHILD_ID, 0L),
        allowMobileData = p.getBoolean(KEY_ALLOW_MOBILE, true),
        familyId = p.getString(KEY_FAMILY_ID, "").orEmpty(),
        masterPin = p.getString(KEY_MASTER_PIN, "1234")?.takeIf { it.isNotBlank() } ?: "1234",
        pairingCode = p.getString(KEY_PAIRING_CODE, "")?.trim().orEmpty()
      )
    } catch (_: Exception) {
      BootConfig()
    }
  }
}

