package com.example.data.local

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.example.data.model.AggregatedActivityByDateItem
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import com.example.data.model.ContentRuleEntity
import com.example.data.model.FamilyConfigEntity
import com.example.data.model.GeofenceZoneEntity
import com.example.data.model.OfflineActivityLogEntity
import com.example.data.model.OfflineLocationPointEntity
import com.example.data.model.OfflineScreenshotEntity
import com.example.data.model.ParentSettingsEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface KidsGuardDao {
  // Family Configuration
  @Query("SELECT * FROM family_config WHERE id = 1")
  fun getFamilyConfig(): Flow<FamilyConfigEntity?>

  @Query("SELECT * FROM family_config WHERE id = 1")
  suspend fun getFamilyConfigOnce(): FamilyConfigEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdateFamilyConfig(config: FamilyConfigEntity)

  @Query("UPDATE family_config SET selectedRole = :role WHERE id = 1")
  suspend fun setDeviceRole(role: String)

  @Query("UPDATE family_config SET currentActiveChildId = :childId WHERE id = 1")
  suspend fun setActiveChildId(childId: Long)

  @Query("UPDATE family_config SET thisDeviceChildId = :childId WHERE id = 1")
  suspend fun setThisDeviceChildId(childId: Long)

  @Query("UPDATE family_config SET parentMasterPin = :pin WHERE id = 1")
  suspend fun updateMasterPin(pin: String)

  @Query("UPDATE family_config SET childUninstallPin = :pin WHERE id = 1")
  suspend fun updateChildUninstallPin(pin: String)

  @Query("UPDATE family_config SET antiUninstallEnabled = :enabled WHERE id = 1")
  suspend fun updateAntiUninstall(enabled: Boolean)

  @Query("UPDATE child_profiles SET updatePending = :pending WHERE id = :childId")
  suspend fun setChildUpdatePending(childId: Long, pending: Boolean)

  @Query("UPDATE child_profiles SET appVersion = :version, updatePending = 0 WHERE id = :childId")
  suspend fun updateChildAppVersion(childId: Long, version: String)

  @Query("UPDATE app_controls SET isBlocked = 0, dailyLimitMinutes = 0, usedMinutesToday = 0 WHERE childId = :childId")
  suspend fun resetAllAppsRestrictionsForChild(childId: Long)

  // Multi-Child Profiles
  @Query("SELECT * FROM child_profiles ORDER BY name")
  fun getAllChildren(): Flow<List<ChildProfileEntity>>

  @Query("SELECT * FROM child_profiles ORDER BY name")
  suspend fun getAllChildrenOnce(): List<ChildProfileEntity>

  @Query("SELECT * FROM child_profiles WHERE id = :id")
  fun getChildById(id: Long): Flow<ChildProfileEntity?>

  @Query("SELECT * FROM child_profiles WHERE id = :id")
  suspend fun getChildByIdOnce(id: Long): ChildProfileEntity?

  @Query("SELECT * FROM child_profiles WHERE pairingCode = :code LIMIT 1")
  suspend fun findChildByPairingCode(code: String): ChildProfileEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChild(child: ChildProfileEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChildren(children: List<ChildProfileEntity>)

  @Update
  suspend fun updateChild(child: ChildProfileEntity)

  @Delete
  suspend fun deleteChild(child: ChildProfileEntity)

  @Query("DELETE FROM child_profiles WHERE id = :id")
  suspend fun deleteChildById(id: Long)

  @Query("DELETE FROM app_controls WHERE childId = :childId")
  suspend fun deleteAppsForChild(childId: Long)

  @Query("DELETE FROM app_controls")
  suspend fun clearAllApps()

  @Query("DELETE FROM app_controls WHERE LOWER(packageName) LIKE '%kidsguard%' OR LOWER(appName) LIKE '%kidsguard%' OR LOWER(appName) LIKE '%родительский контроль%'")
  suspend fun removeSelfAppsFromDb()

  @Query("DELETE FROM app_controls WHERE id NOT IN (SELECT MIN(id) FROM app_controls GROUP BY childId, LOWER(TRIM(packageName)))")
  suspend fun deduplicateAppsInDb()

  @Query("DELETE FROM geofence_zones WHERE childId = :childId")
  suspend fun deleteGeofencesForChild(childId: Long)

  @Query("DELETE FROM alert_notifications WHERE childId = :childId")
  suspend fun deleteAlertsForChild(childId: Long)

  @Query("UPDATE child_profiles SET isDeviceLocked = :locked WHERE id = :id")
  suspend fun setChildDeviceLocked(id: Long, locked: Boolean)

  @Query("UPDATE child_profiles SET dailyScreenLimitMinutes = :limitMinutes WHERE id = :id")
  suspend fun setChildDailyLimit(id: Long, limitMinutes: Int)

  @Query("UPDATE child_profiles SET studyModeActive = :studyMode WHERE id = :id")
  suspend fun setChildStudyMode(id: Long, studyMode: Boolean)

  @Query("UPDATE child_profiles SET isPaired = 1, deviceModel = :model WHERE id = :id")
  suspend fun markChildPaired(id: Long, model: String)

  @Query("UPDATE child_profiles SET batteryLevel = :battery WHERE id = :id")
  suspend fun updateChildBattery(id: Long, battery: Int)

  @Query("UPDATE child_profiles SET lastLocationLat = :lat, lastLocationLng = :lng, lastLocationAddress = :address, lastLocationTime = :time WHERE id = :id")
  suspend fun updateChildLocation(id: Long, lat: Double, lng: Double, address: String, time: Long)

  @Query("UPDATE child_profiles SET usedScreenTimeMinutes = usedScreenTimeMinutes + :addMinutes WHERE id = :id")
  suspend fun addChildUsedScreenTime(id: Long, addMinutes: Int)

  @Query("UPDATE child_profiles SET usedScreenTimeMinutes = 0 WHERE id = :childId")
  suspend fun resetChildUsedScreenTimeToday(childId: Long)

  @Query("UPDATE child_profiles SET usedScreenTimeMinutes = 0")
  suspend fun resetAllChildrenUsedScreenTimeToday()

  @Query("UPDATE app_controls SET usedMinutesToday = 0 WHERE childId = :childId")
  suspend fun resetAllAppsUsedMinutesToday(childId: Long)

  @Query("UPDATE app_controls SET usedMinutesToday = 0")
  suspend fun resetAllAppsUsedMinutesTodayGlobal()

  @Query("UPDATE child_profiles SET allowMobileData = :allow WHERE id = :id")
  suspend fun setChildAllowMobileData(id: Long, allow: Boolean)

  @Query("UPDATE child_profiles SET gpsTrackingEnabled = :enabled WHERE id = :id")
  suspend fun setChildGpsTrackingEnabled(id: Long, enabled: Boolean)

  // App Controls
  @Query("SELECT * FROM app_controls WHERE childId = :childId ORDER BY category, appName")
  fun getAppsForChild(childId: Long): Flow<List<AppControlEntity>>

  @Query("SELECT * FROM app_controls WHERE childId = :childId ORDER BY category, appName")
  suspend fun getAppsForChildOnce(childId: Long): List<AppControlEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertApp(app: AppControlEntity): Long

  @Update
  suspend fun updateApp(app: AppControlEntity)

  @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
  suspend fun getChatMessageByIdOnce(id: Long): ChatMessageEntity?

  @Query("SELECT * FROM app_controls WHERE id = :id LIMIT 1")
  suspend fun getAppByIdOnce(id: Long): AppControlEntity?

  @Query("SELECT * FROM app_controls WHERE childId = :childId AND packageName = :packageName LIMIT 1")
  suspend fun getAppByPackageOnce(childId: Long, packageName: String): AppControlEntity?

  @Query("SELECT * FROM app_controls ORDER BY category, appName")
  fun getAllApps(): Flow<List<AppControlEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertApps(apps: List<AppControlEntity>)

  /**
   * Phase 4B Fix: Strictly replaces the app list for [childId] with [remoteApps] received from the Child's device.
   * Any local Parent apps that were previously in `app_controls` are completely purged (`deleteAppsForChild`),
   * while preserving parent-set `isBlocked` and `dailyLimitMinutes` for matching Child packages.
   */
  @androidx.room.Transaction
  suspend fun replaceChildAppsStrictlyFromRemote(childId: Long, remoteApps: List<AppControlEntity>) {
    val existingByPkg = getAppsForChildOnce(childId).associateBy { it.packageName.lowercase().trim() }
    val strictChildApps = remoteApps
      .filterNot {
        it.packageName.contains("kidsguard", ignoreCase = true) ||
          it.appName.contains("kidsguard", ignoreCase = true) ||
          it.appName.contains("родительский контроль", ignoreCase = true)
      }
      .distinctBy { it.packageName.lowercase().trim() }
      .distinctBy { it.appName.lowercase().trim() }
      .map { remote ->
        val prev = existingByPkg[remote.packageName.lowercase().trim()]
        if (prev != null) {
          remote.copy(
            id = 0,
            childId = childId,
            isBlocked = remote.isBlocked || prev.isBlocked,
            dailyLimitMinutes = if (remote.dailyLimitMinutes > 0) remote.dailyLimitMinutes else prev.dailyLimitMinutes,
            usedMinutesToday = maxOf(remote.usedMinutesToday, prev.usedMinutesToday)
          )
        } else {
          remote.copy(id = 0, childId = childId)
        }
      }
    deleteAppsForChild(childId)
    if (strictChildApps.isNotEmpty()) {
      insertApps(strictChildApps)
    }
  }

  @Query("UPDATE app_controls SET isBlocked = :blocked WHERE id = :id")
  suspend fun setAppBlocked(id: Long, blocked: Boolean)

  @Query("UPDATE app_controls SET dailyLimitMinutes = :limitMinutes WHERE id = :id")
  suspend fun setAppLimit(id: Long, limitMinutes: Int)

  @Query("UPDATE app_controls SET isBlocked = :blocked WHERE childId = :childId AND isEducational = 0")
  suspend fun setNonEducationalAppsBlockedForChild(childId: Long, blocked: Boolean)

  // Geofences
  @Query("SELECT * FROM geofence_zones WHERE childId = :childId ORDER BY name")
  fun getGeofencesForChild(childId: Long): Flow<List<GeofenceZoneEntity>>

  @Query("SELECT * FROM geofence_zones ORDER BY name")
  fun getAllGeofences(): Flow<List<GeofenceZoneEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertGeofence(zone: GeofenceZoneEntity)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertGeofences(zones: List<GeofenceZoneEntity>)

  @Update
  suspend fun updateGeofence(zone: GeofenceZoneEntity)

  @Delete
  suspend fun deleteGeofence(zone: GeofenceZoneEntity)

  // Content Rules
  @Query("SELECT * FROM content_rules ORDER BY ruleType, displayName")
  fun getAllContentRules(): Flow<List<ContentRuleEntity>>

  @Query("SELECT * FROM content_rules ORDER BY ruleType, displayName")
  suspend fun getAllContentRulesOnce(): List<ContentRuleEntity>

  @Query("DELETE FROM content_rules")
  suspend fun clearAllContentRules()

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertContentRules(rules: List<ContentRuleEntity>)

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertContentRule(rule: ContentRuleEntity)

  @Update
  suspend fun updateContentRule(rule: ContentRuleEntity)

  @Query("UPDATE content_rules SET isBlocked = :blocked WHERE id = :id")
  suspend fun setContentRuleBlocked(id: Long, blocked: Boolean)

  @Query("UPDATE content_rules SET hitsCount = hitsCount + 1 WHERE id = :id")
  suspend fun incrementRuleHit(id: Long)

  @Delete
  suspend fun deleteContentRule(rule: ContentRuleEntity)

  // Legacy parent settings fallback
  @Query("SELECT * FROM parent_settings WHERE id = 1")
  fun getSettings(): Flow<ParentSettingsEntity?>

  @Query("SELECT * FROM parent_settings WHERE id = 1")
  suspend fun getSettingsOnce(): ParentSettingsEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOrUpdateSettings(settings: ParentSettingsEntity)

  @Query("UPDATE parent_settings SET isDeviceLockedRemotely = :locked, lockReason = :reason WHERE id = 1")
  suspend fun setRemoteLock(locked: Boolean, reason: String)

  @Query("UPDATE parent_settings SET dailyScreenLimitMinutes = :limitMinutes WHERE id = 1")
  suspend fun setDailyScreenLimit(limitMinutes: Int)

  @Query("UPDATE parent_settings SET isStudyModeActive = :studyMode WHERE id = 1")
  suspend fun setStudyMode(studyMode: Boolean)

  @Query("UPDATE parent_settings SET safeSearchEnabled = :safeSearch WHERE id = 1")
  suspend fun setSafeSearch(safeSearch: Boolean)

  @Query("UPDATE parent_settings SET youtubeRestricted = :restricted WHERE id = 1")
  suspend fun setYoutubeRestricted(restricted: Boolean)

  // Alerts
  @Query("SELECT * FROM alert_notifications ORDER BY timestamp DESC")
  fun getAllAlerts(): Flow<List<AlertNotificationEntity>>

  @Query("SELECT * FROM alert_notifications WHERE childId = :childId ORDER BY timestamp DESC")
  fun getAlertsForChild(childId: Long): Flow<List<AlertNotificationEntity>>

  @Query("SELECT * FROM alert_notifications WHERE id = :id LIMIT 1")
  suspend fun getAlertByIdOnce(id: Long): AlertNotificationEntity?

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertAlert(alert: AlertNotificationEntity)

  @Query("UPDATE alert_notifications SET isRead = 1 WHERE id = :id")
  suspend fun markAlertRead(id: Long)

  @Query("DELETE FROM alert_notifications WHERE id = :id")
  suspend fun deleteAlertById(id: Long)

  @Query("DELETE FROM alert_notifications")
  suspend fun clearAllAlerts()

  // Chat Messages between Parent & Child
  @Query("SELECT * FROM chat_messages WHERE childId = :childId ORDER BY timestamp ASC")
  fun getChatMessagesForChild(childId: Long): Flow<List<ChatMessageEntity>>

  @Query("SELECT * FROM chat_messages ORDER BY timestamp ASC")
  fun getAllChatMessages(): Flow<List<ChatMessageEntity>>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertChatMessage(message: ChatMessageEntity): Long

  @Query("UPDATE chat_messages SET isRead = 1 WHERE childId = :childId AND senderRole != :role")
  suspend fun markChatMessagesRead(childId: Long, role: String)

  @Query("DELETE FROM chat_messages WHERE childId = :childId")
  suspend fun deleteChatMessagesForChild(childId: Long)

  // Offline Activity Logs (Phone & PC Store-and-Forward + Date-Range Aggregation)
  @Query("SELECT * FROM offline_activity_logs WHERE childId = :childId ORDER BY timestamp DESC LIMIT 250")
  fun getOfflineActivityLogsForChild(childId: Long): Flow<List<OfflineActivityLogEntity>>

  @Query("SELECT * FROM offline_activity_logs ORDER BY timestamp DESC LIMIT 300")
  fun getAllOfflineActivityLogs(): Flow<List<OfflineActivityLogEntity>>

  /**
   * Фильтрация сырых событий активности строго по выбранному диапазону времени [startTimestamp, endTimestamp].
   */
  @Query("""
    SELECT * FROM offline_activity_logs
    WHERE (:childId <= 0 OR childId = :childId)
      AND (:deviceType = '' OR deviceType = :deviceType)
      AND timestamp BETWEEN :startTimestamp AND :endTimestamp
    ORDER BY timestamp DESC
  """)
  fun getActivityLogsByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<List<OfflineActivityLogEntity>>

  /**
   * Агрегация времени работы программ (.exe / приложений) строго за выбранный интервал [startTimestamp, endTimestamp].
   * Исключает накопление времени за все дни истории БД.
   */
  @Query("""
    SELECT
      packageName AS packageName,
      MAX(appName) AS appName,
      '' AS windowTitle,
      deviceType AS deviceType,
      COALESCE(SUM(durationSeconds), 0) AS totalDurationSeconds,
      MAX(timestamp) AS lastTimestamp
    FROM offline_activity_logs
    WHERE (:childId <= 0 OR childId = :childId)
      AND deviceType = :deviceType
      AND windowTitle = ''
      AND packageName != '__computer__'
      AND timestamp BETWEEN :startTimestamp AND :endTimestamp
    GROUP BY LOWER(TRIM(packageName))
    HAVING totalDurationSeconds > 0
    ORDER BY totalDurationSeconds DESC
  """)
  fun getAggregatedProcsByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<List<AggregatedActivityByDateItem>>

  /**
   * Агрегация времени просмотра заголовков окон / вкладок браузера строго за выбранный интервал [startTimestamp, endTimestamp].
   */
  @Query("""
    SELECT
      MAX(packageName) AS packageName,
      MAX(appName) AS appName,
      windowTitle AS windowTitle,
      deviceType AS deviceType,
      COALESCE(SUM(durationSeconds), 0) AS totalDurationSeconds,
      MAX(timestamp) AS lastTimestamp
    FROM offline_activity_logs
    WHERE (:childId <= 0 OR childId = :childId)
      AND deviceType = :deviceType
      AND windowTitle != ''
      AND timestamp BETWEEN :startTimestamp AND :endTimestamp
    GROUP BY LOWER(TRIM(windowTitle))
    HAVING totalDurationSeconds > 0
    ORDER BY totalDurationSeconds DESC
  """)
  fun getAggregatedWindowTitlesByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<List<AggregatedActivityByDateItem>>

  /**
   * Суммарное активное время (в секундах) строго за выбранный интервал [startTimestamp, endTimestamp].
   */
  @Query("""
    SELECT COALESCE(SUM(durationSeconds), 0)
    FROM offline_activity_logs
    WHERE (:childId <= 0 OR childId = :childId)
      AND deviceType = :deviceType
      AND windowTitle = ''
      AND packageName != '__computer__'
      AND timestamp BETWEEN :startTimestamp AND :endTimestamp
  """)
  fun getTotalActiveSecondsByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<Long>

  @Query("""
    DELETE FROM offline_activity_logs
    WHERE deviceType = 'PC'
      AND dateIso = :dateIso
      AND (:childId <= 0 OR childId = :childId)
  """)
  suspend fun deletePcActivitySnapshotForDate(childId: Long, dateIso: String)

  @Transaction
  suspend fun replacePcActivitySnapshotForDate(
    childId: Long,
    dateIso: String,
    logs: List<OfflineActivityLogEntity>
  ) {
    deletePcActivitySnapshotForDate(childId, dateIso)
    if (logs.isNotEmpty()) {
      insertOfflineActivityLogs(logs)
    }
  }

  @Query("SELECT * FROM offline_activity_logs WHERE childId = :childId AND isSyncedToParent = 0 ORDER BY timestamp ASC LIMIT 120")
  suspend fun getUnsyncedActivityLogs(childId: Long): List<OfflineActivityLogEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOfflineActivityLog(log: OfflineActivityLogEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOfflineActivityLogs(logs: List<OfflineActivityLogEntity>)

  @Query("UPDATE offline_activity_logs SET isSyncedToParent = 1 WHERE id IN (:ids)")
  suspend fun markActivityLogsSynced(ids: List<Long>)

  @Query("DELETE FROM offline_activity_logs WHERE childId = :childId")
  suspend fun deleteOfflineActivityLogsForChild(childId: Long)

  // Offline Location Points (Store-and-Forward GPS)
  @Query("SELECT * FROM offline_location_points WHERE childId = :childId ORDER BY timestamp DESC LIMIT 250")
  fun getOfflineLocationPointsForChild(childId: Long): Flow<List<OfflineLocationPointEntity>>

  @Query("SELECT * FROM offline_location_points WHERE childId = :childId AND isSyncedToParent = 0 ORDER BY timestamp ASC LIMIT 120")
  suspend fun getUnsyncedLocationPoints(childId: Long): List<OfflineLocationPointEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOfflineLocationPoint(point: OfflineLocationPointEntity): Long

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOfflineLocationPoints(points: List<OfflineLocationPointEntity>)

  @Query("UPDATE offline_location_points SET isSyncedToParent = 1 WHERE id IN (:ids)")
  suspend fun markLocationPointsSynced(ids: List<Long>)

  @Query("DELETE FROM offline_location_points WHERE childId = :childId")
  suspend fun deleteOfflineLocationPointsForChild(childId: Long)

  // Offline Screenshots (Store-and-Forward Screen Captures)
  @Query("SELECT * FROM offline_screenshots WHERE childId = :childId ORDER BY timestamp DESC LIMIT 40")
  fun getOfflineScreenshotsForChild(childId: Long): Flow<List<OfflineScreenshotEntity>>

  @Query("SELECT * FROM offline_screenshots WHERE childId = :childId AND isSyncedToParent = 0 ORDER BY timestamp DESC LIMIT 10")
  suspend fun getUnsyncedScreenshots(childId: Long): List<OfflineScreenshotEntity>

  @Insert(onConflict = OnConflictStrategy.REPLACE)
  suspend fun insertOfflineScreenshot(shot: OfflineScreenshotEntity): Long

  @Query("UPDATE offline_screenshots SET isSyncedToParent = 1 WHERE id IN (:ids)")
  suspend fun markScreenshotsSynced(ids: List<Long>)

  @Query("DELETE FROM offline_screenshots WHERE id NOT IN (SELECT id FROM offline_screenshots WHERE childId = :childId ORDER BY timestamp DESC LIMIT 40) AND childId = :childId")
  suspend fun trimOfflineScreenshots(childId: Long)

  @Query("DELETE FROM offline_screenshots WHERE childId = :childId")
  suspend fun deleteOfflineScreenshotsForChild(childId: Long)
}

