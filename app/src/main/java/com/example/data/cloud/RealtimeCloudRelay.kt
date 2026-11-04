package com.example.data.cloud

import android.util.Log
import com.example.BuildConfig
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import com.example.data.model.LocationBreadcrumb
import com.example.location.LiveLocationTracker
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

private const val TAG = "RealtimeCloudRelay"

/**
 * Pure Socket-First Relay Layer powered by DirectP2pSocketEngine (LAN P2P TCP Socket +
 * Persistent Binary TCP Socket Bus with RETAIN and Per-Child Isolation).
 * Eliminates Firebase quota exhaustion (HTTP 429) and ensures strict separation between Child 1, Child 2, etc.
 */
object RealtimeCloudRelay {
  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(6, TimeUnit.SECONDS)
    .readTimeout(6, TimeUnit.SECONDS)
    .writeTimeout(6, TimeUnit.SECONDS)
    .build()

  private val childrenCache = ConcurrentHashMap<Long, ChildProfileEntity>()
  private val deletedChildIds = ConcurrentHashMap.newKeySet<Long>()
  private val idAliasMap = ConcurrentHashMap<Long, Long>()

  // Authoritative lock state set by Parent or PIN unlock: childId -> Pair(isLocked, timestampMs)
  private val authoritativeLockState = ConcurrentHashMap<Long, Pair<Boolean, Long>>()
  private val authoritativeCoreVersionMap = ConcurrentHashMap<Long, String>()
  @Volatile
  private var latestGlobalLockOverride: Pair<Boolean, Long>? = null

  fun setAuthoritativeChildCoreVersion(childId: Long, versionName: String) {
    val clean = normalizeAppVersion(versionName)
    if (clean.isBlank()) return
    val canonId = resolveCanonicalChildId(childId)
    val targets = linkedSetOf(childId, canonId, 1L, 2L).apply {
      idAliasMap[childId]?.let { add(it) }
      idAliasMap[canonId]?.let { add(it) }
      addAll(childrenCache.keys)
    }
    for (tid in targets) {
      if (tid <= 0L) continue
      val prevVer = authoritativeCoreVersionMap[tid]
      if (prevVer.isNullOrBlank() || com.example.util.ApkUpdateInstaller.compareVersions(clean, prevVer) >= 0) {
        authoritativeCoreVersionMap[tid] = clean
      }
      childrenCache[tid]?.let { prev ->
        if (com.example.util.ApkUpdateInstaller.compareVersions(clean, prev.appVersion) >= 0) {
          childrenCache[tid] = prev.copy(appVersion = clean, updatePending = false)
        }
      }
    }
    _liveChildrenFlow.value = deduplicateChildrenCache()
  }

  fun resolveHighestChildVersion(childId: Long, vararg candidates: String?): String {
    var best = BuildConfig.VERSION_NAME
    val authVer = authoritativeCoreVersionMap[childId]
      ?: idAliasMap[childId]?.let { authoritativeCoreVersionMap[it] }
      ?: ""
    if (authVer.isNotBlank() && com.example.util.ApkUpdateInstaller.compareVersions(authVer, best) > 0) {
      best = authVer
    }
    for (c in candidates) {
      val norm = if (!c.isNullOrBlank()) normalizeAppVersion(c) else ""
      if (norm.isNotBlank()) {
        if (com.example.util.ApkUpdateInstaller.compareVersions(norm, best) > 0) {
          best = norm
        }
      }
    }
    authoritativeCoreVersionMap[childId] = best
    return best
  }

  private val appsCache = ConcurrentHashMap<Long, List<AppControlEntity>>()
  private val historyCache = ConcurrentHashMap<Long, MutableList<LocationBreadcrumb>>()
  private val chatCache = ConcurrentHashMap<Long, MutableList<ChatMessageEntity>>()
  private val alertsCache = ConcurrentHashMap<Long, AlertNotificationEntity>()
  private val deletedAlertTimestamps = ConcurrentHashMap.newKeySet<Long>()
  @Volatile
  private var clearedAllAlertsBeforeMs: Long = 0L
  private val screenBlobCache = ConcurrentHashMap<String, String>()
  private val lastScreenPublishByChild = ConcurrentHashMap<Long, Long>()

  private val _liveChildrenFlow = MutableStateFlow<List<ChildProfileEntity>>(emptyList())
  val liveChildrenFlow: StateFlow<List<ChildProfileEntity>> = _liveChildrenFlow.asStateFlow()

  private val _contentRulesFlow = MutableStateFlow<List<com.example.data.model.ContentRuleEntity>>(emptyList())
  val contentRulesFlow: StateFlow<List<com.example.data.model.ContentRuleEntity>> = _contentRulesFlow.asStateFlow()

  private val _incomingChatsFlow = MutableSharedFlow<ChatMessageEntity>(extraBufferCapacity = 64)
  val incomingChatsFlow: SharedFlow<ChatMessageEntity> = _incomingChatsFlow.asSharedFlow()

  private val _incomingAlertsFlow = MutableSharedFlow<AlertNotificationEntity>(extraBufferCapacity = 64)
  val incomingAlertsFlow: SharedFlow<AlertNotificationEntity> = _incomingAlertsFlow.asSharedFlow()

  fun isRealCustomChildName(name: String?): Boolean {
    val clean = name?.trim().orEmpty()
    return clean.isNotBlank() &&
      !clean.startsWith("Ребёнок #", ignoreCase = true) &&
      !clean.startsWith("Ребенок #", ignoreCase = true) &&
      !clean.equals("Ребёнок", ignoreCase = true) &&
      !clean.equals("Ребенок", ignoreCase = true) &&
      !clean.equals("test", ignoreCase = true)
  }

  fun deduplicateChildrenCache(): List<ChildProfileEntity> {
    val snapshot = childrenCache.values.toList()
    if (snapshot.size <= 1) {
      return snapshot.sortedBy { it.id }
    }
    // Group duplicates by normalized pairingCode or normalized real name
    val canonicalById = LinkedHashMap<Long, ChildProfileEntity>()
    for (item in snapshot.sortedBy { it.id }) {
      val codeClean = item.pairingCode.replace("-", "").replace(" ", "").uppercase()
      val nameClean = item.name.trim().lowercase()
      val realName = isRealCustomChildName(item.name)

      val existingEntry = canonicalById.entries.firstOrNull { (_, c) ->
        val cCode = c.pairingCode.replace("-", "").replace(" ", "").uppercase()
        val cName = c.name.trim().lowercase()
        (codeClean.length >= 6 && cCode == codeClean) ||
          (realName && isRealCustomChildName(c.name) && cName == nameClean)
      }

      if (existingEntry == null) {
        canonicalById[item.id] = item
      } else {
        val canonId = existingEntry.key
        val prev = existingEntry.value
        idAliasMap[item.id] = canonId
        idAliasMap[canonId] = item.id
        // Merge best fields into canonical entry, preferring real name, paired status, and newer pairing code
        val merged = prev.copy(
          name = if (realName) item.name else prev.name,
          age = if (item.age != 10 && item.age > 0) item.age else prev.age,
          deviceModel = if (item.isPaired && !item.deviceModel.contains("Ожидание", ignoreCase = true)) item.deviceModel else prev.deviceModel,
          pairingCode = if (item.pairingCode.isNotBlank() && item.isPaired) item.pairingCode else prev.pairingCode.ifBlank { item.pairingCode },
          isPaired = prev.isPaired || item.isPaired,
          batteryLevel = if (item.batteryLevel in 1..100) item.batteryLevel else prev.batteryLevel,
          lastLocationLat = if (item.lastLocationLat != 0.0) item.lastLocationLat else prev.lastLocationLat,
          lastLocationLng = if (item.lastLocationLng != 0.0) item.lastLocationLng else prev.lastLocationLng,
          lastLocationAddress = if (item.lastLocationLat != 0.0) item.lastLocationAddress else prev.lastLocationAddress,
          lastLocationTime = maxOf(prev.lastLocationTime, item.lastLocationTime),
          appVersion = if (com.example.util.ApkUpdateInstaller.compareVersions(item.appVersion, prev.appVersion) >= 0) item.appVersion else prev.appVersion
        )
        canonicalById[canonId] = merged
        childrenCache[canonId] = merged
        if (item.id != canonId) {
          childrenCache.remove(item.id)
        }
      }
    }
    return canonicalById.values.sortedBy { it.id }
  }

