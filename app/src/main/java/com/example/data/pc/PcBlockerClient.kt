package com.example.data.pc

import com.example.data.cloud.DirectP2pSocketEngine
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.OfflineActivityLogEntity
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

data class PcEntryLimit(
  val daily: Int = 0,
  val total: Int = 0,
  val from: String = "",
  val to: String = "",
  val closeTab: Boolean = true
)

data class PcEntry(
  val name: String,
  val enabled: Boolean,
  val blocked: Boolean,
  val text: String = "",
  val limit: PcEntryLimit = PcEntryLimit(),
  val domainsCount: Int = 0,
  val programsCount: Int = 0,
  val ipsCount: Int = 0,
  val domains: List<String> = emptyList(),
  val programs: List<String> = emptyList(),
  val ips: List<String> = emptyList(),
  val todaySec: Int = 0,
  val totalSec: Int = 0
)

data class PcFullLockState(
  val now: Boolean = false,
  val blocked: Boolean = false,
  val text: String = "без ограничений",
  val hasAccount: Boolean = false,
  val childAccount: String = "",
  val daily: Int = 0,
  val total: Int = 0,
  val from: String = "",
  val to: String = ""
)

data class PcActiveWindow(
  val title: String,
  val proc: String,
  val user: String = "",
  val ts: Long = 0L
)

data class PcActivityItem(
  val label: String,
  val seconds: Int
)

data class PcActivityUser(
  val user: String,
  val procs: List<PcActivityItem> = emptyList(),
  val titles: List<PcActivityItem> = emptyList()
)

data class PcOfflineHistoryItem(
  val ts: Long,
  val day: String,
  val hour: Int,
  val user: String,
  val proc: String,
  val title: String,
  val sec: Int
)

data class PcConnectionState(
  val isOnline: Boolean = false,
  val isLanHttpAuthenticated: Boolean = false,
  val transportLabel: String = "Ожидание связи с ПК (P2P / Wi-Fi)...",
  val pcName: String = "Windows ПК ребёнка",
  val lanIp: String = "",
  val port: Int = 8765,
  val pairingCode: String = "101-701",
  val computerTodaySec: Int = 0,
  val computerTotalSec: Int = 0,
  val fullLock: PcFullLockState = PcFullLockState(),
  val entries: List<PcEntry> = defaultPcEntries(),
  val activeWindows: List<PcActiveWindow> = emptyList(),
  val activityDate: String = "",
  val activityByUser: List<PcActivityUser> = emptyList(),
  val activityByDate: Map<String, List<PcActivityUser>> = emptyMap(),
  val hourlyUsageMinutes: List<Int> = List(24) { 0 },
  val offlineHistory: List<PcOfflineHistoryItem> = emptyList(),
  val screenBase64: String = "",
  val screenTimestampMs: Long = 0L,
  val lastSyncMs: Long = 0L,
  val statusMessage: String? = null
)

private fun defaultPcEntries(): List<PcEntry> = listOf(
  PcEntry(
    name = "TikTok",
    enabled = true,
    blocked = true,
    domainsCount = 13,
    programsCount = 1,
    domains = listOf("tiktok.com", "musical.ly", "tiktokv.com", "tiktokcdn.com"),
    programs = listOf("TikTok.exe")
  ),
  PcEntry(
    name = "Instagram",
    enabled = true,
    blocked = true,
    domainsCount = 3,
    programsCount = 1,
    domains = listOf("instagram.com", "cdninstagram.com", "ig.me"),
    programs = listOf("Instagram.exe")
  ),
  PcEntry(
    name = "YouTube",
    enabled = true,
    blocked = true,
    domainsCount = 7,
    programsCount = 2,
    domains = listOf("youtube.com", "youtu.be", "googlevideo.com", "ytimg.com"),
    programs = listOf("YouTube.exe", "YouTubeMusic.exe")
  ),
  PcEntry(
    name = "Genshin Impact",
    enabled = true,
    blocked = true,
    domainsCount = 6,
    programsCount = 4,
    domains = listOf("genshin.hoyoverse.com", "hoyoverse.com", "hoyolab.com", "mihoyo.com"),
    programs = listOf("GenshinImpact.exe", "YuanShen.exe", "launcher.exe", "HYP.exe")
  ),
  PcEntry(
    name = "Telegram",
    enabled = true,
    blocked = true,
    domainsCount = 10,
    programsCount = 3,
    ipsCount = 8,
    domains = listOf("telegram.org", "web.telegram.org", "t.me", "telegra.ph"),
    programs = listOf("Telegram.exe", "Unigram.exe", "AyuGram.exe")
  ),
  PcEntry(
    name = "VPN и прокси (антиобход)",
    enabled = true,
    blocked = true,
    domainsCount = 28,
    programsCount = 21,
    domains = listOf("nordvpn.com", "protonvpn.com", "windscribe.com", "psiphon.ca", "1.1.1.1"),
    programs = listOf("NordVPN.exe", "ProtonVPN.exe", "Windscribe.exe", " WireGuard.exe", "openvpn.exe")
  )
)

