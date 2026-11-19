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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
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
  // 15-секундная защита: после LOCK/UNLOCK игнорируем isDeviceLocked из входящих
  // CHILD_STATE/CHILD_PATCH, чтобы старый пакет не откатывал только что нажатую кнопку.
  private val lockToggleGraceUntilMs = ConcurrentHashMap<Long, Long>()
  private val authoritativeCoreVersionMap = ConcurrentHashMap<Long, String>()
  // Монотонная отметка времени последнего свежего пакета телеметрии от ребёнка (защита от 75% -> 78% -> 75%)
  private val lastChildTelemetryTsByChild = ConcurrentHashMap<Long, Long>()
  // Авторитетные переключатели родителя (GPS, Wi-Fi+4G, Учёба, Лимит), которые телеметрия ребёнка НЕ имеет права сбрасывать
  data class ParentAuthoritativeToggles(
    val allowMobileData: Boolean? = null,
    val gpsTrackingEnabled: Boolean? = null,
    val studyModeActive: Boolean? = null,
    val dailyScreenLimitMinutes: Int? = null,
    val updatedAtMs: Long = 0L
  )
  private val authoritativeParentToggles = ConcurrentHashMap<Long, ParentAuthoritativeToggles>()
  // Авторитетные блокировки приложений родителя: "{childId}_{pkg}" -> Triple(isBlocked, dailyLimitMinutes, ts)
  private val authoritativeAppRules = ConcurrentHashMap<String, Triple<Boolean?, Int?, Long>>()
  @Volatile
  private var latestGlobalLockOverride: Pair<Boolean, Long>? = null

  fun setAuthoritativeChildCoreVersion(childId: Long, versionName: String) {
    // Пишем версию СТРОГО в этого ребёнка (и его канонический id), без рассылки
    // в 1L/2L/всех детей — иначе версия одного ребёнка затирает остальных.
    val clean = versionName?.trim().orEmpty()
    if (clean.isBlank() || clean.startsWith("1.") || clean.startsWith("2.")) return
    val canonId = resolveCanonicalChildId(childId)
    for (tid in linkedSetOf(childId, canonId)) {
      if (tid <= 0L) continue
      val prevVer = authoritativeCoreVersionMap[tid]
      if (prevVer.isNullOrBlank() || com.example.util.ApkUpdateInstaller.compareVersions(clean, prevVer) >= 0) {
        authoritativeCoreVersionMap[tid] = clean
      }
      childrenCache[tid]?.let { prev ->
        childrenCache[tid] = prev.copy(updatePending = false)
      }
    }
    _liveChildrenFlow.value = deduplicateChildrenCache()
  }

  fun getAuthoritativeParentToggle(childId: Long): ParentAuthoritativeToggles? {
    val direct = authoritativeParentToggles[childId]
    val alias = idAliasMap[childId]?.let { authoritativeParentToggles[it] }
    return listOfNotNull(direct, alias).maxByOrNull { it.updatedAtMs }
  }

  fun resolveHighestChildVersion(childId: Long, vararg candidates: String?): String {
    // Фактическая версия ребёнка. Версию родителя (BuildConfig) НЕ берём за базу и
    // НЕ выбираем максимум — иначе старая версия ребёнка (Pro.39) никогда не отображается.
    val authVer = authoritativeCoreVersionMap[childId]
      ?: idAliasMap[childId]?.let { authoritativeCoreVersionMap[it] }
      ?: ""
    var best = if (authVer.isNotBlank()) authVer else ""
    for (c in candidates) {
      val norm = c?.trim().orEmpty()
      if (norm.isBlank() || norm.startsWith("1.") || norm.startsWith("2.")) continue
      if (best.isBlank() || com.example.util.ApkUpdateInstaller.compareVersions(norm, best) > 0) {
        best = norm
      }
    }
    val resolved = best.ifBlank { BuildConfig.VERSION_NAME }
    if (resolved != BuildConfig.VERSION_NAME) {
      authoritativeCoreVersionMap[childId] = resolved
    }
    return resolved
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

  // Честный онлайн телефона ребёнка в реальном времени:
  // Родитель шлёт PING каждые 8 с, ребёнок отвечает CHILD_PONG (а при пропаже сети или
  // остановке отправляет CHILD_OFFLINE / MQTT Last Will). Нет свежих пакетов 28 с = Оффлайн.
  private const val CHILD_ONLINE_TIMEOUT_MS = 28_000L
  private val childLastSeenMs = ConcurrentHashMap<Long, Long>()
  private val _childOnlineFlow = MutableStateFlow<Map<Long, Boolean>>(emptyMap())
  val childOnlineFlow: StateFlow<Map<Long, Boolean>> = _childOnlineFlow.asStateFlow()
  private val presenceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  init {
    presenceScope.launch {
      while (isActive) {
        delay(2_000L)
        recomputeChildPresence()
      }
    }
  }

  private fun markChildSeen(childId: Long) {
    if (childId <= 0L) return
    val now = System.currentTimeMillis()
    childLastSeenMs[childId] = now
    try {
      val canon = resolveCanonicalChildId(childId)
      if (canon > 0L) childLastSeenMs[canon] = now
    } catch (_: Exception) {
    }
    recomputeChildPresence()
  }

  fun markChildOfflineImmediate(childId: Long) {
    if (childId <= 0L) return
    childLastSeenMs.remove(childId)
    val canon = try { resolveCanonicalChildId(childId) } catch (_: Exception) { childId }
    if (canon > 0L) childLastSeenMs.remove(canon)
    DirectP2pSocketEngine.clearDirectFrameForChild(childId)
    if (canon > 0L && canon != childId) {
      DirectP2pSocketEngine.clearDirectFrameForChild(canon)
    }
    val updated = HashMap(_childOnlineFlow.value)
    updated[childId] = false
    if (canon > 0L) updated[canon] = false
    _childOnlineFlow.value = updated
  }

  fun isChildOnline(childId: Long): Boolean {
    if (childId <= 0L) return false
    val last = childLastSeenMs[childId]
      ?: idAliasMap[childId]?.let { childLastSeenMs[it] }
      ?: childrenCache[childId]?.let { c -> idAliasMap[c.id]?.let { childLastSeenMs[it] } }
      ?: return false
    return System.currentTimeMillis() - last <= CHILD_ONLINE_TIMEOUT_MS
  }

  private fun recomputeChildPresence() {
    val now = System.currentTimeMillis()
    val snapshot = HashMap<Long, Boolean>()
    for ((id, last) in childLastSeenMs) {
      if (now - last <= 5 * 60_000L) {
        snapshot[id] = now - last <= CHILD_ONLINE_TIMEOUT_MS
      } else {
        childLastSeenMs.remove(id)
      }
    }
    if (snapshot != _childOnlineFlow.value) {
      for ((id, on) in snapshot) {
        if (_childOnlineFlow.value[id] == true && !on) {
          // Ребёнок ушёл оффлайн — стираем старый кадр экрана.
          DirectP2pSocketEngine.clearDirectFrameForChild(id)
        }
      }
      _childOnlineFlow.value = snapshot
    }
  }

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
    // Group duplicates STRICTLY by normalized 6-digit pairingCode (NEVER by name alone when pairingCode differs!)
    val canonicalById = LinkedHashMap<Long, ChildProfileEntity>()
    for (item in snapshot.sortedBy { it.id }) {
      val codeClean = item.pairingCode.replace("-", "").replace(" ", "").uppercase()
      val realName = isRealCustomChildName(item.name)

      val existingEntry = canonicalById.entries.firstOrNull { (_, c) ->
        val cCode = c.pairingCode.replace("-", "").replace(" ", "").uppercase()
        codeClean.length >= 6 && cCode == codeClean
      }

      if (existingEntry == null) {
        canonicalById[item.id] = item
      } else {
        val canonId = existingEntry.key
        val prev = existingEntry.value
        idAliasMap[item.id] = canonId
        idAliasMap[canonId] = item.id
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
    if (rawId > 0L) {
      if (childrenCache.containsKey(rawId)) return rawId
      idAliasMap[rawId]?.let { mapped ->
        if (childrenCache.containsKey(mapped)) return mapped
      }
    }
    return if (rawId > 0L) rawId else 1L
  }

  fun setAuthoritativeParentToggle(
    childId: Long,
    allowMobileData: Boolean? = null,
    gpsTrackingEnabled: Boolean? = null,
    studyModeActive: Boolean? = null,
    dailyScreenLimitMinutes: Int? = null
  ) {
    val targetId = if (childId > 0L) childId else 1L
    val canonId = resolveCanonicalChildId(targetId)
    val now = System.currentTimeMillis()
    for (tid in linkedSetOf(targetId, canonId)) {
      if (tid <= 0L) continue
      val prev = authoritativeParentToggles[tid] ?: ParentAuthoritativeToggles()
      authoritativeParentToggles[tid] = prev.copy(
        allowMobileData = allowMobileData ?: prev.allowMobileData,
        gpsTrackingEnabled = gpsTrackingEnabled ?: prev.gpsTrackingEnabled,
        studyModeActive = studyModeActive ?: prev.studyModeActive,
        dailyScreenLimitMinutes = dailyScreenLimitMinutes ?: prev.dailyScreenLimitMinutes,
        updatedAtMs = now
      )
      childrenCache[tid]?.let { existing ->
        childrenCache[tid] = existing.copy(
          allowMobileData = allowMobileData ?: existing.allowMobileData,
          gpsTrackingEnabled = gpsTrackingEnabled ?: existing.gpsTrackingEnabled,
          studyModeActive = studyModeActive ?: existing.studyModeActive,
          dailyScreenLimitMinutes = dailyScreenLimitMinutes ?: existing.dailyScreenLimitMinutes
        )
      }
    }
    _liveChildrenFlow.value = deduplicateChildrenCache()
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

  fun setLocalAuthoritativeLock(childId: Long, locked: Boolean, ts: Long = System.currentTimeMillis()) {
    val targetId = if (childId > 0L) childId else 1L
    val now = System.currentTimeMillis()
    val effectiveTs = if (ts > 0L) ts else now
    val pair = Pair(locked, effectiveTs)
    latestGlobalLockOverride = pair
    val aliasId = idAliasMap[targetId]
    val allTargets = linkedSetOf(targetId).apply {
      if (aliasId != null && aliasId > 0L) add(aliasId)
      if (childrenCache.size <= 1) {
        add(1L)
        add(2L)
        addAll(childrenCache.keys)
      }
    }
    for (tid in allTargets) {
      authoritativeLockState[tid] = pair
      lockToggleGraceUntilMs[tid] = now + 20_000L
    }
    val cachedProfile = childrenCache[targetId] ?: (if (aliasId != null) childrenCache[aliasId] else null) ?: childrenCache.values.firstOrNull()
    cachedProfile?.let { prev ->
      childrenCache[prev.id] = prev.copy(isDeviceLocked = locked)
      _liveChildrenFlow.value = deduplicateChildrenCache()
    }
  }

  fun ingestAuthoritativeLockJson(msg: JSONObject) {
    val rawCid = msg.optLong("childId", msg.optLong("id", 0L))
    val cid = resolveCanonicalChildId(rawCid, msg.optString("pairingCode", ""))
    if (cid <= 0L) return
    val locked = msg.optBoolean("isDeviceLocked", false)
    val now = System.currentTimeMillis()
    val ts = msg.optLong("timestamp", msg.optLong("ts", now))
    if (kotlin.math.abs(now - ts) > 90_000L) return
    val existing = authoritativeLockState[cid]
    val inGrace = now < (lockToggleGraceUntilMs[cid] ?: 0L)
    if (inGrace && existing != null && locked != existing.first && ts <= existing.second + 2_000L) {
      return
    }
    if (existing == null || ts >= existing.second) {
      setLocalAuthoritativeLock(cid, locked, ts)
    }
  }

  suspend fun publishAuthoritativeLock(childId: Long, locked: Boolean) {
    val targetId = if (childId > 0L) childId else 1L
    val now = System.currentTimeMillis()
    val existing = authoritativeLockState[targetId]
    val alreadySentRecently = existing != null && existing.first == locked && (now - existing.second) in 0..1_200L
    setLocalAuthoritativeLock(targetId, locked, now)
    if (alreadySentRecently) return

    val aliasId = idAliasMap[targetId]
    val cachedProfile = childrenCache[targetId] ?: (if (aliasId != null) childrenCache[aliasId] else null) ?: childrenCache.values.firstOrNull()
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
    val targets = linkedSetOf(targetId).apply {
      if (aliasId != null && aliasId > 0L) add(aliasId)
      if (childrenCache.size <= 1) add(1L)
    }
    for (tid in targets) {
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_LOCK}/$tid",
        json = JSONObject(lockJson.toString()).apply {
          put("childId", tid)
          put("id", tid)
        },
        retain = false
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

  // ID профилей детей, которыми родитель активно управляет в текущей сессии
  private val authorizedChildIds = ConcurrentHashMap.newKeySet<Long>()
  // Анти-дребезг исходящих команд родителя: "${childId}_${cmd}_${payload}" -> timestampMs
  private val lastOutgoingCmdTs = ConcurrentHashMap<String, Long>()

  fun authorizeChildId(childId: Long) {
    if (childId > 0L) {
      authorizedChildIds.add(childId)
    }
  }

  fun isAuthorizedChild(childId: Long): Boolean = authorizedChildIds.contains(childId)

  fun ingestImmediateStateJson(msg: JSONObject) {
    val type = msg.optString("type")
    val rawId = msg.optLong("id", msg.optLong("childId", 0L))
    val msgCodeRaw = msg.optString("pairingCode", "").trim()
    val msgCodeClean = msgCodeRaw.replace("-", "").replace(" ", "").uppercase()
    // Строгая привязка по 6-значному проверочному коду
    val matchedByCodeId = if (msgCodeClean.length >= 6) {
      childrenCache.values.firstOrNull {
        it.pairingCode.replace("-", "").replace(" ", "").uppercase() == msgCodeClean
      }?.id
    } else null
    if (matchedByCodeId != null && rawId > 0L && matchedByCodeId != rawId) {
      idAliasMap[rawId] = matchedByCodeId
      idAliasMap[matchedByCodeId] = rawId
    }
    val id = when {
      matchedByCodeId != null -> matchedByCodeId
      rawId > 0L && childrenCache.containsKey(rawId) -> rawId
      rawId > 0L -> idAliasMap[rawId] ?: rawId
      else -> 0L
    }
    if (id <= 0L) return
    val now = System.currentTimeMillis()
    val pktTs = msg.optLong("ts", msg.optLong("timestamp", 0L))

    when (type) {
      "LOCK_STATE" -> {
        ingestAuthoritativeLockJson(msg)
        return
      }
      "CHILD_OFFLINE" -> {
        com.example.util.DeviceControlDebugger.logEvent(
          "СТАТУС_ОФФЛАЙН",
          "Телефон ребёнка #$id сообщил об уходе в Оффлайн (сеть отключена или сервис остановлен)",
          "WARNING",
          "P2P"
        )
        markChildOfflineImmediate(id)
        return
      }
      "CHILD_DELETE" -> {
        val deleteTargetId = if (rawId > 0L) rawId else id
        childrenCache.remove(deleteTargetId)
        authorizedChildIds.remove(deleteTargetId)
        authoritativeLockState.remove(deleteTargetId)
        lastChildTelemetryTsByChild.remove(deleteTargetId)
        authoritativeParentToggles.remove(deleteTargetId)
        appsCache.remove(deleteTargetId)
        historyCache.remove(deleteTargetId)
        chatCache.remove(deleteTargetId)
        markChildOfflineImmediate(deleteTargetId)
      }
      "CHILD_PONG" -> {
        // Лёгкий ответ на PING: принимаем только свежий пакет (не старше 35 с).
        val ageMs = if (pktTs > 0L) kotlin.math.abs(now - pktTs) else 0L
        if (pktTs <= 0L || ageMs <= 35_000L) {
          val wasOnline = isChildOnline(id)
          authorizeChildId(id)
          if (rawId > 0L) authorizeChildId(rawId)
          markChildSeen(id)
          if (!wasOnline) {
            com.example.util.DeviceControlDebugger.logEvent(
              "СТАТУС_ОНЛАЙН",
              "Телефон ребёнка #$id вышел на связь (CHILD_PONG, задержка ${ageMs} мс)",
              "SUCCESS",
              "P2P"
            )
          }
        } else {
          com.example.util.DeviceControlDebugger.logEvent(
            "СТАРЫЙ_PONG_ОТБРОШЕН",
            "Отброшен устаревший CHILD_PONG от ребёнка #$id (возраст ${ageMs / 1000} с)",
            "WARNING",
            "P2P"
          )
        }
        return
      }
      "CHILD_STATE" -> {
        val isFromChild = msg.optBoolean("isChildDevice", false)
        // Защита от вчерашних retained-пакетов с брокеров (78% заряда и ложный Онлайн):
        // если пакет от ребёнка старше 45 секунд — отбрасываем его!
        if (isFromChild) {
          val ageMs = if (pktTs > 0L) kotlin.math.abs(now - pktTs) else 0L
          if (pktTs > 0L && ageMs > 45_000L) {
            com.example.util.DeviceControlDebugger.logEvent(
              "СТАРЫЙ_ПАКЕТ_ОТБРОШЕН",
              "Отброшен устаревший CHILD_STATE от #$id (возраст ${ageMs / 1000} с, батарея=${msg.optInt("batteryLevel", -1)}%)",
              "BLOCKED",
              "P2P"
            )
            return
          }
          val lastTs = lastChildTelemetryTsByChild[id] ?: 0L
          if (pktTs > 0L && lastTs > 0L && pktTs < lastTs - 1500L && (now - lastTs) < 120_000L) {
            com.example.util.DeviceControlDebugger.logEvent(
              "ДУБЛЬ_БРОКЕРА_ОТБРОШЕН",
              "Отброшен запаздывающий CHILD_STATE со второго брокера (батарея=${msg.optInt("batteryLevel", -1)}%, старше на ${lastTs - pktTs} мс)",
              "INTERCEPTED",
              "P2P"
            )
            return
          }
        } else if (deletedChildIds.contains(id)) {
          return
        }

        var prev = childrenCache[id]
        val isAuthorizedTarget = authorizedChildIds.contains(id) || (rawId > 0L && authorizedChildIds.contains(rawId))
        // На телефоне родителя НЕ создаём чужой/вчерашний профиль из брокера, если его нет локально, код не совпал и родитель к нему не обращался!
        if (DirectP2pSocketEngine.currentRole != "CHILD" && prev == null && matchedByCodeId == null && !isAuthorizedTarget && isFromChild) {
          com.example.util.DeviceControlDebugger.logEvent(
            "ЧУЖОЙ_ПРОФИЛЬ_ОТБРОШЕН",
            "Игнорирован пакет CHILD_STATE без совпадающего 6-значного кода сопряжения (код пакета: $msgCodeClean)",
            "BLOCKED",
            "P2P"
          )
          return
        }
        // Строгая защита 6-значным кодом привязки на телефоне родителя (Мать и Отец):
        // НИКОГДА автоматически не подменяем локальный 6-значный код чужим кодом из эфира только из-за совпадения ID (например, id=1 или id=2)!
        // Подключение второго родителя к телефону ребёнка разрешено ТОЛЬКО при точном совпадении 6-значного кода привязки.
        if (DirectP2pSocketEngine.currentRole != "CHILD" && prev != null && isFromChild) {
          val localCode = prev.pairingCode.replace("-", "").replace(" ", "").uppercase()
          if (localCode.length >= 6 && msgCodeClean.length >= 6 && localCode != msgCodeClean) {
            com.example.util.DeviceControlDebugger.logEvent(
              "НЕСОВПАДЕНИЕ_КОДА",
              "Отброшен пакет CHILD_STATE с чужим кодом $msgCodeClean (в профиле родителя код $localCode). Для подключения второго родителя введите 6-значный код ребёнка.",
              "BLOCKED",
              "P2P"
            )
            return
          }
        }

        if (isFromChild) {
          if (pktTs > 0L) {
            val lastTs = lastChildTelemetryTsByChild[id] ?: 0L
            lastChildTelemetryTsByChild[id] = maxOf(lastTs, pktTs)
          }
          authorizeChildId(id)
          markChildSeen(id)
          deletedChildIds.remove(id)
          if (rawId > 0L) deletedChildIds.remove(rawId)
          val incomingBatt = msg.optInt("batteryLevel", -1)
          if (incomingBatt in 1..100 && prev?.batteryLevel != incomingBatt) {
            com.example.util.DeviceControlDebugger.logEvent(
              "БАТАРЕЯ_ОБНОВЛЕНА",
              "Получен реальный заряд ребёнка #$id: $incomingBatt%",
              "SUCCESS",
              "P2P"
            )
          }
        }

        if (!isFromChild) {
          // Пакет от родителя: фиксируем авторитетные переключатели родителя
          if (msg.has("allowMobileData") || msg.has("gpsTrackingEnabled") || msg.has("studyModeActive") || msg.has("dailyScreenLimitMinutes")) {
            val prevToggles = authoritativeParentToggles[id] ?: ParentAuthoritativeToggles()
            authoritativeParentToggles[id] = prevToggles.copy(
              allowMobileData = if (msg.has("allowMobileData")) msg.optBoolean("allowMobileData", true) else prevToggles.allowMobileData,
              gpsTrackingEnabled = if (msg.has("gpsTrackingEnabled")) msg.optBoolean("gpsTrackingEnabled", true) else prevToggles.gpsTrackingEnabled,
              studyModeActive = if (msg.has("studyModeActive")) msg.optBoolean("studyModeActive", false) else prevToggles.studyModeActive,
              dailyScreenLimitMinutes = if (msg.has("dailyScreenLimitMinutes")) msg.optInt("dailyScreenLimitMinutes", 120) else prevToggles.dailyScreenLimitMinutes,
              updatedAtMs = now
            )
          }
        }

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
          prev != null && prev.batteryLevel in 1..100 -> prev.batteryLevel
          !isFromChild && DirectP2pSocketEngine.currentRole == "CHILD" && rawBatt in 1..100 -> rawBatt
          else -> -1
        }
        val authLock = getAuthoritativeLock(id)
        val resolvedLock = authLock?.first ?: (prev?.isDeviceLocked ?: msg.optBoolean("isDeviceLocked", false))
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
        val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val startOfTodayMs = com.example.util.DeviceUsageTracker.getStartOfTodayMidnightMillis()
        val pktDateIso = msg.optString("usageDateIso", "")
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

        // Пункт 3: Переключатели родителя (GPS, Wi-Fi+4G, Учёба, Лимит) НИКОГДА не перезаписываются пакетом от ребёнка!
        val parentToggles = authoritativeParentToggles[id]
        val resolvedStudyMode = when {
          !isFromChild && msg.has("studyModeActive") -> msg.optBoolean("studyModeActive", false)
          parentToggles?.studyModeActive != null -> parentToggles.studyModeActive
          prev != null -> prev.studyModeActive
          else -> msg.optBoolean("studyModeActive", false)
        }
        val resolvedAllowMobileData = when {
          !isFromChild && msg.has("allowMobileData") -> msg.optBoolean("allowMobileData", true)
          parentToggles?.allowMobileData != null -> parentToggles.allowMobileData
          prev != null -> prev.allowMobileData
          else -> msg.optBoolean("allowMobileData", true)
        }
        val resolvedGpsTracking = when {
          !isFromChild && msg.has("gpsTrackingEnabled") -> msg.optBoolean("gpsTrackingEnabled", true)
          parentToggles?.gpsTrackingEnabled != null -> parentToggles.gpsTrackingEnabled
          prev != null -> prev.gpsTrackingEnabled
          else -> msg.optBoolean("gpsTrackingEnabled", true)
        }
        val resolvedDailyLimit = when {
          !isFromChild && msg.has("dailyScreenLimitMinutes") -> msg.optInt("dailyScreenLimitMinutes", 120)
          parentToggles?.dailyScreenLimitMinutes != null -> parentToggles.dailyScreenLimitMinutes
          prev != null -> prev.dailyScreenLimitMinutes
          else -> msg.optInt("dailyScreenLimitMinutes", 120)
        }

        val entity = ChildProfileEntity(
          id = id,
          name = resolvedName,
          age = resolvedAge,
          deviceModel = if (isFromChild) msg.optString("deviceModel", prev?.deviceModel ?: "Смартфон ребёнка") else (prev?.deviceModel ?: msg.optString("deviceModel", "Ожидание подключения")),
          pairingCode = prev?.pairingCode?.takeIf { it.isNotBlank() } ?: msg.optString("pairingCode", "${100 + id}-${700 + id}").ifBlank { "${100 + id}-${700 + id}" },
          isPaired = if (isFromChild && matchedByCodeId != null) true else (prev?.isPaired ?: msg.optBoolean("isPaired", false)),
          batteryLevel = resolvedBatt,
          isDeviceLocked = resolvedLock,
          dailyScreenLimitMinutes = resolvedDailyLimit,
          usedScreenTimeMinutes = resolvedUsedScreenMinutes,
          studyModeActive = resolvedStudyMode,
          allowMobileData = resolvedAllowMobileData,
          gpsTrackingEnabled = resolvedGpsTracking,
          colorHex = msg.optLong("colorHex", prev?.colorHex ?: if (id % 2L == 1L) 0xFF0EA5E9 else 0xFF10B981),
          appVersion = resolvedAppVersion,
          updatePending = resolvedUpdatePending,
          lastLocationLat = if (validLoc) rawLat else (prev?.lastLocationLat ?: 0.0),
          lastLocationLng = if (validLoc) rawLng else (prev?.lastLocationLng ?: 0.0),
          lastLocationAddress = if (validLoc) cleanAddr else (prev?.lastLocationAddress ?: "Ожидание точного GPS-сигнала с телефона ребёнка..."),
          lastLocationTime = if (validLoc) msg.optLong("lastLocationTime", now) else (prev?.lastLocationTime ?: 0L)
        )
        childrenCache[id] = entity
      }
      "CHILD_PATCH" -> {
        if (deletedChildIds.contains(id)) return
        val isFromChild = msg.optBoolean("isChildDevice", false) ||
          msg.has("batteryLevel") ||
          msg.has("lastLocationLat")
        if (isFromChild) {
          if (pktTs > 0L && kotlin.math.abs(now - pktTs) > 45_000L) {
            return
          }
          val lastTs = lastChildTelemetryTsByChild[id] ?: 0L
          if (pktTs > 0L && lastTs > 0L && pktTs < lastTs - 1500L && (now - lastTs) < 120_000L) {
            return
          }
          if (pktTs > 0L) {
            lastChildTelemetryTsByChild[id] = maxOf(lastTs, pktTs)
          }
          markChildSeen(id)
        }
        var existingPrev = childrenCache[id]
        val isAuthorizedTarget = authorizedChildIds.contains(id) || (rawId > 0L && authorizedChildIds.contains(rawId))
        if (DirectP2pSocketEngine.currentRole != "CHILD" && existingPrev == null && matchedByCodeId == null && !isAuthorizedTarget && isFromChild) {
          return
        }
        if (DirectP2pSocketEngine.currentRole != "CHILD" && existingPrev != null && isFromChild) {
          val localCode = existingPrev.pairingCode.replace("-", "").replace(" ", "").uppercase()
          if (localCode.length >= 6 && msgCodeClean.length >= 6 && localCode != msgCodeClean) {
            return
          }
        }
        if (isFromChild) {
          authorizeChildId(id)
          val incomingBatt = msg.optInt("batteryLevel", -1)
          if (incomingBatt in 1..100 && existingPrev?.batteryLevel != incomingBatt) {
            com.example.util.DeviceControlDebugger.logEvent(
              "БАТАРЕЯ_ОБНОВЛЕНА",
              "Получен реальный заряд ребёнка #$id: $incomingBatt%",
              "SUCCESS",
              "P2P"
            )
          }
        }
        val defaultName = "Ребёнок #$id"
        val prev = existingPrev ?: ChildProfileEntity(
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
        if (msg.has("pairingCode") && (!updated.isPaired || updated.pairingCode.isBlank())) {
          val pc = msg.optString("pairingCode", updated.pairingCode)
          if (pc.isNotBlank()) updated = updated.copy(pairingCode = pc)
        }
        if (msg.has("deviceModel")) updated = updated.copy(deviceModel = msg.optString("deviceModel", updated.deviceModel))
        if (msg.has("isPaired") && (matchedByCodeId != null || isAuthorizedTarget || !isFromChild || DirectP2pSocketEngine.currentRole == "CHILD")) {
          updated = updated.copy(isPaired = msg.optBoolean("isPaired", updated.isPaired))
        }
        if (msg.has("isDeviceLocked")) {
          val locked = msg.optBoolean("isDeviceLocked", false)
          val ts = msg.optLong("ts", now)
          val inGrace = now < (lockToggleGraceUntilMs[id] ?: 0L)
          val existingAuth = getAuthoritativeLock(id)
          if (!inGrace && kotlin.math.abs(now - ts) <= 90_000L && (existingAuth == null || ts >= existingAuth.second)) {
            setLocalAuthoritativeLock(id, locked, ts)
            updated = updated.copy(isDeviceLocked = locked)
          } else {
            existingAuth?.let { auth ->
              updated = updated.copy(isDeviceLocked = auth.first)
            }
          }
        } else {
          getAuthoritativeLock(id)?.let { auth ->
            updated = updated.copy(isDeviceLocked = auth.first)
          }
        }
        // Переключатели родителя меняются ТОЛЬКО когда пакет пришёл от родителя (!isFromChild)
        if (!isFromChild) {
          if (msg.has("studyModeActive")) {
            val v = msg.optBoolean("studyModeActive", updated.studyModeActive)
            updated = updated.copy(studyModeActive = v)
            val pt = authoritativeParentToggles[id] ?: ParentAuthoritativeToggles()
            authoritativeParentToggles[id] = pt.copy(studyModeActive = v, updatedAtMs = now)
          }
          if (msg.has("allowMobileData")) {
            val v = msg.optBoolean("allowMobileData", updated.allowMobileData)
            updated = updated.copy(allowMobileData = v)
            val pt = authoritativeParentToggles[id] ?: ParentAuthoritativeToggles()
            authoritativeParentToggles[id] = pt.copy(allowMobileData = v, updatedAtMs = now)
          }
          if (msg.has("gpsTrackingEnabled")) {
            val v = msg.optBoolean("gpsTrackingEnabled", updated.gpsTrackingEnabled)
            updated = updated.copy(gpsTrackingEnabled = v)
            val pt = authoritativeParentToggles[id] ?: ParentAuthoritativeToggles()
            authoritativeParentToggles[id] = pt.copy(gpsTrackingEnabled = v, updatedAtMs = now)
          }
          if (msg.has("dailyScreenLimitMinutes")) {
            val v = msg.optInt("dailyScreenLimitMinutes", updated.dailyScreenLimitMinutes)
            updated = updated.copy(dailyScreenLimitMinutes = v)
            val pt = authoritativeParentToggles[id] ?: ParentAuthoritativeToggles()
            authoritativeParentToggles[id] = pt.copy(dailyScreenLimitMinutes = v, updatedAtMs = now)
          }
        } else {
          authoritativeParentToggles[id]?.let { pt ->
            updated = updated.copy(
              allowMobileData = pt.allowMobileData ?: updated.allowMobileData,
              gpsTrackingEnabled = pt.gpsTrackingEnabled ?: updated.gpsTrackingEnabled,
              studyModeActive = pt.studyModeActive ?: updated.studyModeActive,
              dailyScreenLimitMinutes = pt.dailyScreenLimitMinutes ?: updated.dailyScreenLimitMinutes
            )
          }
        }
        if (msg.has("usedScreenTimeMinutes")) {
          val todayIso = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
          val startOfTodayMs = com.example.util.DeviceUsageTracker.getStartOfTodayMidnightMillis()
          val pktDateIso = msg.optString("usageDateIso", "")
          val isFromToday = if (pktDateIso.isNotBlank()) pktDateIso == todayIso else pktTs >= startOfTodayMs
          val incomingUsed = if (isFromToday) msg.optInt("usedScreenTimeMinutes", updated.usedScreenTimeMinutes) else 0
          updated = updated.copy(usedScreenTimeMinutes = incomingUsed)
        }
        if (msg.has("batteryLevel") && isFromChild) {
          val b = msg.optInt("batteryLevel", -1)
          if (b in 1..100) updated = updated.copy(batteryLevel = b)
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
              lastLocationTime = msg.optLong("lastLocationTime", msg.optLong("ts", now))
            )
          }
        }
        childrenCache[id] = updated
      }
    }
    _liveChildrenFlow.value = deduplicateChildrenCache()
  }

  suspend fun ingestImmediateDataJson(m: JSONObject) {
    val now = System.currentTimeMillis()
    val cutoff24h = now - 24L * 3600L * 1000L
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
        if (!m.optBoolean("isFromChildHardware", false)) {
          return
        }
        if (DirectP2pSocketEngine.currentRole != "CHILD") {
          val incomingAndroidId = m.optString("deviceAndroidId", "").trim()
          if (incomingAndroidId.isNotBlank()) {
            val localAndroidId = try {
              android.provider.Settings.Secure.getString(
                DirectP2pSocketEngine.appContext?.contentResolver,
                android.provider.Settings.Secure.ANDROID_ID
              ).orEmpty()
            } catch (_: Exception) {
              ""
            }
            if (localAndroidId.isNotBlank() && incomingAndroidId == localAndroidId) {
              return
            }
          }
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
                val authRule = authoritativeAppRules["${cid}_$pkgKey"] ?: authoritativeAppRules["${rawCid}_$pkgKey"]
                val keepAuthRule = authRule != null && (now - authRule.third) < 600_000L
                val resolvedBlocked = if (keepAuthRule && authRule?.first != null) authRule.first!! else o.optBoolean("isBlocked", false)
                val resolvedLimit = if (keepAuthRule && authRule?.second != null) authRule.second!! else o.optInt("dailyLimitMinutes", 0)
                parsed.add(
                  AppControlEntity(
                    id = "${cid}_$pkgKey".hashCode().toLong(),
                    childId = cid,
                    packageName = pkg,
                    appName = appName,
                    category = o.optString("category", "Другое"),
                    dailyLimitMinutes = resolvedLimit,
                    usedMinutesToday = if (isFromToday) o.optInt("usedMinutesToday", 0) else 0,
                    isBlocked = resolvedBlocked,
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
        val pkg = m.optString("packageName", "").trim()
        val pkgKey = pkg.lowercase()
        val blockedVal = if (m.has("isBlocked")) m.optBoolean("isBlocked") else null
        val limitVal = if (m.has("dailyLimitMinutes")) m.optInt("dailyLimitMinutes") else null
        if (pkgKey.isNotBlank()) {
          authoritativeAppRules["${cid}_$pkgKey"] = Triple(blockedVal, limitVal, now)
        }
        val current = appsCache[cid]?.toMutableList()
        if (current != null) {
          val idx = current.indexOfFirst { it.packageName.equals(pkg, ignoreCase = true) }
          if (idx >= 0) {
            var item = current[idx]
            if (blockedVal != null) item = item.copy(isBlocked = blockedVal)
            if (limitVal != null) item = item.copy(dailyLimitMinutes = limitVal)
            current[idx] = item
            appsCache[cid] = current
          }
        }
        // Мгновенно синхронизируем изменение блокировки/лимита в Room DB (чтобы оба родителя — Мать и Отец —
        // а также телефон ребёнка видели актуальный статус без задержки)
        val ctx = DirectP2pSocketEngine.appContext
        if (ctx != null && pkg.isNotBlank()) {
          try {
            val dao = com.example.data.local.KidsGuardDatabase.getDatabase(ctx).kidsGuardDao()
            val dbApps = dao.getAppsForChildOnce(cid)
            val targetApp = dbApps.firstOrNull { it.packageName.trim().equals(pkg, ignoreCase = true) }
            if (targetApp != null) {
              val updatedApp = targetApp.copy(
                isBlocked = blockedVal ?: targetApp.isBlocked,
                dailyLimitMinutes = limitVal ?: targetApp.dailyLimitMinutes
              )
              if (updatedApp != targetApp) {
                dao.updateApp(updatedApp)
              }
            }
          } catch (_: Exception) {}
        }
      }
      "CHAT" -> {
        val ts = m.optLong("timestamp", m.optLong("ts", now))
        // Отбрасываем старые (вчерашние/retained) сообщения и сигналы сирены старше 60 секунд!
        if (kotlin.math.abs(now - ts) > 60_000L) return
        val senderRole = m.optString("senderRole", m.optString("sender", "PARENT")).ifBlank { "PARENT" }
        val senderName = m.optString("senderName", m.optString("childName", ""))
        val text = m.optString("text", "").trim()
        val isAttention = m.optBoolean("isAttentionSignal", m.optBoolean("attention", false))
        if (text.isNotBlank()) {
          val chatEntity = ChatMessageEntity(
            id = ts,
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
            if (list.none { kotlin.math.abs(it.timestamp - ts) < 10_000L && it.text.trim() == text && it.senderRole == senderRole }) {
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
        val ts = m.optLong("timestamp", m.optLong("ts", now))
        if (kotlin.math.abs(now - ts) > 60_000L) return
        val id = m.optLong("id", ts)
        val alertType = m.optString("alertType", "GENERAL")
        val payloadStr = m.optString("payload", "")
        if (alertType == "REMOTE_UPDATE_COMPLETED" && payloadStr.isNotBlank()) {
          setAuthoritativeChildCoreVersion(cid, payloadStr)
        }
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
          it.type == alertType && it.childId == cid && kotlin.math.abs(it.timestamp - ts) < 10_000L
        }
        alertsCache[id] = alertEntity
        if (!isDuplicate) {
          _incomingAlertsFlow.tryEmit(alertEntity)
        }
      }
      "CHILD_DEBUG_LOGS" -> {
        val arr = m.optJSONArray("logs") ?: return
        val childVer = m.optString("childAppVersion", BuildConfig.VERSION_NAME).ifBlank { BuildConfig.VERSION_NAME }
        com.example.util.DeviceControlDebugger.ingestRemoteChildLogs(cid, arr, childVer)
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

    val parentToggles = authoritativeParentToggles[resolvedId]
    val resolvedAllowMobileData = if (!isChildDevice) child.allowMobileData else (parentToggles?.allowMobileData ?: existing?.allowMobileData ?: child.allowMobileData)
    val resolvedGpsTracking = if (!isChildDevice) child.gpsTrackingEnabled else (parentToggles?.gpsTrackingEnabled ?: existing?.gpsTrackingEnabled ?: child.gpsTrackingEnabled)
    val resolvedStudyMode = if (!isChildDevice) child.studyModeActive else (parentToggles?.studyModeActive ?: existing?.studyModeActive ?: child.studyModeActive)
    val resolvedDailyLimit = if (!isChildDevice) child.dailyScreenLimitMinutes else (parentToggles?.dailyScreenLimitMinutes ?: existing?.dailyScreenLimitMinutes ?: child.dailyScreenLimitMinutes)

    val normalizedVer = normalizeAppVersion(child.appVersion.ifBlank { BuildConfig.VERSION_NAME })
    val updated = child.copy(
      id = resolvedId,
      name = resolvedName,
      age = resolvedAge,
      batteryLevel = mergedBattery,
      isDeviceLocked = resolvedLock,
      allowMobileData = resolvedAllowMobileData,
      gpsTrackingEnabled = resolvedGpsTracking,
      studyModeActive = resolvedStudyMode,
      dailyScreenLimitMinutes = resolvedDailyLimit,
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
    // НЕ retain: чтобы вчерашний заряд (78%) и старый статус никогда не хранились на брокерах!
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/${updated.id}",
      json = json,
      retain = false
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
        retain = false
      )
    }
  }

  /**
   * Ответ ребёнка на PING родителя. Это НЕ состояние профиля: только тип CHILD_PONG,
   * поэтому не пишет Room, не меняет battery/location и не будит лишние экраны.
   */
  suspend fun publishChildPong(childId: Long) {
    val rawId = if (childId > 0L) childId else 1L
    val targetId = resolveCanonicalChildId(rawId)
    val json = JSONObject().apply {
      put("type", "CHILD_PONG")
      put("id", targetId)
      put("isChildDevice", true)
      put("ts", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$targetId",
      json = json,
      retain = false
    )
    DirectP2pSocketEngine.broadcastUdpJson(json)
  }

  fun publishChildOffline(childId: Long) {
    val rawId = if (childId > 0L) childId else 1L
    val targetId = resolveCanonicalChildId(rawId)
    val json = JSONObject().apply {
      put("type", "CHILD_OFFLINE")
      put("id", targetId)
      put("isChildDevice", true)
      put("ts", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$targetId",
      json = json,
      retain = false
    )
    DirectP2pSocketEngine.broadcastUdpJson(json)
  }

  suspend fun publishChildFields(childId: Long, fields: Map<String, Any>) {
    val rawId = if (childId > 0) childId else 1L
    val targetId = resolveCanonicalChildId(rawId, (fields["pairingCode"] as? String).orEmpty())
    if (fields.containsKey("isDeviceLocked")) {
      val locked = fields["isDeviceLocked"] as? Boolean ?: false
      setLocalAuthoritativeLock(targetId, locked)
    }
    val isFromChild = (fields["isChildDevice"] as? Boolean == true) ||
      fields.containsKey("batteryLevel") ||
      fields.containsKey("lastLocationLat")

    if (!isFromChild) {
      setAuthoritativeParentToggle(
        childId = targetId,
        allowMobileData = fields["allowMobileData"] as? Boolean,
        gpsTrackingEnabled = fields["gpsTrackingEnabled"] as? Boolean,
        studyModeActive = fields["studyModeActive"] as? Boolean,
        dailyScreenLimitMinutes = fields["dailyScreenLimitMinutes"] as? Int
      )
    }

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

    // КРИТИЧНО (Пункт 3): Отправляем ТОЛЬКО точечный CHILD_PATCH без retain,
    // чтобы телефон ребёнка при отправке заряда батареи НИКОГДА не публиковал дефолтный
    // профиль с allowMobileData=true / gpsTrackingEnabled=true и не включал сам режимы GPS и Wi-Fi+4G!
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$targetId",
      json = patchJson,
      retain = false
    )
    DirectP2pSocketEngine.broadcastUdpJson(patchJson)
  }

  suspend fun publishDeleteChild(childId: Long) {
    deletedChildIds.add(childId)
    childrenCache.remove(childId)
    authoritativeLockState.remove(childId)
    lastChildTelemetryTsByChild.remove(childId)
    authoritativeParentToggles.remove(childId)
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
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$childId",
      json = json,
      retain = true
    )
  }

  fun clearAllChildrenCache() {
    childrenCache.clear()
    authoritativeLockState.clear()
    lastChildTelemetryTsByChild.clear()
    authoritativeParentToggles.clear()
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
    if (commandType.isEmpty()) return
    val targetId = if (childId > 0L) childId else 1L
    authorizeChildId(targetId)
    val now = System.currentTimeMillis()
    // Анти-дребезг исходящих команд родителя (например, двойной STOP_LIVE_STREAM при закрытии окна за 20 мс)
    val outSig = "${targetId}_${commandType}_${payload.trim()}"
    val prevOutTs = lastOutgoingCmdTs[outSig] ?: 0L
    val outDebounceMs = when (commandType) {
      "LOUD_RING" -> 3_000L
      "STOP_LIVE_STREAM", "START_LIVE_STREAM", "TAKE_SCREENSHOT", "REQUEST_POWER_STATS", "QUERY_ACTIVE_APP" -> 450L
      else -> 300L
    }
    if (now - prevOutTs in 0 until outDebounceMs) {
      return
    }
    lastOutgoingCmdTs[outSig] = now

    val aliasId = idAliasMap[targetId]
    if (commandType == "LOCK") {
      publishAuthoritativeLock(targetId, true)
    } else if (commandType == "UNLOCK") {
      publishAuthoritativeLock(targetId, false)
    }
    val cachedProfile = getCachedChild(targetId)
    val uniqueCmdId = "${targetId}_${commandType}_${now}"
    val json = JSONObject().apply {
      put("type", "CMD")
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("command", commandType)
      put("payload", payload)
      put("timestamp", now)
      put("cmdId", uniqueCmdId)
    }
    // Никогда не retain для обычных команд (особенно LOUD_RING, LOCK, UNLOCK)!
    val shouldRetain = false
    val targets = linkedSetOf(targetId).apply {
      if (aliasId != null && aliasId > 0L && aliasId != targetId && !childrenCache.containsKey(targetId)) {
        add(aliasId)
      }
      if ((commandType == "LOCK" || commandType == "UNLOCK") && childrenCache.size <= 1) {
        add(1L)
      }
    }
    for (tid in targets) {
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_CMD}/$tid",
        json = JSONObject(json.toString()).apply {
          put("childId", tid)
          put("cmdId", uniqueCmdId)
        },
        retain = shouldRetain
      )
    }
    // Внимание: publishTcpSocketPacket уже сам отправляет пакет по прямому LAN-сокету (pushDirectLanPacket),
    // поэтому повторно pushDirectLanPacket здесь НЕ вызываем (иначе ребёнок получал дубль по LAN)!
    DirectP2pSocketEngine.broadcastUdpJson(json)
    if (commandType != "PING" && commandType != "MAP_VIEWING") {
      com.example.util.DeviceControlDebugger.logEvent(
        "КОМАНДА_ОТПРАВЛЕНА",
        "Отправлена команда [$commandType] для ребёнка #$targetId (payload=${payload.take(60)})",
        "SUCCESS",
        "P2P"
      )
    }
  }

  fun observeRemoteCommandsFlow(childId: Long): Flow<Pair<String, String>?> = flow<Pair<String, String>?> {
    while (true) {
      delay(5000L)
    }
  }.flowOn(Dispatchers.IO)

  /**
   * Выгрузка диагностического лога телефона ребёнка родителю (по запросу или автоматически при появлении сети после офлайна).
   */
  fun publishChildDebugLogs(
    childId: Long,
    fullDump: Boolean = true,
    triggerReason: String = if (fullDump) "Запрос родителя" else "Авто-догрузка после офлайна"
  ) {
    val rawId = if (childId > 0L) childId else 1L
    val targetId = resolveCanonicalChildId(rawId)
    if (DirectP2pSocketEngine.currentRole == "CHILD") {
      com.example.util.DeviceControlDebugger.logFullDeviceSnapshot(
        context = DirectP2pSocketEngine.appContext,
        role = "CHILD",
        childId = targetId,
        triggerReason = triggerReason
      )
    }
    val logsArr = com.example.util.DeviceControlDebugger.exportUnsyncedOrRecentLogsJsonArray(
      fullDump = fullDump,
      limit = if (fullDump) 140 else 100
    )
    if (logsArr.length() == 0) return
    val json = JSONObject().apply {
      put("type", "CHILD_DEBUG_LOGS")
      put("childId", targetId)
      put("childAppVersion", BuildConfig.VERSION_NAME)
      put("logs", logsArr)
      put("timestamp", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/logs_$targetId",
      json = json,
      retain = false
    )
  }

  /** Лёгкая телеметрия расхода энергии приложения (раз в минуту, без истории). */
  suspend fun publishPowerStats(childId: Long, appPercent: Int, mahPerHour: Float, percentPerHour: Float) {
    if (DirectP2pSocketEngine.currentRole != "CHILD") return
    val rawId = if (childId > 0L) childId else 1L
    val canonicalId = resolveCanonicalChildId(rawId)
    com.example.util.DeviceControlDebugger.logEvent(
      "РАСХОД_ЭНЕРГИИ",
      "Потребление KidsGuard на телефоне ребёнка #$canonicalId: $appPercent% (${"%.1f".format(mahPerHour)} мА·ч, ${"%.1f".format(percentPerHour)}%/ч)",
      "INFO",
      "CHILD"
    )
    val json = JSONObject().apply {
      put("type", "POWER_STATS")
      put("childId", rawId)
      put("canonicalChildId", canonicalId)
      put("appEnergyPercent", appPercent)
      put("appMahPerHour", mahPerHour.toDouble())
      put("percentPerHour", percentPerHour.toDouble())
      put("timestamp", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/power_$rawId",
      json = json,
      retain = false
    )
  }

  suspend fun publishScreenSnapshot(childId: Long, activeAppName: String, base64Bitmap: String) {
    val targetId = if (childId > 0L) childId else 1L
    val now = System.currentTimeMillis()
    val prevMs = lastScreenPublishByChild[targetId] ?: 0L
    if (now - prevMs < 500L) return
    lastScreenPublishByChild[targetId] = now
    DirectP2pSocketEngine.pushDirectScreenFrame(targetId, activeAppName, base64Bitmap)
  }

  suspend fun publishActiveAppOnly(childId: Long, activeAppName: String) {
    val targetId = if (childId > 0L) childId else 1L
    val cachedProfile = getCachedChild(targetId)
    val json = JSONObject().apply {
      put("type", "DIRECT_SCREEN_FRAME")
      put("childId", targetId)
      if (!cachedProfile?.pairingCode.isNullOrBlank()) {
        put("pairingCode", cachedProfile?.pairingCode)
      }
      put("activeAppName", activeAppName)
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
      retain = false
    )
  }

  suspend fun publishChildApps(childId: Long, apps: List<AppControlEntity>) {
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
      put("deviceModel", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim())
      put("deviceFingerprint", android.os.Build.FINGERPRINT)
      put(
        "deviceAndroidId",
        try {
          android.provider.Settings.Secure.getString(
            ctx?.contentResolver,
            android.provider.Settings.Secure.ANDROID_ID
          ).orEmpty()
        } catch (_: Exception) {
          ""
        }
      )
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
    val rawId = if (childId > 0L) childId else 1L
    val targetId = resolveCanonicalChildId(rawId)
    val pkgKey = packageName.trim().lowercase()
    val now = System.currentTimeMillis()
    if (pkgKey.isNotBlank()) {
      authoritativeAppRules["${targetId}_$pkgKey"] = Triple(isBlocked, limitMinutes, now)
      authoritativeAppRules["${rawId}_$pkgKey"] = Triple(isBlocked, limitMinutes, now)
    }
    // Немедленно обновляем локальный кэш appsCache на телефоне родителя, чтобы poller не откатил кнопку назад!
    for (cid in linkedSetOf(targetId, rawId)) {
      val current = appsCache[cid]?.toMutableList() ?: continue
      val idx = current.indexOfFirst { it.packageName.trim().equals(packageName.trim(), ignoreCase = true) }
      if (idx >= 0) {
        var item = current[idx]
        if (isBlocked != null) item = item.copy(isBlocked = isBlocked)
        if (limitMinutes != null) item = item.copy(dailyLimitMinutes = limitMinutes)
        current[idx] = item
        appsCache[cid] = current
      }
    }
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
      put("timestamp", now)
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
    val effectiveTs = msg.timestamp.takeIf { it > 0 } ?: System.currentTimeMillis()
    val storedMsg = msg.copy(id = effectiveTs, childId = targetId, timestamp = effectiveTs)
    val list = chatCache.getOrPut(targetId) { mutableListOf() }
    synchronized(list) {
      if (list.none { kotlin.math.abs(it.timestamp - storedMsg.timestamp) < 10_000L && it.text.trim() == storedMsg.text.trim() && it.senderRole == storedMsg.senderRole }) {
        list.add(storedMsg)
      }
    }
    val json = JSONObject().apply {
      put("type", "CHAT")
      put("id", effectiveTs)
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
      put("text", msg.text.trim())
      put("timestamp", effectiveTs)
      put("isAttentionSignal", msg.isAttentionSignal)
      put("attention", msg.isAttentionSignal)
    }
    // Пункт 4 и 5: retain = false! Чаты и сигналы сирены НИКОГДА не должны висеть на брокере со вчерашнего дня!
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/chat_$targetId",
      json = json,
      retain = false
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
    // retain = false: тревоги и сигналы внимания не должны храниться на брокере и срабатывать повторно на следующий день!
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_DATA}/alert_$targetId",
      json = json,
      retain = false
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
    // Никакого «если в кэше один список — отдать его любому ребёнку»: иначе родитель
    // видел список программ другого устройства (например, своего собственного телефона).
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
