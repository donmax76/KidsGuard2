package com.example.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import com.example.BuildConfig
import com.example.data.cloud.CloudSyncManager
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.AlertNotificationEntity
import com.example.service.KidsGuardAccessibilityService
import com.example.service.KidsGuardBackgroundService
import com.example.service.LockOverlayService
import com.example.ui.screens.ChildLockScreenActivity
import com.example.util.ApkUpdateInstaller
import com.example.util.DeviceBatteryUtil
import com.example.util.DeviceControlDebugger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "KidsGuardBootReceiver"

class BootCompletedReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent) {
    val action = intent.action ?: return
    val appContext = context.applicationContext

    // 1. Handle PackageInstaller Session Callback for Background Silent OTA Update
    if (action == ApkUpdateInstaller.ACTION_OTA_INSTALL_STATUS) {
      val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
      val statusMessage = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "no message"
      val sessionId = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
      val otherPkg = intent.getStringExtra(PackageInstaller.EXTRA_OTHER_PACKAGE_NAME) ?: ""
      val statusName = decodePackageInstallerStatus(status)

      Log.i(TAG, "OTA PackageInstaller callback: sessionId=$sessionId, status=$status ($statusName), message='$statusMessage', otherPkg='$otherPkg'")
      DeviceControlDebugger.logEvent(
        "OTA_INSTALL_STATUS",
        "Session #$sessionId -> $statusName ($status): $statusMessage",
        if (status == PackageInstaller.STATUS_SUCCESS) "SUCCESS" else if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) "INFO" else "ERROR"
      )