/**
 * Клиент управления компьютером ребёнка (Windows Hosts Blocker v5).
 * Работает одновременно по двум каналам:
 * 1) Прямой P2P TCP-туннель (через Интернет и домашнюю сеть по коду привязки, например 101-701).
 * 2) Локальная веб-панель Hosts Blocker по домашнему Wi-Fi (http://<IP>:8765 с авторизацией по паролю и CSRF).
 */
object PcBlockerClient {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

  private val httpClient = OkHttpClient.Builder()
    .connectTimeout(4, TimeUnit.SECONDS)
    .readTimeout(5, TimeUnit.SECONDS)
    .writeTimeout(4, TimeUnit.SECONDS)
    .build()

  @Volatile
  private var sessionCookie: String? = null

  @Volatile
  private var csrfToken: String = ""

  @Volatile
  private var pcPassword: String = ""

  private val _state = MutableStateFlow(PcConnectionState())
  val state: StateFlow<PcConnectionState> = _state.asStateFlow()

  fun setPairingCode(code: String) {
    val clean = code.trim().ifBlank { "101-701" }
    _state.value = _state.value.copy(pairingCode = clean)
    requestSync()
  }

  fun onUdpBeaconReceived(pcName: String, ip: String, port: Int, pairingCode: String) {
    val cur = _state.value
    val updatedIp = if (ip.isNotBlank() && ip != "127.0.0.1") ip else cur.lanIp
    _state.value = cur.copy(
      pcName = pcName.ifBlank { cur.pcName },
      lanIp = updatedIp,
      port = if (port in 1024..65535) port else cur.port,
      pairingCode = pairingCode.ifBlank { cur.pairingCode },
      isOnline = true,
      transportLabel = if (cur.isLanHttpAuthenticated) {
        "🟢 Wi-Fi LAN ($updatedIp:$port) + Интернет P2P"
      } else {
        "🟢 ПК в сети ($updatedIp:$port) • Интернет P2P активен"
      }
    )
  }

  fun ingestP2pStateJson(msg: JSONObject) {
    try {
      val msgCode = msg.optString("pairing_code", "").trim()
      val currentCode = _state.value.pairingCode.trim()
      val resolvedCode = if (msgCode.isNotBlank()) msgCode else currentCode.ifBlank { "101-701" }
      val parsed = parseStateJson(
        msg = msg,
        current = _state.value.copy(pairingCode = resolvedCode),
        transport = if (_state.value.isLanHttpAuthenticated) {
          "🟢 Wi-Fi LAN (${_state.value.lanIp}:${_state.value.port}) + Интернет P2P"
        } else {
          "🌐 Интернет P2P-мост (${msg.optString("pc_name", "Windows ПК")} • Код $resolvedCode)"
        }
      )
      val statusErr = msg.optString("auth_error", "")
      _state.value = if (statusErr.isNotBlank()) {
        parsed.copy(statusMessage = statusErr)
      } else {
        parsed
      }
    } catch (_: Exception) {}
  }

  fun ingestP2pScreenJson(msg: JSONObject) {
    val msgCode = msg.optString("pairing_code", "").trim()
    val b64 = msg.optString("screen_b64", "")
    if (b64.isNotBlank()) {
      _state.value = _state.value.copy(
        pairingCode = if (msgCode.isNotBlank()) msgCode else _state.value.pairingCode,
        screenBase64 = b64,
        screenTimestampMs = msg.optLong("ts", System.currentTimeMillis()),
        isOnline = true,
        statusMessage = "Живой кадр монитора ПК получен"
      )
    }
  }

