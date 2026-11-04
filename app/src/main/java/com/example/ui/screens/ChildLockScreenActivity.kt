package com.example.ui.screens

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
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

class ChildLockScreenActivity : ComponentActivity() {
  private var isUnlocked = false
  private val activityScope = CoroutineScope(Dispatchers.Main + Job())

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.data.cloud.DirectP2pSocketEngine.appContext = applicationContext

    DeviceTotalLockManager.activeLockActivityCloser = {
      isUnlocked = true
      try {
        finishAndRemoveTask()
      } catch (_: Exception) {
        finish()
      }
    }

    // If system overlay permission is granted, use the single static overlay instead of a competing Activity
    if (Settings.canDrawOverlays(applicationContext) &&
        com.example.service.KidsGuardAccessibilityService.instance != null) {
      isUnlocked = true
      val reason = intent.getStringExtra("EXTRA_REASON") ?: "Экран заблокирован родительским контролем KidsGuard"
      com.example.service.KidsGuardAccessibilityService.instance?.enforceTotalLockFromService(reason)
      finish()
      return
    }

    window.addFlags(
      WindowManager.LayoutParams.FLAG_FULLSCREEN or
          WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
          WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
          WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
    )
    // Экран блокировки не должен гореть сутками — через 45 секунд разрешаем подсветке
    // погаснуть (сама блокировка остаётся и появится при включении экрана).
    window.decorView.postDelayed({
      try {
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
      } catch (_: Exception) {}
    }, 45_000L)

    val reason = intent.getStringExtra("EXTRA_REASON")
      ?: "Телефон заблокирован родительским контролем KidsGuard.\nРазблокируйте через телефон родителя или введите PIN-код ниже."

    val lockView = UnifiedLockScreenViewBuilder.buildLockView(
      context = this,
      reasonText = reason,
      isDeviceTotalLock = true,
      onCloseToHome = null,
      onUnlocked = {
        isUnlocked = true
        DeviceTotalLockManager.releaseTotalLock(applicationContext)
        finishAndRemoveTask()
      }
    )
    setContentView(lockView)

    // Listen to local DB, 1s authoritative lock state, and P2P UNLOCK commands
    activityScope.launch(Dispatchers.IO) {
      val database = KidsGuardDatabase.getDatabase(applicationContext)
      val dao = database.kidsGuardDao()
      val cloudSync = com.example.data.cloud.CloudSyncManager(applicationContext)

      launch {
        while (isActive && !isUnlocked) {
          delay(800L)
          val cfg = dao.getFamilyConfigOnce()
          if (cfg == null || cfg.selectedRole != "CHILD") {
            isUnlocked = true
            launch(Dispatchers.Main) { finishAndRemoveTask() }
            break
          }
          val cid = cfg.thisDeviceChildId.takeIf { it > 0L }
            ?: cfg.currentActiveChildId.takeIf { it > 0L }
            ?: 1L
          val authLocked = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(cid)?.first
          if (authLocked == false) {
            isUnlocked = true
            dao.setChildDeviceLocked(cid, false)
            launch(Dispatchers.Main) { finishAndRemoveTask() }
            break
          }
        }
      }

      dao.getFamilyConfig().collectLatest { cfg ->
        if (cfg == null || cfg.selectedRole != "CHILD") {
          isUnlocked = true
          launch(Dispatchers.Main) { finishAndRemoveTask() }
          return@collectLatest
        }
        val childId = cfg.thisDeviceChildId.takeIf { it > 0 } ?: cfg.currentActiveChildId
        if (childId > 0) {
          launch {
            dao.getChildById(childId).collectLatest { child ->
              val authLocked = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(childId)?.first
              val effectivelyLocked = authLocked ?: (child?.isDeviceLocked ?: true)
              if (child != null && !effectivelyLocked) {
                isUnlocked = true
                launch(Dispatchers.Main) { finishAndRemoveTask() }
              }
            }
          }
          launch {
            cloudSync.observeRemoteCommands(childId).collectLatest { cmdPair ->
              if (cmdPair?.first == "UNLOCK") {
                isUnlocked = true
                com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(childId, false)
                dao.setChildDeviceLocked(childId, false)
                launch(Dispatchers.Main) { finishAndRemoveTask() }
              }
            }
          }
        }
      }
    }
  }

  override fun onDestroy() {
    if (DeviceTotalLockManager.activeLockActivityCloser != null) {
      DeviceTotalLockManager.activeLockActivityCloser = null
    }
    super.onDestroy()
  }

  @Deprecated("Deprecated in Java")
  override fun onBackPressed() {}

  override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
    if (keyCode == KeyEvent.KEYCODE_BACK ||
        keyCode == KeyEvent.KEYCODE_HOME ||
        keyCode == KeyEvent.KEYCODE_APP_SWITCH) {
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  companion object {
    fun launch(context: Context, reason: String = "Экран заблокирован родительским контролем KidsGuard") {
      if (Settings.canDrawOverlays(context.applicationContext) &&
          com.example.service.KidsGuardAccessibilityService.instance != null) {
        com.example.service.KidsGuardAccessibilityService.instance?.enforceTotalLockFromService(reason)
        return
      }
      val intent = Intent(context, ChildLockScreenActivity::class.java).apply {
        putExtra("EXTRA_REASON", reason)
        addFlags(
          Intent.FLAG_ACTIVITY_NEW_TASK or
              Intent.FLAG_ACTIVITY_CLEAR_TOP or
              Intent.FLAG_ACTIVITY_SINGLE_TOP or
              Intent.FLAG_ACTIVITY_NO_ANIMATION
        )
      }
      context.startActivity(intent)
    }
  }
}
