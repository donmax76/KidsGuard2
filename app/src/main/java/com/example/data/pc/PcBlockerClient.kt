package com.example.data.pc

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.data.local.KidsGuardDatabase
import com.example.data.model.OfflineActivityLogEntity
import java.text.SimpleDateFormat
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okio.BufferedSource
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
  // Время ПОЛУЧЕНИЯ кадра на телефоне (часы телефона). Часы ПК могут быть сдвинуты
  // на час/пояс — для подписи «кадр получен в …» используем именно это значение.
  val screenReceivedAtMs: Long = 0L,
  val lastSyncMs: Long = 0L,
  val statusMessage: String? = null
)

/** Один живой кадр экрана ПК, готовый к отрисовке в Compose. */
data class PcLiveFrame(
  val bitmap: Bitmap,
  val timestampMs: Long = System.currentTimeMillis(),
  val width: Int = bitmap.width,
  val height: Int = bitmap.height
)

/** Профиль качества живого потока экрана ПК (влияет на размер кадра и трафик). */
enum class PcStreamQuality(val label: String, val width: Int, val height: Int, val jpegQuality: Int) {
  ECONOMY("Эконом", 480, 270, 45),
  BALANCED("Баланс", 640, 360, 50),
  HD("HD", 960, 540, 60)
}