  /**
   * Подключение к Hosts Blocker:
   * - Через Интернет (4G/LTE/разные сети) по коду привязки P2P (если IP пустой или ПК в другой сети).
   * - Напрямую по домашнему Wi-Fi (`http://<ip>:<port>`) при нахождении в одной сети.
   */
  fun connectLanWithPassword(ip: String, port: Int, password: String, pairingCode: String = "") {
    val cleanIp = ip.trim()
    val cleanPort = if (port in 1024..65535) port else 8765
    if (password.isNotBlank()) {
      pcPassword = password
    }
    if (pairingCode.isNotBlank()) {
      _state.value = _state.value.copy(pairingCode = pairingCode.trim())
    }
    scope.launch {
      if (cleanIp.isBlank()) {
        _state.value = _state.value.copy(
          transportLabel = "🌐 Интернет P2P-мост (Код ${_state.value.pairingCode})",
          statusMessage = "Подключение через Интернет по коду P2P (${_state.value.pairingCode})..."
        )
        requestSync()
        return@launch
      }
      try {
        val loginBody = JSONObject().apply {
          put("password", password)
        }.toString().toRequestBody(jsonMediaType)

        val loginReq = Request.Builder()
          .url("http://$cleanIp:$cleanPort/api/login")
          .post(loginBody)
          .build()

        httpClient.newCall(loginReq).execute().use { resp ->
          val bodyStr = resp.body?.string().orEmpty()
          val json = if (bodyStr.startsWith("{")) JSONObject(bodyStr) else JSONObject()
          if (resp.isSuccessful && json.optBoolean("ok", false)) {
            val setCookie = resp.header("Set-Cookie").orEmpty()
            val cookiePart = setCookie.substringBefore(";").trim()
            if (cookiePart.startsWith("hb_session=")) {
              sessionCookie = cookiePart
            } else if (json.optString("session").isNotBlank()) {
              sessionCookie = "hb_session=${json.optString("session")}"
            }
            val csrf = json.optString("csrf", "")
            if (csrf.isNotBlank()) {
              csrfToken = csrf
            }
            _state.value = _state.value.copy(
              lanIp = cleanIp,
              port = cleanPort,
              isOnline = true,
              isLanHttpAuthenticated = true,
              transportLabel = "🟢 Wi-Fi LAN ($cleanIp:$cleanPort) + Интернет P2P",
              statusMessage = "Успешный вход в Hosts Blocker по Wi-Fi + P2P!"
            )
            fetchLanStateNow(cleanIp, cleanPort)
          } else {
            val err = json.optString("error", "Неверный пароль или панель выключена (HTTP ${resp.code})")
            _state.value = _state.value.copy(
              lanIp = cleanIp,
              port = cleanPort,
              statusMessage = err
            )
          }
        }
      } catch (e: Exception) {
        _state.value = _state.value.copy(
          lanIp = cleanIp,
          port = cleanPort,
          transportLabel = "🌐 Интернет P2P-мост (Код ${_state.value.pairingCode})",
          statusMessage = "Вне домашнего Wi-Fi ($cleanIp недоступен) — работаем через Интернет P2P-мост"
        )
        requestSync()
      }
    }
  }

  private suspend fun fetchLanStateNow(ip: String = _state.value.lanIp, port: Int = _state.value.port): Boolean =
    withContext(Dispatchers.IO) {
      val cookie = sessionCookie ?: return@withContext false
      if (ip.isBlank()) return@withContext false
      try {
        val req = Request.Builder()
          .url("http://$ip:$port/api/state")
          .header("Cookie", cookie)
          .get()
          .build()
        httpClient.newCall(req).execute().use { resp ->
          if (resp.code == 401) {
            sessionCookie = null
            _state.value = _state.value.copy(isLanHttpAuthenticated = false)
            return@withContext false
          }
          if (resp.isSuccessful) {
            val bodyStr = resp.body?.string().orEmpty()
            if (bodyStr.startsWith("{")) {
              val msg = JSONObject(bodyStr)
              val csrf = msg.optString("csrf", "")
              if (csrf.isNotBlank()) csrfToken = csrf
              _state.value = parseStateJson(
                msg = msg,
                current = _state.value,
                transport = "🟢 Прямое подключение Wi-Fi LAN ($ip:$port)"
              )
              return@withContext true
            }
          }
        }
      } catch (_: Exception) {}
      false
    }

