package com.example.service

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import com.example.data.cloud.CloudSyncManager
import com.example.data.local.KidsGuardDatabase
import com.example.util.DeviceTotalLockManager
import com.example.util.UnifiedLockScreenViewBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val TAG = "LockOverlayService"

class LockOverlayService : Service() {
  private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
  private var windowManager: WindowManager? = null
  private var overlayView: View? = null
  private var activeChildId: Long = 0L

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onCreate() {
    super.onCreate()
    com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext
    windowManager = getSystemService(Context.WINDOW_SERVICE) as? WindowManager
    observeLockCommands()
  }

  private fun observeLockCommands() {
    val cloudSync = CloudSyncManager(applicationContext)

    serviceScope.launch(Dispatchers.IO) {
      val database = KidsGuardDatabase.getDatabase(applicationContext)
      val dao = database.kidsGuardDao()

      // Fast 1-second authoritative lock state watcher so UNLOCK takes effect in <1s
      launch {
        while (isActive) {
          delay(900L)
          val currentCfg = dao.getFamilyConfigOnce()
          if (currentCfg?.selectedRole != "CHILD") {
            launch(Dispatchers.Main) {
              hideLockOverlay()
              stopSelf()
            }
            break
          }
          val cid = currentCfg.thisDeviceChildId.takeIf { it > 0L }
            ?: currentCfg.currentActiveChildId.takeIf { it > 0L }
            ?: activeChildId
          if (cid > 0L) {
            val authLocked = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(cid)?.first
            if (authLocked == false && overlayView != null) {
              dao.setChildDeviceLocked(cid, false)
              launch(Dispatchers.Main) {
                hideLockOverlay()
                stopSelf()
              }
            }
          }
        }
      }

      dao.getFamilyConfig().collectLatest { config ->
        val isChildRole = config?.selectedRole == "CHILD"
        val childId = if (isChildRole && config != null && config.thisDeviceChildId > 0) {
          config.thisDeviceChildId
        } else if (isChildRole && config != null && config.currentActiveChildId > 0) {
          config.currentActiveChildId
        } else {
          0L
        }
        activeChildId = childId

        if (!isChildRole || childId <= 0) {
          launch(Dispatchers.Main) {
            hideLockOverlay()
            stopSelf()
          }
          return@collectLatest
        }

        // If AccessibilityService is already running, let AccessibilityService own the single overlay
        if (KidsGuardAccessibilityService.instance != null) {
          launch(Dispatchers.Main) {
            hideLockOverlay()
            stopSelf()
          }
          return@collectLatest
        }

        launch {
          dao.getChildById(childId).collectLatest { child ->
            val currentCfg = dao.getFamilyConfigOnce()
            if (currentCfg?.selectedRole != "CHILD") {
              launch(Dispatchers.Main) {
                hideLockOverlay()
                stopSelf()
              }
              return@collectLatest
            }
            child?.let {
              val authLocked = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(childId)?.first
              val effectivelyLocked = authLocked ?: it.isDeviceLocked
              launch(Dispatchers.Main) {
                if (effectivelyLocked) {
                  showFullDeviceLockOverlay("Телефон заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                } else {
                  hideLockOverlay()
                  stopSelf()
                }
              }
            }
          }
        }

        launch {
          cloudSync.observeRemoteCommands(childId).collectLatest { cmdPair ->
            val currentCfg = dao.getFamilyConfigOnce()
            if (currentCfg?.selectedRole != "CHILD") {
              launch(Dispatchers.Main) {
                hideLockOverlay()
                stopSelf()
              }
              return@collectLatest
            }
            cmdPair?.let { (cmd, _) ->
              Log.d(TAG, "LockOverlayService received remote command: $cmd")
              when (cmd) {
                "LOCK" -> {
                  com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(childId, true)
                  dao.setChildDeviceLocked(childId, true)
                  launch(Dispatchers.Main) {
                    showFullDeviceLockOverlay("Телефон заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже.")
                  }
                }
                "UNLOCK" -> {
                  com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(childId, false)
                  dao.setChildDeviceLocked(childId, false)
                  launch(Dispatchers.Main) {
                    hideLockOverlay()
                    stopSelf()
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  @SuppressLint("InflateParams")
  private fun showFullDeviceLockOverlay(reasonText: String) {
    if (!Settings.canDrawOverlays(applicationContext)) {
      return
    }

    if (overlayView != null) {
      overlayView?.findViewById<TextView>(android.R.id.text1)?.text = reasonText
      return
    }

    try {
      val layoutType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
      } else {
        @Suppress("DEPRECATION")
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
      }

      val flags = WindowManager.LayoutParams.FLAG_FULLSCREEN or
          WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED

      val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        layoutType,
        flags,
        PixelFormat.OPAQUE
      ).apply {
        gravity = Gravity.CENTER
      }

      val layout = UnifiedLockScreenViewBuilder.buildLockView(
        context = this,
        reasonText = reasonText,
        isDeviceTotalLock = true,
        onCloseToHome = null,
        onUnlocked = {
          hideLockOverlay()
          DeviceTotalLockManager.releaseTotalLock(applicationContext)
          stopSelf()
        }
      )

      overlayView = layout
      windowManager?.addView(overlayView, params)
      // Через 45 секунд разрешаем экрану погаснуть (оверлей остаётся и появится при
      // включении экрана) — иначе заблокированный телефон горит всю ночь и сажает батарею.
      android.os.Handler(mainLooper).postDelayed({
        try {
          val p = overlayView?.layoutParams as? WindowManager.LayoutParams
          if (p != null) {
            p.flags = p.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON.inv()
            windowManager?.updateViewLayout(overlayView, p)
          }
        } catch (_: Exception) {}
      }, 45_000L)
      Log.d(TAG, "Unified full-screen device lock overlay displayed")
    } catch (e: Exception) {
      Log.e(TAG, "showFullDeviceLockOverlay failed: ${e.message}")
    }
  }

  private fun hideLockOverlay() {
    overlayView?.let {
      try {
        windowManager?.removeView(it)
      } catch (e: Exception) {
        Log.e(TAG, "hideLockOverlay error: ${e.message}")
      }
      overlayView = null
      Log.d(TAG, "Lock overlay hidden")
    }
  }

  override fun onDestroy() {
    hideLockOverlay()
    super.onDestroy()
  }

  companion object {
    fun start(context: Context) {
      val intent = Intent(context, LockOverlayService::class.java)
      context.startService(intent)
    }

    fun stop(context: Context) {
      try {
        val intent = Intent(context, LockOverlayService::class.java)
        context.stopService(intent)
      } catch (_: Exception) {}
    }
  }
}
