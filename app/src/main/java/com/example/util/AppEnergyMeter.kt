package com.example.util

import android.content.Context
import android.os.BatteryManager
import android.os.health.SystemHealthManager
import android.os.health.UidHealthStats
import kotlin.math.roundToInt

/**
 * Оценка энергопотребления САМОГО приложения (без системных прав).
 *
 * Используем публичный [SystemHealthManager.takeMyUidSnapshot] — он даёт израсходованные
 * UID'ом мА·ч по компонентам (CPU, wakelock, радио, GPS, датчики…), и счётчик заряда
 * [BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER]. Долю считаем как «потрачено приложением /
 * потрачено всем телефоном с момента отключения от зарядки» — это близко к цифре, которую
 * Android показывает в «Батарея → Расход».
 *
 * Замер делается не чаще раза в 5 минут (одна операция чтения счётчиков), поэтому на батарею
 * почти не влияет.
 */
object AppEnergyMeter {
  private const val PREFS = "kidsguard_app_energy"
  private const val KEY_COUNTER = "base_counter"
  private const val KEY_APP_MAH = "base_app_mah"
  private const val KEY_LEVEL = "base_level"
  private const val KEY_TS = "base_ts"

  data class Reading(
    val appPercent: Int,
    val mahPerHour: Float,
    val percentPerHour: Float,
    val windowHours: Float
  )

  /** Начать окно измерения (после отключения зарядки). */
  fun startWindow(context: Context) {
    try {
      val bm = context.getSystemService(BatteryManager::class.java)
      val counter = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER) ?: -1
      val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
      val appMah = appPowerMah(context)
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        .putInt(KEY_COUNTER, counter)
        .putFloat(KEY_APP_MAH, appMah.toFloat())
        .putInt(KEY_LEVEL, level)
        .putLong(KEY_TS, System.currentTimeMillis())
        .apply()
    } catch (_: Exception) {
    }
  }

  fun reset(context: Context) {
    try {
      context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
    } catch (_: Exception) {
    }
  }

  fun isCharging(context: Context): Boolean {
    return try {
      val intent = context.registerReceiver(
        null,
        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)
      ) ?: return false
      val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
      status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
    } catch (_: Exception) {
      false
    }
  }

  /** null — данных ещё нет (например, только что включили/поставили на зарядку). */
  fun read(context: Context): Reading? {
    try {
      val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
      if (!prefs.contains(KEY_TS)) {
        if (!isCharging(context)) startWindow(context)
        return null
      }
      val bm = context.getSystemService(BatteryManager::class.java) ?: return cpuFallback()
      val counter = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
      val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
      if (level <= 0) return cpuFallback()

      val baseCounter = prefs.getInt(KEY_COUNTER, -1)
      val baseAppMah = prefs.getFloat(KEY_APP_MAH, -1f)
      val baseLevel = prefs.getInt(KEY_LEVEL, -1)
      val baseTs = prefs.getLong(KEY_TS, 0L)
      val now = System.currentTimeMillis()
      val appMahNow = appPowerMah(context)

      // На части телефонов системные счётчики энергии (HealthStats) недоступны —
      // показываем оценку по CPU-нагрузке приложения, чтобы показатель вообще был.
      if (appMahNow <= 0.0) return cpuFallback()
      if (baseAppMah <= 0f) {
        startWindow(context)
        return null
      }
      if (baseTs <= 0L || now - baseTs < 2 * 60_000L) return null

      val levelDrop = baseLevel - level
      // Реальная ёмкость батареи: charge_counter (µА·ч) / уровень заряда. Раньше
      // здесь был хардкод 4000 мА·ч — на аппаратах с батареей 5000/4500 мА·ч доля
      // приложения занижалась (28,8% вместо 42% в системных настройках).
      val derivedCapacityMah = if (counter > 0 && level in 1..100) {
        (counter / 1000.0) / (level / 100.0)
      } else {
        4000.0
      }
      // Всего израсходовано устройством с начала окна: по счётчику заряда, а при
      // его отсутствии — по проценту заряда и вычисленной ёмкости.
      val totalMah = when {
        baseCounter > 0 && counter > 0 && counter < baseCounter ->
          (baseCounter - counter) / 1000.0
        levelDrop > 0 -> levelDrop / 100.0 * derivedCapacityMah
        else -> 0.0
      }
      if (totalMah < 0.5) {
        if (counter >= baseCounter || levelDrop <= 0) startWindow(context)
        return null
      }

      val usedAppMah = appMahNow - baseAppMah.toDouble()
      if (usedAppMah <= 0.0) return null
      val share = (usedAppMah / totalMah * 100.0).coerceIn(0.0, 100.0)
      val hours = ((now - baseTs) / 3_600_000.0).coerceAtLeast(0.05)
      val mahPerHour = usedAppMah / hours
      val pctPerHour = (mahPerHour / derivedCapacityMah * 100.0).coerceIn(0.0, 100.0)

      return Reading(
        appPercent = share.roundToInt(),
        mahPerHour = mahPerHour.toFloat(),
        percentPerHour = pctPerHour.toFloat(),
        windowHours = hours.toFloat()
      )
    } catch (_: Exception) {
      return null
    }
  }

  /** Резерв, когда телефон не отдаёт системные счётчики энергии: доля CPU-времени. */
  private fun cpuFallback(): Reading? {
    return try {
      val startElapsed = android.os.Process.getStartElapsedRealtime()
      val elapsed = android.os.SystemClock.elapsedRealtime() - startElapsed
      if (elapsed < 2 * 60_000L) return null
      val cpuMs = android.os.Process.getElapsedCpuTime().coerceAtLeast(0L)
      val share = (cpuMs.toDouble() / elapsed.toDouble()).coerceIn(0.0, 1.0)
      Reading(
        appPercent = (share * 100.0).roundToInt(),
        mahPerHour = 0f,
        percentPerHour = 0f,
        windowHours = elapsed / 3_600_000f
      )
    } catch (_: Exception) {
      null
    }
  }

  private val powerKeyCache: IntArray by lazy {
    // Через рефлексию: набор STATS_POWER_* отличается между версиями Android,
    // а отсутствующая константа не должна ломать сборку.
    val names = listOf(
      "STATS_POWER_CPU",
      "STATS_POWER_WAKELOCK",
      "STATS_POWER_MOBILE_RADIO",
      "STATS_POWER_WIFI",
      "STATS_POWER_GPS",
      "STATS_POWER_SENSOR",
      "STATS_POWER_CAMERA",
      "STATS_POWER_FLASHLIGHT",
      "STATS_POWER_BLUETOOTH"
    )
    names.mapNotNull { name ->
      try {
        UidHealthStats::class.java.getField(name).getInt(null)
      } catch (_: Exception) {
        null
      }
    }.toIntArray()
  }

  /** Суммарно израсходовано UID'ом приложения, мА·ч (по данным системы). */
  private fun appPowerMah(context: Context): Double {
    return try {
      val mgr = context.getSystemService(Context.SYSTEM_HEALTH_SERVICE) as? SystemHealthManager
        ?: return 0.0
      val stats = mgr.takeMyUidSnapshot() ?: return 0.0
      var total = 0.0
      for (key in powerKeyCache) {
        try {
          // STATS_POWER_* — это measurement-ключи (мА·ч), а не вложенные stats-карты.
          if (stats.hasMeasurement(key)) total += stats.getMeasurement(key).toDouble()
        } catch (_: Exception) {
        }
      }
      total
    } catch (_: Exception) {
      0.0
    }
  }
}
