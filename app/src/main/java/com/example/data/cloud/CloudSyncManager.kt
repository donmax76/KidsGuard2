package com.example.data.cloud

import android.content.Context
import android.util.Log
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

private const val TAG = "CloudSyncManager"

class CloudSyncManager(private val context: Context) {
  val firebaseService = FirebaseSyncService(context)

  suspend fun upsertChildProfile(child: ChildProfileEntity) = withContext(Dispatchers.IO) {
    firebaseService.upsertChildProfile(child)
  }

  suspend fun findChildByPairingCode(code: String): ChildProfileEntity? = withContext(Dispatchers.IO) {
    firebaseService.findChildByPairingCode(code)
  }

  suspend fun markChildPaired(childId: Long, pairingCode: String, deviceModel: String) = withContext(Dispatchers.IO) {
    firebaseService.markChildPairedInCloud(childId, pairingCode, deviceModel)
  }

  fun observeAllChildren(): Flow<List<ChildProfileEntity>> = firebaseService.observeAllChildren()
  fun observeChild(childId: Long): Flow<ChildProfileEntity?> = firebaseService.observeChild(childId)

  // Real child apps
  suspend fun uploadChildApps(childId: Long, apps: List<AppControlEntity>) = withContext(Dispatchers.IO) {
    firebaseService.uploadChildApps(childId, apps)
  }

  fun observeAppsForChild(childId: Long): Flow<List<AppControlEntity>> = firebaseService.observeAppsForChild(childId)

  suspend fun updateAppBlocked(childId: Long, packageName: String, isBlocked: Boolean) = withContext(Dispatchers.IO) {
    firebaseService.updateAppBlocked(childId, packageName, isBlocked)
  }

  suspend fun updateAppLimit(childId: Long, packageName: String, limitMinutes: Int) = withContext(Dispatchers.IO) {
    firebaseService.updateAppLimit(childId, packageName, limitMinutes)
  }

  // Remote Commands (Parent -> Child realtime commands)
  suspend fun sendRemoteCommand(childId: Long, command: String, payload: String = "") = withContext(Dispatchers.IO) {
    firebaseService.sendRemoteCommand(childId, command, payload)
  }

  fun observeRemoteCommands(childId: Long): Flow<Pair<String, String>?> = firebaseService.observeRemoteCommands(childId)

  suspend fun clearRemoteCommand(childId: Long) = withContext(Dispatchers.IO) {
    firebaseService.clearRemoteCommand(childId)
  }

  suspend fun updateRemoteLock(childId: Long, locked: Boolean) = withContext(Dispatchers.IO) {
    firebaseService.updateRemoteLock(childId, locked)
  }

  suspend fun updateDailyLimit(childId: Long, limit: Int) = withContext(Dispatchers.IO) {
    firebaseService.updateDailyLimit(childId, limit)
  }

  suspend fun updateStudyMode(childId: Long, active: Boolean) = withContext(Dispatchers.IO) {
    firebaseService.updateStudyMode(childId, active)
  }

  suspend fun updateAllowMobileData(childId: Long, allow: Boolean) = withContext(Dispatchers.IO) {
    firebaseService.updateAllowMobileData(childId, allow)
  }

  suspend fun updateGpsTrackingEnabled(childId: Long, enabled: Boolean) = withContext(Dispatchers.IO) {
    firebaseService.updateGpsTrackingEnabled(childId, enabled)
  }

  suspend fun updateLocation(childId: Long, lat: Double, lng: Double, address: String) = withContext(Dispatchers.IO) {
    firebaseService.updateLocation(childId, lat, lng, address)
  }

  fun observeLocationHistory24h(childId: Long): Flow<List<com.example.data.model.LocationBreadcrumb>> =
    firebaseService.observeLocationHistory24h(childId)

  suspend fun sendChatMessage(message: ChatMessageEntity) = withContext(Dispatchers.IO) {
    firebaseService.sendChatMessage(message)
  }

  fun observeChatMessages(childId: Long): Flow<List<ChatMessageEntity>> = firebaseService.observeChatMessages(childId)

  suspend fun sendAlert(alert: AlertNotificationEntity) = withContext(Dispatchers.IO) {
    firebaseService.sendAlert(alert)
  }

  fun observeAlerts(): Flow<List<AlertNotificationEntity>> = firebaseService.observeAlerts()

  suspend fun deleteChildFromCloud(childId: Long) = withContext(Dispatchers.IO) {
    firebaseService.deleteChildFromCloud(childId)
  }

  suspend fun upsertBatteryLevel(childId: Long, level: Int) = withContext(Dispatchers.IO) {
    try {
      firebaseService.updateChildBattery(childId, level)
    } catch (_: Exception) {}
  }

  suspend fun syncChildAppVersion(childId: Long, version: String) = withContext(Dispatchers.IO) {
    try {
      firebaseService.updateChildAppVersion(childId, version)
    } catch (_: Exception) {}
  }

  suspend fun updateActiveAppOnly(childId: Long, activeAppName: String) = withContext(Dispatchers.IO) {
    firebaseService.updateActiveAppOnly(childId, activeAppName)
  }

  suspend fun uploadScreenSnapshot(childId: Long, activeAppName: String, base64Bitmap: String) = withContext(Dispatchers.IO) {
    firebaseService.uploadScreenSnapshot(childId, activeAppName, base64Bitmap)
  }

  fun observeScreenSnapshot(childId: Long): Flow<Pair<String, String>?> = firebaseService.observeScreenSnapshot(childId)

  suspend fun pollLatestScreenSnapshot(childId: Long): Triple<String, String, Long>? = withContext(Dispatchers.IO) {
    firebaseService.pollLatestScreenSnapshot(childId)
  }

  suspend fun triggerRemoteAppUpdate(childId: Long, latestVersion: String, downloadUrl: String) = withContext(Dispatchers.IO) {
    firebaseService.triggerRemoteAppUpdate(childId, latestVersion, downloadUrl)
  }

  suspend fun syncContentRulesToCloud(rules: List<com.example.data.model.ContentRuleEntity>) = withContext(Dispatchers.IO) {
    firebaseService.syncContentRulesToCloud(rules)
  }

  fun observeContentRulesFromCloud(): Flow<List<com.example.data.model.ContentRuleEntity>> =
    firebaseService.observeContentRulesFromCloud()
}
