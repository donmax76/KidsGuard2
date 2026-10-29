package com.example.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "child_profiles")
data class ChildProfileEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val name: String,
  val age: Int,
  val deviceModel: String = "Не подключено",
  val pairingCode: String, // 6-digit code e.g. "582-194"
  val isPaired: Boolean = false,
  val batteryLevel: Int = -1,
  val isDeviceLocked: Boolean = false,
  val dailyScreenLimitMinutes: Int = 150,
  val usedScreenTimeMinutes: Int = 0,
  val lastLocationLat: Double = 0.0,
  val lastLocationLng: Double = 0.0,
  val lastLocationAddress: String = "Ожидание точного GPS-сигнала с телефона ребёнка...",
  val lastLocationTime: Long = 0L,
  val colorHex: Long = 0xFF0EA5E9,
  val bedtimeStartHour: Int = 21,
  val bedtimeStartMinute: Int = 30,
  val bedtimeEndHour: Int = 7,
  val bedtimeEndMinute: Int = 0,
  val bedtimeEnabled: Boolean = true,
  val studyModeActive: Boolean = false,
  val appVersion: String = "3.2.1 Pro",
  val updatePending: Boolean = false,
  val uninstallProtected: Boolean = true,
  val allowMobileData: Boolean = true,
  val gpsTrackingEnabled: Boolean = true
)

@Entity(tableName = "family_config")
data class FamilyConfigEntity(
  @PrimaryKey
  val id: Int = 1,
  val familyId: String = "FAM-89124",
  val familyName: String = "Моя семья",
  val parentMasterPin: String = "1234",
  val childUninstallPin: String = "1234",
  val selectedRole: String = "UNCONFIGURED", // "UNCONFIGURED", "PARENT", "CHILD"
  val currentActiveChildId: Long = 0,
  val thisDeviceChildId: Long = 0,
  val antiUninstallEnabled: Boolean = true,
  val accessibilityGranted: Boolean = false,
  val usageStatsGranted: Boolean = false
)

@Entity(tableName = "app_controls")
data class AppControlEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long = 1, // Associated with specific child
  val packageName: String,
  val appName: String,
  val iconType: String,
  val category: String,
  val dailyLimitMinutes: Int,
  val usedMinutesToday: Int = 0,
  val isBlocked: Boolean = false,
  val isEducational: Boolean = false
)

@Entity(tableName = "geofence_zones")
data class GeofenceZoneEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long = 1,
  val name: String,
  val latitude: Double,
  val longitude: Double,
  val radiusMeters: Float = 250f,
  val isSafeZone: Boolean = true,
  val notifyOnEntry: Boolean = true,
  val notifyOnExit: Boolean = true,
  val iconType: String = "home"
)

@Entity(tableName = "content_rules")
data class ContentRuleEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val ruleType: String, // "CATEGORY", "DOMAIN", "KEYWORD"
  val value: String,
  val displayName: String,
  val isBlocked: Boolean = true,
  val hitsCount: Int = 0
)

@Entity(tableName = "parent_settings")
data class ParentSettingsEntity(
  @PrimaryKey
  val id: Int = 1,
  val childName: String = "Ребенок",
  val childAge: Int = 10,
  val batteryLevel: Int = -1,
  val isDeviceLockedRemotely: Boolean = false,
  val lockReason: String = "Заблокировано родителем",
  val dailyScreenLimitMinutes: Int = 150,
  val usedScreenTimeMinutes: Int = 0,
  val bedtimeStartHour: Int = 21,
  val bedtimeStartMinute: Int = 30,
  val bedtimeEndHour: Int = 7,
  val bedtimeEndMinute: Int = 0,
  val bedtimeEnabled: Boolean = true,
  val safeSearchEnabled: Boolean = true,
  val youtubeRestricted: Boolean = true,
  val pinCode: String = "1234",
  val isStudyModeActive: Boolean = false,
  val lastLocationLat: Double = 0.0,
  val lastLocationLng: Double = 0.0,
  val lastLocationAddress: String = "Ожидание координат...",
  val lastLocationTime: Long = System.currentTimeMillis()
)

@Entity(tableName = "alert_notifications")
data class AlertNotificationEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long = 1,
  val type: String, // "SOS", "GEOFENCE_ENTER", "GEOFENCE_EXIT", "LIMIT_REACHED", "WEB_BLOCKED", "BONUS_REQUEST", "DEVICE_PAIRED"
  val title: String,
  val message: String,
  val timestamp: Long = System.currentTimeMillis(),
  val isRead: Boolean = false,
  val extraPayload: String = ""
)

data class LocationBreadcrumb(
  val latitude: Double,
  val longitude: Double,
  val timestamp: Long,
  val title: String,
  val speedKmh: Float = 0f
)

@Entity(tableName = "chat_messages")
data class ChatMessageEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long,
  val senderRole: String, // "PARENT" or "CHILD"
  val senderName: String,
  val text: String,
  val timestamp: Long = System.currentTimeMillis(),
  val isRead: Boolean = false,
  val isAttentionSignal: Boolean = false
)

@Entity(tableName = "offline_activity_logs")
data class OfflineActivityLogEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long = 1L,
  val deviceType: String = "PHONE", // "PHONE" or "PC"
  val packageName: String,
  val appName: String,
  val windowTitle: String = "",
  val durationSeconds: Int = 60,
  val hourOfDay: Int = 12, // 0..23 for hourly Recharts chart
  val dateIso: String = "", // YYYY-MM-DD
  val timestamp: Long = System.currentTimeMillis(),
  val isSyncedToParent: Boolean = false
)

@Entity(tableName = "offline_location_points")
data class OfflineLocationPointEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long = 1L,
  val latitude: Double,
  val longitude: Double,
  val address: String,
  val speedKmh: Float = 0f,
  val accuracyMeters: Float = 10f,
  val timestamp: Long = System.currentTimeMillis(),
  val isSyncedToParent: Boolean = false
)

@Entity(tableName = "offline_screenshots")
data class OfflineScreenshotEntity(
  @PrimaryKey(autoGenerate = true)
  val id: Long = 0,
  val childId: Long = 1L,
  val deviceType: String = "PHONE", // "PHONE" or "PC"
  val activeAppName: String,
  val statusNote: String,
  val base64Bitmap: String,
  val timestamp: Long = System.currentTimeMillis(),
  val isSyncedToParent: Boolean = false
)

/**
 * Проекция SQLite Room для агрегированного времени активности (программ и заголовков окон)
 * строго за выбранный интервал дат [startTimestamp, endTimestamp].
 */
data class AggregatedActivityByDateItem(
  val packageName: String,
  val appName: String,
  val windowTitle: String,
  val deviceType: String,
  val totalDurationSeconds: Long,
  val lastTimestamp: Long
)