  private suspend fun postLanAction(path: String, payload: JSONObject): Boolean = withContext(Dispatchers.IO) {
    val cur = _state.value
    val cookie = sessionCookie ?: return@withContext false
    if (cur.lanIp.isBlank() || !cur.isLanHttpAuthenticated) return@withContext false
    try {
      val req = Request.Builder()
        .url("http://${cur.lanIp}:${cur.port}$path")
        .header("Cookie", cookie)
        .header("X-CSRF", csrfToken)
        .post(payload.toString().toRequestBody(jsonMediaType))
        .build()
      httpClient.newCall(req).execute().use { resp ->
        if (resp.isSuccessful) {
          val bodyStr = resp.body?.string().orEmpty()
          if (bodyStr.startsWith("{")) {
            val j = JSONObject(bodyStr)
            if (j.optBoolean("ok", false)) {
              if (path == "/api/screen" && j.optString("screen_b64").isNotBlank()) {
                _state.value = _state.value.copy(
                  screenBase64 = j.optString("screen_b64"),
                  screenTimestampMs = j.optLong("ts", System.currentTimeMillis()),
                  statusMessage = "Скриншот экрана ПК получен по Wi-Fi"
                )
              } else {
                fetchLanStateNow(cur.lanIp, cur.port)
              }
              return@withContext true
            } else {
              val err = j.optString("error")
              if (err.isNotBlank()) {
                _state.value = _state.value.copy(statusMessage = err)
              }
            }
          }
        }
      }
    } catch (_: Exception) {}
    false
  }

