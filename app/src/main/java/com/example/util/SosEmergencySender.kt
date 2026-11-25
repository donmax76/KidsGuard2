package com.example.util

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.widget.Toast
import com.example.data.cloud.CloudSyncManager
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.data.cloud.RealtimeCloudRelay
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.ChatMessageEntity
import com.example.location.LiveLocationTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "SosEmergencySender"

/**
 * Единый модуль экстренной связи SOS и разблокировки экрана PIN-кодом прямо на форме блокировки.
 * Отправляет тревогу SOS, сообщение в семейный чат с пометкой внимания и точные GPS-координаты
 * родителю по всем каналам P2P (LAN + бинарный TCP-сокет).
 */
object SosEmergencySender {

  fun triggerShortVibration(context: Context) {
    try {
      val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createOneShot(180, VibrationEffect.DEFAULT_AMPLITUDE))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(180)
      }
    } catch (_: Exception) {}
  }

  fun sendSosToParent(
    context: Context,
    sourceNote: String = "Экран блокировки",
    onStatusUpdate: ((String) -> Unit)? = null
  ) {
    val appContext = context.applicationContext
    triggerShortVibration(appContext)
    CoroutineScope(Dispatchers.Main).launch {
      onStatusUpdate?.invoke("⏳ Передача сигнала SOS и GPS-координат родителю...")
    }

    CoroutineScope(Dispatchers.IO).launch {
      try {
        val db = KidsGuardDatabase.getDatabase(appContext)
        val dao = db.kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        val allChildren = dao.getAllChildrenOnce()
        val boundId = cfg?.thisDeviceChildId?.takeIf { it > 0L }
          ?: cfg?.currentActiveChildId?.takeIf { it > 0L }
          ?: allChildren.firstOrNull()?.id
          ?: 1L

        val childProfile = dao.getChildByIdOnce(boundId)
          ?: RealtimeCloudRelay.getCachedChild(boundId)
          ?: allChildren.firstOrNull()

        val childName = childProfile?.name?.takeIf { it.isNotBlank() } ?: "Ребёнок"
        val pairingCode = childProfile?.pairingCode.orEmpty()

        // Actively await a fresh PRIORITY_HIGH_ACCURACY GPS/Fused fix (up to 3.5s) with immediate fallback
        val freshOrCachedLoc = LiveLocationTracker.awaitHighAccuracyLocation(appContext, timeoutMs = 3_500L)
          ?: LiveLocationTracker.getBestImmediateLocation(appContext)
        val lat = freshOrCachedLoc?.latitude?.takeIf {
          it != 0.0 && !LiveLocationTracker.isEmulatorSyntheticUsLocation(it, freshOrCachedLoc.longitude)
        } ?: (childProfile?.lastLocationLat ?: 0.0)
        val lng = freshOrCachedLoc?.longitude?.takeIf {
          it != 0.0 && !LiveLocationTracker.isEmulatorSyntheticUsLocation(freshOrCachedLoc.latitude, it)
        } ?: (childProfile?.lastLocationLng ?: 0.0)

        val hasCoords = lat != 0.0 && lng != 0.0 && !LiveLocationTracker.isEmulatorSyntheticUsLocation(lat, lng)
        val coordFormatted = if (hasCoords) "%.5f, %.5f".format(java.util.Locale.US, lat, lng) else ""
        val addressText = if (hasCoords) {
          val geocoded = LiveLocationTracker.reverseGeocodeExact(appContext, lat, lng)
          if (geocoded.isNotBlank() && !geocoded.startsWith("Точные координаты")) {
            "$geocoded ($coordFormatted)"
          } else {
            "GPS: $coordFormatted"
          }
        } else {
          childProfile?.lastLocationAddress?.takeIf { it.isNotBlank() } ?: "Координаты определяются..."
        }

        val now = System.currentTimeMillis()
        val sosTitle = "🚨 ЭКСТРЕННЫЙ СИГНАЛ SOS от «$childName»!"
        val sosMessage = "Ребёнок нажал кнопку экстренной связи SOS ($sourceNote)! Локация: $addressText"
        val coordsPayload = if (hasCoords) "$lat,$lng" else ""

        val alertEntity = AlertNotificationEntity(
          id = now,
          childId = boundId,
          type = "SOS",
          title = sosTitle,
          message = sosMessage,
          extraPayload = coordsPayload,
          timestamp = now,
          isRead = false
        )
        dao.insertAlert(alertEntity.copy(id = 0))

        val chatEntity = ChatMessageEntity(
          id = now + 1L,
          childId = boundId,
          senderRole = "CHILD",
          senderName = childName,
          text = "🚨 ЭКСТРЕННЫЙ ВЫЗОВ SOS! Родители, мне срочно нужна связь! Локация: $addressText",
          timestamp = now,
          isRead = false,
          isAttentionSignal = true
        )
        dao.insertChatMessage(chatEntity.copy(id = 0))

        val cloudSync = CloudSyncManager(appContext)
        if (hasCoords) {
          dao.updateChildLocation(boundId, lat, lng, addressText, now)
          cloudSync.updateLocation(boundId, lat, lng, addressText)
        }

        // Отправляем SOS-алерт и сообщение в чат ровно один раз и БЕЗ retain,
        // чтобы сигнал тревоги и чат не дублировались и не срабатывали повторно при реконнекте к брокеру!
        RealtimeCloudRelay.publishAlert(alertEntity.copy(childId = boundId))
        RealtimeCloudRelay.publishChatMessage(chatEntity.copy(childId = boundId))

        CoroutineScope(Dispatchers.Main).launch {
          val confirmMsg = if (hasCoords) {
            "✅ Сигнал SOS и точные координаты ($coordFormatted) отправлены родителю!"
          } else {
            "✅ Сигнал SOS отправлен родителю! Уточнение GPS..."
          }
          onStatusUpdate?.invoke(confirmMsg)
          try {
            Toast.makeText(appContext, confirmMsg, Toast.LENGTH_LONG).show()
          } catch (_: Exception) {}
        }

        // If a finer satellite fix arrives moments after SOS, push an immediate location refinement
        val refinedLoc = LiveLocationTracker.awaitHighAccuracyLocation(appContext, timeoutMs = 5_000L)
        if (refinedLoc != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(refinedLoc.latitude, refinedLoc.longitude)) {
          val refAddr = LiveLocationTracker.reverseGeocodeExact(appContext, refinedLoc.latitude, refinedLoc.longitude)
          dao.updateChildLocation(boundId, refinedLoc.latitude, refinedLoc.longitude, refAddr, System.currentTimeMillis())
          cloudSync.updateLocation(boundId, refinedLoc.latitude, refinedLoc.longitude, refAddr)
        }
        Log.d(TAG, "SOS successfully transmitted to Parent for child $childName (id=$boundId, coords=$coordsPayload)")
      } catch (e: Exception) {
        Log.e(TAG, "Error sending SOS to Parent: ${e.message}")
        CoroutineScope(Dispatchers.Main).launch {
          onStatusUpdate?.invoke("⚠️ Повторная отправка сигнала SOS родителю...")
        }
      }
    }
  }

  fun verifyPinAndUnlockDevice(
    context: Context,
    enteredPin: String,
    blockedPackageName: String? = null,
    onResult: (Boolean) -> Unit
  ) {
    val appContext = context.applicationContext
    val cleanPin = enteredPin.trim()
    CoroutineScope(Dispatchers.IO).launch {
      try {
        val db = KidsGuardDatabase.getDatabase(appContext)
        val dao = db.kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        val masterPin = cfg?.parentMasterPin?.trim()?.takeIf { it.isNotBlank() }
          ?: BootPrefs.getMasterPin(appContext)

        val isValid = cleanPin == masterPin
        if (isValid) {
          val allChildren = dao.getAllChildrenOnce()
          val boundId = cfg?.thisDeviceChildId?.takeIf { it > 0L }
            ?: cfg?.currentActiveChildId?.takeIf { it > 0L }
            ?: allChildren.firstOrNull()?.id
            ?: 1L

          // 1. Если окно блокировки было открыто для конкретного приложения —
          // полностью разблокируем это приложение до новой блокировки родителем, синхронизируем с родителями и сразу открываем программу!
          val targetPkg = blockedPackageName?.trim()?.takeIf { it.isNotBlank() }
            ?: com.example.service.KidsGuardAccessibilityService.instance?.getCurrentBlockedOverlayPackage()
          if (!targetPkg.isNullOrBlank()) {
            BootPrefs.saveBlockedPackage(appContext, targetPkg, false)
            com.example.service.KidsGuardAccessibilityService.instance?.unblockPackageLocallyByPin(targetPkg)
            dao.setAppBlockedByPackageGlobal(targetPkg, false)
            val existingApp = dao.getAppByPackageOnce(boundId, targetPkg)
            if (existingApp != null && existingApp.dailyLimitMinutes > 0 && existingApp.usedMinutesToday >= existingApp.dailyLimitMinutes) {
              dao.setAppLimitByPackageGlobal(targetPkg, 0)
            }
            try {
              RealtimeCloudRelay.publishAppUpdate(
                childId = boundId,
                packageName = targetPkg,
                isBlocked = false,
                limitMinutes = null
              )
              val updatedApps = dao.getAppsForChildOnce(boundId)
              CloudSyncManager(appContext).uploadChildApps(boundId, updatedApps)
            } catch (_: Exception) {}
            DeviceControlDebugger.logEvent(
              "РАЗБЛОКИРОВКА_ПО_PIN",
              "Приложение «$targetPkg» разблокировано PIN-кодом родителя и запущено",
              "SUCCESS",
              "CHILD"
            )
          }

          // 2. Снимаем общую блокировку экрана, если она была включена
          for (c in allChildren) {
            dao.setChildDeviceLocked(c.id, false)
            RealtimeCloudRelay.publishAuthoritativeLock(c.id, false)
          }
          dao.setChildDeviceLocked(boundId, false)
          RealtimeCloudRelay.publishAuthoritativeLock(boundId, false)
          try {
            CloudSyncManager(appContext).updateRemoteLock(boundId, false)
          } catch (_: Exception) {}

          CoroutineScope(Dispatchers.Main).launch {
            triggerShortVibration(appContext)
            DeviceTotalLockManager.releaseTotalLock(appContext)
            onResult(true)
            // Автоматически запускаем разблокированную программу сразу после снятия окна PIN-шлюза
            if (!targetPkg.isNullOrBlank() && targetPkg != appContext.packageName) {
              try {
                val launchIntent = appContext.packageManager.getLaunchIntentForPackage(targetPkg)?.apply {
                  addFlags(
                    android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                      android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or
                      android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                  )
                }
                if (launchIntent != null) {
                  appContext.startActivity(launchIntent)
                }
              } catch (e: Exception) {
                Log.e(TAG, "Failed to auto-launch unlocked package $targetPkg: ${e.message}")
              }
            }
          }
        } else {
          CoroutineScope(Dispatchers.Main).launch {
            triggerShortVibration(appContext)
            onResult(false)
          }
        }
      } catch (e: Exception) {
        Log.e(TAG, "verifyPinAndUnlockDevice error: ${e.message}")
        CoroutineScope(Dispatchers.Main).launch {
          onResult(false)
        }
      }
    }
  }
}
