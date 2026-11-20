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

  data class BootConfig(
    val role: String = "",
    val permanentHardwareRole: String = "",
    val childId: Long = 0L,
    val allowMobileData: Boolean = true,
    val familyId: String = "",
    val masterPin: String = "1234"
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
    masterPin: String? = null
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
      editor.commit()
    } catch (_: Exception) {
    }
  }

  fun savePermanentHardwareRole(context: Context, hardwareRole: String, childId: Long = 0L) {
    try {
      val editor = prefs(context).edit()
        .putString(KEY_PERMANENT_ROLE, hardwareRole)
        .putString(KEY_ROLE, hardwareRole)
      if (childId > 0L) {
        editor.putLong(KEY_CHILD_ID, childId)
      }
      editor.commit()
    } catch (_: Exception) {}
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
        masterPin = p.getString(KEY_MASTER_PIN, "1234")?.takeIf { it.isNotBlank() } ?: "1234"
      )
    } catch (_: Exception) {
      BootConfig()
    }
  }
}

