package com.example

import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.example.data.local.KidsGuardDatabase
import com.example.service.KidsGuardBackgroundService
import com.example.service.LockOverlayService
import com.example.ui.MainScreen
import com.example.ui.theme.MyApplicationTheme
import com.example.ui.theme.Slate950
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
  companion object {
    /** Родитель на переднем плане: только тогда шлём PING/телеметрию. */
    @Volatile
    var isAppForeground: Boolean = false
  }

  private val locationPermissionLauncher = registerForActivityResult(
    androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
  ) {
    syncServicesWithCurrentRole()
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()
    com.example.util.ScreenShotManager.restorePersistentLiveStreamState(applicationContext)
    handleSessionInstallerIntent(intent)

    if (!com.example.location.LiveLocationTracker.isEmulator()) {
      syncServicesWithCurrentRole()
    }

    setContent {
      MyApplicationTheme(darkTheme = true) {
        Surface(
          modifier = Modifier.fillMaxSize(),
          color = Slate950
        ) {
          MainScreen()
        }
      }
    }
  }

  fun requestChildLocationPermissionsIfNeeded() {
    if (com.example.location.LiveLocationTracker.isEmulator()) return
    val hasLoc = com.example.util.DeviceSecurityPermissionHelper.hasLocationPermission(this)
    if (!hasLoc) {
      try {
        locationPermissionLauncher.launch(
          arrayOf(
            android.Manifest.permission.ACCESS_FINE_LOCATION,
            android.Manifest.permission.ACCESS_COARSE_LOCATION
          )
        )
      } catch (_: Exception) {}
    }
  }

  override fun onResume() {
    super.onResume()
    isAppForeground = true
    com.example.util.ScreenShotManager.restorePersistentLiveStreamState(applicationContext)
    if (!com.example.location.LiveLocationTracker.isEmulator()) {
      syncServicesWithCurrentRole()
    }
    sendParentSession(active = true)
  }

  override fun onPause() {
    super.onPause()
    isAppForeground = false
    sendParentSession(active = false)
  }

  /**
   * PARENT_SESSION_START/STOP: ребёнок просыпается только на время сессии родителя,
   * а REQUEST_POWER_STATS читает HealthStats ровно один раз при открытии.
   */
  private fun sendParentSession(active: Boolean) {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole != "PARENT") return@launch
        val childId = cfg.currentActiveChildId.takeIf { it > 0L }
          ?: dao.getAllChildrenOnce().firstOrNull()?.id
          ?: return@launch
        val cloud = com.example.data.cloud.CloudSyncManager(applicationContext)
        if (active) {
          cloud.sendRemoteCommand(childId, "PARENT_SESSION_START", "1")
          cloud.sendRemoteCommand(childId, "REQUEST_POWER_STATS", "1")
        } else {
          cloud.sendRemoteCommand(childId, "PARENT_SESSION_STOP", "1")
        }
      } catch (_: Exception) {}
    }
  }

  private fun syncServicesWithCurrentRole() {
    lifecycleScope.launch(Dispatchers.IO) {
      try {
        val dao = KidsGuardDatabase.getDatabase(applicationContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole == "CHILD") {
          val childId = cfg.thisDeviceChildId.takeIf { it > 0 }
            ?: cfg.currentActiveChildId.takeIf { it > 0 }
            ?: 1L
          com.example.util.ScreenShotManager.restorePersistentLiveStreamState(applicationContext)
          KidsGuardBackgroundService.start(
            applicationContext,
            childId,
            enableProjectionFgs = com.example.util.ScreenShotManager.isLiveStreamEnabled(applicationContext)
          )
          window?.decorView?.postDelayed({
            com.example.util.ScreenShotManager.captureActivityDecorView(this@MainActivity)
          }, 400L)
        } else if (cfg?.selectedRole == "PARENT") {
          // This device is in PARENT mode: ensure child lock & child background services are stopped,
          // but preserve persisted live stream preference in case user switches back to Child mode
          com.example.util.ScreenShotManager.stopMediaProjection(applicationContext, disablePreference = false)
          LockOverlayService.stop(applicationContext)
          KidsGuardBackgroundService.stop(applicationContext)
        }
      } catch (_: Exception) {}
    }
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    handleSessionInstallerIntent(intent)
  }

  private fun handleSessionInstallerIntent(intent: Intent?) {
    if (intent == null) return
    val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -999)
    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
      val confirmIntent: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
      } else {
        @Suppress("DEPRECATION")
        intent.getParcelableExtra(Intent.EXTRA_INTENT)
      }
      if (confirmIntent != null) {
        confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
          startActivity(confirmIntent)
        } catch (_: Exception) {}
      }
    }
  }
}
