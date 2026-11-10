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
  private const val KEY_CHILD_ID = "childId"
  private const val KEY_ALLOW_MOBILE = "allowMobileData"
  private const val KEY_FAMILY_ID = "familyId"

  data class BootConfig(
    val role: String = "",
    val childId: Long = 0L,
    val allowMobileData: Boolean = true,
    val familyId: String = ""
  )

  private fun prefs(context: Context) =
    context.createDeviceProtectedStorageContext().getSharedPreferences(NAME, Context.MODE_PRIVATE)

  fun save(
    context: Context,
    role: String,
    childId: Long,
    allowMobileData: Boolean,
    familyId: String
  ) {
    try {
      prefs(context).edit()
        .putString(KEY_ROLE, role)
        .putLong(KEY_CHILD_ID, childId)
        .putBoolean(KEY_ALLOW_MOBILE, allowMobileData)
        .putString(KEY_FAMILY_ID, familyId)
        .apply()
    } catch (_: Exception) {
    }
  }

  fun load(context: Context): BootConfig {
    return try {
      val p = prefs(context)
      BootConfig(
        role = p.getString(KEY_ROLE, "").orEmpty(),
        childId = p.getLong(KEY_CHILD_ID, 0L),
        allowMobileData = p.getBoolean(KEY_ALLOW_MOBILE, true),
        familyId = p.getString(KEY_FAMILY_ID, "").orEmpty()
      )
    } catch (_: Exception) {
      BootConfig()
    }
  }
}
