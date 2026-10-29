package com.example.util

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.util.Log

private const val TAG = "DeviceBatteryUtil"

object DeviceBatteryUtil {

  fun getAccurateBatteryPercentage(context: Context): Int {
    try {
      // Method 1 (Primary Authoritative Source): Sticky Intent ACTION_BATTERY_CHANGED via BatteryManager.EXTRA_LEVEL
      // Ensures exact match with the Android status bar (prevents OEM ROMs returning 71% from BATTERY_PROPERTY_CAPACITY when actual is 95%)
      val batteryFilter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
      val batteryStatus: Intent? = context.applicationContext.registerReceiver(null, batteryFilter)
      if (batteryStatus != null) {
        val level: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale: Int = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level >= 0 && scale > 0) {
          val calculated = ((level * 100f) / scale.toFloat()).toInt().coerceIn(0, 100)
          if (calculated > 0) {
            return calculated
          }
        }
      }

      // Method 2 (Fallback): BatteryManager property
      val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
      if (bm != null) {
        val capacity = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (capacity in 1..100) {
          return capacity
        }
      }
    } catch (e: Exception) {
      Log.e(TAG, "Error obtaining battery level: ${e.message}")
    }
    return 100
  }
}