/** Состояние живого видеопотока экрана ПК (сколько кадров/с, какой транспорт, ошибки). */
data class PcStreamStatus(
  val isActive: Boolean = false,
  val transport: String = "",
  val fps: Float = 0f,
  val frames: Int = 0,
  val lastFrameMs: Long = 0L,
  val error: String? = null,
  val quality: PcStreamQuality = PcStreamQuality.BALANCED
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

  // ==== Изоляция ПК ====
  // На публичном брокере много компьютеров с кодом по умолчанию 101-701. Без фильтра
  // телефон принимал PC_STATE/PC_SCREEN/кадры от ЧУЖИХ ПК: список правил «прыгал»,
  // могли показываться чужие экраны. Храним код + уникальный pc_id и принимаем только их.
  @Volatile
  private var configuredCode: String = "101-701"

  @Volatile
  private var configuredPcId: String = ""

  @Volatile
  private var codeExplicitlySet: Boolean = false

  @Volatile
  private var pcIdentityLoaded: Boolean = false

  private fun pcPrefs() = DirectP2pSocketEngine.appContext
    ?.getSharedPreferences("kidsguard_pc_client", android.content.Context.MODE_PRIVATE)

  private fun loadPersistedPcIdentity() {
    if (pcIdentityLoaded) return
    val p = pcPrefs() ?: return
    pcIdentityLoaded = true
    val savedCode = p.getString("pc_code", "").orEmpty().trim()
    if (savedCode.isNotBlank()) {
      configuredCode = savedCode
      codeExplicitlySet = true
    }
    val savedId = p.getString("pc_id", "").orEmpty().trim()
    if (savedId.isNotBlank()) configuredPcId = savedId
    val savedPwd = p.getString("pc_password", "").orEmpty()
    if (savedPwd.isNotBlank()) pcPassword = savedPwd
    _state.value = _state.value.copy(pairingCode = configuredCode)
  }

  private fun persistPcIdentity() {
    val editor = pcPrefs()?.edit() ?: return
    editor.putString("pc_code", configuredCode)
    editor.putString("pc_id", configuredPcId)
    editor.apply()
  }

  private fun normalizePcCode(code: String): String =
    code.replace("-", "").replace(" ", "").trim().uppercase()

  /** Принимаем пакет, только если он от нашего ПК: совпал код и (если известен) pc_id. */
  private fun matchesConfiguredPc(code: String, pcId: String): Boolean {
    if (code.isNotBlank() && normalizePcCode(code) != normalizePcCode(configuredCode)) {
      // Пока код не задан пользователем и ПК ещё не опознан — принимаем первый пакет
      // (обратная совместимость со старой версией скрипта без pc_id). Дальше — только свой.
      if (codeExplicitlySet || configuredPcId.isNotBlank()) return false
    }
    if (pcId.isNotBlank() && configuredPcId.isNotBlank() && pcId.trim() != configuredPcId) return false
    return true
  }

  private fun learnPcIdentity(code: String, pcId: String) {
    var changed = false
    if (code.isNotBlank() && !codeExplicitlySet && normalizePcCode(code) != normalizePcCode(configuredCode)) {
      configuredCode = code.trim()
      changed = true
    }
    if (pcId.isNotBlank() && configuredPcId.isBlank()) {
      // Опознали конкретный ПК — фиксируем его и больше не слушаем чужие с тем же кодом.
      configuredPcId = pcId.trim()
      codeExplicitlySet = true
      changed = true
    }
    if (changed) persistPcIdentity()
  }

  private val _state = MutableStateFlow(PcConnectionState())
  val state: StateFlow<PcConnectionState> = _state.asStateFlow()

  fun setPairingCode(code: String) {
    loadPersistedPcIdentity()
    val clean = code.trim().ifBlank { "101-701" }
    configuredCode = clean
    // Пользователь явно указал код — заново опознаём ПК по этому коду.
    configuredPcId = ""
    codeExplicitlySet = true
    _state.value = _state.value.copy(pairingCode = clean)
    persistPcIdentity()
    requestSync()
  }

  /**
   * Сбросить запомненный pc_id. Нужно, если ПК переустановили/сменили конфиг и его id
   * изменился: приложение заново опознает компьютер по коду.
   */
  fun resetPcIdentity() {
    loadPersistedPcIdentity()
    configuredPcId = ""
    codeExplicitlySet = false
    persistPcIdentity()
    _state.value = _state.value.copy(
      pairingCode = configuredCode,
      statusMessage = "Привязка к ПК сброшена — ищу компьютер заново по коду"
    )
    requestSync()
  }

  fun onUdpBeaconReceived(pcName: String, ip: String, port: Int, pairingCode: String, pcId: String = "") {
    loadPersistedPcIdentity()
    // Маяк приходит по локальной сети от реального ПК — он главнее случайно
    // «пойманного» чужого состояния из интернета. Переключаемся на этот ПК.
    if (pairingCode.isNotBlank()) configuredCode = pairingCode.trim()
    if (pcId.isNotBlank()) configuredPcId = pcId.trim()
    codeExplicitlySet = true
    persistPcIdentity()
    val cur = _state.value
    val updatedIp = if (ip.isNotBlank() && ip != "127.0.0.1") ip else cur.lanIp
    _state.value = cur.copy(
      pcName = pcName.ifBlank { cur.pcName },
      lanIp = updatedIp,
      port = if (port in 1024..65535) port else cur.port,
      pairingCode = configuredCode,
      isOnline = true,
      transportLabel = if (cur.isLanHttpAuthenticated) {
        "🟢 Wi-Fi LAN ($updatedIp:$port) + Интернет P2P"
      } else {
        "🟢 ПК в сети ($updatedIp:$port) • Интернет P2P активен"
      }
    )
  }

  fun ingestP2pStateJson(msg: JSONObject) {
    // Телефону ребёнка пакеты о ПК не нужны: не тратим его батарею и не пишем чужую историю.
    if (DirectP2pSocketEngine.currentRole == "CHILD") return
    loadPersistedPcIdentity()
    val msgCode = msg.optString("pairing_code", "").trim()
    val msgPcId = msg.optString("pc_id", "").trim()
    if (!matchesConfiguredPc(msgCode, msgPcId)) return
    learnPcIdentity(msgCode, msgPcId)
    try {
      val parsed = parseStateJson(
        msg = msg,
        current = _state.value.copy(pairingCode = configuredCode),
        transport = if (_state.value.isLanHttpAuthenticated) {
          "🟢 Wi-Fi LAN (${_state.value.lanIp}:${_state.value.port}) + Интернет P2P"
        } else {
          "🌐 Интернет P2P-мост (${msg.optString("pc_name", "Windows ПК")} • Код $configuredCode)"
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
    if (DirectP2pSocketEngine.currentRole == "CHILD") return
    loadPersistedPcIdentity()
    val msgCode = msg.optString("pairing_code", "").trim()
    val msgPcId = msg.optString("pc_id", "").trim()
    if (!matchesConfiguredPc(msgCode, msgPcId)) return
    learnPcIdentity(msgCode, msgPcId)
    val b64 = msg.optString("screen_b64", "")
    if (b64.isNotBlank()) {
      _state.value = _state.value.copy(
        pairingCode = configuredCode,
        screenBase64 = b64,
        screenTimestampMs = msg.optLong("ts", System.currentTimeMillis()),
        screenReceivedAtMs = System.currentTimeMillis(),
        isOnline = true,
        statusMessage = "Живой кадр монитора ПК получен"
      )
    }
  }

  // ==================== ЖИВОЙ ПОТОК ЭКРАНА ПК ====================
  // Два пути доставки кадров:
  //  1) MJPEG по домашнему Wi-Fi: GET /api/stream (multipart/x-mixed-replace) — плавно, дёшево;
  //  2) кадры PC_STREAM_FRAME через P2P-мост по Интернету (4G/5G/другая сеть).
  // Поток включается ТОЛЬКО когда экран ПК реально открыт на телефоне и приложение на переднем
  // плане, и выключается сразу при уходе с экрана — это главное условие экономии батареи.

  private val _liveFrame = MutableStateFlow<PcLiveFrame?>(null)
  val liveFrame: StateFlow<PcLiveFrame?> = _liveFrame.asStateFlow()

  private val _streamStatus = MutableStateFlow(PcStreamStatus())
  val streamStatus: StateFlow<PcStreamStatus> = _streamStatus.asStateFlow()

  @Volatile
  private var streamRequested = false

  @Volatile
  private var streamFps = 4

  @Volatile
  private var streamQuality = PcStreamQuality.BALANCED

  private var streamLeaseJob: Job? = null
  private var streamMjpegJob: Job? = null
  private var streamCall: Call? = null

  private val decodeBusy = AtomicBoolean(false)
  private val frameCounter = AtomicInteger(0)
  private val streamEpoch = AtomicInteger(0)
  private val frameTimes = ArrayDeque<Long>()
  private val frameLock = Any()

  @Volatile
  private var lastAcceptedFrameTs = 0L

  fun isScreenStreamActive(): Boolean = streamRequested

  /** Включает живой поток экрана ПК (продлевает «аренду» на ПК и открывает MJPEG по Wi-Fi). */
  fun startScreenStream(fps: Int = 4, quality: PcStreamQuality = PcStreamQuality.BALANCED) {
    // Экран ПК смотрит только родитель — телефону ребёнка это не нужно.
    if (DirectP2pSocketEngine.currentRole == "CHILD") return
    loadPersistedPcIdentity()
    val cleanFps = fps.coerceIn(1, 10)
    if (streamRequested && streamFps == cleanFps && streamQuality == quality) {
      if (streamMjpegJob?.isActive != true) startLanMjpegIfPossible()
      return
    }
    performStop()
    streamEpoch.incrementAndGet()
    streamRequested = true
    streamFps = cleanFps
    streamQuality = quality
    frameCounter.set(0)
    synchronized(frameLock) { frameTimes.clear() }
    _streamStatus.value = PcStreamStatus(
      isActive = true,
      transport = "Подключение к экрану ПК...",
      frames = 0,
      quality = quality
    )
    // Для MJPEG по Wi-Fi параметры кадра задаём отдельным LAN-запросом (сам поток
    // продлевает аренду уже на стороне ПК).
    if (_state.value.isLanHttpAuthenticated) {
      scope.launch {
        postLanAction("/api/stream_start", streamParamsJson(cleanFps, quality))
      }
    }
    streamLeaseJob = scope.launch {
      while (isActive && streamRequested) {
        // Даём MJPEG-соединению по Wi-Fi шанс установиться, чтобы не запросить
        // параллельно ещё и P2P-кадры.
        if (_state.value.isLanHttpAuthenticated) delay(1_500L)
        // Если уже идёт MJPEG по домашнему Wi-Fi, P2P-кадры не запрашиваем — иначе ПК
        // кодировал бы одно и то же видео дважды (лишний CPU, трафик и батарея).
        val lanStreamRunning = _state.value.isLanHttpAuthenticated && streamMjpegJob?.isActive == true
        if (!lanStreamRunning) {
          sendP2pCommand("/api/stream_start", streamParamsJson(cleanFps, quality).apply {
            put("duration", 8)
          })
        }
        delay(5_000L)
      }
    }
    startLanMjpegIfPossible()
  }

  private fun streamParamsJson(fps: Int, quality: PcStreamQuality): JSONObject = JSONObject().apply {
    put("fps", fps)
    put("w", quality.width)
    put("h", quality.height)
    put("quality", quality.jpegQuality)
    put("binary", true) // JPEG без Base64 — в 1.33 раза меньше трафика и без лишнего парсинга
    put("duration", 25)
  }

  /**
   * Выключает поток. Остановка отложена на 0.7 с: при переходе между экранами
   * (дашборд ↔ экран ПК) onDispose старого экрана срабатывает после старта нового,
   * и мгновенная остановка гасила бы только что включённый поток.
   */
  fun stopScreenStream() {
    val epoch = streamEpoch.incrementAndGet()
    scope.launch {
      delay(700L)
      if (streamEpoch.get() != epoch) return@launch
      performStop()
    }
  }

  private fun performStop() {
    val wasActive = streamRequested
    streamRequested = false
    streamLeaseJob?.cancel()
    streamLeaseJob = null
    streamMjpegJob?.cancel()
    streamMjpegJob = null
    try {
      streamCall?.cancel()
    } catch (_: Exception) {
    }
    streamCall = null
    if (wasActive) {
      scope.launch { sendP2pCommand("/api/stream_stop", JSONObject()) }
    }
    _streamStatus.value = _streamStatus.value.copy(
      isActive = false,
      transport = "",
      fps = 0f,
      error = null
    )
  }

  private fun startLanMjpegIfPossible() {
    val st = _state.value
    if (!streamRequested || !st.isLanHttpAuthenticated || st.lanIp.isBlank()) return
    streamMjpegJob?.cancel()
    streamMjpegJob = scope.launch(Dispatchers.IO) {
      val ip = st.lanIp
      val port = st.port
      val cookie = sessionCookie ?: return@launch
      val client = httpClient.newBuilder()
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()
      val call = client.newCall(
        Request.Builder()
          .url("http://$ip:$port/api/stream")
          .header("Cookie", cookie)
          .build()
      )
      streamCall = call
      try {
        call.execute().use { resp ->
          val ctype = resp.header("Content-Type").orEmpty()
          if (!resp.isSuccessful || !ctype.contains("multipart", ignoreCase = true)) {
            _streamStatus.value = _streamStatus.value.copy(
              error = "MJPEG недоступен (HTTP ${resp.code}) — работаем через P2P"
            )
            return@use
          }
          _streamStatus.value = _streamStatus.value.copy(
            transport = "🟢 Wi-Fi LAN • живой MJPEG",
            error = null
          )
          val source = resp.body?.source() ?: return@use
          readMjpegStream(source)
        }
      } catch (e: Exception) {
        if (streamRequested) {
          _streamStatus.value = _streamStatus.value.copy(
            error = "LAN-поток прерван: ${e.message ?: "нет связи"} — переключаемся на P2P"
          )
        }
      } finally {
        if (streamCall === call) streamCall = null
      }
    }
  }

  /** Разбор multipart/x-mixed-replace: у каждого кадра есть Content-Length, читаем ровно его. */
  private suspend fun readMjpegStream(source: BufferedSource) {
    var contentLength = -1
    while (currentCoroutineContext().isActive && streamRequested) {
      val line = try {
        source.readUtf8Line()
      } catch (_: Exception) {
        null
      } ?: break
      when {
        line.startsWith("--") -> contentLength = -1
        line.startsWith("Content-Length:", ignoreCase = true) ->
          contentLength = line.substringAfter(':').trim().toIntOrNull() ?: -1
        line.isBlank() && contentLength in 1..5_000_000 -> {
          val bytes = try {
            source.readByteArray(contentLength.toLong())
          } catch (_: Exception) {
            break
          }
          publishFrameBytes(bytes, "🟢 Wi-Fi LAN • живой MJPEG")
          contentLength = -1
        }
      }
    }
  }

  /**
   * Бинарный кадр P2P: 4 байта seq + 8 байт timestamp (big-endian) + JPEG.
   * Без Base64 — меньше трафика и нет парсинга килобайтных JSON-строк на каждый кадр.
   * Топик: .../pc_stream_bin/<код>/<pc_id> — по нему отсекаем кадры чужих ПК.
   */
  fun ingestP2pStreamFrameBinary(topic: String, bytes: ByteArray, offset: Int, length: Int) {
    if (DirectP2pSocketEngine.currentRole == "CHILD") return
    if (!streamRequested || length <= 12) return
    loadPersistedPcIdentity()
    val parts = topic.split("/")
    val topicPcId = parts.lastOrNull().orEmpty().trim()
    val topicCode = if (parts.size >= 2) parts[parts.size - 2].trim() else ""
    if (!matchesConfiguredPc(topicCode, topicPcId)) return
    learnPcIdentity(topicCode, topicPcId)
    try {
      val buf = ByteBuffer.wrap(bytes, offset, length).order(ByteOrder.BIG_ENDIAN)
      buf.getInt() // seq
      val ts = buf.getLong()
      if (ts <= lastAcceptedFrameTs) return
      lastAcceptedFrameTs = ts
      val jpeg = ByteArray(buf.remaining())
      buf.get(jpeg)
      publishFrameBytes(jpeg, "🌐 Интернет P2P • живой поток")
    } catch (_: Exception) {
    }
  }

  /** Приём кадра живого потока, пришедшего через P2P-мост (Интернет). */
  fun ingestP2pStreamFrame(msg: JSONObject) {
    if (DirectP2pSocketEngine.currentRole == "CHILD") return
    if (!streamRequested) return
    loadPersistedPcIdentity()
    val msgCode = msg.optString("pairing_code", "").trim()
    val msgPcId = msg.optString("pc_id", "").trim()
    if (!matchesConfiguredPc(msgCode, msgPcId)) return
    learnPcIdentity(msgCode, msgPcId)
    val b64 = msg.optString("b64", "")
    if (b64.isBlank()) return
    val ts = msg.optLong("ts", System.currentTimeMillis())
    if (ts <= lastAcceptedFrameTs) return
    lastAcceptedFrameTs = ts
    scope.launch(Dispatchers.Default) {
      try {
        val bytes = Base64.decode(b64, Base64.DEFAULT)
        publishFrameBytes(bytes, "🌐 Интернет P2P • живой поток")
      } catch (_: Exception) {
      }
    }
  }

  private fun publishFrameBytes(bytes: ByteArray, transport: String) {
    if (bytes.isEmpty() || !streamRequested) return
    if (!decodeBusy.compareAndSet(false, true)) return
    scope.launch(Dispatchers.Default) {
      try {
        val opts = BitmapFactory.Options().apply {
          inPreferredConfig = Bitmap.Config.RGB_565
        }
        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts) ?: return@launch
        val now = System.currentTimeMillis()
        _liveFrame.value = PcLiveFrame(bitmap = bmp, timestampMs = now)
        updateStreamStats(now, transport)
      } catch (_: Exception) {
      } finally {
        decodeBusy.set(false)
      }
    }
  }

  private fun updateStreamStats(now: Long, transport: String) {
    frameCounter.incrementAndGet()
    var fps = 0f
    synchronized(frameLock) {
      frameTimes.addLast(now)
      while (frameTimes.size > 12) frameTimes.removeFirst()
      if (frameTimes.size >= 2) {
        val span = (frameTimes.last() - frameTimes.first()).coerceAtLeast(1L)
        fps = (frameTimes.size - 1) * 1000f / span.toFloat()
      }
    }
    _streamStatus.value = _streamStatus.value.copy(
      isActive = true,
      transport = transport,
      fps = (fps * 10f).toInt() / 10f,
      frames = frameCounter.get(),
      lastFrameMs = now,
      error = null
    )
  }

  /**
   * Подключение к Hosts Blocker:
   * - Через Интернет (4G/LTE/разные сети) по коду привязки P2P (если IP пустой или ПК в другой сети).
   * - Напрямую по домашнему Wi-Fi (`http://<ip>:<port>`) при нахождении в одной сети.
   */
  fun connectLanWithPassword(ip: String, port: Int, password: String, pairingCode: String = "") {
    val cleanIp = ip.trim()
    val cleanPort = if (port in 1024..65535) port else 8765
    loadPersistedPcIdentity()
    if (password.isNotBlank()) {
      pcPassword = password
      pcPrefs()?.edit()?.putString("pc_password", password)?.apply()
    }
    if (pairingCode.isNotBlank()) {
      configuredCode = pairingCode.trim()
      configuredPcId = ""
      codeExplicitlySet = true
      persistPcIdentity()
      _state.value = _state.value.copy(pairingCode = configuredCode)
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
                  screenReceivedAtMs = System.currentTimeMillis(),
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
    // Просмотр (синхронизация/снимок/поток) должен работать сразу, даже пока ПК ещё
    // не опознан по pc_id. Блокируем только ИЗМЕНЕНИЕ правил, чтобы не задеть чужой ПК.
    val isReadOnly = action == "/api/sync" || action == "/api/screen" ||
      action == "/api/stream_start" || action == "/api/stream_stop"
    if (!isReadOnly && configuredPcId.isBlank() && !codeExplicitlySet) {
      _state.value = _state.value.copy(
        statusMessage = "Укажите код P2P компьютера в «⚙ Настройка подключения к ПК»"
      )
      return
    }
    val code = configuredCode.trim().ifBlank { "101-701" }
    // Только свой код: раньше команда дублировалась ещё и на 101-701, из-за чего
    // можно было случайно управлять ЧУЖИМ компьютером с кодом по умолчанию.
    val pkt = JSONObject().apply {
      put("type", "PC_CMD")
      put("pairing_code", code)
      put("action", action)
      if (pcPassword.isNotBlank()) {
        put("password", pcPassword)
      }
      put("data", data)
      put("ts", System.currentTimeMillis())
    }
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_ROOT}/pc_cmd/$code",
      json = pkt,
      retain = false
    )
    DirectP2pSocketEngine.publishTcpSocketPacket(
      topic = "${DirectP2pSocketEngine.TOPIC_ROOT_LEGACY_PC}/pc_cmd/$code",
      json = pkt,
      retain = false
    )
  }

  fun requestSync() {
    loadPersistedPcIdentity()
    scope.launch {
      sendP2pCommand("/api/sync", JSONObject())
      if (_state.value.isLanHttpAuthenticated) {
        fetchLanStateNow()
      }
    }
  }

  fun requestPcScreenshot() {
    loadPersistedPcIdentity()
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
      pairingCode = current.pairingCode,
      computerTodaySec = todayComputerSec,
      computerTotalSec = msg.optInt("computer_total_sec", current.computerTotalSec),
      fullLock = fullState,
      entries = if (parsedEntries.isNotEmpty()) {
        parsedEntries.distinctBy { it.name.trim().lowercase() }
      } else {
        current.entries
      },
      activeWindows = windows,
      activityDate = todayIso,
      activityByUser = todayUsers,
      activityByDate = mergedByDate,
      hourlyUsageMinutes = hourlyList,
      offlineHistory = offlineList,
      screenBase64 = resolvedScrB64,
      screenTimestampMs = resolvedScrTs,
      screenReceivedAtMs = if (embeddedScrB64.isNotBlank()) System.currentTimeMillis() else current.screenReceivedAtMs,
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

  private val syncedPcActivityHashes = java.util.concurrent.ConcurrentHashMap<String, Int>()

  @Volatile
  private var lastPcActivitySyncMs = 0L

  /**
   * Сбрасывает историю активности ПК в SQLite, но ТОЛЬКО если данные реально изменились
   * и не чаще раза в 15 секунд. Раньше запись шла на каждый пакет PC_STATE (раз в 3–4 с),
   * что непрерывно дёргало диск и сажало батарею телефона.
   */
  private fun syncPcActivityByDateToRoom(byDate: Map<String, List<PcActivityUser>>) {
    if (byDate.isEmpty()) return
    val appCtx = DirectP2pSocketEngine.appContext ?: return
    val changed = LinkedHashMap<String, Pair<List<PcActivityUser>, Int>>()
    for ((dateIso, users) in byDate) {
      if (dateIso.isBlank()) continue
      val sig = users.joinToString("|") { u ->
        u.user + ":" + u.procs.joinToString(",") { "${it.label}=${it.seconds}" } +
          ";" + u.titles.joinToString(",") { "${it.label}=${it.seconds}" }
      }.hashCode()
      if (syncedPcActivityHashes[dateIso] != sig) {
        changed[dateIso] = Pair(users, sig)
      }
    }
    if (changed.isEmpty()) return
    val now = System.currentTimeMillis()
    if (now - lastPcActivitySyncMs < 15_000L) return
    lastPcActivitySyncMs = now
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
        for ((dateIso, entry) in changed) {
          val users = entry.first
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
          syncedPcActivityHashes[dateIso] = entry.second
        }
      } catch (_: Exception) {}
    }
  }
}