      when (status) {
        PackageInstaller.STATUS_PENDING_USER_ACTION -> {
          // System requires confirmation dialog (when not provisioned as Device Owner);
          // launch it cleanly and let KidsGuardAccessibilityService auto-click "Обновить"/"Установить" in <250ms!
          ApkUpdateInstaller.isInstallingUpdate = true
          val confirmIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
          } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_INTENT)
          }
          if (confirmIntent != null) {
            try {
              // Ensure no overlay blocks the PackageInstaller button (Android tapjacking protection)
              LockOverlayService.stop(appContext)
              KidsGuardAccessibilityService.instance?.prepareForSilentUpdate()
              confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
              appContext.startActivity(confirmIntent)
              Log.i(TAG, "Launched PackageInstaller STATUS_PENDING_USER_ACTION confirmation intent for Accessibility auto-clicker")
            } catch (e: Exception) {
              Log.e(TAG, "Failed launching confirmIntent: ${e.message}", e)
              DeviceControlDebugger.logEvent("OTA_CONFIRM_INTENT_ERROR", "Ошибка запуска окна подтверждения: ${e.message}", "ERROR")
              ApkUpdateInstaller.launchActionViewFallback(appContext)
            }
          } else {
            Log.w(TAG, "STATUS_PENDING_USER_ACTION had null EXTRA_INTENT; falling back to ACTION_VIEW")
            ApkUpdateInstaller.launchActionViewFallback(appContext)
          }
        }
        PackageInstaller.STATUS_SUCCESS -> {
          ApkUpdateInstaller.isInstallingUpdate = false
          Log.i(TAG, "OTA PackageInstaller STATUS_SUCCESS! Restoring child services & reporting new version.")
          restoreServicesAndNotifyParentAfterUpdate(appContext, isPackageReplacedOrSuccess = true)
        }
        else -> {
          ApkUpdateInstaller.isInstallingUpdate = false
          Log.e(TAG, "OTA PackageInstaller FAILED: status=$status ($statusName), msg='$statusMessage' -> Falling back to ACTION_VIEW installer")
          notifyParentOfOtaError(appContext, statusName, statusMessage)
          ApkUpdateInstaller.launchActionViewFallback(appContext)
        }
      }
      return
    }

    // 2. Handle Device Boot or In-Place APK Replacement (MY_PACKAGE_REPLACED / PACKAGE_REPLACED)
    val isSelfPackageReplaced = action == Intent.ACTION_MY_PACKAGE_REPLACED ||
      (action == Intent.ACTION_PACKAGE_REPLACED && intent.data?.schemeSpecificPart == appContext.packageName)

    if (
      action == Intent.ACTION_BOOT_COMPLETED ||
      action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
      action == "android.intent.action.QUICKBOOT_POWERON" ||
      isSelfPackageReplaced
    ) {
      ApkUpdateInstaller.isInstallingUpdate = false
      Log.i(TAG, "System event ($action). Restoring Accessibility Service & 24/7 KidsGuard protection with v${BuildConfig.VERSION_NAME}.")

      // Immediately restore/rebind Accessibility Service after OTA package replacement or reboot
      val a11yRestored = com.example.util.DeviceSecurityPermissionHelper.restoreAccessibilityServiceAfterOta(appContext)

      DeviceControlDebugger.logEvent(
        if (isSelfPackageReplaced) "АВТООБНОВЛЕНИЕ_ЗАВЕРШЕНО" else "ПЕРЕЗАГРУЗКА_ТЕЛЕФОНА",
        if (isSelfPackageReplaced) {
          "Пакет KidsGuard успешно обновлён до v${BuildConfig.VERSION_NAME} (Accessibility=${if (a11yRestored) "АКТИВЕН" else "ПЕРЕЗАПУСК"}). Все службы перезапущены."
        } else {
          "Система перезагружена. Восстановление служб защиты v${BuildConfig.VERSION_NAME}..."
        },
        "SUCCESS"
      )
      restoreServicesAndNotifyParentAfterUpdate(appContext, isPackageReplacedOrSuccess = isSelfPackageReplaced)
    }
  }

  private fun decodePackageInstallerStatus(status: Int): String = when (status) {
    PackageInstaller.STATUS_PENDING_USER_ACTION -> "STATUS_PENDING_USER_ACTION"
    PackageInstaller.STATUS_SUCCESS -> "STATUS_SUCCESS"
    PackageInstaller.STATUS_FAILURE -> "STATUS_FAILURE"
    PackageInstaller.STATUS_FAILURE_BLOCKED -> "STATUS_FAILURE_BLOCKED"
    PackageInstaller.STATUS_FAILURE_ABORTED -> "STATUS_FAILURE_ABORTED"
    PackageInstaller.STATUS_FAILURE_INVALID -> "STATUS_FAILURE_INVALID"
    PackageInstaller.STATUS_FAILURE_CONFLICT -> "STATUS_FAILURE_CONFLICT"
    PackageInstaller.STATUS_FAILURE_STORAGE -> "STATUS_FAILURE_STORAGE"
    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "STATUS_FAILURE_INCOMPATIBLE"
    else -> "STATUS_UNKNOWN_$status"
  }

  private fun notifyParentOfOtaError(appContext: Context, statusName: String, statusMessage: String) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val dao = KidsGuardDatabase.getDatabase(appContext).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        val cid = cfg?.thisDeviceChildId?.takeIf { it > 0L }
          ?: cfg?.currentActiveChildId?.takeIf { it > 0L }
          ?: 1L
        dao.setChildUpdatePending(cid, false)
        val alert = AlertNotificationEntity(
          childId = cid,
          type = "OTA_INSTALL_ERROR",
          title = "⚠️ Диагностика OTA ($statusName)",
          message = "При установке APK через PackageInstaller получен статус $statusName: $statusMessage",
          extraPayload = BuildConfig.VERSION_NAME,
          timestamp = System.currentTimeMillis()
        )
        dao.insertAlert(alert)
        CloudSyncManager(appContext).sendAlert(alert)
      } catch (e: Exception) {
        Log.w(TAG, "notifyParentOfOtaError failed: ${e.message}")
      }
    }
  }

  private fun restoreServicesAndNotifyParentAfterUpdate(
    appContext: Context,
    isPackageReplacedOrSuccess: Boolean
  ) {
    CoroutineScope(Dispatchers.IO).launch {
      try {
        // 0. Always synchronize unified BuildConfig.VERSION_NAME across CoreEngineHotUpdater & restore Accessibility
        val unifiedBuildVer = com.example.util.CoreEngineHotUpdater.syncUnifiedBuildConfigVersion(appContext)
        com.example.util.DeviceSecurityPermissionHelper.restoreAccessibilityServiceAfterOta(appContext)
        com.example.util.ScreenShotManager.restorePersistentLiveStreamState(appContext)

        val database = KidsGuardDatabase.getDatabase(appContext)
        val dao = database.kidsGuardDao()
        val config = dao.getFamilyConfigOnce()

        // Ensure all local child profiles reflect the unified BuildConfig.VERSION_NAME post-update
        for (c in dao.getAllChildrenOnce()) {
          if (c.appVersion != unifiedBuildVer) {
            dao.updateChildAppVersion(c.id, unifiedBuildVer)
          }
        }

        if (config != null && config.selectedRole == "CHILD") {
          val childId = config.thisDeviceChildId.takeIf { it > 0 }
            ?: config.currentActiveChildId.takeIf { it > 0 }
            ?: dao.getAllChildrenOnce().firstOrNull()?.id
            ?: 1L

          com.example.util.CoreEngineHotUpdater.syncUnifiedBuildConfigVersion(appContext, childId)
          com.example.util.CoreEngineHotUpdater.setChildCoreVersionName(appContext, childId, unifiedBuildVer)
          com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeChildCoreVersion(childId, unifiedBuildVer)

          Log.i(TAG, "Child device restored on boot/update for Child ID: $childId (unifiedVer=$unifiedBuildVer)")

          // 1. Ensure P2P Socket Engine is running in CHILD role
          DirectP2pSocketEngine.startEngine("CHILD", childId)

          // 2. Update local DB version & clear updatePending flag
          dao.updateChildAppVersion(childId, unifiedBuildVer)
          dao.setChildUpdatePending(childId, false)

          // 3. Restart 24/7 Background service immediately with all persisted permissions (including live stream)
          val liveStreamEnabled = com.example.util.ScreenShotManager.isLiveStreamEnabled(appContext)
          KidsGuardBackgroundService.start(appContext, childId, enableProjectionFgs = liveStreamEnabled)

          // 4. Push updated unified BuildConfig.VERSION_NAME, battery, and completion alert to Parent phone
          try {
            val cloudSync = CloudSyncManager(appContext)
            cloudSync.syncChildAppVersion(childId, unifiedBuildVer)
            val batt = DeviceBatteryUtil.getAccurateBatteryPercentage(appContext)
            dao.updateChildBattery(childId, batt)
            cloudSync.upsertBatteryLevel(childId, batt)

            val child = dao.getChildByIdOnce(childId)
            if (child != null) {
              cloudSync.upsertChildProfile(
                child.copy(
                  appVersion = unifiedBuildVer,
                  updatePending = false,
                  batteryLevel = batt
                )
              )
            }

            if (isPackageReplacedOrSuccess) {
              val childName = child?.name ?: "Ребёнок #$childId"
              val alert = AlertNotificationEntity(
                childId = childId,
                type = "REMOTE_UPDATE_COMPLETED",
                title = "✅ Обновление установлено у «$childName»",
                message = "Устройство «$childName» обновилось до актуальной версии v$unifiedBuildVer (MDM). Спец. возможности и службы родительского контроля восстановлены автоматически.",
                extraPayload = unifiedBuildVer,
                timestamp = System.currentTimeMillis()
              )
              dao.insertAlert(alert)
              cloudSync.sendAlert(alert)
            }
          } catch (e: Exception) {
            Log.e(TAG, "Cloud sync after update error: ${e.message}")
          }

          // 5. Re-enforce lock if device was locked prior to reboot/update
          val child = dao.getChildByIdOnce(childId)
          if (child != null && child.isDeviceLocked) {
            Log.w(TAG, "Child device was locked prior to reboot/update! Enforcing lock immediately.")
            DeviceControlDebugger.logEvent("БЛОКИРОВКА_ПОСЛЕ_РЕБУТА", "Телефон был заблокирован! Замок наложен сразу при старте.", "BLOCKED")
            LockOverlayService.start(appContext)
            ChildLockScreenActivity.launch(appContext, "Телефон заблокирован родителем!\nПерезагрузка не отменяет блокировку.")
          }

          // 6. Second-pass Accessibility watchdog: if AccessibilityService is still not bound 2.5s after OTA, re-trigger restore
          kotlinx.coroutines.delay(2500L)
          if (KidsGuardAccessibilityService.instance == null) {
            Log.w(TAG, "AccessibilityService not yet bound 2.5s after OTA/boot; running second-pass restoreAccessibilityServiceAfterOta")
            com.example.util.DeviceSecurityPermissionHelper.restoreAccessibilityServiceAfterOta(appContext)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "Error handling boot/update restore: ${e.message}")
      }
    }
  }
}