  fun resolveCanonicalChildId(rawId: Long, pairingCode: String = "", childName: String = ""): Long {
    val cleanCode = pairingCode.replace("-", "").replace(" ", "").uppercase()
    if (cleanCode.length >= 6) {
      val matched = childrenCache.values.firstOrNull {
        it.pairingCode.replace("-", "").replace(" ", "").uppercase() == cleanCode
      }
      if (matched != null) {
        if (rawId > 0L && rawId != matched.id) {
          idAliasMap[rawId] = matched.id
          idAliasMap[matched.id] = rawId
        }
        return matched.id
      }
    }
    if (isRealCustomChildName(childName)) {
      val normName = childName.trim().lowercase()
      val matchedByName = childrenCache.values.firstOrNull {
        isRealCustomChildName(it.name) && it.name.trim().lowercase() == normName
      }
      if (matchedByName != null) {
        if (rawId > 0L && rawId != matchedByName.id) {
          idAliasMap[rawId] = matchedByName.id
          idAliasMap[matchedByName.id] = rawId
        }
        return matchedByName.id
      }
    }
    if (rawId > 0L) {
      if (childrenCache.containsKey(rawId)) return rawId
      idAliasMap[rawId]?.let { mapped ->
        if (childrenCache.containsKey(mapped)) return mapped
      }
    }
    if (childrenCache.size == 1 && cleanCode.isEmpty()) {
      val singleId = childrenCache.keys.first()
      if (rawId > 0L && rawId != singleId) {
        idAliasMap[rawId] = singleId
        idAliasMap[singleId] = rawId
      }
      return singleId
    }
    return if (rawId > 0L) rawId else 1L
  }

  fun getAuthoritativeLock(childId: Long): Pair<Boolean, Long>? {
    val direct = authoritativeLockState[childId]
    val alias = idAliasMap[childId]?.let { authoritativeLockState[it] }
    val bestSpecific = listOfNotNull(direct, alias).maxByOrNull { it.second }
    val global = latestGlobalLockOverride
    if (bestSpecific != null && global != null) {
      return if (global.second >= bestSpecific.second) global else bestSpecific
    }
    return bestSpecific ?: global
  }

  fun getCachedChild(childId: Long): ChildProfileEntity? {
    childrenCache[childId]?.let { return it }
    val alias = idAliasMap[childId]
    if (alias != null) {
      childrenCache[alias]?.let { return it }
    }
    if (childrenCache.size == 1) return childrenCache.values.firstOrNull()
    return null
  }

  fun mergeOfflineBreadcrumbs(childId: Long, crumbs: List<LocationBreadcrumb>) {
    val resolvedId = resolveCanonicalChildId(childId)
    if (resolvedId <= 0L || crumbs.isEmpty()) return
    val cutoff24h = System.currentTimeMillis() - 24L * 3600L * 1000L
    val list = historyCache.getOrPut(resolvedId) { mutableListOf() }
    synchronized(list) {
      for (c in crumbs) {
        if (c.timestamp >= cutoff24h && list.none { kotlin.math.abs(it.timestamp - c.timestamp) < 2000L }) {
          list.add(c)
        }
      }
      list.sortBy { it.timestamp }
    }
  }

  fun ingestAuthoritativeLockJson(msg: JSONObject) {
    val rawCid = msg.optLong("childId", msg.optLong("id", 0L))
    val cid = resolveCanonicalChildId(rawCid, msg.optString("pairingCode", ""))
    if (cid <= 0L) return
    val locked = msg.optBoolean("isDeviceLocked", false)
    val now = System.currentTimeMillis()
    val ts = msg.optLong("timestamp", msg.optLong("ts", now))
    // Ignore stale retained MQTT LOCK_STATE packets older than 20s so phone never locks itself on startup/pairing
    if (locked && kotlin.math.abs(now - ts) > 20_000L) return
    val existing = authoritativeLockState[cid]
    if (!locked || existing == null || ts >= existing.second) {
      val pair = Pair(locked, ts)
      latestGlobalLockOverride = pair
      authoritativeLockState[cid] = pair
      if (rawCid > 0L) authoritativeLockState[rawCid] = pair
      authoritativeLockState[1L] = pair
      authoritativeLockState[2L] = pair
      for (k in childrenCache.keys) {
        authoritativeLockState[k] = pair
      }
      childrenCache[cid]?.let { prev ->
        childrenCache[cid] = prev.copy(isDeviceLocked = locked)
        _liveChildrenFlow.value = deduplicateChildrenCache()
      }
    }
  }

