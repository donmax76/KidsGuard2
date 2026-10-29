package com.example.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Database(
  entities = [
    ChildProfileEntity::class,
    FamilyConfigEntity::class,
    AppControlEntity::class,
    GeofenceZoneEntity::class,
    ContentRuleEntity::class,
    ParentSettingsEntity::class,
    AlertNotificationEntity::class,
    ChatMessageEntity::class,
    OfflineActivityLogEntity::class,
    OfflineLocationPointEntity::class,
    OfflineScreenshotEntity::class
  ],
  version = 9,
  exportSchema = false
)
abstract class KidsGuardDatabase : RoomDatabase() {
  abstract fun kidsGuardDao(): KidsGuardDao

  companion object {
    @Volatile
    private var INSTANCE: KidsGuardDatabase? = null

    private val MIGRATION_6_7 = object : androidx.room.migration.Migration(6, 7) {
      override fun migrate(db: SupportSQLiteDatabase) {
        try {
          db.execSQL("ALTER TABLE child_profiles ADD COLUMN gpsTrackingEnabled INTEGER NOT NULL DEFAULT 1")
        } catch (_: Exception) {}
      }
    }

    private val MIGRATION_8_9 = object : androidx.room.migration.Migration(8, 9) {
      override fun migrate(db: SupportSQLiteDatabase) {
        try {
          db.execSQL(
            "CREATE TABLE IF NOT EXISTS `offline_activity_logs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `childId` INTEGER NOT NULL, `deviceType` TEXT NOT NULL, `packageName` TEXT NOT NULL, `appName` TEXT NOT NULL, `windowTitle` TEXT NOT NULL, `durationSeconds` INTEGER NOT NULL, `hourOfDay` INTEGER NOT NULL, `dateIso` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isSyncedToParent` INTEGER NOT NULL)"
          )
          db.execSQL(
            "CREATE TABLE IF NOT EXISTS `offline_location_points` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `childId` INTEGER NOT NULL, `latitude` REAL NOT NULL, `longitude` REAL NOT NULL, `address` TEXT NOT NULL, `speedKmh` REAL NOT NULL, `accuracyMeters` REAL NOT NULL, `timestamp` INTEGER NOT NULL, `isSyncedToParent` INTEGER NOT NULL)"
          )
          db.execSQL(
            "CREATE TABLE IF NOT EXISTS `offline_screenshots` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `childId` INTEGER NOT NULL, `deviceType` TEXT NOT NULL, `activeAppName` TEXT NOT NULL, `statusNote` TEXT NOT NULL, `base64Bitmap` TEXT NOT NULL, `timestamp` INTEGER NOT NULL, `isSyncedToParent` INTEGER NOT NULL)"
          )
        } catch (_: Exception) {}
      }
    }

    fun getDatabase(context: Context): KidsGuardDatabase {
      return INSTANCE ?: synchronized(this) {
        val instance = Room.databaseBuilder(
          context.applicationContext,
          KidsGuardDatabase::class.java,
          "kidsguard_database"
        )
          .addMigrations(MIGRATION_6_7, MIGRATION_8_9)
          .fallbackToDestructiveMigration()
          .addCallback(DatabaseCallback())
          .build()
        INSTANCE = instance
        instance
      }
    }

    private class DatabaseCallback : Callback() {
      override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)
        INSTANCE?.let { database ->
          CoroutineScope(Dispatchers.IO).launch {
            populateInitialData(database.kidsGuardDao())
          }
        }
      }

      suspend fun populateInitialData(dao: KidsGuardDao) {
        // Family Configuration (No mock children! Clean slate for the parent)
        dao.insertOrUpdateFamilyConfig(
          FamilyConfigEntity(
            id = 1,
            familyId = "FAM-73921",
            familyName = "Моя семья",
            parentMasterPin = "1234",
            selectedRole = "UNCONFIGURED",
            currentActiveChildId = 0,
            thisDeviceChildId = 0,
            antiUninstallEnabled = true
          )
        )

        // Web Filter system categories (active for all children)
        val initialRules = listOf(
          ContentRuleEntity(ruleType = "CATEGORY", value = "ADULT", displayName = "Контент 18+ и материалы для взрослых", isBlocked = true, hitsCount = 0),
          ContentRuleEntity(ruleType = "CATEGORY", value = "GAMBLING", displayName = "Азартные игры и казино", isBlocked = true, hitsCount = 0),
          ContentRuleEntity(ruleType = "CATEGORY", value = "VIOLENCE", displayName = "Насилие и оружие", isBlocked = true, hitsCount = 0),
          ContentRuleEntity(ruleType = "CATEGORY", value = "MALWARE", displayName = "Фишинговые и опасные сайты", isBlocked = true, hitsCount = 0),
          ContentRuleEntity(ruleType = "DOMAIN", value = "casino-vulkan.example", displayName = "casino-vulkan.example", isBlocked = true, hitsCount = 0),
          ContentRuleEntity(ruleType = "KEYWORD", value = "вейп", displayName = "Ключевое слово: вейп", isBlocked = true, hitsCount = 0)
        )
        dao.insertContentRules(initialRules)
      }
    }
  }
}
