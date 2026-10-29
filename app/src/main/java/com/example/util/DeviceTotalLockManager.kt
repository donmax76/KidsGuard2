package com.example.util

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.example.data.local.KidsGuardDatabase
import com.example.service.KidsGuardAccessibilityService
import com.example.service.LockOverlayService
import com.example.ui.screens.ChildLockScreenActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "DeviceTotalLockManager"

object DeviceTotalLockManager {

  @Volatile
  var isCurrentlyLocked: Boolean = false

  @Volatile
  var activeLockActivityCloser: (() -> Unit)? = null

  fun enforceTotalLock(context: Context, reason: String = "Экран заблокирован родительским контролем KidsGuard") {
    val appContext = context.applicationContext

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val dao = KidsGuardDatabase.getDatabase(appContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        // NEVER lock a phone that is in PARENT mode!
        if (cfg?.selectedRole != "CHILD") {
          Log.w(TAG, "Ignored enforceTotalLock because device role is ${cfg?.selectedRole} (not CHILD)")
          return@launch
        }
        isCurrentlyLocked = true
        launch(Dispatchers.Main) {
          val canDraw = Settings.canDrawOverlays(appContext)
          val a11yService = KidsGuardAccessibilityService.instance
          if (canDraw && a11yService != null) {
            // Use the single AccessibilityService overlay and stop any competing overlay/activity!
            try {
              LockOverlayService.stop(appContext)
            } catch (_: Exception) {}
            try {
              activeLockActivityCloser?.invoke()
            } catch (_: Exception) {}
            try {
              a11yService.enforceTotalLockFromService(reason)
            } catch (_: Exception) {}
          } else if (canDraw) {
            // Use LockOverlayService as the single overlay owner (no background Activity loop!)
            try {
              activeLockActivityCloser?.invoke()
            } catch (_: Exception) {}
            try {
              LockOverlayService.start(appContext)
            } catch (_: Exception) {}
          } else {
            // Fallback when SYSTEM_ALERT_WINDOW is not yet granted: show single ChildLockScreenActivity
            try {
              ChildLockScreenActivity.launch(appContext, reason)
            } catch (e: Exception) {
              Log.e(TAG, "Error launching ChildLockScreenActivity: ${e.message}")
            }
          }
        }
        Log.d(TAG, "Enforced total lock on CHILD device with message: $reason")
      } catch (e: Exception) {
        Log.e(TAG, "Error enforcing total lock: ${e.message}")
      }
    }
  }

  fun releaseTotalLock(context: Context) {
    val appContext = context.applicationContext
    isCurrentlyLocked = false
    CoroutineScope(Dispatchers.Main).launch {
      try {
        LockOverlayService.stop(appContext)
      } catch (_: Exception) {}
      try {
        KidsGuardAccessibilityService.instance?.releaseTotalLockFromService()
      } catch (_: Exception) {}
      try {
        activeLockActivityCloser?.invoke()
      } catch (_: Exception) {}
    }
  }
}