  private fun sendP2pCommand(action: String, data: JSONObject) {
    val code = _state.value.pairingCode.trim().ifBlank { "101-701" }
    val codes = linkedSetOf(code, "101-701")
    for (targetCode in codes) {
      val pkt = JSONObject().apply {
        put("type", "PC_CMD")
        put("pairing_code", targetCode)
        put("action", action)
        if (pcPassword.isNotBlank()) {
          put("password", pcPassword)
        }
        put("data", data)
        put("ts", System.currentTimeMillis())
      }
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_ROOT}/pc_cmd/$targetCode",
        json = pkt,
        retain = false
      )
      DirectP2pSocketEngine.publishTcpSocketPacket(
        topic = "${DirectP2pSocketEngine.TOPIC_ROOT_LEGACY_PC}/pc_cmd/$targetCode",
        json = pkt,
        retain = false
      )
    }
  }

  fun requestSync() {
    scope.launch {
      sendP2pCommand("/api/sync", JSONObject())
      if (_state.value.isLanHttpAuthenticated) {
        fetchLanStateNow()
      }
    }
  }

  fun requestPcScreenshot() {
    scope.launch {
      sendP2pCommand("/api/screen", JSONObject())
      if (_state.value.isLanHttpAuthenticated) {
        postLanAction("/api/screen", JSONObject())
      }
    }
  }

  fun toggleEntry(name: String, enabled: Boolean) {
    // Оптимистичное обновление UI
    val updatedEntries = _state.value.entries.map {
      if (it.name == name) {
        val hasLim = it.limit.daily > 0 || it.limit.total > 0 || (it.limit.from.isNotBlank() && it.limit.to.isNotBlank())
        it.copy(enabled = enabled, blocked = if (!enabled) false else !hasLim || it.blocked)
      } else it
    }
    _state.value = _state.value.copy(
      entries = updatedEntries,
      statusMessage = if (enabled) "Правило «$name» включено" else "Правило «$name» выключено"
    )
    val data = JSONObject().apply {
      put("name", name)
      put("enabled", enabled)
    }
    scope.launch {
      sendP2pCommand("/api/toggle", data)
      postLanAction("/api/toggle", data)
    }
  }

  fun updateEntryLimit(name: String, daily: Int, total: Int, from: String, to: String, closeTab: Boolean) {
    val newLim = PcEntryLimit(daily = daily, total = total, from = from.trim(), to = to.trim(), closeTab = closeTab)
    val updatedEntries = _state.value.entries.map {
      if (it.name == name) it.copy(limit = newLim) else it
    }
    _state.value = _state.value.copy(
      entries = updatedEntries,
      statusMessage = "Лимиты для «$name» сохранены"
    )
    val data = JSONObject().apply {
      put("name", name)
      put("daily", daily)
      put("total", total)
      put("from", from.trim())
      put("to", to.trim())
      put("close_tab", closeTab)
    }
    scope.launch {
      sendP2pCommand("/api/limit", data)
      postLanAction("/api/limit", data)
    }
  }

  fun resetEntryCounter(name: String) {
    _state.value = _state.value.copy(statusMessage = "Сброс счётчика времени «$name» отправлен")
    val data = JSONObject().apply {
      put("name", name)
    }
    scope.launch {
      sendP2pCommand("/api/reset", data)
      postLanAction("/api/reset", data)
    }
  }

  fun toggleFullPcLock(lockNow: Boolean, childAccount: String = _state.value.fullLock.childAccount) {
    val cleanAcct = childAccount.trim()
    val curFl = _state.value.fullLock
    _state.value = _state.value.copy(
      fullLock = curFl.copy(
        now = lockNow,
        blocked = lockNow,
        hasAccount = cleanAcct.isNotBlank() || curFl.hasAccount,
        childAccount = cleanAcct.ifBlank { curFl.childAccount },
        text = if (lockNow) "заблокирован вручную" else "без ограничений"
      ),
      statusMessage = if (lockNow) {
        "Команда блокировки ПК (отключение аккаунта и выход из сеанса) отправлена!"
      } else {
        "Блокировка ПК снята, вход в Windows разрешён"
      }
    )
    val data = JSONObject().apply {
      put("now", lockNow)
      if (cleanAcct.isNotBlank()) {
        put("child_account", cleanAcct)
      }
    }
    scope.launch {
      sendP2pCommand("/api/fulllock", data)
      postLanAction("/api/fulllock", data)
    }
  }

  fun saveFullPcLockSchedule(childAccount: String, daily: Int, total: Int, from: String, to: String) {
    val cleanAcct = childAccount.trim()
    val curFl = _state.value.fullLock
    _state.value = _state.value.copy(
      fullLock = curFl.copy(
        hasAccount = cleanAcct.isNotBlank(),
        childAccount = cleanAcct,
        daily = daily,
        total = total,
        from = from.trim(),
        to = to.trim()
      ),
      statusMessage = "Расписание блокировки ПК и аккаунт «$cleanAcct» сохранены"
    )
    val data = JSONObject().apply {
      put("child_account", cleanAcct)
      put("daily", daily)
      put("total", total)
      put("from", from.trim())
      put("to", to.trim())
    }
    scope.launch {
      sendP2pCommand("/api/fulllock_schedule", data)
      postLanAction("/api/fulllock_schedule", data)
    }
  }

  fun addEntry(name: String, domains: List<String>, programs: List<String>, ips: List<String> = emptyList()) {
    val cleanName = name.trim()
    if (cleanName.isBlank()) return
    val newEntry = PcEntry(
      name = cleanName,
      enabled = true,
      blocked = true,
      domainsCount = domains.size,
      programsCount = programs.size,
      ipsCount = ips.size,
      domains = domains,
      programs = programs,
      ips = ips
    )
    val existing = _state.value.entries.filterNot { it.name.equals(cleanName, ignoreCase = true) }
    _state.value = _state.value.copy(
      entries = existing + newEntry,
      statusMessage = "Добавлена блокировка на ПК: «$cleanName»"
    )
    val data = JSONObject().apply {
      put("name", cleanName)
      put("enabled", true)
      put("domains", JSONArray(domains))
      put("programs", JSONArray(programs))
      put("ips", JSONArray(ips))
    }
    scope.launch {
      sendP2pCommand("/api/add", data)
      postLanAction("/api/add", data)
    }
  }

  fun deleteEntry(name: String) {
    _state.value = _state.value.copy(
      entries = _state.value.entries.filterNot { it.name == name },
      statusMessage = "Запись «$name» удалена с ПК"
    )
    val data = JSONObject().apply {
      put("name", name)
    }
    scope.launch {
      sendP2pCommand("/api/delete", data)
      postLanAction("/api/delete", data)
    }
  }

  fun parseStateJson(msg: JSONObject, current: PcConnectionState, transport: String): PcConnectionState {
    val fullObj = msg.optJSONObject("full") ?: JSONObject()
    val fullState = PcFullLockState(
      now = fullObj.optBoolean("now", false),
      blocked = fullObj.optBoolean("blocked", false),
      text = fullObj.optString("text", "без ограничений"),
      hasAccount = fullObj.optBoolean("has_account", false),
      childAccount = fullObj.optString("child_account", current.fullLock.childAccount),
      daily = fullObj.optInt("daily", 0),
      total = fullObj.optInt("total", 0),
      from = fullObj.optString("from", ""),
      to = fullObj.optString("to", "")
    )

    val entriesArr = msg.optJSONArray("entries")
    val parsedEntries = mutableListOf<PcEntry>()
    if (entriesArr != null) {
      for (i in 0 until entriesArr.length()) {
        val e = entriesArr.optJSONObject(i) ?: continue
        val limObj = e.optJSONObject("limit") ?: JSONObject()
        val domArr = e.optJSONArray("domains")
        val progArr = e.optJSONArray("programs")
        val ipArr = e.optJSONArray("ips")
        val domList = mutableListOf<String>()
        val progList = mutableListOf<String>()
        val ipList = mutableListOf<String>()
        if (domArr != null) for (j in 0 until domArr.length()) domList.add(domArr.optString(j))
        if (progArr != null) for (j in 0 until progArr.length()) progList.add(progArr.optString(j))
        if (ipArr != null) for (j in 0 until ipArr.length()) ipList.add(ipArr.optString(j))

        val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
        val rawActDate = msg.optString("activity_date", "").trim()
        val isPacketFromToday = rawActDate.isNotBlank() && rawActDate == todayIso

        parsedEntries.add(
          PcEntry(
            name = e.optString("name", "Запись"),
            enabled = e.optBoolean("enabled", false),
            blocked = e.optBoolean("blocked", false),
            text = e.optString("text", ""),
            limit = PcEntryLimit(
              daily = limObj.optInt("daily", 0),
              total = limObj.optInt("total", 0),
              from = limObj.optString("from", ""),
              to = limObj.optString("to", ""),
              closeTab = limObj.optBoolean("close_tab", true)
            ),
            domainsCount = e.optInt("domains_count", domList.size),
            programsCount = e.optInt("programs_count", progList.size),
            ipsCount = e.optInt("ips_count", ipList.size),
            domains = domList,
            programs = progList,
            ips = ipList,
            todaySec = if (isPacketFromToday) e.optInt("today_sec", 0) else 0,
            totalSec = e.optInt("total_sec", 0)
          )
        )
      }
    }

    val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    val rawActDate = msg.optString("activity_date", "").trim()
    val isPacketFromToday = rawActDate.isNotBlank() && rawActDate == todayIso
    val msgActDate = rawActDate.ifBlank { todayIso }

    val winArr = msg.optJSONArray("active_windows")
    val windows = mutableListOf<PcActiveWindow>()
    if (winArr != null && isPacketFromToday) {
      for (i in 0 until winArr.length()) {
        val w = winArr.optJSONObject(i) ?: continue
        windows.add(
          PcActiveWindow(
            title = w.optString("title", ""),
            proc = w.optString("proc", ""),
            user = w.optString("user", ""),
            ts = (w.optDouble("ts", 0.0) * 1000).toLong()
          )
        )
      }
    }

    fun parseUsersActivityJson(usersObj: JSONObject?): List<PcActivityUser> {
      if (usersObj == null) return emptyList()
      val result = mutableListOf<PcActivityUser>()
      val keys = usersObj.keys()
      while (keys.hasNext()) {
        val u = keys.next()
        val uObj = usersObj.optJSONObject(u) ?: continue
        val pArr = uObj.optJSONArray("procs")
        val tArr = uObj.optJSONArray("titles")
        val procs = mutableListOf<PcActivityItem>()
        val titles = mutableListOf<PcActivityItem>()
        if (pArr != null) {
          for (i in 0 until pArr.length()) {
            val item = pArr.optJSONObject(i) ?: continue
            procs.add(PcActivityItem(item.optString("name"), item.optInt("seconds", 0)))
          }
        }
        if (tArr != null) {
          for (i in 0 until tArr.length()) {
            val item = tArr.optJSONObject(i) ?: continue
            titles.add(PcActivityItem(item.optString("title"), item.optInt("seconds", 0)))
          }
        }
        result.add(PcActivityUser(user = u, procs = procs, titles = titles))
      }
      return result
    }

    val actUsers = parseUsersActivityJson(msg.optJSONObject("activity"))

    val mergedByDate = current.activityByDate.toMutableMap()
    if (actUsers.isNotEmpty()) {
      mergedByDate[msgActDate] = actUsers
    }

    val actByDateObj = msg.optJSONObject("activity_by_date")
    if (actByDateObj != null) {
      val dayKeys = actByDateObj.keys()
      while (dayKeys.hasNext()) {
        val dKey = dayKeys.next()
        val dayUsers = parseUsersActivityJson(actByDateObj.optJSONObject(dKey))
        if (dayUsers.isNotEmpty()) {
          mergedByDate[dKey] = dayUsers
        }
      }
    }

    val hourlyArr = msg.optJSONArray("hourly_usage")
    val hourlyList = if (isPacketFromToday && hourlyArr != null && hourlyArr.length() == 24) {
      List(24) { idx -> hourlyArr.optInt(idx, 0).coerceIn(0, 60) }
    } else if (!isPacketFromToday) {
      List(24) { 0 }
    } else {
      current.hourlyUsageMinutes
    }

    val offlineArr = msg.optJSONArray("offline_history")
    val offlineList = if (offlineArr != null) {
      buildList {
        for (i in 0 until offlineArr.length()) {
          val o = offlineArr.optJSONObject(i) ?: continue
          add(
            PcOfflineHistoryItem(
              ts = o.optLong("ts", System.currentTimeMillis()),
              day = o.optString("day", ""),
              hour = o.optInt("hour", 0).coerceIn(0, 23),
              user = o.optString("user", "PC"),
              proc = o.optString("proc", ""),
              title = o.optString("title", ""),
              sec = o.optInt("sec", 0)
            )
          )
        }
      }
    } else {
      current.offlineHistory
    }

    // Если в offlineHistory есть записи за дни, которых ещё нет в mergedByDate — группируем их строго по дате (day)
    if (offlineList.isNotEmpty()) {
      val byDay = offlineList.filter { it.day.isNotBlank() }.groupBy { it.day }
      for ((dayIso, itemsForDay) in byDay) {
        if (dayIso !in mergedByDate) {
          val byUser = itemsForDay.groupBy { it.user.ifBlank { "Windows" } }
          val usersForDay = byUser.map { (usr, uItems) ->
            val procsAgg = uItems.filter { it.proc.isNotBlank() }
              .groupBy { it.proc }
              .map { (pName, pList) -> PcActivityItem(pName, pList.sumOf { it.sec }) }
              .sortedByDescending { it.seconds }
            val titlesAgg = uItems.filter { it.title.isNotBlank() }
              .groupBy { it.title }
              .map { (tName, tList) -> PcActivityItem(tName, tList.sumOf { it.sec }) }
              .sortedByDescending { it.seconds }
            PcActivityUser(user = usr, procs = procsAgg, titles = titlesAgg)
          }
          if (usersForDay.isNotEmpty()) {
            mergedByDate[dayIso] = usersForDay
          }
        }
      }
    }

    // Синхронизируем по-дневные снимки активности ПК в локальную БД SQLite (offline_activity_logs)
    syncPcActivityByDateToRoom(mergedByDate)

    val ipFromMsg = msg.optString("local_ip", "")
    val embeddedScrB64 = msg.optString("screen_b64", "")
    val resolvedScrB64 = if (embeddedScrB64.isNotBlank()) embeddedScrB64 else current.screenBase64
    val resolvedScrTs = if (embeddedScrB64.isNotBlank()) msg.optLong("screen_ts", System.currentTimeMillis()) else current.screenTimestampMs

    // Strict Per-Day Isolation: If the PC was not used today (e.g. today is the 29th and the packet is from the 27th/28th),
    // today's usage MUST be 0 seconds and empty user list!
    val todayUsers = mergedByDate[todayIso] ?: if (isPacketFromToday) actUsers else emptyList()
    val todayComputerSec = if (isPacketFromToday) {
      msg.optInt("computer_today_sec", current.computerTodaySec)
    } else {
      todayUsers.sumOf { u -> u.procs.sumOf { it.seconds } }
    }

    return current.copy(
      isOnline = true,
      transportLabel = transport,
      pcName = msg.optString("pc_name", current.pcName).ifBlank { current.pcName },
      lanIp = if (ipFromMsg.isNotBlank() && ipFromMsg != "127.0.0.1") ipFromMsg else current.lanIp,
      port = msg.optInt("port", current.port),
      pairingCode = msg.optString("pairing_code", current.pairingCode).ifBlank { current.pairingCode },
      computerTodaySec = todayComputerSec,
      computerTotalSec = msg.optInt("computer_total_sec", current.computerTotalSec),
      fullLock = fullState,
      entries = if (parsedEntries.isNotEmpty()) parsedEntries else current.entries,
      activeWindows = windows,
      activityDate = todayIso,
      activityByUser = todayUsers,
      activityByDate = mergedByDate,
      hourlyUsageMinutes = hourlyList,
      offlineHistory = offlineList,
      screenBase64 = resolvedScrB64,
      screenTimestampMs = resolvedScrTs,
      lastSyncMs = System.currentTimeMillis()
    )
  }

  private fun parseDateIsoToTimestampMs(dateIso: String): Long {
    val todayIso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    if (dateIso == todayIso) {
      return System.currentTimeMillis()
    }
    return try {
      val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(dateIso)
      if (parsed != null) {
        val cal = Calendar.getInstance()
        cal.time = parsed
        cal.set(Calendar.HOUR_OF_DAY, 12)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
      } else {
        System.currentTimeMillis()
      }
    } catch (_: Exception) {
      System.currentTimeMillis()
    }
  }

  private fun syncPcActivityByDateToRoom(byDate: Map<String, List<PcActivityUser>>) {
    if (byDate.isEmpty()) return
    val appCtx = DirectP2pSocketEngine.appContext ?: return
    scope.launch {
      try {
        val dao = KidsGuardDatabase.getDatabase(appCtx).kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        val children = dao.getAllChildrenOnce()
        val childId = when {
          cfg != null && cfg.currentActiveChildId > 0L -> cfg.currentActiveChildId
          children.isNotEmpty() -> children.first().id
          else -> 1L
        }
        for ((dateIso, users) in byDate) {
          if (dateIso.isBlank()) continue
          val anchorTs = parseDateIsoToTimestampMs(dateIso)
          val cal = Calendar.getInstance().apply { timeInMillis = anchorTs }
          val hour = cal.get(Calendar.HOUR_OF_DAY).coerceIn(0, 23)
          val dayLogs = mutableListOf<OfflineActivityLogEntity>()
          for (uAct in users) {
            val winUser = uAct.user.ifBlank { "Windows" }
            for (p in uAct.procs) {
              if (p.label.isNotBlank() && p.seconds > 0) {
                dayLogs.add(
                  OfflineActivityLogEntity(
                    childId = childId,
                    deviceType = "PC",
                    packageName = p.label.trim(),
                    appName = winUser,
                    windowTitle = "",
                    durationSeconds = p.seconds,
                    hourOfDay = hour,
                    dateIso = dateIso,
                    timestamp = anchorTs,
                    isSyncedToParent = true
                  )
                )
              }
            }
            for (t in uAct.titles) {
              if (t.label.isNotBlank() && t.seconds > 0) {
                dayLogs.add(
                  OfflineActivityLogEntity(
                    childId = childId,
                    deviceType = "PC",
                    packageName = "browser",
                    appName = winUser,
                    windowTitle = t.label.trim(),
                    durationSeconds = t.seconds,
                    hourOfDay = hour,
                    dateIso = dateIso,
                    timestamp = anchorTs,
                    isSyncedToParent = true
                  )
                )
              }
            }
          }
          dao.replacePcActivitySnapshotForDate(childId, dateIso, dayLogs)
        }
      } catch (_: Exception) {}
    }
  }
}
