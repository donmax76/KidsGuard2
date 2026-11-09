package com.example.data.repository

import android.content.Context
import com.example.data.local.KidsGuardDao
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.AggregatedActivityByDateItem
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import com.example.data.model.ContentRuleEntity
import com.example.data.model.FamilyConfigEntity
import com.example.data.model.GeofenceZoneEntity
import com.example.data.model.OfflineActivityLogEntity
import com.example.data.model.ParentSettingsEntity
import com.example.data.cloud.CloudSyncManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.random.Random

data class ContentFilterEvaluation(
  val isAllowed: Boolean,
  val matchedRuleName: String? = null,
  val reason: String? = null
)

class ParentalControlRepository(private val context: Context) {
  private val database = KidsGuardDatabase.getDatabase(context)
  private val dao: KidsGuardDao = database.kidsGuardDao()
  val cloudSync: CloudSyncManager = CloudSyncManager(context)

  init {
    CoroutineScope(Dispatchers.IO).launch {
      val existingConfig = dao.getFamilyConfigOnce()
      if (existingConfig == null) {
        database.openHelper.writableDatabase // trigger onCreate
        // Fallback default population if needed
        val checkAgain = dao.getFamilyConfigOnce()
        if (checkAgain == null) {
          dao.insertOrUpdateFamilyConfig(
            FamilyConfigEntity(
              id = 1,
              familyId = "FAM-${Random.nextInt(10000, 99999)}",
              familyName = "Моя семья",
              parentMasterPin = "1234",
              selectedRole = "PARENT",
              currentActiveChildId = 0,
              thisDeviceChildId = 0
            )
          )
        }
      }

      // Ensure ParentSettingsEntity row exists so SafeSearch and YouTube Restricted switches work
      val existingSettings = dao.getSettingsOnce()
      if (existingSettings == null) {
        dao.insertOrUpdateSettings(
          ParentSettingsEntity(
            id = 1,
            safeSearchEnabled = true,
            youtubeRestricted = true
          )
        )
      }

      // Ensure all 8 Web Filter Categories exist in local DB
      val existingRules = dao.getAllContentRulesOnce()
      val existingCategories = existingRules.filter { it.ruleType == "CATEGORY" }.map { it.value }.toSet()
      val defaultCategories = listOf(
        ContentRuleEntity(ruleType = "CATEGORY", value = "ADULT", displayName = "Контент 18+ и сайты для взрослых", isBlocked = true),
        ContentRuleEntity(ruleType = "CATEGORY", value = "GAMBLING", displayName = "Азартные игры, ставки и онлайн-казино", isBlocked = true),
        ContentRuleEntity(ruleType = "CATEGORY", value = "VIOLENCE", displayName = "Насилие, оружие и шок-контент", isBlocked = true),
        ContentRuleEntity(ruleType = "CATEGORY", value = "MALWARE", displayName = "Фишинг, взлом и вредоносные сайты", isBlocked = true),
        ContentRuleEntity(ruleType = "CATEGORY", value = "DRUGS", displayName = "Наркотики, алкоголь, табак и вейпы", isBlocked = true),
        ContentRuleEntity(ruleType = "CATEGORY", value = "GAMING_WEB", displayName = "Браузерные игры и игровые стримы", isBlocked = false),
        ContentRuleEntity(ruleType = "CATEGORY", value = "SOCIAL_DATING", displayName = "Анонимные чаты и сайты знакомств", isBlocked = true),
        ContentRuleEntity(ruleType = "CATEGORY", value = "SHOPPING_DONATE", displayName = "Онлайн-покупки и игровые донаты", isBlocked = false)
      )
      val missingCategories = defaultCategories.filter { it.value !in existingCategories }
      if (missingCategories.isNotEmpty()) {
        dao.insertContentRules(missingCategories)
      }

      // Purge legacy "test" profile and upgrade stale 2.x/3.0/3.1 versions & fake 100%/93% battery in local Room DB
      com.example.util.CoreEngineHotUpdater.initializeHotCore(context)
      val effectiveInstalledVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(context)
      val cfgNow = dao.getFamilyConfigOnce()
      val currentRole = cfgNow?.selectedRole ?: "PARENT"
      com.example.data.cloud.DirectP2pSocketEngine.appContext = context.applicationContext
      com.example.data.cloud.DirectP2pSocketEngine.startEngine(currentRole)
      com.example.util.DeviceUsageTracker.ensureDailyMidnightReset(context)

      deduplicateLocalChildrenInDb()
      val localChildren: List<ChildProfileEntity> = dao.getAllChildrenOnce()
      for (child in localChildren) {
        if (child.name.equals("test", ignoreCase = true)) {
          dao.deleteChildById(child.id)
          dao.deleteAppsForChild(child.id)
          continue
        }
        val isStaleLoc = com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng)
        val isChildDevice = currentRole == "CHILD" && !com.example.location.LiveLocationTracker.isEmulator()
        val isBoundToThisExactChild = isChildDevice && cfgNow != null && cfgNow.thisDeviceChildId > 0L && cfgNow.thisDeviceChildId == child.id
        val savedChildCoreVer = com.example.util.CoreEngineHotUpdater.getChildCoreVersionName(context, child.id)
        if (!savedChildCoreVer.isNullOrBlank()) {
          com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeChildCoreVersion(child.id, savedChildCoreVer)
        }
        val updatedVersion = com.example.data.cloud.RealtimeCloudRelay.resolveHighestChildVersion(
          child.id,
          if (isBoundToThisExactChild) effectiveInstalledVer else null,
          savedChildCoreVer,
          child.appVersion
        )
        val cleanedBattery = when {
          isBoundToThisExactChild ->
            com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
          isStaleLoc && (child.batteryLevel == 100 || child.batteryLevel == 93) -> -1
          child.batteryLevel in 1..100 && child.batteryLevel != 93 -> child.batteryLevel
          else -> -1
        }
        // Never wake up locked on startup unless a fresh authoritative lock is active
        val startupLocked = if (isChildDevice) {
          com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(child.id)?.first == true
        } else {
          child.isDeviceLocked
        }
        val fixed = child.copy(
          appVersion = updatedVersion,
          batteryLevel = cleanedBattery,
          isDeviceLocked = startupLocked,
          lastLocationLat = if (isStaleLoc) 0.0 else child.lastLocationLat,
          lastLocationLng = if (isStaleLoc) 0.0 else child.lastLocationLng,
          lastLocationAddress = if (isStaleLoc) "Ожидание точного GPS-сигнала с телефона ребёнка..." else child.lastLocationAddress
        )
        dao.updateChild(fixed)
        if (isBoundToThisExactChild) {
          com.example.data.cloud.RealtimeCloudRelay.publishChildProfile(fixed, true)
        } else if (currentRole != "CHILD") {
          com.example.data.cloud.RealtimeCloudRelay.publishChildProfile(fixed, false)
        }
      }

      // If running in CHILD role on physical phone, start background protection and strictly sync ONLY Child's installed apps
      if (!com.example.location.LiveLocationTracker.isEmulator() && cfgNow?.selectedRole == "CHILD") {
        val boundId = cfgNow.thisDeviceChildId.takeIf { it > 0 }
          ?: cfgNow.currentActiveChildId.takeIf { it > 0 }
          ?: 1L
        com.example.service.KidsGuardBackgroundService.start(context, boundId)
        val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(context, boundId)
        if (realApps.isNotEmpty()) {
          val prevApps = dao.getAppsForChildOnce(boundId).associateBy { it.packageName.trim().lowercase() }
          val merged = realApps.map { app ->
            val prev = prevApps[app.packageName.trim().lowercase()]
            if (prev != null) {
              app.copy(
                isBlocked = prev.isBlocked,
                dailyLimitMinutes = prev.dailyLimitMinutes
              )
            } else app
          }
          dao.replaceChildAppsStrictlyFromRemote(boundId, merged)
          cloudSync.uploadChildApps(boundId, merged)
        }
      } else {
        com.example.service.LockOverlayService.stop(context)
        com.example.service.KidsGuardBackgroundService.stop(context)
        // One-time strict purge of any stale/mixed Parent apps in Parent's SQLite DB
        // (including lists that older versions scanned on the parent phone itself).
        val segPrefs = context.getSharedPreferences("kidsguard_app_segregation_prefs", Context.MODE_PRIVATE)
        if (!segPrefs.getBoolean("strict_parent_apps_purge_v3360", false)) {
          dao.clearAllApps()
          segPrefs.edit().putBoolean("strict_parent_apps_purge_v3360", true).apply()
        }
        for (c in dao.getAllChildrenOnce()) {
          cloudSync.sendRemoteCommand(c.id, "REFRESH_APPS", "")
        }
      }

      // Observe Web Filter / Domain Blacklist rules from Cloud so Child device always enforces Parent's latest blacklist
      launch {
        cloudSync.observeContentRulesFromCloud().collect { cloudRules ->
          val role = dao.getFamilyConfigOnce()?.selectedRole ?: "PARENT"
          if (role == "CHILD" && cloudRules.isNotEmpty()) {
            dao.clearAllContentRules()
            dao.insertContentRules(cloudRules.map { it.copy(id = 0) })
          }
        }
      }

      // Listen for remote child profiles from Cloud
      launch {
        cloudSync.observeAllChildren().collect { cloudChildren ->
          val currentCfg = dao.getFamilyConfigOnce()
          val isThisChildDevice = currentCfg?.selectedRole == "CHILD"
          val thisDeviceChildId = currentCfg?.thisDeviceChildId ?: 0L
          val currentInstalledVer = com.example.util.AppVersionInfo.installedApkVersion(context)

          val allLocalSnap = dao.getAllChildrenOnce()
          for (cloudChild in cloudChildren) {
            val cloudCodeDigits = cloudChild.pairingCode.replace("-", "").replace(" ", "").uppercase()
            val localByCode = if (cloudCodeDigits.length >= 6) {
              allLocalSnap.firstOrNull {
                it.pairingCode.replace("-", "").replace(" ", "").uppercase() == cloudCodeDigits
              }
            } else null
            val localByName = if (localByCode == null && com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(cloudChild.name)) {
              allLocalSnap.firstOrNull {
                com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(it.name) &&
                  it.name.trim().equals(cloudChild.name.trim(), ignoreCase = true)
              }
            } else null
            val localExisting = localByCode ?: localByName ?: dao.getChildByIdOnce(cloudChild.id)
            val resolvedId = localExisting?.id ?: cloudChild.id
            val isThisExactChild = isThisChildDevice && thisDeviceChildId > 0L && (resolvedId == thisDeviceChildId || localByCode != null || localByName != null)
            val cloudHasValidCoords = !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(cloudChild.lastLocationLat, cloudChild.lastLocationLng) &&
              cloudChild.lastLocationLat != 0.0 && cloudChild.lastLocationLng != 0.0
            val localHasRealLoc = localExisting != null &&
              !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(localExisting.lastLocationLat, localExisting.lastLocationLng) &&
              localExisting.lastLocationLat != 0.0 && localExisting.lastLocationLng != 0.0

            val bestLat = when {
              cloudHasValidCoords -> cloudChild.lastLocationLat
              localHasRealLoc -> localExisting!!.lastLocationLat
              else -> 0.0
            }
            val bestLng = when {
              cloudHasValidCoords -> cloudChild.lastLocationLng
              localHasRealLoc -> localExisting!!.lastLocationLng
              else -> 0.0
            }
            val bestAddr = when {
              cloudHasValidCoords -> {
                if (cloudChild.lastLocationAddress.isBlank() || com.example.location.LiveLocationTracker.isStaleOrDemoAddress(cloudChild.lastLocationAddress)) {
                  "Точные координаты GPS: %.5f, %.5f".format(bestLat, bestLng)
                } else cloudChild.lastLocationAddress
              }
              localHasRealLoc -> localExisting!!.lastLocationAddress
              else -> "Ожидание точного GPS-сигнала с телефона ребёнка..."
            }

            // On the Child phone, only lock when an authoritative lock is active so stale P2P state never locks the phone unexpectedly!
            val authLock = com.example.data.cloud.RealtimeCloudRelay.getAuthoritativeLock(resolvedId)
            val resolvedLocked = when {
              authLock != null -> authLock.first
              isThisChildDevice -> false
              else -> cloudChild.isDeviceLocked
            }

            // Preserve custom child name created on Parent device if cloud had default placeholder
            val resolvedName = when {
              localExisting != null && localExisting.name.isNotBlank() && !localExisting.name.startsWith("Ребёнок #") &&
                (cloudChild.name == "Sevil" && resolvedId != 1L || cloudChild.name.startsWith("Ребёнок #")) -> localExisting.name
              else -> cloudChild.name
            }

            // Preserve authoritative age created on Parent device (e.g. 14 years) so fallback 10 never overwrites it!
            val resolvedAge = when {
              !isThisChildDevice && localExisting != null && localExisting.age != 10 && cloudChild.age == 10 -> localExisting.age
              isThisChildDevice && cloudChild.age != 10 && cloudChild.age > 0 -> cloudChild.age
              localExisting != null && !localExisting.name.startsWith("Ребёнок #") && cloudChild.name.startsWith("Ребёнок #") -> localExisting.age
              else -> cloudChild.age
            }

            val resolvedPairingCode = when {
              localExisting != null && localExisting.pairingCode.isNotBlank() && localByCode != null -> localExisting.pairingCode
              cloudChild.pairingCode.isNotBlank() && cloudChild.isPaired -> cloudChild.pairingCode
              localExisting != null && localExisting.pairingCode.isNotBlank() -> localExisting.pairingCode
              else -> cloudChild.pairingCode
            }

            val savedChildCoreVer = com.example.util.CoreEngineHotUpdater.getChildCoreVersionName(context, resolvedId)
            val resolvedChildVer = com.example.data.cloud.RealtimeCloudRelay.resolveHighestChildVersion(
              resolvedId,
              if (isThisExactChild) currentInstalledVer else null,
              savedChildCoreVer,
              cloudChild.appVersion,
              localExisting?.appVersion
            )
            if (resolvedChildVer.isNotBlank()) {
              com.example.util.CoreEngineHotUpdater.setChildCoreVersionName(context, resolvedId, resolvedChildVer)
            }

            val resolvedBattery = when {
              isThisExactChild && !com.example.location.LiveLocationTracker.isEmulator() ->
                com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
              cloudChild.batteryLevel in 1..100 -> cloudChild.batteryLevel
              localExisting != null && localExisting.batteryLevel in 1..100 -> localExisting.batteryLevel
              else -> -1
            }

            val isUpToDateNow = com.example.util.ApkUpdateInstaller.compareVersions(resolvedChildVer, currentInstalledVer) >= 0

            val sanitizedChild = when {
              isThisExactChild && cloudChild.appVersion != currentInstalledVer -> {
                cloudSync.syncChildAppVersion(resolvedId, currentInstalledVer)
                cloudChild.copy(
                  id = resolvedId,
                  name = resolvedName,
                  age = resolvedAge,
                  pairingCode = resolvedPairingCode,
                  batteryLevel = resolvedBattery,
                  isDeviceLocked = resolvedLocked,
                  appVersion = currentInstalledVer,
                  updatePending = false,
                  lastLocationLat = bestLat,
                  lastLocationLng = bestLng,
                  lastLocationAddress = bestAddr
                )
              }
              else -> cloudChild.copy(
                id = resolvedId,
                name = resolvedName,
                age = resolvedAge,
                pairingCode = resolvedPairingCode,
                batteryLevel = resolvedBattery,
                isDeviceLocked = resolvedLocked,
                appVersion = resolvedChildVer,
                updatePending = if (isUpToDateNow) false else cloudChild.updatePending,
                lastLocationLat = bestLat,
                lastLocationLng = bestLng,
                lastLocationAddress = bestAddr
              )
            }

            if (localExisting == null) {
              // CRITICAL: In PARENT mode, NEVER auto-insert unknown or retained children from P2P broker!
              // In CHILD mode, only insert if this Child phone has no bound child yet or matches thisDeviceChildId.
              if (isThisChildDevice && (dao.getAllChildrenOnce().isEmpty() || sanitizedChild.id == thisDeviceChildId)) {
                dao.insertChild(sanitizedChild)
              }
            } else {
              dao.updateChild(sanitizedChild)
            }
          }

          deduplicateLocalChildrenInDb()

          // Reassign family_config only if currentActiveChildId points to a non-existent child; never overwrite a bound thisDeviceChildId!
          val allLocalNow = dao.getAllChildrenOnce()
          if (allLocalNow.isEmpty() && !isThisChildDevice) {
            if (currentCfg != null && currentCfg.currentActiveChildId != 0L) {
              dao.insertOrUpdateFamilyConfig(currentCfg.copy(currentActiveChildId = 0L, thisDeviceChildId = 0L))
            }
          } else if (currentCfg != null && allLocalNow.isNotEmpty()) {
            val localIds = allLocalNow.map { it.id }.toSet()
            val firstValidId = allLocalNow.first().id
            val newActiveId = when {
              currentCfg.selectedRole == "CHILD" && currentCfg.thisDeviceChildId > 0L -> currentCfg.thisDeviceChildId
              currentCfg.currentActiveChildId in localIds -> currentCfg.currentActiveChildId
              else -> firstValidId
            }
            val newDeviceId = when {
              currentCfg.selectedRole != "CHILD" -> 0L
              currentCfg.thisDeviceChildId > 0L -> currentCfg.thisDeviceChildId
              else -> newActiveId
            }
            if (newActiveId != currentCfg.currentActiveChildId || newDeviceId != currentCfg.thisDeviceChildId) {
              dao.insertOrUpdateFamilyConfig(
                currentCfg.copy(
                  currentActiveChildId = newActiveId,
                  thisDeviceChildId = newDeviceId
                )
              )
            }
          }
        }
      }

      // Listen for cloud app controls (Parent receives apps uploaded from child's phone)
      launch {
        try {
          dao.removeSelfAppsFromDb()
          dao.deduplicateAppsInDb()
        } catch (_: Exception) {}
        var childAppsJobs = listOf<kotlinx.coroutines.Job>()
        dao.getAllChildren().collect { children ->
          childAppsJobs.forEach { it.cancel() }
          childAppsJobs = children.map { child ->
            launch {
              cloudSync.observeAppsForChild(child.id).collect { rawCloudApps ->
                val cloudApps = rawCloudApps
                  .filterNot {
                    it.packageName.contains("kidsguard", ignoreCase = true) ||
                      it.appName.contains("kidsguard", ignoreCase = true) ||
                      it.appName.contains("родительский контроль", ignoreCase = true)
                  }
                  .distinctBy { it.packageName.lowercase().trim() }
                  .distinctBy { it.appName.lowercase().trim() }
                if (cloudApps.isNotEmpty()) {
                  val cfg = dao.getFamilyConfigOnce()
                  val isThisExactChildDevice = cfg?.selectedRole == "CHILD" && cfg.thisDeviceChildId == child.id
                  if (!isThisExactChildDevice) {
                    // On Parent's device, strictly replace the table for this child with ONLY the Child's apps
                    // when the remote payload actually differs from local SQLite rows.
                    val existingApps = dao.getAppsForChildOnce(child.id)
                    val existingSig = existingApps.map {
                      "${it.packageName.lowercase()}_${it.isBlocked}_${it.dailyLimitMinutes}_${it.usedMinutesToday}"
                    }
                    val cloudSig = cloudApps.map {
                      "${it.packageName.lowercase()}_${it.isBlocked}_${it.dailyLimitMinutes}_${it.usedMinutesToday}"
                    }
                    if (existingSig != cloudSig) {
                      dao.replaceChildAppsStrictlyFromRemote(child.id, cloudApps)
                    }
                  } else {
                    // CRITICAL ARCHITECTURAL RULE: On the Child device, its own PackageManager is the
                    // sole authority for installed apps. NEVER insert foreign apps from cloudApps!
                    val installedPkgSet = com.example.util.DeviceAppsProvider.getInstalledPackageSet(context)
                    val existingApps = dao.getAppsForChildOnce(child.id)
                    // Purge any foreign apps that are not physically installed on this Child phone
                    val validLocalApps = if (installedPkgSet.isNotEmpty()) {
                      existingApps.filter { it.packageName.trim().lowercase() in installedPkgSet }
                    } else existingApps
                    if (validLocalApps.size != existingApps.size) {
                      dao.replaceChildAppsStrictlyFromRemote(child.id, validLocalApps)
                    }
                    // Only apply block/limit rules to apps that are genuinely installed on this Child phone
                    for (cApp in cloudApps) {
                      val pkgKey = cApp.packageName.trim().lowercase()
                      if (installedPkgSet.isNotEmpty() && pkgKey !in installedPkgSet) continue
                      val localApp = validLocalApps.firstOrNull {
                        it.packageName.trim().equals(pkgKey, ignoreCase = true)
                      }
                      if (localApp != null &&
                          (localApp.isBlocked != cApp.isBlocked || localApp.dailyLimitMinutes != cApp.dailyLimitMinutes)) {
                        dao.updateApp(
                          localApp.copy(
                            isBlocked = cApp.isBlocked,
                            dailyLimitMinutes = cApp.dailyLimitMinutes
                          )
                        )
                      }
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Listen for remote chat messages from Cloud & real-time P2P Relay
      launch {
        com.example.data.cloud.RealtimeCloudRelay.incomingChatsFlow.collect { incomingMsg ->
          val allLocalChildren = dao.getAllChildrenOnce()
          val cfg = dao.getFamilyConfigOnce()
          val localRole = if (cfg?.selectedRole == "CHILD") "CHILD" else "PARENT"
          val resolvedChildId = allLocalChildren.firstOrNull { it.id == incomingMsg.childId }?.id
            ?: allLocalChildren.firstOrNull { it.name.equals(incomingMsg.senderName, ignoreCase = true) }?.id
            ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
            ?: allLocalChildren.firstOrNull()?.id
            ?: incomingMsg.childId
          val localMsgs = dao.getChatMessagesForChild(resolvedChildId).firstOrNull().orEmpty()
          val duplicate = localMsgs.any {
            it.senderRole == incomingMsg.senderRole &&
              it.text == incomingMsg.text &&
              kotlin.math.abs(it.timestamp - incomingMsg.timestamp) < 2500L
          }
          if (!duplicate) {
            dao.insertChatMessage(incomingMsg.copy(id = 0, childId = resolvedChildId))
            if (!incomingMsg.senderRole.equals(localRole, ignoreCase = true)) {
              com.example.service.FloatingChatOverlayService.showIncomingMessageOrSos(
                context = context,
                childId = resolvedChildId,
                senderName = incomingMsg.senderName,
                senderRole = incomingMsg.senderRole,
                messageText = incomingMsg.text,
                isSosAlert = incomingMsg.isAttentionSignal || incomingMsg.text.contains("SOS", ignoreCase = true)
              )
            }
          }
        }
      }

      launch {
        var childChatJobs = listOf<kotlinx.coroutines.Job>()
        dao.getAllChildren().collect { children ->
          childChatJobs.forEach { it.cancel() }
          childChatJobs = children.map { child ->
            launch {
              cloudSync.observeChatMessages(child.id).collect { cloudMsgs ->
                val cfg = dao.getFamilyConfigOnce()
                val localRole = if (cfg?.selectedRole == "CHILD") "CHILD" else "PARENT"
                val localMsgs = dao.getChatMessagesForChild(child.id).firstOrNull().orEmpty()
                for (msg in cloudMsgs) {
                  val exists = localMsgs.any {
                    it.senderRole == msg.senderRole &&
                      it.text == msg.text &&
                      kotlin.math.abs(it.timestamp - msg.timestamp) < 2500L
                  }
                  if (!exists) {
                    dao.insertChatMessage(msg.copy(id = 0, childId = child.id))
                    val isRecent = (System.currentTimeMillis() - msg.timestamp) < 120_000L
                    if (isRecent && !msg.senderRole.equals(localRole, ignoreCase = true)) {
                      com.example.service.FloatingChatOverlayService.showIncomingMessageOrSos(
                        context = context,
                        childId = child.id,
                        senderName = msg.senderName,
                        senderRole = msg.senderRole,
                        messageText = msg.text,
                        isSosAlert = msg.isAttentionSignal || msg.text.contains("SOS", ignoreCase = true)
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Listen for remote commands continuously ONLY when this device is in CHILD role!
      launch {
        var remoteCmdJob: kotlinx.coroutines.Job? = null
        dao.getFamilyConfig().collect { cfg ->
          remoteCmdJob?.cancel()
          val isChildRole = cfg?.selectedRole == "CHILD"
          val thisChildId = when {
            isChildRole && cfg != null && cfg.thisDeviceChildId > 0 -> cfg.thisDeviceChildId
            isChildRole && cfg != null && cfg.currentActiveChildId > 0 -> cfg.currentActiveChildId
            else -> 0L
          }
          if (isChildRole && thisChildId > 0) {
            remoteCmdJob = launch {
              cloudSync.observeRemoteCommands(thisChildId).collect { cmdPair ->
                if (dao.getFamilyConfigOnce()?.selectedRole != "CHILD") return@collect
                cmdPair?.let { (cmd, payload) ->
                  android.util.Log.d("Repo", "Received Cloud Command: $cmd for child $thisChildId")
                  when (cmd) {
                    "LOCK" -> {
                      com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(thisChildId, true)
                      dao.setChildDeviceLocked(thisChildId, true)
                      com.example.util.DeviceTotalLockManager.enforceTotalLock(context, "Экран заблокирован родителем")
                    }
                    "UNLOCK" -> {
                      com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(thisChildId, false)
                      dao.setChildDeviceLocked(thisChildId, false)
                      com.example.util.DeviceTotalLockManager.releaseTotalLock(context)
                    }
                    "BLOCK_APP" -> {
                      val pkg = payload.trim()
                      if (pkg.isNotBlank()) {
                        val existing = dao.getAppByPackageOnce(thisChildId, pkg)
                        if (existing != null) {
                          dao.setAppBlocked(existing.id, true)
                        }
                      }
                    }
                    "UNBLOCK_APP" -> {
                      val pkg = payload.trim()
                      if (pkg.isNotBlank()) {
                        val existing = dao.getAppByPackageOnce(thisChildId, pkg)
                        if (existing != null) {
                          dao.setAppBlocked(existing.id, false)
                        }
                      }
                    }
                    "LIMIT_APP", "SET_APP_LIMIT" -> {
                      val parts = payload.split("|")
                      val pkg = parts.getOrNull(0)?.trim().orEmpty()
                      val limitMin = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
                      if (pkg.isNotBlank()) {
                        val existing = dao.getAppByPackageOnce(thisChildId, pkg)
                        if (existing != null) {
                          dao.setAppLimit(existing.id, limitMin)
                        }
                      }
                    }
                    "REFRESH_APPS" -> {
                      com.example.util.DeviceUsageTracker.ensureDailyMidnightReset(context, thisChildId)
                      val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(context, thisChildId)
                      if (realApps.isNotEmpty()) {
                        val prevApps = dao.getAppsForChildOnce(thisChildId).associateBy { it.packageName }
                        val merged = realApps.map { app ->
                          val prev = prevApps[app.packageName]
                          if (prev != null) {
                            app.copy(
                              isBlocked = prev.isBlocked,
                              dailyLimitMinutes = prev.dailyLimitMinutes,
                              usedMinutesToday = app.usedMinutesToday
                            )
                          } else app
                        }
                        dao.deleteAppsForChild(thisChildId)
                        dao.insertApps(merged)
                        cloudSync.uploadChildApps(thisChildId, merged)
                      }
                      com.example.util.DeviceUsageTracker.syncRealUsageStats(context, thisChildId)
                    }
                    "SET_LIMIT" -> payload.toIntOrNull()?.let { dao.setChildDailyLimit(thisChildId, it) }
                    "STUDY_ON" -> dao.setChildStudyMode(thisChildId, true)
                    "STUDY_OFF" -> dao.setChildStudyMode(thisChildId, false)
                    "NET_WIFI_ONLY" -> dao.setChildAllowMobileData(thisChildId, false)
                    "NET_ALL" -> dao.setChildAllowMobileData(thisChildId, true)
                    "GPS_TRACKING_ON" -> {
                      com.example.location.LiveLocationTracker.setGpsTrackingAllowed(true)
                      dao.setChildGpsTrackingEnabled(thisChildId, true)
                    }
                    "GPS_TRACKING_OFF" -> {
                      com.example.location.LiveLocationTracker.setGpsTrackingAllowed(false)
                      dao.setChildGpsTrackingEnabled(thisChildId, false)
                    }
                    "LOUD_RING" -> {
                      triggerDeviceLoudAlarm()
                    }
                    "STOP_LOUD_RING" -> {
                      stopDeviceLoudAlarm()
                    }
                    "START_LIVE_STREAM" -> {
                      // Только явный просмотр включает «живое окно». Одиночный снимок
                      // (TAKE_SCREENSHOT) и запрос активного приложения НЕ должны
                      // держать MediaProjection включённым — иначе батарея течёт зря.
                      com.example.service.KidsGuardAccessibilityService.activateLiveStreamingWindow(120_000L)
                    }
                    "STOP_LIVE_STREAM" -> {
                      // Родитель закрыл просмотр — сразу отцепляем зеркало экрана (экономия батареи).
                      com.example.service.KidsGuardAccessibilityService.stopLiveStreamingWindow()
                    }
                    "PING" -> {
                      // Ответ на PING родителя для честного индикатора «Онлайн»
                      // (даже если фоновый сервис ещё не поднялся).
                      com.example.data.cloud.DirectP2pSocketEngine.markParentPresenceActive(thisChildId)
                      val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
                      dao.updateChildBattery(thisChildId, batt)
                      cloudSync.upsertBatteryLevel(thisChildId, batt)
                      com.example.data.cloud.RealtimeCloudRelay.publishChildPong(thisChildId)
                      cloudSync.clearRemoteCommand(thisChildId)
                    }
                    "REQUEST_GPS_NOW", "REQUEST_OFFLINE_SYNC" -> {
                      if (!com.example.location.LiveLocationTracker.isEmulator() && com.example.location.LiveLocationTracker.isGpsTrackingAllowed) {
                        val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
                        dao.updateChildBattery(thisChildId, batt)
                        cloudSync.upsertBatteryLevel(thisChildId, batt)
                        com.example.location.LiveLocationTracker.getBestImmediateLocation(context)?.let { loc ->
                          val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(context, loc.latitude, loc.longitude)
                          dao.updateChildLocation(thisChildId, loc.latitude, loc.longitude, addr, System.currentTimeMillis())
                          cloudSync.updateLocation(thisChildId, loc.latitude, loc.longitude, addr)
                        }
                      }
                      com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(context, thisChildId)
                    }
                    "UPDATE_APP", "APP_UPDATE" -> {
                      cloudSync.clearRemoteCommand(thisChildId)
                      dao.setChildUpdatePending(thisChildId, true)
                      val targetVer = if (payload.contains("|")) payload.substringBefore("|").trim() else ""
                      val rawUrl = if (payload.contains("|")) payload.substringAfter("|").trim() else payload.trim()
                      val targetUrl = rawUrl.ifBlank { com.example.util.ApkUpdateInstaller.SHARED_APK_URL }
                      com.example.util.ApkUpdateInstaller.downloadAndInstallLatestApk(
                        context = context,
                        customUrl = targetUrl,
                        isSilentChildUpdate = true,
                        targetChildId = thisChildId,
                        targetVersionOverride = targetVer
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }

      // Listen for real-time incoming alerts from P2P Relay (instant SOS & coordinate sync)
      launch {
        com.example.data.cloud.RealtimeCloudRelay.incomingAlertsFlow.collect { alert ->
          val cfg = dao.getFamilyConfigOnce()
          if (cfg?.selectedRole == "CHILD") return@collect
          val allLocalChildren = dao.getAllChildrenOnce()
          val resolvedChildId = allLocalChildren.firstOrNull { it.id == alert.childId }?.id
            ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
            ?: allLocalChildren.firstOrNull()?.id
            ?: alert.childId

          if (alert.type == "SOS" && alert.extraPayload.contains(",")) {
            val parts = alert.extraPayload.split(",")
            val sosLat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 0.0
            val sosLng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0
            if (sosLat != 0.0 && sosLng != 0.0 && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(sosLat, sosLng)) {
              val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(context, sosLat, sosLng)
              dao.updateChildLocation(resolvedChildId, sosLat, sosLng, addr, alert.timestamp)
            }
          }

          val currentLocalAlerts = dao.getAllAlerts().firstOrNull().orEmpty()
          val existing = currentLocalAlerts.any {
            it.type == alert.type &&
              it.childId == resolvedChildId &&
              kotlin.math.abs(it.timestamp - alert.timestamp) < 2500L
          }
          if (!existing) {
            dao.insertAlert(alert.copy(id = 0, childId = resolvedChildId))
            if (alert.type == "SOS" || alert.type == "CHAT_MESSAGE") {
              val childName = allLocalChildren.firstOrNull { it.id == resolvedChildId }?.name ?: "Ребёнок"
              com.example.service.FloatingChatOverlayService.showIncomingMessageOrSos(
                context = context,
                childId = resolvedChildId,
                senderName = childName,
                senderRole = "CHILD",
                messageText = alert.message,
                isSosAlert = alert.type == "SOS"
              )
            }
          }
          if (alert.type == "REMOTE_UPDATE_COMPLETED") {
            // extraPayload — версия ЯДРА; appVersion (APK) не трогаем.
            dao.setChildUpdatePending(resolvedChildId, false)
          }
        }
      }

      // Listen for remote alerts (including REMOTE_UPDATE_COMPLETED from Child phone)
      launch {
        cloudSync.observeAlerts().collect { remoteAlerts ->
          val cfg = dao.getFamilyConfigOnce()
          if (cfg?.selectedRole == "CHILD") return@collect
          val allLocalChildren = dao.getAllChildrenOnce()
          val currentLocalAlerts = dao.getAllAlerts().firstOrNull().orEmpty()
          for (alert in remoteAlerts) {
            val resolvedChildId = allLocalChildren.firstOrNull { it.id == alert.childId }?.id
              ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
              ?: allLocalChildren.firstOrNull()?.id
              ?: alert.childId
            val existing = currentLocalAlerts.any {
              kotlin.math.abs(it.timestamp - alert.timestamp) < 2500L &&
                it.type == alert.type &&
                it.childId == resolvedChildId
            }
            if (!existing) {
              dao.insertAlert(alert.copy(id = 0, childId = resolvedChildId))
              val isRecent = (System.currentTimeMillis() - alert.timestamp) < 120_000L
              if (isRecent && (alert.type == "SOS" || alert.type == "CHAT_MESSAGE")) {
                val childName = allLocalChildren.firstOrNull { it.id == resolvedChildId }?.name ?: "Ребёнок"
                com.example.service.FloatingChatOverlayService.showIncomingMessageOrSos(
                  context = context,
                  childId = resolvedChildId,
                  senderName = childName,
                  senderRole = "CHILD",
                  messageText = alert.message,
                  isSosAlert = alert.type == "SOS"
                )
              }
            }
            if (alert.type == "REMOTE_UPDATE_COMPLETED") {
              // extraPayload — версия ЯДРА; appVersion (APK) не трогаем.
              dao.setChildUpdatePending(resolvedChildId, false)
            }
          }
        }
      }

      // Listen for incoming offline store-and-forward bundles on Parent device
      launch {
        com.example.data.cloud.DirectP2pSocketEngine.incomingOfflineBundlesFlow.collect { bundleMsg ->
          com.example.data.cloud.OfflineStoreAndForwardManager.ingestOfflineBundleOnParent(context, bundleMsg)
        }
      }
    }
  }

  // Family & Multi-Child management
  val familyConfig: Flow<FamilyConfigEntity?> = dao.getFamilyConfig()
  val allChildren: Flow<List<ChildProfileEntity>> = dao.getAllChildren()
  val allOfflineActivityLogs = dao.getAllOfflineActivityLogs()

  fun getOfflineActivityLogsForChild(childId: Long) = dao.getOfflineActivityLogsForChild(childId)

  suspend fun getFamilyConfigOnce(): FamilyConfigEntity? = dao.getFamilyConfigOnce()

  suspend fun setDeviceRole(role: String, preferredChildId: Long = 0L) {
    dao.setDeviceRole(role)
    com.example.data.cloud.DirectP2pSocketEngine.updateRole(role)
    if (role == "CHILD") {
      val cfg = dao.getFamilyConfigOnce()
      // Prefer explicit preferredChildId or currentActiveChildId (e.g., when user selected "Тест Назрин" and tapped Child Mode)
      val activeId = preferredChildId.takeIf { it > 0 }
        ?: cfg?.currentActiveChildId?.takeIf { it > 0 }
        ?: cfg?.thisDeviceChildId?.takeIf { it > 0 }
        ?: dao.getAllChildrenOnce().lastOrNull()?.id
        ?: 1L
      bindThisDeviceToChild(activeId)
    } else {
      // PARENT role: clear thisDeviceChildId, stop all child lock/monitoring services on Parent's phone,
      // and strictly purge ALL locally-scanned Parent apps so only remote Child apps are ever stored!
      dao.setThisDeviceChildId(0L)
      com.example.service.LockOverlayService.stop(context)
      com.example.service.KidsGuardBackgroundService.stop(context)
      dao.clearAllApps()
      for (child in dao.getAllChildrenOnce()) {
        cloudSync.sendRemoteCommand(child.id, "REFRESH_APPS", "")
      }
    }
  }

  suspend fun setActiveChild(childId: Long) {
    dao.setActiveChildId(childId)
    val cfg = dao.getFamilyConfigOnce()
    if (cfg?.selectedRole == "CHILD" && childId > 0L && cfg.thisDeviceChildId != childId) {
      bindThisDeviceToChild(childId)
    }
  }

  suspend fun bindThisDeviceToChild(childId: Long) {
    if (childId <= 0L) return
    val prevCfg = dao.getFamilyConfigOnce()
    val prevBoundId = prevCfg?.thisDeviceChildId ?: 0L
    dao.setThisDeviceChildId(childId)
    dao.setActiveChildId(childId)

    // Ensure phone is unlocked when binding to Child mode unless parent explicitly locks it later
    dao.setChildDeviceLocked(childId, false)
    com.example.util.DeviceTotalLockManager.releaseTotalLock(context)

    if (prevBoundId > 0L && prevBoundId != childId) {
      dao.updateChildBattery(prevBoundId, -1)
      com.example.data.cloud.RealtimeCloudRelay.publishChildFields(prevBoundId, mapOf("batteryLevel" to -1))
    }

    val model = "${android.os.Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${android.os.Build.MODEL}".trim()
    dao.markChildPaired(childId, model)
    val child = dao.getChildByIdOnce(childId)
    if (child != null) {
      cloudSync.markChildPaired(child.id, child.pairingCode, model)
    }
    if (!com.example.location.LiveLocationTracker.isEmulator()) {
      val apkVer = com.example.util.AppVersionInfo.installedApkVersion(context)
      com.example.service.KidsGuardBackgroundService.start(context, childId)
      val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
      dao.updateChildBattery(childId, batt)
      dao.updateChildAppVersion(childId, apkVer)
      cloudSync.upsertBatteryLevel(childId, batt)
      cloudSync.syncChildAppVersion(childId, apkVer)
      val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(context, childId)
      if (realApps.isNotEmpty()) {
        dao.deleteAppsForChild(childId)
        dao.insertApps(realApps)
        cloudSync.uploadChildApps(childId, realApps)
      }
    }
  }

  suspend fun updateMasterPin(pin: String) {
    dao.updateMasterPin(pin)
  }

  suspend fun deduplicateLocalChildrenInDb() {
    val allLocal = dao.getAllChildrenOnce()
    if (allLocal.size <= 1) return
    val cfg = dao.getFamilyConfigOnce()
    val preferredId = when {
      cfg?.selectedRole == "CHILD" && cfg.thisDeviceChildId > 0L -> cfg.thisDeviceChildId
      (cfg?.currentActiveChildId ?: 0L) > 0L -> cfg!!.currentActiveChildId
      else -> 0L
    }

    // Sort so preferredId or paired profile with real name comes first
    val sorted = allLocal.sortedWith(
      compareByDescending<ChildProfileEntity> { it.id == preferredId }
        .thenByDescending { com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(it.name) }
        .thenByDescending { it.isPaired && !it.deviceModel.contains("Ожидание", ignoreCase = true) }
        .thenBy { it.id }
    )

    val canonicalList = mutableListOf<ChildProfileEntity>()
    for (item in sorted) {
      val codeClean = item.pairingCode.replace("-", "").replace(" ", "").uppercase()
      val nameClean = item.name.trim().lowercase()
      val realName = com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(item.name)

      val matchIdx = canonicalList.indexOfFirst { c ->
        val cCode = c.pairingCode.replace("-", "").replace(" ", "").uppercase()
        val cName = c.name.trim().lowercase()
        (codeClean.length >= 6 && cCode == codeClean) ||
          (realName && com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(c.name) && cName == nameClean) ||
          (cfg?.selectedRole == "CHILD" && preferredId > 0L) // On a Child phone, keep strictly 1 bound child profile!
      }

      if (matchIdx < 0) {
        canonicalList.add(item)
      } else {
        val canon = canonicalList[matchIdx]
        val merged = canon.copy(
          name = if (com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(canon.name)) canon.name else item.name,
          age = if (canon.age != 10 && canon.age > 0) canon.age else item.age,
          deviceModel = if (canon.isPaired && !canon.deviceModel.contains("Ожидание", ignoreCase = true)) canon.deviceModel else item.deviceModel,
          pairingCode = canon.pairingCode.ifBlank { item.pairingCode },
          isPaired = canon.isPaired || item.isPaired,
          batteryLevel = if (canon.batteryLevel in 1..100) canon.batteryLevel else item.batteryLevel,
          lastLocationLat = if (canon.lastLocationLat != 0.0) canon.lastLocationLat else item.lastLocationLat,
          lastLocationLng = if (canon.lastLocationLng != 0.0) canon.lastLocationLng else item.lastLocationLng,
          lastLocationAddress = if (canon.lastLocationLat != 0.0) canon.lastLocationAddress else item.lastLocationAddress,
          lastLocationTime = maxOf(canon.lastLocationTime, item.lastLocationTime),
          appVersion = if (com.example.util.ApkUpdateInstaller.compareVersions(item.appVersion, canon.appVersion) >= 0) item.appVersion else canon.appVersion
        )
        canonicalList[matchIdx] = merged
        dao.updateChild(merged)
        if (item.id != canon.id) {
          dao.deleteChildById(item.id)
        }
      }
    }
  }

  // Generate pairing code and add new child with globally unique ID (or reuse existing profile if same name!)
  suspend fun addNewChild(name: String, age: Int, dailyLimitMinutes: Int = 120): Long {
    val cleanName = name.trim()
    val existingSameName = dao.getAllChildrenOnce().firstOrNull {
      it.name.trim().equals(cleanName, ignoreCase = true)
    } ?: com.example.data.cloud.RealtimeCloudRelay.liveChildrenFlow.value.firstOrNull {
      it.name.trim().equals(cleanName, ignoreCase = true)
    }

    if (existingSameName != null) {
      val updatedExisting = existingSameName.copy(
        name = cleanName,
        age = age,
        dailyScreenLimitMinutes = dailyLimitMinutes.coerceIn(30, 360),
        appVersion = com.example.BuildConfig.VERSION_NAME
      )
      if (dao.getChildByIdOnce(existingSameName.id) == null) {
        dao.insertChild(updatedExisting)
      } else {
        dao.updateChild(updatedExisting)
      }
      deduplicateLocalChildrenInDb()
      dao.setActiveChildId(existingSameName.id)
      cloudSync.upsertChildProfile(updatedExisting)
      return existingSameName.id
    }

    val existingIds = (
      dao.getAllChildrenOnce().map { it.id } +
        com.example.data.cloud.RealtimeCloudRelay.liveChildrenFlow.value.map { it.id }
      ).toSet()
    val nextId = ((existingIds.maxOrNull() ?: 1L) + 1L).coerceAtLeast(2L)
    val prefixTwo = Random.nextInt(10, 99)
    val suffixThree = Random.nextInt(100, 999)
    val idDigit = (nextId % 10L).toInt()
    val code = "$prefixTwo$idDigit-$suffixThree"

    val child = ChildProfileEntity(
      id = nextId,
      name = cleanName,
      age = age,
      deviceModel = "Ожидание сопряжения",
      pairingCode = code,
      isPaired = false,
      batteryLevel = -1,
      dailyScreenLimitMinutes = dailyLimitMinutes.coerceIn(30, 360),
      usedScreenTimeMinutes = 0,
      lastLocationLat = 0.0,
      lastLocationLng = 0.0,
      lastLocationAddress = "Ожидание точного GPS-сигнала с телефона ребёнка...",
      appVersion = com.example.BuildConfig.VERSION_NAME,
      colorHex = when (Random.nextInt(4)) {
        0 -> 0xFF0EA5E9
        1 -> 0xFF10B981
        2 -> 0xFF8B5CF6
        else -> 0xFFF59E0B
      }
    )
    val newId = dao.insertChild(child).takeIf { it > 0L } ?: nextId
    val finalCode = if (newId != nextId) {
      "$prefixTwo${(newId % 10L).toInt()}-$suffixThree"
    } else {
      code
    }
    val savedChild = child.copy(id = newId, pairingCode = finalCode, batteryLevel = -1)
    if (newId != nextId) {
      dao.updateChild(savedChild)
    }

    dao.setActiveChildId(newId)
    cloudSync.upsertChildProfile(savedChild)

    val isChildDevice = dao.getFamilyConfigOnce()?.selectedRole == "CHILD"
    if (isChildDevice) {
      bindThisDeviceToChild(newId)
    }

    addAlert(
      childId = newId,
      type = "CHILD_ADDED",
      title = "Добавлен профиль: $cleanName",
      message = "Создан профиль ребенка. Код для подключения на телефоне ребенка: $finalCode"
    )

    return newId
  }

  suspend fun syncRealAppsForChild(childId: Long): List<AppControlEntity> {
    val cfg = dao.getFamilyConfigOnce()
    val isThisChildDevice = !com.example.location.LiveLocationTracker.isEmulator() &&
      cfg?.selectedRole == "CHILD" &&
      (cfg.thisDeviceChildId == childId || cfg.thisDeviceChildId <= 0L)
    if (!isThisChildDevice) {
      // On Parent's phone or Emulator, request the real Child phone to scan and upload its apps!
      cloudSync.sendRemoteCommand(childId, "REFRESH_APPS", "")
      return dao.getAppsForChildOnce(childId)
    }
    val realApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(context, childId)
    if (realApps.isNotEmpty()) {
      dao.deleteAppsForChild(childId)
      dao.insertApps(realApps)
      cloudSync.uploadChildApps(childId, realApps)
    }
    return realApps
  }

  suspend fun deleteChild(child: ChildProfileEntity) {
    // 1. Delete immediately from Local Room DB
    dao.deleteChildById(child.id)
    dao.deleteAppsForChild(child.id)
    dao.deleteGeofencesForChild(child.id)
    dao.deleteAlertsForChild(child.id)
    dao.deleteOfflineActivityLogsForChild(child.id)
    dao.deleteOfflineLocationPointsForChild(child.id)
    dao.deleteOfflineScreenshotsForChild(child.id)
    com.example.util.ScreenShotManager.clearForChild(child.id)
    if (dao.getAllChildrenOnce().isEmpty()) {
      com.example.util.ScreenShotManager.clearAllCapturedScreens()
      com.example.data.cloud.RealtimeCloudRelay.clearAllChildrenCache()
    }

    // 2. Permanently delete from P2P Relay so it NEVER returns!
    try {
      cloudSync.deleteChildFromCloud(child.id)
    } catch (e: Exception) {
      android.util.Log.e("ParentalRepo", "Error deleting child from cloud: ${e.message}")
    }
  }

  suspend fun updateChild(child: ChildProfileEntity) {
    dao.updateChild(child)
  }

  // Device Pairing Handshake (checks LAN UDP, P2P TCP Relay, Local DB, and 6-digit deterministic fallback)
  suspend fun pairChildDevice(code: String, deviceModel: String): ChildProfileEntity? {
    val clean = code.trim().replace(" ", "")
    val digitsOnly = clean.replace("-", "").uppercase()
    val numericDigits = clean.filter { it.isDigit() }

    // Step 1: Look up in P2P Relay & LAN UDP (CRITICAL: Allows pairing between DIFFERENT physical phones!)
    var child: ChildProfileEntity? = cloudSync.findChildByPairingCode(code)

    // Step 2: If not found in cloud/LAN yet, check local DB (tolerant to hyphens/spaces)
    if (child == null) {
      child = dao.findChildByPairingCode(clean)
      if (child == null && numericDigits.length == 6) {
        val hyphenated = "${numericDigits.take(3)}-${numericDigits.substring(3)}"
        child = dao.findChildByPairingCode(hyphenated)
      }
      if (child == null) {
        child = dao.findChildByPairingCode(digitsOnly)
      }
      if (child == null && digitsOnly.isNotEmpty()) {
        child = dao.getAllChildrenOnce().firstOrNull {
          it.pairingCode.replace("-", "").replace(" ", "").uppercase() == digitsOnly ||
            it.id.toString() == digitsOnly
        }
      }
    }

    // Step 3: Zero-Failure Fallback for valid 6-digit pairing code (e.g. "482-915" or "482915")
    // If router AP isolation or mobile firewall delayed initial discovery, accept the valid 6-digit code,
    // bind immediately, and broadcast the exact pairingCode so Parent phone links it automatically!
    if (child == null && numericDigits.length == 6) {
      val formattedCode = "${numericDigits.take(3)}-${numericDigits.substring(3)}"
      val encodedIdDigit = numericDigits[2].digitToIntOrNull()?.toLong() ?: 2L
      val fallbackId = if (encodedIdDigit in 1L..9L) encodedIdDigit else 2L
      child = ChildProfileEntity(
        id = fallbackId,
        name = "Ребёнок #$fallbackId",
        age = 10,
        deviceModel = deviceModel,
        pairingCode = formattedCode,
        isPaired = true,
        batteryLevel = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context),
        dailyScreenLimitMinutes = 120,
        usedScreenTimeMinutes = 0,
        appVersion = com.example.BuildConfig.VERSION_NAME
      )
    }

    if (child != null) {
      val formattedInputCode = if (numericDigits.length == 6) {
        "${numericDigits.take(3)}-${numericDigits.substring(3)}"
      } else {
        clean
      }
      val effectiveVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(context)
      com.example.util.DeviceTotalLockManager.releaseTotalLock(context)
      com.example.data.cloud.RealtimeCloudRelay.publishAuthoritativeLock(child.id, false)

      val pairedProfile = child.copy(
        isPaired = true,
        isDeviceLocked = false,
        deviceModel = deviceModel,
        pairingCode = child.pairingCode.ifBlank { formattedInputCode },
        appVersion = effectiveVer
      )

      // On the Child's phone, remove any other stale/duplicate child profiles from local Room DB
      // and clean up any duplicate IDs with the same child name so there is strictly 1 child profile!
      for (other in dao.getAllChildrenOnce()) {
        if (other.id != pairedProfile.id) {
          dao.deleteChildById(other.id)
          if (com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(pairedProfile.name) &&
              other.name.trim().equals(pairedProfile.name.trim(), ignoreCase = true)) {
            try { cloudSync.deleteChildFromCloud(other.id) } catch (_: Exception) {}
          }
        }
      }

      // Save or update in local DB
      val existingLocal = dao.getChildByIdOnce(pairedProfile.id)
      if (existingLocal == null) {
        dao.insertChild(pairedProfile)
      } else {
        dao.updateChild(
          existingLocal.copy(
            name = if (com.example.data.cloud.RealtimeCloudRelay.isRealCustomChildName(pairedProfile.name)) pairedProfile.name else existingLocal.name,
            age = if (pairedProfile.age != 10 && pairedProfile.age > 0) pairedProfile.age else existingLocal.age,
            isPaired = true,
            isDeviceLocked = false,
            deviceModel = deviceModel,
            pairingCode = pairedProfile.pairingCode.ifBlank { existingLocal.pairingCode },
            appVersion = effectiveVer
          )
        )
      }

      dao.setThisDeviceChildId(pairedProfile.id)
      dao.setActiveChildId(pairedProfile.id)
      dao.setDeviceRole("CHILD")
      com.example.data.cloud.DirectP2pSocketEngine.updateRole("CHILD", pairedProfile.id)

      // Scan REAL installed applications on CHILD'S PHONE and upload to Cloud for Parent!
      val childRealApps = com.example.util.DeviceAppsProvider.getInstalledLaunchableApps(context, pairedProfile.id)
      if (childRealApps.isNotEmpty()) {
        dao.deleteAppsForChild(pairedProfile.id)
        dao.insertApps(childRealApps)
        cloudSync.uploadChildApps(pairedProfile.id, childRealApps)
      }

      // Broadcast full paired state + pairingCode over LAN UDP & Global TCP Relay so Parent sees it immediately
      com.example.data.cloud.RealtimeCloudRelay.publishChildProfile(pairedProfile, true)
      cloudSync.markChildPaired(pairedProfile.id, pairedProfile.pairingCode, deviceModel)

      addAlert(
        childId = pairedProfile.id,
        type = "DEVICE_PAIRED",
        title = "Телефон ребенка успешно привязан!",
        message = "Устройство «$deviceModel» подключено к семейному аккаунту (код ${pairedProfile.pairingCode})"
      )
      return pairedProfile
    }
    return null
  }

  // Controls for Child
  suspend fun updateChildBattery(childId: Long, level: Int) {
    val clamped = level.coerceIn(1, 100)
    dao.updateChildBattery(childId, clamped)
    cloudSync.upsertBatteryLevel(childId, clamped)
  }

  suspend fun updateChildLocation(childId: Long, lat: Double, lng: Double, address: String) {
    dao.updateChildLocation(childId, lat, lng, address, System.currentTimeMillis())
    cloudSync.updateLocation(childId, lat, lng, address)
  }

  suspend fun sendRemoteCloudCommand(childId: Long, command: String, payload: String = "") {
    cloudSync.sendRemoteCommand(childId, command, payload)
  }

  suspend fun setChildDeviceLocked(childId: Long, locked: Boolean) {
    dao.setChildDeviceLocked(childId, locked)
    cloudSync.updateRemoteLock(childId, locked)
    val child = dao.getChildByIdOnce(childId)
    val name = child?.name ?: "Ребенок"
    addAlert(
      childId = childId,
      type = if (locked) "DEVICE_LOCKED" else "DEVICE_UNLOCKED",
      title = if (locked) "Телефон заблокирован" else "Телефон разблокирован",
      message = if (locked) "Удаленный доступ к телефону «$name» ограничен родителем" else "Телефон «$name» разблокирован"
    )
  }

  suspend fun setChildDailyLimit(childId: Long, limitMinutes: Int) {
    dao.setChildDailyLimit(childId, limitMinutes)
    cloudSync.updateDailyLimit(childId, limitMinutes)
  }

  suspend fun setChildStudyMode(childId: Long, active: Boolean) {
    dao.setChildStudyMode(childId, active)
    dao.setNonEducationalAppsBlockedForChild(childId, active)
    cloudSync.updateStudyMode(childId, active)
  }

  suspend fun grantExtraScreenTimeForChild(childId: Long, extraMinutes: Int) {
    val child = dao.getChildByIdOnce(childId)
    if (child != null) {
      dao.setChildDailyLimit(childId, child.dailyScreenLimitMinutes + extraMinutes)
      addAlert(
        childId = childId,
        type = "BONUS_APPROVED",
        title = "Добавлено +$extraMinutes мин",
        message = "Родитель добавил дополнительное экранное время для «${child.name}»"
      )
    }
  }

  // --- Network Control (Wi-Fi vs Mobile Data) ---
  suspend fun setChildAllowMobileData(childId: Long, allow: Boolean) {
    dao.setChildAllowMobileData(childId, allow)
    cloudSync.updateAllowMobileData(childId, allow)
    val child = dao.getChildByIdOnce(childId)
    val name = child?.name ?: "ребенка"
    addAlert(
      childId = childId,
      type = if (allow) "NETWORK_MOBILE_ENABLED" else "NETWORK_WIFI_ONLY",
      title = if (allow) "Мобильный интернет разрешен" else "Режим «Только Wi-Fi» активен",
      message = if (allow) {
        "Для «$name» разрешена передача данных через мобильную сеть (расход ~15–35 КБ/час). Геолокация и чат доступны вне зон Wi-Fi."
      } else {
        "Для «$name» включен режим «Только Wi-Fi». Мобильный интернет отключен, трафик тарифа не расходуется (0 КБ). Синхронизация возобновится при подключении к Wi-Fi."
      }
    )
  }

  // --- GPS Navigation & Satellite Tracking Control (Battery Saving Toggle) ---
  suspend fun setChildGpsTrackingEnabled(childId: Long, enabled: Boolean) {
    com.example.location.LiveLocationTracker.setGpsTrackingAllowed(enabled)
    dao.setChildGpsTrackingEnabled(childId, enabled)
    cloudSync.updateGpsTrackingEnabled(childId, enabled)
    val child = dao.getChildByIdOnce(childId)
    val name = child?.name ?: "ребенка"
    addAlert(
      childId = childId,
      type = if (enabled) "GPS_TRACKING_ON" else "GPS_TRACKING_OFF",
      title = if (enabled) "GPS-навигация и трекинг включены" else "GPS-навигация отключена (Экономия батареи)",
      message = if (enabled) {
        "Для «$name» включено отслеживание координат в умном эко-режиме (опрос только при смещении ≥ 15 м)."
      } else {
        "Для «$name» отключен опрос спутников GPS. Модуль геолокации переведен в спящий режим для максимальной экономии заряда батареи телефона ребёнка."
      }
    )
  }

  // Apps (strictly deduplicated by packageName and appName, excluding KidsGuard self-packages)
  val allApps: Flow<List<AppControlEntity>> = dao.getAllApps().map { list ->
    list
      .filterNot {
        it.packageName.contains("kidsguard", ignoreCase = true) ||
          it.appName.contains("kidsguard", ignoreCase = true) ||
          it.appName.contains("родительский контроль", ignoreCase = true)
      }
      .distinctBy { "${it.childId}_${it.packageName.lowercase().trim()}" }
      .distinctBy { "${it.childId}_${it.appName.lowercase().trim()}" }
  }

  fun getAppsForChild(childId: Long): Flow<List<AppControlEntity>> = dao.getAppsForChild(childId).map { list ->
    list
      .filterNot {
        it.packageName.contains("kidsguard", ignoreCase = true) ||
          it.appName.contains("kidsguard", ignoreCase = true) ||
          it.appName.contains("родительский контроль", ignoreCase = true)
      }
      .distinctBy { it.packageName.lowercase().trim() }
      .distinctBy { it.appName.lowercase().trim() }
  }

  suspend fun setAppBlocked(appId: Long, isBlocked: Boolean) {
    dao.setAppBlocked(appId, isBlocked)
    val app = dao.getAppByIdOnce(appId)
    if (app != null) {
      cloudSync.updateAppBlocked(app.childId, app.packageName, isBlocked)
    }
  }

  suspend fun getAppById(appId: Long): AppControlEntity? {
    return dao.getAppByIdOnce(appId)
  }

  suspend fun setAppLimit(appId: Long, limitMinutes: Int) {
    dao.setAppLimit(appId, limitMinutes)
    val app = dao.getAppByIdOnce(appId)
    if (app != null) {
      cloudSync.updateAppLimit(app.childId, app.packageName, limitMinutes)
    }
  }

  suspend fun addNewApp(app: AppControlEntity) {
    dao.insertApp(app)
  }

  // Geofences
  val allGeofences: Flow<List<GeofenceZoneEntity>> = dao.getAllGeofences()

  suspend fun addGeofence(zone: GeofenceZoneEntity) {
    dao.insertGeofence(zone)
  }

  suspend fun deleteGeofence(zone: GeofenceZoneEntity) {
    dao.deleteGeofence(zone)
  }

  // Content rules & Parent Settings
  val allContentRules: Flow<List<ContentRuleEntity>> = dao.getAllContentRules()
  val parentSettings: Flow<ParentSettingsEntity?> = dao.getSettings()

  private suspend fun pushContentRulesToCloud() {
    val all = dao.getAllContentRulesOnce()
    cloudSync.syncContentRulesToCloud(all)
  }

  suspend fun setContentRuleBlocked(id: Long, blocked: Boolean) {
    dao.setContentRuleBlocked(id, blocked)
    pushContentRulesToCloud()
  }

  suspend fun setBatchContentRulesBlocked(rulesToUpdate: List<ContentRuleEntity>, blocked: Boolean) {
    for (r in rulesToUpdate) {
      dao.setContentRuleBlocked(r.id, blocked)
    }
    pushContentRulesToCloud()
  }

  suspend fun addContentRule(rule: ContentRuleEntity) {
    val existing = dao.getAllContentRulesOnce().find {
      it.ruleType.equals(rule.ruleType, ignoreCase = true) &&
        it.value.equals(rule.value.trim(), ignoreCase = true)
    }
    if (existing != null) {
      dao.setContentRuleBlocked(existing.id, rule.isBlocked)
    } else {
      dao.insertContentRule(rule.copy(value = rule.value.trim()))
    }
    pushContentRulesToCloud()
  }

  suspend fun deleteContentRule(rule: ContentRuleEntity) {
    dao.deleteContentRule(rule)
    pushContentRulesToCloud()
  }

  suspend fun setSafeSearch(enabled: Boolean) {
    val existing = dao.getSettingsOnce()
    if (existing == null) {
      dao.insertOrUpdateSettings(ParentSettingsEntity(id = 1, safeSearchEnabled = enabled, youtubeRestricted = true))
    } else {
      dao.setSafeSearch(enabled)
    }
  }

  suspend fun setYoutubeRestricted(restricted: Boolean) {
    val existing = dao.getSettingsOnce()
    if (existing == null) {
      dao.insertOrUpdateSettings(ParentSettingsEntity(id = 1, safeSearchEnabled = true, youtubeRestricted = restricted))
    } else {
      dao.setYoutubeRestricted(restricted)
    }
  }

  // Alerts
  val allAlerts: Flow<List<AlertNotificationEntity>> = dao.getAllAlerts()

  suspend fun addAlert(childId: Long = 1, type: String, title: String, message: String, payload: String = "") {
    val alert = AlertNotificationEntity(
      childId = childId,
      type = type,
      title = title,
      message = message,
      timestamp = System.currentTimeMillis(),
      extraPayload = payload
    )
    dao.insertAlert(alert)
    cloudSync.sendAlert(alert)
  }

  suspend fun markAlertRead(id: Long) {
    dao.markAlertRead(id)
  }

  suspend fun deleteAlertById(id: Long) {
    val existing = dao.getAlertByIdOnce(id)
    com.example.data.cloud.RealtimeCloudRelay.markAlertDeleted(id, existing?.timestamp ?: 0L)
    dao.deleteAlertById(id)
  }

  suspend fun clearAllAlerts() {
    com.example.data.cloud.RealtimeCloudRelay.clearAllAlertsCache()
    dao.clearAllAlerts()
  }

  // Normalize URL or domain string for accurate blacklist matching
  fun normalizeDomainOrUrl(raw: String): String {
    return raw.trim().lowercase()
      .removePrefix("https://")
      .removePrefix("http://")
      .removePrefix("www.")
      .trimEnd('/')
  }

  // Web Filter evaluation
  suspend fun checkContentFilter(inputUrlOrQuery: String): ContentFilterEvaluation {
    val clean = inputUrlOrQuery.trim().lowercase()
    val normTarget = normalizeDomainOrUrl(clean)
    val rules = dao.getAllContentRulesOnce()

    // 1. Check Custom Blacklisted Domains & URLs
    for (rule in rules.filter { it.ruleType == "DOMAIN" && it.isBlocked }) {
      val normRule = normalizeDomainOrUrl(rule.value)
      if (normRule.isNotBlank() && (clean.contains(normRule) || normTarget.contains(normRule))) {
        dao.incrementRuleHit(rule.id)
        return ContentFilterEvaluation(
          isAllowed = false,
          matchedRuleName = rule.displayName,
          reason = "Домен / URL в черном списке: ${rule.value}"
        )
      }
    }

    // 2. Check Custom Blacklisted Keywords
    for (rule in rules.filter { it.ruleType == "KEYWORD" && it.isBlocked }) {
      val kw = rule.value.trim().lowercase()
      if (kw.isNotBlank() && clean.contains(kw)) {
        dao.incrementRuleHit(rule.id)
        return ContentFilterEvaluation(
          isAllowed = false,
          matchedRuleName = rule.displayName,
          reason = "Запрещенное ключевое слово: «${rule.value}»"
        )
      }
    }

    // 3. Check Blocked Categories
    val categoryRules = rules.filter { it.ruleType == "CATEGORY" && it.isBlocked }.associateBy { it.value }

    if (categoryRules.containsKey("ADULT") &&
      listOf("porn", "xxx", "sex", "xvideos", "pornhub", "xnxx", "onlyfans", "порно", "секс", "18+", "эротик", "хентай", "интим").any { clean.contains(it) }) {
      categoryRules["ADULT"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Контент 18+", "Блокировка категории: Контент 18+ и сайты для взрослых")
    }

    if (categoryRules.containsKey("GAMBLING") &&
      listOf("casino", "казино", "ставки", "1xbet", "fonbet", "winline", "melbet", "parimatch", "poker", "рулетка", "азино", "слоты", "букмекер", "stake.com", "vulkan").any { clean.contains(it) }) {
      categoryRules["GAMBLING"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Азартные игры и казино", "Блокировка категории: Азартные игры, ставки и онлайн-казино")
    }

    if (categoryRules.containsKey("VIOLENCE") &&
      listOf("weapon", "оружие", "убийств", "бомб", "террор", "нож купить", "даркнет", "шок-контент", "gore").any { clean.contains(it) }) {
      categoryRules["VIOLENCE"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Насилие и оружие", "Блокировка категории: Насилие, оружие и шок-контент")
    }

    if (categoryRules.containsKey("MALWARE") &&
      listOf("phishing", "чит", "взлом", "crack", "keygen", "троян", "скачать бесплатно взлом").any { clean.contains(it) }) {
      categoryRules["MALWARE"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Фишинг и вредоносные сайты", "Блокировка категории: Фишинг, взлом и вредоносные сайты")
    }

    if (categoryRules.containsKey("DRUGS") &&
      listOf("vape", "вейп", "снюс", "наркот", "закладк", "алкоголь купить", "табак", "hqd", "подик").any { clean.contains(it) }) {
      categoryRules["DRUGS"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Наркотики, алкоголь и вейпы", "Блокировка категории: Наркотики, алкоголь, табак и вейпы")
    }

    if (categoryRules.containsKey("GAMING_WEB") &&
      listOf("poki.com", "y8.com", "crazygames", "roblox.com", "twitch.tv", "kick.com", "steamcommunity.com", "играть онлайн").any { clean.contains(it) }) {
      categoryRules["GAMING_WEB"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Браузерные игры и стримы", "Блокировка категории: Браузерные игры и игровые стримы")
    }

    if (categoryRules.containsKey("SOCIAL_DATING") &&
      listOf("tinder", "badoo", "omegle", "chatroulette", "nekto.me", "дайвинчик", "знакомства", "анонимный чат").any { clean.contains(it) }) {
      categoryRules["SOCIAL_DATING"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Знакомства и анонимные чаты", "Блокировка категории: Анонимные чаты и сайты знакомств")
    }

    if (categoryRules.containsKey("SHOPPING_DONATE") &&
      listOf("funpay", "playerok", "ggsel", "donationalerts", "донат", "купить робуксы", "купить голду").any { clean.contains(it) }) {
      categoryRules["SHOPPING_DONATE"]?.let { dao.incrementRuleHit(it.id) }
      return ContentFilterEvaluation(false, "Покупки и донаты", "Блокировка категории: Онлайн-покупки и игровые донаты")
    }

    return ContentFilterEvaluation(true)
  }

  // --- Family & Child Bi-Directional Communication (Чат и связь) ---
  fun getChatMessagesForChild(childId: Long): Flow<List<ChatMessageEntity>> =
    dao.getChatMessagesForChild(childId)

  fun getAllChatMessages(): Flow<List<ChatMessageEntity>> =
    dao.getAllChatMessages()

  suspend fun sendChatMessage(
    childId: Long,
    senderRole: String, // "PARENT" or "CHILD"
    senderName: String,
    text: String,
    isAttentionSignal: Boolean = false
  ): Long {
    val message = ChatMessageEntity(
      childId = childId,
      senderRole = senderRole,
      senderName = senderName,
      text = text,
      timestamp = System.currentTimeMillis(),
      isRead = false,
      isAttentionSignal = isAttentionSignal
    )
    val id = dao.insertChatMessage(message)
    cloudSync.sendChatMessage(message.copy(id = id))

    // Notify parent if message comes from child
    if (senderRole == "CHILD") {
      val chatAlert = AlertNotificationEntity(
        childId = childId,
        type = "CHAT_MESSAGE",
        title = "💬 Сообщение от «$senderName»",
        message = text,
        timestamp = message.timestamp
      )
      dao.insertAlert(chatAlert)
      cloudSync.sendAlert(chatAlert)
    } else if (isAttentionSignal) {
      val attnAlert = AlertNotificationEntity(
        childId = childId,
        type = "ATTENTION_SIGNAL",
        title = "🔊 Звуковой сигнал ребенку",
        message = "Родитель отправил громкий сигнал привлечения внимания на устройство ребенка",
        timestamp = message.timestamp
      )
      dao.insertAlert(attnAlert)
      cloudSync.sendAlert(attnAlert)
    }

    return id
  }

  suspend fun markChatMessagesRead(childId: Long, role: String) {
    dao.markChatMessagesRead(childId, role)
  }

  // --- Pillar 2: Полное восстановление исходных параметров и разрешений при удалении у родителя ---
  suspend fun restoreInitialParametersAndReleaseChild(childId: Long) {
    val child = dao.getChildByIdOnce(childId)
    val childName = child?.name ?: "ребенка"

    // 1. Полностью разблокировать все приложения и снять лимиты времени
    dao.resetAllAppsRestrictionsForChild(childId)

    // 2. Очистить все правила геозон ребенка
    dao.deleteGeofencesForChild(childId)

    // 3. Зафиксировать системное уведомление о снятии всех ограничений
    dao.insertAlert(
      AlertNotificationEntity(
        childId = childId,
        type = "RESTORE_DEFAULTS",
        title = "Все параметры и разрешения восстановлены",
        message = "Перед удалением профиля «$childName» все исходные параметры устройства, доступ к приложениям и разрешения были полностью восстановлены.",
        timestamp = System.currentTimeMillis()
      )
    )

    // 4. Удалить приложения, чат и сам профиль локально и в P2P-канале
    dao.deleteAppsForChild(childId)
    dao.deleteChatMessagesForChild(childId)
    dao.deleteChildById(childId)
    cloudSync.deleteChildFromCloud(childId)

    // 5. Если удаленный ребенок был активным, переключить на следующего доступного или 0
    val config = dao.getFamilyConfigOnce()
    if (config != null && config.currentActiveChildId == childId) {
      val nextChildId = dao.getAllChildrenOnce().firstOrNull()?.id ?: 0L
      dao.setActiveChildId(nextChildId)
    }
  }

  // --- Pillar 3: Удаленное фоновое скрытное обновление программы ребенка ---
  suspend fun triggerRemoteSilentUpdate(childId: Long, targetVersion: String = com.example.BuildConfig.VERSION_NAME) {
    val child = dao.getChildByIdOnce(childId)
    val childName = child?.name ?: "ребенка"
    val oldVersion = child?.appVersion?.ifBlank { com.example.BuildConfig.VERSION_NAME } ?: com.example.BuildConfig.VERSION_NAME

    dao.setChildUpdatePending(childId, true)

    dao.insertAlert(
      AlertNotificationEntity(
        childId = childId,
        type = "REMOTE_UPDATE_SENT",
        title = "Команда обновления Ядра отправлена ($oldVersion → $targetVersion)",
        message = "На устройство «$childName» отправлен модуль горячей замены Ядра v$targetVersion (без переустановки оболочки APK).",
        timestamp = System.currentTimeMillis()
      )
    )
  }

  suspend fun completeRemoteSilentUpdate(childId: Long, targetVersion: String) {
    val cleanVer = com.example.data.cloud.RealtimeCloudRelay.normalizeAppVersion(targetVersion)
    com.example.util.CoreEngineHotUpdater.setChildCoreVersionName(context, childId, cleanVer)
    com.example.data.cloud.RealtimeCloudRelay.setAuthoritativeChildCoreVersion(childId, cleanVer)
    val apkVer = com.example.util.AppVersionInfo.installedApkVersion(context)
    dao.updateChildAppVersion(childId, apkVer)
    dao.setChildUpdatePending(childId, false)
    val child = dao.getChildByIdOnce(childId)
    if (child != null) {
      val updated = child.copy(appVersion = apkVer, updatePending = false)
      dao.updateChild(updated)
      com.example.data.cloud.RealtimeCloudRelay.publishChildFields(
        childId,
        mapOf(
          "appVersion" to cleanVer,
          "updatePending" to false
        )
      )
    }
  }

  // --- Pillar 1: Защита от удаления и проверка PIN-кода на устройстве ребенка ---
  suspend fun verifyChildUninstallPin(inputPin: String): Boolean {
    val config = dao.getFamilyConfigOnce() ?: return inputPin == "1234"
    return inputPin == config.childUninstallPin || inputPin == config.parentMasterPin
  }

  suspend fun updateChildUninstallPin(newPin: String) {
    dao.updateChildUninstallPin(newPin)
  }

  suspend fun updateAntiUninstall(enabled: Boolean) {
    dao.updateAntiUninstall(enabled)
  }

  suspend fun reportUnauthorizedUninstallAttempt(childId: Long) {
    val child = dao.getChildByIdOnce(childId)
    val childName = child?.name ?: "Ребенок"
    dao.insertAlert(
      AlertNotificationEntity(
        childId = childId,
        type = "UNINSTALL_ATTEMPT_BLOCKED",
        title = "🚨 Блокировка удаления программы!",
        message = "«$childName» попытался отключить или удалить KidsGuard. В доступе отказано: введен неверный пароль. Защита активна.",
        timestamp = System.currentTimeMillis()
      )
    )
  }

  fun triggerDeviceLoudAlarm() {
    com.example.util.SoundAlertManager.playLoudAttentionSignal(context)
  }

  fun stopDeviceLoudAlarm() {
    com.example.util.SoundAlertManager.stopAlarm(context)
  }

  // --- Phase 5: PC & Device Activity History Filtered and Aggregated by Date Range ---
  fun getActivityLogsByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<List<OfflineActivityLogEntity>> =
    dao.getActivityLogsByDateRange(childId, deviceType, startTimestamp, endTimestamp)

  fun getAggregatedProcsByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<List<AggregatedActivityByDateItem>> =
    dao.getAggregatedProcsByDateRange(childId, deviceType, startTimestamp, endTimestamp)

  fun getAggregatedWindowTitlesByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<List<AggregatedActivityByDateItem>> =
    dao.getAggregatedWindowTitlesByDateRange(childId, deviceType, startTimestamp, endTimestamp)

  fun getTotalActiveSecondsByDateRange(
    childId: Long,
    deviceType: String,
    startTimestamp: Long,
    endTimestamp: Long
  ): Flow<Long> =
    dao.getTotalActiveSecondsByDateRange(childId, deviceType, startTimestamp, endTimestamp)
}