  suspend fun publishAuthoritativeLock(childId: Long, locked: Boolean) {
    val targetId = if (childId > 0L) childId else 1L
    val now = System.currentTimeMillis()
    val pair = Pair(locked, now)
    latestGlobalLockOverride = pair
    val aliasId = idAliasMap[targetId]
    val allTargets = linkedSetOf(targetId, 1L, 2L).apply {
      if (aliasId != null && aliasId > 0L) add(aliasId)
      addAll(childrenCache.keys)
      addAll(authoritativeLockState.keys)
    }
    for (tid in allTargets) {
      authoritativeLockState[tid] = pair
    }
    val cachedProfile = childrenCache[targetId] ?: (if (aliasId != null) childrenCache[aliasId] else null) ?: childrenCache.values.firstOrNull()
    cachedProfile?.let { prev ->
      childrenCache[prev.id] = prev.copy(isDeviceLocked = locked)
      _liveChildrenFlow.value = deduplicateChildrenCache()
    }
    val lockJson = JSONObject().apply {
      put("type", "LOCK_STATE")
      put("childId", targetId)
      put("id", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("isDeviceLocked", locked)
      put("timestamp", now)
      put("ts", now)
    }
    // Only retain UNLOCK (false) on broker so stale LOCK (true) never re-locks device on reconnect
    val shouldRetain = !locked
    for (tid in linkedSetOf(targetId, aliasId ?: targetId, 1L, 2L)) {
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_LOCK}/$tid",
        json = JSONObject(lockJson.toString()).apply {
          put("childId", tid)
          put("id", tid)
        },
        retain = shouldRetain
      )
    }
    DirectP2pSocketEngine.pushDirectLanPacket(lockJson)
    DirectP2pSocketEngine.broadcastUdpJson(lockJson)
  }

  fun normalizeAppVersion(ver: String?): String {
    if (ver.isNullOrBlank()) return BuildConfig.VERSION_NAME
    val clean = ver.trim()
    if (clean.startsWith("1.") || clean.startsWith("2.")) {
      return BuildConfig.VERSION_NAME
    }
    return clean
  }

  suspend fun uploadLargeBlob(text: String): String? = withContext(Dispatchers.IO) {
    try {
      val body = text.toRequestBody("text/plain; charset=utf-8".toMediaType())
      val req = Request.Builder()
        .url("https://bytebin.lucko.me/post")
        .header("User-Agent", "KidsGuardPro/3.2")
        .post(body)
        .build()
      httpClient.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          val json = JSONObject(resp.body?.string().orEmpty())
          val key = json.optString("key")
          if (key.isNotBlank()) {
            screenBlobCache[key] = text
            return@withContext key
          }
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "uploadLargeBlob error: ${e.message}")
    }
    null
  }

  suspend fun downloadLargeBlob(key: String): String? = withContext(Dispatchers.IO) {
    screenBlobCache[key]?.let { return@withContext it }
    try {
      val req = Request.Builder()
        .url("https://bytebin.lucko.me/$key")
        .header("User-Agent", "KidsGuardPro/3.2")
        .get()
        .build()
      httpClient.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          val content = resp.body?.string().orEmpty()
          if (content.isNotBlank()) {
            screenBlobCache[key] = content
            return@withContext content
          }
        }
      }
    } catch (e: Exception) {
      Log.d(TAG, "downloadLargeBlob error: ${e.message}")
    }
    null
  }

  fun ingestImmediateStateJson(msg: JSONObject) {
    val type = msg.optString("type")
    val rawId = msg.optLong("id", msg.optLong("childId", 0L))
    val msgCodeClean = msg.optString("pairingCode", "").replace("-", "").replace(" ", "").uppercase()
    val msgName = msg.optString("name", "").trim()
    val matchedByCodeId = if (msgCodeClean.length >= 6) {
      childrenCache.values.firstOrNull {
        it.pairingCode.replace("-", "").replace(" ", "").uppercase() == msgCodeClean
      }?.id
    } else null
    val matchedByNameId = if (matchedByCodeId == null && isRealCustomChildName(msgName)) {
      childrenCache.values.firstOrNull {
        isRealCustomChildName(it.name) && it.name.trim().equals(msgName, ignoreCase = true)
      }?.id
    } else null
    val matchedId = matchedByCodeId ?: matchedByNameId
    if (matchedId != null && rawId > 0L && matchedId != rawId) {
      idAliasMap[rawId] = matchedId
      idAliasMap[matchedId] = rawId
    }
    val id = matchedId ?: (if (rawId > 0L) (idAliasMap[rawId] ?: rawId) else 0L)
    if (id <= 0L) return
    when (type) {
      "LOCK_STATE" -> {
        ingestAuthoritativeLockJson(msg)
        return
      }
      "CHILD_DELETE" -> {
        val deleteTargetId = if (rawId > 0L) rawId else id
        childrenCache.remove(deleteTargetId)
        authoritativeLockState.remove(deleteTargetId)
        appsCache.remove(deleteTargetId)
        historyCache.remove(deleteTargetId)
        chatCache.remove(deleteTargetId)
        DirectP2pSocketEngine.clearDirectFrameForChild(deleteTargetId)
      }
      "CHILD_STATE" -> {
        val isFromChild = msg.optBoolean("isChildDevice", false)
        if (isFromChild) {
          deletedChildIds.remove(id)
          if (rawId > 0L) deletedChildIds.remove(rawId)
        } else if (deletedChildIds.contains(id)) {
          return
        }
        val prev = childrenCache[id]
        val rawLat = msg.optDouble("lastLocationLat", 0.0)
        val rawLng = msg.optDouble("lastLocationLng", 0.0)
        val rawAddr = msg.optString("lastLocationAddress", "")
        val validLoc = !LiveLocationTracker.isEmulatorSyntheticUsLocation(rawLat, rawLng) &&
          rawLat != 0.0 && rawLng != 0.0
        val cleanAddr = if (validLoc) {
          if (rawAddr.isBlank() || LiveLocationTracker.isStaleOrDemoAddress(rawAddr)) {
            "Точные координаты GPS: %.5f, %.5f".format(rawLat, rawLng)
          } else rawAddr
        } else ""
        val rawBatt = msg.optInt("batteryLevel", -1)

        val resolvedBatt = when {
          isFromChild && rawBatt in 1..100 -> rawBatt
          isFromChild && rawBatt == -1 -> prev?.batteryLevel?.takeIf { it in 1..100 } ?: -1
          prev != null && prev.batteryLevel in 1..100 -> prev.batteryLevel
          rawBatt in 1..100 -> rawBatt
          else -> -1
        }
        val authLock = getAuthoritativeLock(id)
        val resolvedLock = authLock?.first ?: false
        val msgAppVer = msg.optString("appVersion").takeIf { it.isNotBlank() }
        val resolvedAppVersion = resolveHighestChildVersion(
          id,
          if (isFromChild) msgAppVer else null,
          prev?.appVersion,
          msgAppVer
        )
        val resolvedUpdatePending = when {
          com.example.util.ApkUpdateInstaller.isSameVersion(resolvedAppVersion, BuildConfig.VERSION_NAME) ||
            com.example.util.ApkUpdateInstaller.compareVersions(resolvedAppVersion, BuildConfig.VERSION_NAME) >= 0 -> false
          msg.has("updatePending") -> msg.optBoolean("updatePending", prev?.updatePending ?: false)
          else -> prev?.updatePending ?: false
        }
        val defaultName = "Ребёнок #$id"
        val resolvedName = when {
          prev != null && prev.name.isNotBlank() && !prev.name.startsWith("Ребёнок #") &&
            (isFromChild && msg.optString("name", "").startsWith("Ребёнок #")) -> prev.name
          !isFromChild && msg.optString("name", "").isNotBlank() && !msg.optString("name", "").startsWith("Ребёнок #") ->
            msg.optString("name")
          else -> msg.optString("name", prev?.name ?: defaultName).ifBlank { prev?.name ?: defaultName }
        }
        val msgAge = msg.optInt("age", prev?.age ?: 10)
        val resolvedAge = when {
          !isFromChild && msg.has("age") && msgAge > 0 -> msgAge
          isFromChild && prev != null && prev.age > 0 && (msgAge == 10 || msg.optString("name", "").startsWith("Ребёнок #")) -> prev.age
          prev != null && !prev.name.startsWith("Ребёнок #") && isFromChild -> prev.age
          else -> msgAge
        }
        // Strict Per-Day Isolation: only accept usedScreenTimeMinutes if the packet is from TODAY
        val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val startOfTodayMs = com.example.util.DeviceUsageTracker.getStartOfTodayMidnightMillis()
        val pktDateIso = msg.optString("usageDateIso", "")
        val pktTs = msg.optLong("ts", msg.optLong("timestamp", 0L))
        val isFromToday = if (pktDateIso.isNotBlank()) {
          pktDateIso == todayIso
        } else {
          pktTs >= startOfTodayMs
        }
        val rawUsedScreenMinutes = if (isFromChild) {
          msg.optInt("usedScreenTimeMinutes", prev?.usedScreenTimeMinutes ?: 0)
        } else {
          prev?.usedScreenTimeMinutes ?: 0
        }
        val resolvedUsedScreenMinutes = if (isFromToday) rawUsedScreenMinutes else 0

        val entity = ChildProfileEntity(
          id = id,
          name = resolvedName,
          age = resolvedAge,
          deviceModel = if (isFromChild) msg.optString("deviceModel", prev?.deviceModel ?: "Смартфон ребёнка") else (prev?.deviceModel ?: msg.optString("deviceModel", "Ожидание подключения")),
          pairingCode = prev?.pairingCode?.takeIf { it.isNotBlank() } ?: msg.optString("pairingCode", "${100 + id}-${700 + id}").ifBlank { "${100 + id}-${700 + id}" },
          isPaired = if (isFromChild) true else (prev?.isPaired ?: msg.optBoolean("isPaired", false)),
          batteryLevel = resolvedBatt,
          isDeviceLocked = resolvedLock,
          dailyScreenLimitMinutes = msg.optInt("dailyScreenLimitMinutes", prev?.dailyScreenLimitMinutes ?: 120),
          usedScreenTimeMinutes = resolvedUsedScreenMinutes,
          studyModeActive = msg.optBoolean("studyModeActive", prev?.studyModeActive ?: false),
          allowMobileData = msg.optBoolean("allowMobileData", prev?.allowMobileData ?: true),
          gpsTrackingEnabled = msg.optBoolean("gpsTrackingEnabled", prev?.gpsTrackingEnabled ?: true),
          colorHex = msg.optLong("colorHex", prev?.colorHex ?: if (id % 2L == 1L) 0xFF0EA5E9 else 0xFF10B981),
          appVersion = resolvedAppVersion,
          updatePending = resolvedUpdatePending,
          lastLocationLat = if (validLoc) rawLat else (prev?.lastLocationLat ?: 0.0),
          lastLocationLng = if (validLoc) rawLng else (prev?.lastLocationLng ?: 0.0),
          lastLocationAddress = if (validLoc) cleanAddr else (prev?.lastLocationAddress ?: "Ожидание точного GPS-сигнала с телефона ребёнка..."),
          lastLocationTime = if (validLoc) msg.optLong("lastLocationTime", System.currentTimeMillis()) else (prev?.lastLocationTime ?: 0L)
        )
        childrenCache[id] = entity
      }
      "CHILD_PATCH" -> {
        if (deletedChildIds.contains(id)) return
        val isFromChild = msg.optBoolean("isChildDevice", false) ||
          msg.has("batteryLevel") ||
          msg.has("lastLocationLat")
        val defaultName = "Ребёнок #$id"
        val prev = childrenCache[id] ?: ChildProfileEntity(
          id = id,
          name = defaultName,
          age = 10,
          deviceModel = "Ожидание подключения",
          pairingCode = msg.optString("pairingCode", "${100 + id}-${700 + id}").ifBlank { "${100 + id}-${700 + id}" },
          isPaired = false,
          batteryLevel = -1,
          appVersion = if (isFromChild) normalizeAppVersion(msg.optString("appVersion", BuildConfig.VERSION_NAME)) else BuildConfig.VERSION_NAME
        )
        var updated = prev
        if (msg.has("name")) {
          val n = msg.optString("name", updated.name)
          if (n.isNotBlank() && (!n.startsWith("Ребёнок #") || updated.name.startsWith("Ребёнок #"))) {
            updated = updated.copy(name = n)
          }
        }
        if (msg.has("pairingCode")) {
          val pc = msg.optString("pairingCode", updated.pairingCode)
          if (pc.isNotBlank()) updated = updated.copy(pairingCode = pc)
        }
        if (msg.has("deviceModel")) updated = updated.copy(deviceModel = msg.optString("deviceModel", updated.deviceModel))
        if (msg.has("isPaired")) updated = updated.copy(isPaired = msg.optBoolean("isPaired", updated.isPaired))
        if (msg.has("isDeviceLocked")) {
          val locked = msg.optBoolean("isDeviceLocked", false)
          val now = System.currentTimeMillis()
          val ts = msg.optLong("ts", now)
          if (!locked || kotlin.math.abs(now - ts) <= 20_000L) {
            val pair = Pair(locked, ts)
            latestGlobalLockOverride = pair
            authoritativeLockState[id] = pair
            updated = updated.copy(isDeviceLocked = locked)
          }
        } else {
          getAuthoritativeLock(id)?.let { auth ->
            updated = updated.copy(isDeviceLocked = auth.first)
          }
        }
        if (msg.has("studyModeActive")) updated = updated.copy(studyModeActive = msg.optBoolean("studyModeActive", updated.studyModeActive))
        if (msg.has("allowMobileData")) updated = updated.copy(allowMobileData = msg.optBoolean("allowMobileData", updated.allowMobileData))
        if (msg.has("gpsTrackingEnabled")) updated = updated.copy(gpsTrackingEnabled = msg.optBoolean("gpsTrackingEnabled", updated.gpsTrackingEnabled))
        if (msg.has("dailyScreenLimitMinutes")) updated = updated.copy(dailyScreenLimitMinutes = msg.optInt("dailyScreenLimitMinutes", updated.dailyScreenLimitMinutes))
        if (msg.has("usedScreenTimeMinutes")) {
          val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
          val startOfTodayMs = com.example.util.DeviceUsageTracker.getStartOfTodayMidnightMillis()
          val pktDateIso = msg.optString("usageDateIso", "")
          val pktTs = msg.optLong("ts", msg.optLong("timestamp", 0L))
          val isFromToday = if (pktDateIso.isNotBlank()) pktDateIso == todayIso else pktTs >= startOfTodayMs
          val incomingUsed = if (isFromToday) msg.optInt("usedScreenTimeMinutes", updated.usedScreenTimeMinutes) else 0
          updated = updated.copy(usedScreenTimeMinutes = incomingUsed)
        }
        if (msg.has("batteryLevel")) {
          val b = msg.optInt("batteryLevel", -1)
          if (b == -1 || b in 1..100) updated = updated.copy(batteryLevel = b)
        }
        if (msg.has("appVersion") && isFromChild) {
          val reportedVer = normalizeAppVersion(msg.optString("appVersion"))
          val highestVer = resolveHighestChildVersion(id, reportedVer, updated.appVersion)
          val isCurrent = com.example.util.ApkUpdateInstaller.compareVersions(highestVer, BuildConfig.VERSION_NAME) >= 0
          updated = updated.copy(
            appVersion = highestVer,
            updatePending = if (isCurrent) false else updated.updatePending
          )
        }
        if (msg.has("updatePending")) {
          val isCurrent = com.example.util.ApkUpdateInstaller.compareVersions(updated.appVersion, BuildConfig.VERSION_NAME) >= 0
          updated = updated.copy(updatePending = if (isCurrent) false else msg.optBoolean("updatePending", false))
        }
        if (msg.has("lastLocationLat") && msg.has("lastLocationLng")) {
          val lat = msg.optDouble("lastLocationLat", 0.0)
          val lng = msg.optDouble("lastLocationLng", 0.0)
          val addr = msg.optString("lastLocationAddress", "")
          if (!LiveLocationTracker.isEmulatorSyntheticUsLocation(lat, lng) && lat != 0.0 && lng != 0.0) {
            updated = updated.copy(
              lastLocationLat = lat,
              lastLocationLng = lng,
              lastLocationAddress = if (LiveLocationTracker.isStaleOrDemoAddress(addr)) "Точные координаты GPS: %.5f, %.5f".format(lat, lng) else addr,
              lastLocationTime = msg.optLong("lastLocationTime", msg.optLong("ts", System.currentTimeMillis()))
            )
          }
        }
        childrenCache[id] = updated
      }
    }
    _liveChildrenFlow.value = deduplicateChildrenCache()
  }

  suspend fun ingestImmediateDataJson(m: JSONObject) {
    val cutoff24h = System.currentTimeMillis() - 24L * 3600L * 1000L
    val rawCid = m.optLong("childId", 1L).coerceAtLeast(1L)
    val cid = resolveCanonicalChildId(rawCid, m.optString("pairingCode", ""))
    when (m.optString("type")) {
      "LOC_HISTORY" -> {
        val lat = m.optDouble("lat", 0.0)
        val lng = m.optDouble("lng", 0.0)
        val ts = m.optLong("timestamp", m.optLong("ts", 0L))
        val addr = m.optString("address", "")
        val speed = m.optDouble("speedKmh", 0.0).toFloat()
        if (ts >= cutoff24h && !LiveLocationTracker.isEmulatorSyntheticUsLocation(lat, lng) && lat != 0.0 && lng != 0.0) {
          val list = historyCache.getOrPut(cid) { mutableListOf() }
          synchronized(list) {
            if (list.none { kotlin.math.abs(it.timestamp - ts) < 2000L }) {
              list.add(
                LocationBreadcrumb(
                  latitude = lat,
                  longitude = lng,
                  timestamp = ts,
                  title = if (LiveLocationTracker.isStaleOrDemoAddress(addr)) "Координаты GPS: %.5f, %.5f".format(lat, lng) else addr,
                  speedKmh = speed
                )
              )
            }
          }
        }
      }
      "APPS_LIST" -> {
        // Strict hardware origin check: reject any legacy/retained APPS_LIST packet that wasn't
        // explicitly stamped by a verified Child device scanning its own PackageManager.
        if (!m.optBoolean("isFromChildHardware", false)) {
          return
        }
        val rawArr = m.optJSONArray("apps")
        val key = m.optString("blobKey", "")
        val jsonText = when {
          rawArr != null -> rawArr.toString()
          key.isNotBlank() -> downloadLargeBlob(key)
          else -> null
        }
        val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val startOfTodayMs = com.example.util.DeviceUsageTracker.getStartOfTodayMidnightMillis()
        val pktDateIso = m.optString("usageDateIso", "")
        val pktTs = m.optLong("timestamp", m.optLong("ts", 0L))
        val isFromToday = if (pktDateIso.isNotBlank()) pktDateIso == todayIso else pktTs >= startOfTodayMs

        if (!jsonText.isNullOrBlank()) {
          try {
            val arr = JSONArray(jsonText)
            val parsed = mutableListOf<AppControlEntity>()
            val seenPkgs = HashSet<String>()
            val seenNames = HashSet<String>()
            for (i in 0 until arr.length()) {
              val o = arr.getJSONObject(i)
              val pkg = o.optString("packageName", "").trim()
              val pkgKey = pkg.lowercase()
              val appName = o.optString("appName", pkg).trim()
              val nameKey = appName.lowercase()
              if (pkg.isNotBlank() &&
                  !pkgKey.contains("kidsguard") &&
                  !nameKey.contains("kidsguard") &&
                  !nameKey.contains("родительский контроль") &&
                  seenPkgs.add(pkgKey) &&
                  seenNames.add(nameKey)
              ) {
                parsed.add(
                  AppControlEntity(
                    id = "${cid}_$pkgKey".hashCode().toLong(),
                    childId = cid,
                    packageName = pkg,
                    appName = appName,
                    category = o.optString("category", "Другое"),
                    dailyLimitMinutes = o.optInt("dailyLimitMinutes", 0),
                    usedMinutesToday = if (isFromToday) o.optInt("usedMinutesToday", 0) else 0,
                    isBlocked = o.optBoolean("isBlocked", false),
                    iconType = o.optString("iconType", "other"),
                    isEducational = o.optBoolean("isEducational", false)
                  )
                )
              }
            }
            if (parsed.isNotEmpty()) {
              appsCache[cid] = parsed
              if (rawCid != cid) {
                appsCache[rawCid] = parsed.map { it.copy(childId = rawCid) }
              }
            }
          } catch (_: Exception) {}
        }
      }
      "APP_PATCH" -> {
        val pkg = m.optString("packageName", "")
        val current = appsCache[cid]?.toMutableList() ?: return
        val idx = current.indexOfFirst { it.packageName == pkg }
        if (idx >= 0) {
          var item = current[idx]
          if (m.has("isBlocked")) item = item.copy(isBlocked = m.optBoolean("isBlocked", item.isBlocked))
          if (m.has("dailyLimitMinutes")) item = item.copy(dailyLimitMinutes = m.optInt("dailyLimitMinutes", item.dailyLimitMinutes))
          current[idx] = item
          appsCache[cid] = current
        }
      }
      "CHAT" -> {
        val ts = m.optLong("timestamp", m.optLong("ts", System.currentTimeMillis()))
        val id = m.optLong("id", ts)
        val senderRole = m.optString("senderRole", m.optString("sender", "PARENT")).ifBlank { "PARENT" }
        val senderName = m.optString("senderName", m.optString("childName", ""))
        val text = m.optString("text", "")
        val isAttention = m.optBoolean("isAttentionSignal", m.optBoolean("attention", false))
        if (text.isNotBlank()) {
          val chatEntity = ChatMessageEntity(
            id = id,
            childId = cid,
            senderRole = senderRole,
            senderName = senderName,
            text = text,
            timestamp = ts,
            isRead = false,
            isAttentionSignal = isAttention
          )
          val list = chatCache.getOrPut(cid) { mutableListOf() }
          var added = false
          synchronized(list) {
            if (list.none { kotlin.math.abs(it.timestamp - ts) < 1500L && it.text == text && it.senderRole == senderRole }) {
              list.add(chatEntity)
              added = true
            }
          }
          if (added) {
            _incomingChatsFlow.tryEmit(chatEntity)
          }
        }
      }
      "ALERT" -> {
        val ts = m.optLong("timestamp", m.optLong("ts", System.currentTimeMillis()))
        val id = m.optLong("id", ts)
        val alertType = m.optString("alertType", "GENERAL")
        val payloadStr = m.optString("payload", "")
        if (alertType == "REMOTE_UPDATE_COMPLETED" && payloadStr.isNotBlank()) {
          setAuthoritativeChildCoreVersion(cid, payloadStr)
        }
        // If SOS carries coordinates ("lat,lng"), also update the cached child location immediately
        if (alertType == "SOS" && payloadStr.contains(",")) {
          val parts = payloadStr.split(",")
          val sosLat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 0.0
          val sosLng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0
          if (sosLat != 0.0 && sosLng != 0.0 && !LiveLocationTracker.isEmulatorSyntheticUsLocation(sosLat, sosLng)) {
            childrenCache[cid]?.let { prev ->
              childrenCache[cid] = prev.copy(
                lastLocationLat = sosLat,
                lastLocationLng = sosLng,
                lastLocationTime = ts
              )
              _liveChildrenFlow.value = deduplicateChildrenCache()
            }
          }
        }
        if (ts <= clearedAllAlertsBeforeMs || deletedAlertTimestamps.any { kotlin.math.abs(it - ts) < 2500L }) {
          return
        }
        val alertEntity = AlertNotificationEntity(
          id = id,
          childId = cid,
          type = alertType,
          title = m.optString("title", ""),
          message = m.optString("message", ""),
          extraPayload = payloadStr,
          timestamp = ts,
          isRead = false
        )
        val isDuplicate = alertsCache.values.any {
          it.type == alertType && it.childId == cid && kotlin.math.abs(it.timestamp - ts) < 1500L
        }
        alertsCache[id] = alertEntity
        if (!isDuplicate) {
          _incomingAlertsFlow.tryEmit(alertEntity)
        }
      }
      "RULES_LIST" -> {
        val arr = m.optJSONArray("rules") ?: return
        val parsed = mutableListOf<com.example.data.model.ContentRuleEntity>()
        for (i in 0 until arr.length()) {
          val o = arr.optJSONObject(i) ?: continue
          val rType = o.optString("ruleType", "")
          val rVal = o.optString("value", "")
          if (rType.isNotBlank() && rVal.isNotBlank()) {
            parsed.add(
              com.example.data.model.ContentRuleEntity(
                id = o.optLong("id", 0L),
                ruleType = rType,
                value = rVal,
                displayName = o.optString("displayName", rVal),
                isBlocked = o.optBoolean("isBlocked", true),
                hitsCount = o.optInt("hitsCount", 0)
              )
            )
          }
        }
        if (parsed.isNotEmpty()) {
          _contentRulesFlow.value = parsed
        }
      }
    }
  }

  suspend fun publishChildProfile(child: ChildProfileEntity, isChildDevice: Boolean) {
    if (child.name.equals("test", ignoreCase = true)) return
    val resolvedId = resolveCanonicalChildId(child.id, child.pairingCode, child.name)
    deletedChildIds.remove(resolvedId)
    val existing = childrenCache[resolvedId] ?: childrenCache[child.id]
    val mergedBattery = when {
      isChildDevice && child.batteryLevel in 1..100 -> child.batteryLevel
      existing != null && existing.batteryLevel in 1..100 -> existing.batteryLevel
      else -> -1
    }
    val hasValidLoc = isChildDevice &&
      !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) &&
      child.lastLocationLat != 0.0 && child.lastLocationLng != 0.0
    val lat = if (hasValidLoc) child.lastLocationLat else (existing?.lastLocationLat ?: 0.0)
    val lng = if (hasValidLoc) child.lastLocationLng else (existing?.lastLocationLng ?: 0.0)
    val addr = if (hasValidLoc) {
      if (child.lastLocationAddress.isBlank() || LiveLocationTracker.isStaleOrDemoAddress(child.lastLocationAddress)) {
        "Точные координаты GPS: %.5f, %.5f".format(lat, lng)
      } else child.lastLocationAddress
    } else (existing?.lastLocationAddress ?: "Ожидание точного GPS-сигнала с телефона ребёнка...")
    val locTime = if (hasValidLoc) child.lastLocationTime else (existing?.lastLocationTime ?: 0L)

    val authLock = getAuthoritativeLock(resolvedId)
    val resolvedLock = when {
      authLock != null -> authLock.first
      !isChildDevice -> child.isDeviceLocked
      existing != null -> existing.isDeviceLocked
      else -> child.isDeviceLocked
    }

    val resolvedName = when {
      isChildDevice && existing != null && !existing.name.startsWith("Ребёнок #") && child.name.startsWith("Ребёнок #") -> existing.name
      else -> child.name
    }
    val resolvedAge = when {
      isChildDevice && existing != null && existing.age != 10 && child.age == 10 -> existing.age
      isChildDevice && existing != null && !existing.name.startsWith("Ребёнок #") && child.name.startsWith("Ребёнок #") -> existing.age
      else -> child.age
    }

    val normalizedVer = normalizeAppVersion(child.appVersion.ifBlank { BuildConfig.VERSION_NAME })
    val updated = child.copy(
      id = resolvedId,
      name = resolvedName,
      age = resolvedAge,
      batteryLevel = mergedBattery,
      isDeviceLocked = resolvedLock,
      lastLocationLat = lat,
      lastLocationLng = lng,
      lastLocationAddress = addr,
      lastLocationTime = locTime,
      appVersion = normalizedVer
    )
    childrenCache[resolvedId] = updated
    if (child.id != resolvedId) {
      childrenCache.remove(child.id)
    }
    _liveChildrenFlow.value = deduplicateChildrenCache()

    val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val json = JSONObject().apply {
      put("type", "CHILD_STATE")
      put("id", updated.id)
      put("usageDateIso", todayIso)
      put("name", updated.name)
      put("age", updated.age)
      put("deviceModel", updated.deviceModel)
      put("pairingCode", updated.pairingCode)
      put("isPaired", updated.isPaired)
      put("batteryLevel", updated.batteryLevel)
      put("isDeviceLocked", updated.isDeviceLocked)
      put("dailyScreenLimitMinutes", updated.dailyScreenLimitMinutes)
      put("usedScreenTimeMinutes", updated.usedScreenTimeMinutes)
      put("studyModeActive", updated.studyModeActive)
      put("allowMobileData", updated.allowMobileData)
      put("gpsTrackingEnabled", updated.gpsTrackingEnabled)
      put("appVersion", updated.appVersion)
      put("updatePending", updated.updatePending)
      put("colorHex", updated.colorHex)
      put("lastLocationLat", updated.lastLocationLat)
      put("lastLocationLng", updated.lastLocationLng)
      put("lastLocationAddress", updated.lastLocationAddress)
      put("lastLocationTime", updated.lastLocationTime)
      put("isChildDevice", isChildDevice)
      if (isChildDevice) {
        put("lanIp", DirectP2pSocketEngine.getLocalIpv4Address())
      }
      put("ts", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/${updated.id}",
      json = json,
      retain = true
    )
    DirectP2pSocketEngine.broadcastUdpJson(json)
  }

  fun broadcastCachedChildrenOverLanUdp(targetIp: String? = null) {
    for (child in childrenCache.values) {
      if (deletedChildIds.contains(child.id)) continue
      val json = JSONObject().apply {
        put("type", "CHILD_STATE")
        put("id", child.id)
        put("name", child.name)
        put("age", child.age)
        put("deviceModel", child.deviceModel)
        put("pairingCode", child.pairingCode)
        put("isPaired", child.isPaired)
        put("batteryLevel", child.batteryLevel)
        put("isDeviceLocked", getAuthoritativeLock(child.id)?.first ?: child.isDeviceLocked)
        put("dailyScreenLimitMinutes", child.dailyScreenLimitMinutes)
        put("usedScreenTimeMinutes", child.usedScreenTimeMinutes)
        put("studyModeActive", child.studyModeActive)
        put("allowMobileData", child.allowMobileData)
        put("gpsTrackingEnabled", child.gpsTrackingEnabled)
        put("appVersion", child.appVersion)
        put("updatePending", child.updatePending)
        put("colorHex", child.colorHex)
        put("lastLocationLat", child.lastLocationLat)
        put("lastLocationLng", child.lastLocationLng)
        put("lastLocationAddress", child.lastLocationAddress)
        put("lastLocationTime", child.lastLocationTime)
        put("isChildDevice", false)
        put("ts", System.currentTimeMillis())
      }
      DirectP2pSocketEngine.broadcastUdpJson(json, targetIp)
    }
  }

  fun rebroadcastAllChildrenForPairing(requesterIp: String? = null) {
    for (child in childrenCache.values) {
      if (deletedChildIds.contains(child.id)) continue
      val json = JSONObject().apply {
        put("type", "CHILD_STATE")
        put("id", child.id)
        put("name", child.name)
        put("age", child.age)
        put("deviceModel", child.deviceModel)
        put("pairingCode", child.pairingCode)
        put("isPaired", child.isPaired)
        put("batteryLevel", child.batteryLevel)
        put("isDeviceLocked", getAuthoritativeLock(child.id)?.first ?: child.isDeviceLocked)
        put("dailyScreenLimitMinutes", child.dailyScreenLimitMinutes)
        put("usedScreenTimeMinutes", child.usedScreenTimeMinutes)
        put("studyModeActive", child.studyModeActive)
        put("allowMobileData", child.allowMobileData)
        put("gpsTrackingEnabled", child.gpsTrackingEnabled)
        put("appVersion", child.appVersion)
        put("updatePending", child.updatePending)
        put("colorHex", child.colorHex)
        put("lastLocationLat", child.lastLocationLat)
        put("lastLocationLng", child.lastLocationLng)
        put("lastLocationAddress", child.lastLocationAddress)
        put("lastLocationTime", child.lastLocationTime)
        put("isChildDevice", false)
        put("ts", System.currentTimeMillis())
      }
      DirectP2pSocketEngine.broadcastUdpJson(json, requesterIp)
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_STATE}/${child.id}",
        json = json,
        retain = true
      )
    }
  }

  suspend fun publishChildFields(childId: Long, fields: Map<String, Any>) {
    val rawId = if (childId > 0) childId else 1L
    val targetId = resolveCanonicalChildId(rawId, (fields["pairingCode"] as? String).orEmpty())
    if (fields.containsKey("isDeviceLocked")) {
      val locked = fields["isDeviceLocked"] as? Boolean ?: false
      publishAuthoritativeLock(targetId, locked)
    }
    val isFromChild = (fields["isChildDevice"] as? Boolean == true) ||
      fields.containsKey("batteryLevel") ||
      fields.containsKey("lastLocationLat")
    val existingProfile = getCachedChild(targetId)
    val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val patchJson = JSONObject().apply {
      put("type", "CHILD_PATCH")
      put("id", targetId)
      put("usageDateIso", todayIso)
      if (!existingProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", existingProfile?.pairingCode)
      }
      for ((k, v) in fields) {
        put(k, v)
      }
      put("isChildDevice", isFromChild)
      put("lanIp", DirectP2pSocketEngine.getLocalIpv4Address())
      put("ts", System.currentTimeMillis())
    }
    ingestImmediateStateJson(patchJson)

    // Publish full merged state to per-child topic with RETAIN=true
    val fullChild = childrenCache[targetId]
    if (fullChild != null) {
      val stateJson = JSONObject().apply {
        put("type", "CHILD_STATE")
        put("id", fullChild.id)
        put("usageDateIso", todayIso)
        put("name", fullChild.name)
        put("age", fullChild.age)
        put("deviceModel", fullChild.deviceModel)
        put("pairingCode", fullChild.pairingCode)
        put("isPaired", fullChild.isPaired)
        put("batteryLevel", fullChild.batteryLevel)
        put("isDeviceLocked", getAuthoritativeLock(targetId)?.first ?: fullChild.isDeviceLocked)
        put("dailyScreenLimitMinutes", fullChild.dailyScreenLimitMinutes)
        put("usedScreenTimeMinutes", fullChild.usedScreenTimeMinutes)
        put("studyModeActive", fullChild.studyModeActive)
        put("allowMobileData", fullChild.allowMobileData)
        put("gpsTrackingEnabled", fullChild.gpsTrackingEnabled)
        put("appVersion", fullChild.appVersion)
        put("updatePending", fullChild.updatePending)
        put("colorHex", fullChild.colorHex)
        put("lastLocationLat", fullChild.lastLocationLat)
        put("lastLocationLng", fullChild.lastLocationLng)
        put("lastLocationAddress", fullChild.lastLocationAddress)
        put("lastLocationTime", fullChild.lastLocationTime)
        put("isChildDevice", isFromChild)
        put("lanIp", DirectP2pSocketEngine.getLocalIpv4Address())
        put("ts", System.currentTimeMillis())
      }
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$targetId",
        json = stateJson,
        retain = true
      )
      DirectP2pSocketEngine.broadcastUdpJson(stateJson)
    } else {
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$targetId",
        json = patchJson,
        retain = true
      )
      DirectP2pSocketEngine.broadcastUdpJson(patchJson)
    }
  }

  suspend fun publishDeleteChild(childId: Long) {
    deletedChildIds.add(childId)
    childrenCache.remove(childId)
    authoritativeLockState.remove(childId)
    appsCache.remove(childId)
    historyCache.remove(childId)
    chatCache.remove(childId)
    lastScreenPublishByChild.remove(childId)
    DirectP2pSocketEngine.clearDirectFrameForChild(childId)
    _liveChildrenFlow.value = childrenCache.values.sortedBy { it.id }
    val json = JSONObject().apply {
      put("type", "CHILD_DELETE")
      put("id", childId)
      put("childId", childId)
      put("ts", System.currentTimeMillis())
    }
    // Publish with retain = true so the broker replaces any retained CHILD_STATE with CHILD_DELETE!
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$childId",
      json = json,
      retain = true
    )
  }

  fun clearAllChildrenCache() {
    childrenCache.clear()
    authoritativeLockState.clear()
    appsCache.clear()
    historyCache.clear()
    chatCache.clear()
    lastScreenPublishByChild.clear()
    DirectP2pSocketEngine.clearAllFrames()
    _liveChildrenFlow.value = emptyList()
  }

  suspend fun pollChildrenFromRelay(): List<ChildProfileEntity> {
    return deduplicateChildrenCache()
  }

  fun updateCachedChildFromFirestore(child: ChildProfileEntity): ChildProfileEntity {
    if (deletedChildIds.contains(child.id) || child.name.equals("test", ignoreCase = true)) return child
    val relayChild = childrenCache[child.id]
    val cleanVer = normalizeAppVersion(relayChild?.appVersion ?: child.appVersion)
    val cleanBattery = when {
      relayChild != null && relayChild.batteryLevel in 1..100 -> relayChild.batteryLevel
      child.batteryLevel in 1..100 -> child.batteryLevel
      else -> -1
    }
    val hasRelayLoc = relayChild != null && relayChild.lastLocationLat != 0.0 && relayChild.lastLocationLng != 0.0
    val authLock = getAuthoritativeLock(child.id)
    val resolvedLock = when {
      authLock != null -> authLock.first
      relayChild != null -> relayChild.isDeviceLocked
      else -> child.isDeviceLocked
    }
    val merged = child.copy(
      name = relayChild?.name?.takeIf { it.isNotBlank() } ?: child.name,
      deviceModel = relayChild?.deviceModel ?: child.deviceModel,
      pairingCode = child.pairingCode.takeIf { it.isNotBlank() } ?: relayChild?.pairingCode.orEmpty(),
      isPaired = relayChild?.isPaired ?: child.isPaired,
      batteryLevel = cleanBattery,
      isDeviceLocked = resolvedLock,
      studyModeActive = relayChild?.studyModeActive ?: child.studyModeActive,
      allowMobileData = relayChild?.allowMobileData ?: child.allowMobileData,
      gpsTrackingEnabled = relayChild?.gpsTrackingEnabled ?: child.gpsTrackingEnabled,
      dailyScreenLimitMinutes = relayChild?.dailyScreenLimitMinutes ?: child.dailyScreenLimitMinutes,
      usedScreenTimeMinutes = relayChild?.usedScreenTimeMinutes ?: child.usedScreenTimeMinutes,
      appVersion = cleanVer,
      lastLocationLat = if (hasRelayLoc) relayChild!!.lastLocationLat else child.lastLocationLat,
      lastLocationLng = if (hasRelayLoc) relayChild!!.lastLocationLng else child.lastLocationLng,
      lastLocationAddress = if (hasRelayLoc) relayChild!!.lastLocationAddress else child.lastLocationAddress,
      lastLocationTime = if (hasRelayLoc) relayChild!!.lastLocationTime else child.lastLocationTime
    )
    childrenCache[child.id] = merged
    _liveChildrenFlow.value = childrenCache.values.sortedBy { it.id }
    return merged
  }

  suspend fun publishRemoteCommand(childId: Long, commandType: String, payload: String) {
    val targetId = if (childId > 0L) childId else 1L
    val aliasId = idAliasMap[targetId]
    if (commandType == "LOCK") {
      publishAuthoritativeLock(targetId, true)
    } else if (commandType == "UNLOCK") {
      publishAuthoritativeLock(targetId, false)
    }
    val cachedProfile = getCachedChild(targetId)
    val json = JSONObject().apply {
      put("type", "CMD")
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("command", commandType)
      put("payload", payload)
      put("timestamp", System.currentTimeMillis())
    }
    val shouldRetain = commandType == "APP_UPDATE" || commandType == "UPDATE_APP" || commandType.isEmpty()
    val targets = if (commandType == "LOCK" || commandType == "UNLOCK") {
      linkedSetOf(targetId, aliasId ?: targetId, 1L, 2L)
    } else {
      linkedSetOf(targetId, aliasId ?: targetId)
    }
    for (tid in targets) {
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_CMD}/$tid",
        json = JSONObject(json.toString()).apply { put("childId", tid) },
        retain = shouldRetain
      )
    }
    DirectP2pSocketEngine.pushDirectLanPacket(json)
    DirectP2pSocketEngine.broadcastUdpJson(json)
  }

  fun observeRemoteCommandsFlow(childId: Long): Flow<Pair<String, String>?> = flow<Pair<String, String>?> {
    while (true) {
      delay(5000L)
    }
  }.flowOn(Dispatchers.IO)

  suspend fun publishScreenSnapshot(childId: Long, activeAppName: String, base64Bitmap: String) {
    val targetId = if (childId > 0L) childId else 1L
    val now = System.currentTimeMillis()
    val prevMs = lastScreenPublishByChild[targetId] ?: 0L
    if (now - prevMs < 950L) return
    lastScreenPublishByChild[targetId] = now
    // Send frame directly inside the binary TCP socket packet (with RETAIN=true on per-child topic) + LAN P2P socket!
    DirectP2pSocketEngine.pushDirectScreenFrame(targetId, activeAppName, base64Bitmap)
  }

  suspend fun publishActiveAppOnly(childId: Long, activeAppName: String) {
    val targetId = if (childId > 0L) childId else 1L
    val prevB64 = DirectP2pSocketEngine.getLatestDirectFrameForChild(targetId)?.second.orEmpty()
    val cachedProfile = getCachedChild(targetId)
    val json = JSONObject().apply {
      put("type", "DIRECT_SCREEN_FRAME")
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("activeAppName", activeAppName)
      if (prevB64.isNotBlank()) put("base64Bitmap", prevB64)
      put("lanIp", DirectP2pSocketEngine.getLocalIpv4Address())
      put("timestamp", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_SCREEN}/$targetId",
      json = json,
      retain = false
    )
  }

  suspend fun pollLatestScreenSnapshot(childId: Long): Triple<String, String, Long>? {
    val targetId = if (childId > 0L) childId else 1L
    return DirectP2pSocketEngine.getLatestDirectFrameForChild(targetId)
  }

  suspend fun publishLocationBreadcrumb(
    childId: Long,
    lat: Double,
    lng: Double,
    address: String,
    speedKmh: Float,
    timestamp: Long
  ) {
    val targetId = resolveCanonicalChildId(if (childId > 0L) childId else 1L)
    val list = historyCache.getOrPut(targetId) { mutableListOf() }
    val crumb = LocationBreadcrumb(
      latitude = lat,
      longitude = lng,
      timestamp = timestamp,
      title = address,
      speedKmh = speedKmh
    )
    synchronized(list) {
      list.add(crumb)
    }
    val cachedProfile = getCachedChild(targetId)
    val json = JSONObject().apply {
      put("type", "LOC_HISTORY")
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("lat", lat)
      put("lng", lng)
      put("address", address)
      put("speedKmh", speedKmh.toDouble())
      put("timestamp", timestamp)
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/loc_$targetId",
      json = json,
      retain = true
    )
  }

  suspend fun publishChildApps(childId: Long, apps: List<AppControlEntity>) {
    // Architectural Guard: Parent device must NEVER publish an APPS_LIST packet!
    if (DirectP2pSocketEngine.currentRole != "CHILD") return
    val ctx = DirectP2pSocketEngine.appContext
    val installedSet = if (ctx != null) {
      com.example.util.DeviceAppsProvider.getInstalledPackageSet(ctx)
    } else emptySet()
    val verifiedApps = if (installedSet.isNotEmpty()) {
      apps.filter { it.packageName.trim().lowercase() in installedSet }
    } else apps
    if (verifiedApps.isEmpty()) return

    val targetId = resolveCanonicalChildId(if (childId > 0L) childId else 1L)
    appsCache[targetId] = verifiedApps
    val arr = JSONArray()
    for (app in verifiedApps) {
      arr.put(
        JSONObject().apply {
          put("packageName", app.packageName)
          put("appName", app.appName)
          put("category", app.category)
          put("dailyLimitMinutes", app.dailyLimitMinutes)
          put("usedMinutesToday", app.usedMinutesToday)
          put("isBlocked", app.isBlocked)
          put("iconType", app.iconType)
          put("isEducational", app.isEducational)
        }
      )
    }
    val cachedProfile = getCachedChild(targetId)
    val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
    val json = JSONObject().apply {
      put("type", "APPS_LIST")
      put("childId", targetId)
      put("isFromChildHardware", true)
      put("usageDateIso", todayIso)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("apps", arr)
      put("timestamp", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/apps_$targetId",
      json = json,
      retain = true
    )
  }

  suspend fun publishAppUpdate(childId: Long, packageName: String, isBlocked: Boolean?, limitMinutes: Int?) {
    val targetId = resolveCanonicalChildId(if (childId > 0L) childId else 1L)
    val cachedProfile = getCachedChild(targetId)
    val json = JSONObject().apply {
      put("type", "APP_PATCH")
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("packageName", packageName)
      if (isBlocked != null) put("isBlocked", isBlocked)
      if (limitMinutes != null) put("dailyLimitMinutes", limitMinutes)
      put("timestamp", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/$targetId",
      json = json,
      retain = false
    )
  }

  suspend fun publishChatMessage(msg: ChatMessageEntity) {
    val targetId = resolveCanonicalChildId(if (msg.childId > 0L) msg.childId else 1L)
    val cachedProfile = getCachedChild(targetId)
    val effectiveId = msg.id.takeIf { it > 0 } ?: System.currentTimeMillis()
    val storedMsg = msg.copy(id = effectiveId, childId = targetId)
    val list = chatCache.getOrPut(targetId) { mutableListOf() }
    synchronized(list) {
      if (list.none { kotlin.math.abs(it.timestamp - storedMsg.timestamp) < 1500L && it.text == storedMsg.text && it.senderRole == storedMsg.senderRole }) {
        list.add(storedMsg)
      }
    }
    val json = JSONObject().apply {
      put("type", "CHAT")
      put("id", effectiveId)
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      if (!cachedProfile?.name.isNullOrBlank()) {
        put("childName", cachedProfile?.name)
      }
      put("senderRole", msg.senderRole)
      put("sender", msg.senderRole)
      put("senderName", msg.senderName)
      put("text", msg.text)
      put("timestamp", msg.timestamp)
      put("isAttentionSignal", msg.isAttentionSignal)
      put("attention", msg.isAttentionSignal)
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/chat_$targetId",
      json = json,
      retain = true
    )
    DirectP2pSocketEngine.pushDirectLanPacket(json)
    DirectP2pSocketEngine.broadcastUdpJson(json)
  }

  suspend fun publishAlert(alert: AlertNotificationEntity) {
    val targetId = resolveCanonicalChildId(if (alert.childId > 0L) alert.childId else 1L)
    val cachedProfile = getCachedChild(targetId)
    val effectiveId = alert.id.takeIf { it > 0 } ?: System.currentTimeMillis()
    alertsCache[effectiveId] = alert.copy(id = effectiveId, childId = targetId)
    val json = JSONObject().apply {
      put("type", "ALERT")
      put("id", effectiveId)
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      if (!cachedProfile?.name.isNullOrBlank()) {
        put("childName", cachedProfile?.name)
      }
      put("alertType", alert.type)
      put("title", alert.title)
      put("message", alert.message)
      put("payload", alert.extraPayload)
      put("timestamp", alert.timestamp)
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/alert_$targetId",
      json = json,
      retain = true
    )
    DirectP2pSocketEngine.pushDirectLanPacket(json)
    DirectP2pSocketEngine.broadcastUdpJson(json)
  }

  suspend fun pollDataTopic() {
    // Handled instantaneously by DirectP2pSocketEngine binary TCP socket stream
  }

  fun getCachedHistory24h(childId: Long): List<LocationBreadcrumb> {
    val targetId = resolveCanonicalChildId(if (childId > 0L) childId else 1L)
    val all = mutableListOf<LocationBreadcrumb>()
    historyCache[targetId]?.let { synchronized(it) { all.addAll(it) } }
    if (all.isEmpty()) {
      idAliasMap[targetId]?.let { alias ->
        historyCache[alias]?.let { synchronized(it) { all.addAll(it) } }
      }
    }
    if (all.isEmpty() && historyCache.size == 1) {
      historyCache.values.firstOrNull()?.let { synchronized(it) { all.addAll(it) } }
    }
    val cutoff = System.currentTimeMillis() - 24L * 3600L * 1000L
    return all.filter { it.timestamp >= cutoff }.distinctBy { it.timestamp / 2000L }.sortedBy { it.timestamp }
  }

  fun getCachedApps(childId: Long): List<AppControlEntity> {
    val targetId = resolveCanonicalChildId(if (childId > 0L) childId else 1L)
    appsCache[targetId]?.let { if (it.isNotEmpty()) return it.map { a -> a.copy(childId = childId) } }
    idAliasMap[targetId]?.let { alias ->
      appsCache[alias]?.let { if (it.isNotEmpty()) return it.map { a -> a.copy(childId = childId) } }
    }
    if (appsCache.size == 1) {
      appsCache.values.firstOrNull()?.let { if (it.isNotEmpty()) return it.map { a -> a.copy(childId = childId) } }
    }
    return emptyList()
  }

  fun getCachedChats(childId: Long): List<ChatMessageEntity> {
    val targetId = resolveCanonicalChildId(if (childId > 0L) childId else 1L)
    val list = chatCache[targetId]
      ?: idAliasMap[targetId]?.let { chatCache[it] }
      ?: (if (chatCache.size == 1) chatCache.values.firstOrNull() else null)
      ?: return emptyList()
    synchronized(list) {
      return list.map { it.copy(childId = childId) }.sortedBy { it.timestamp }
    }
  }

  fun getCachedAlerts(): List<AlertNotificationEntity> {
    return alertsCache.values
      .filterNot { alert ->
        alert.timestamp <= clearedAllAlertsBeforeMs ||
          deletedAlertTimestamps.any { kotlin.math.abs(it - alert.timestamp) < 2500L }
      }
      .sortedByDescending { it.timestamp }
  }

  fun markAlertDeleted(alertId: Long, timestamp: Long) {
    if (timestamp > 0L) {
      deletedAlertTimestamps.add(timestamp)
    }
    alertsCache.remove(alertId)
    val matchingKeys = alertsCache.entries
      .filter { it.key == alertId || (timestamp > 0L && kotlin.math.abs(it.value.timestamp - timestamp) < 2500L) }
      .map { it.key }
    for (k in matchingKeys) {
      alertsCache.remove(k)
    }
  }

  fun clearAllAlertsCache() {
    val now = System.currentTimeMillis()
    clearedAllAlertsBeforeMs = now
    for (alert in alertsCache.values) {
      deletedAlertTimestamps.add(alert.timestamp)
    }
    alertsCache.clear()
  }

  suspend fun publishContentRules(rules: List<com.example.data.model.ContentRuleEntity>) {
    _contentRulesFlow.value = rules
    val arr = JSONArray()
    for (r in rules) {
      arr.put(
        JSONObject().apply {
          put("id", r.id)
          put("ruleType", r.ruleType)
          put("value", r.value)
          put("displayName", r.displayName)
          put("isBlocked", r.isBlocked)
          put("hitsCount", r.hitsCount)
        }
      )
    }
    val json = JSONObject().apply {
      put("type", "RULES_LIST")
      put("rules", arr)
      put("timestamp", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/rules",
      json = json,
      retain = true
    )
  }
}
