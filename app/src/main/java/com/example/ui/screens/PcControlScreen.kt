package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.model.AggregatedActivityByDateItem
import com.example.data.pc.PcActivityItem
import com.example.data.pc.PcBlockerClient
import com.example.data.pc.PcEntry
import com.example.data.pc.PcStreamQuality
import com.example.ui.ActivityDateFilterPreset
import com.example.ui.ActivityDateFilterState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import android.widget.Toast
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.rememberCoroutineScope
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.util.ScreenShotManager
import com.example.util.ScreenVideoRecorder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private fun formatPcTimestamp(ms: Long): String =
  if (ms <= 0L) "" else SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(ms))

private fun formatDurationRu(totalSeconds: Long): String {
  val sec = totalSeconds.coerceAtLeast(0L)
  val hours = sec / 3600L
  val minutes = (sec % 3600L) / 60L
  val remSec = sec % 60L
  return when {
    hours > 0L -> "${hours} ч ${minutes} мин"
    minutes > 0L -> "${minutes} мин ${remSec} сек"
    else -> "${remSec} сек"
  }
}

private fun cleanPcWindowTitle(rawTitle: String): String {
  var t = rawTitle.trim()
  val suffixes = listOf(
    " - Google Chrome", " — Google Chrome",
    " - Microsoft​ Edge", " - Microsoft Edge", " — Microsoft Edge",
    " — Яндекс Браузер", " - Yandex",
    " - Opera", " — Opera",
    " - Mozilla Firefox", " — Mozilla Firefox",
    " - Brave", " — Brave"
  )
  for (s in suffixes) {
    if (t.endsWith(s, ignoreCase = true)) {
      t = t.dropLast(s.length).trim()
    }
  }
  return t
}

private fun buildWatchUrlForTitle(rawTitle: String): String {
  val cleaned = cleanPcWindowTitle(rawTitle)
  val low = cleaned.lowercase()
  return when {
    low.contains("youtube") -> {
      val q = cleaned.replace(Regex("(?i)\\s*[-—|]\\s*youtube.*$"), "").trim().ifBlank { cleaned }
      "https://www.youtube.com/results?search_query=" + Uri.encode(q)
    }
    low.contains("tiktok") -> {
      val q = cleaned.replace(Regex("(?i)\\s*[-—|]\\s*tiktok.*$"), "").trim().ifBlank { cleaned }
      "https://www.tiktok.com/search?q=" + Uri.encode(q)
    }
    low.contains("rutube") -> {
      val q = cleaned.replace(Regex("(?i)\\s*[-—|]\\s*rutube.*$"), "").trim().ifBlank { cleaned }
      "https://rutube.ru/search/?query=" + Uri.encode(q)
    }
    low.contains("vk") || low.contains("вконтакте") -> {
      "https://www.google.com/search?q=" + Uri.encode(cleaned)
    }
    else -> "https://www.google.com/search?q=" + Uri.encode(cleaned)
  }
}

@Composable
fun PcControlScreen(
  activeChildPairingCode: String = "101-701",
  dateFilterState: ActivityDateFilterState = ActivityDateFilterState.buildToday(),
  filteredSqlProcs: List<AggregatedActivityByDateItem> = emptyList(),
  filteredSqlTitles: List<AggregatedActivityByDateItem> = emptyList(),
  filteredSqlTotalSeconds: Long = 0L,
  onSelectTodayFilter: () -> Unit = {},
  onSelectYesterdayFilter: () -> Unit = {},
  onSelectCustomDateRange: (Long, Long) -> Unit = { _, _ -> },
  onBack: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val pcState by PcBlockerClient.state.collectAsState()

  var showConnectionSetup by remember { mutableStateOf(false) }
  var showFullLockSchedule by remember { mutableStateOf(false) }
  var showAddRuleDialog by remember { mutableStateOf(false) }
  var showFullPcScreenDialog by rememberSaveable { mutableStateOf(false) }
  var showCustomDateRangeDialog by remember { mutableStateOf(false) }
  var expandedEntryName by remember { mutableStateOf<String?>(null) }
  // История ПК не должна смешивать пользователей Windows: "" = все, иначе выбранная учётка.
  var selectedHistoryUser by rememberSaveable { mutableStateOf("") }

  var ipInput by remember(pcState.lanIp) { mutableStateOf(pcState.lanIp) }
  var portInput by remember(pcState.port) { mutableStateOf(pcState.port.toString()) }
  var passwordInput by remember { mutableStateOf("") }
  var pairingCodeInput by remember(pcState.pairingCode) {
    // Код P2P относится к ПК (не к профилю ребёнка). В домашней сети он подхватывается
    // из UDP-маяка, вручную вводится один раз и сохраняется.
    mutableStateOf(pcState.pairingCode.ifBlank { "101-701" })
  }

  // При смене ПК не тащим выбранную учётку/историю со старого компьютера.
  LaunchedEffect(pcState.pairingCode, pcState.pcName) {
    selectedHistoryUser = ""
  }

  var childAccountInput by remember(pcState.fullLock.childAccount) {
    mutableStateOf(pcState.fullLock.childAccount)
  }
  var flDailyInput by remember(pcState.fullLock.daily) {
    mutableStateOf(if (pcState.fullLock.daily > 0) pcState.fullLock.daily.toString() else "")
  }
  var flTotalInput by remember(pcState.fullLock.total) {
    mutableStateOf(if (pcState.fullLock.total > 0) pcState.fullLock.total.toString() else "")
  }
  var flFromInput by remember(pcState.fullLock.from) { mutableStateOf(pcState.fullLock.from) }
  var flToInput by remember(pcState.fullLock.to) { mutableStateOf(pcState.fullLock.to) }

  val coroutineScope = rememberCoroutineScope()
  var isRecordingPcVideo by remember { mutableStateOf(false) }
  var pcRecordingSeconds by remember { mutableIntStateOf(0) }
  var recordedPcVideoUri by remember { mutableStateOf<Uri?>(null) }
  var autoRefreshScreen by rememberSaveable { mutableStateOf(true) }
  // Полноэкранный плеер: щипок = зум, панорамирование двумя пальцами, HUD
  // скрывается через 3 с бездействия и возвращается одиночным тапом.
  var pcZoom by rememberSaveable { mutableStateOf(1f) }
  var pcPanX by rememberSaveable { mutableStateOf(0f) }
  var pcPanY by rememberSaveable { mutableStateOf(0f) }
  var pcHudVisible by remember { mutableStateOf(true) }
  var pcHudInteraction by remember { mutableIntStateOf(0) }
  // Качество сохраняем между поворотами и перезапусками: иначе после поворота
  // HD сбрасывался на «Баланс» 640×360.
  var pcStreamQuality by rememberSaveable(
    stateSaver = androidx.compose.runtime.saveable.Saver(
      save = { it.name },
      restore = { name -> PcStreamQuality.valueOf(name) }
    )
  ) { mutableStateOf(PcBlockerClient.savedStreamQuality()) }
  LaunchedEffect(pcStreamQuality) {
    PcBlockerClient.saveStreamQuality(pcStreamQuality)
  }

  // Автоскрытие полупрозрачного меню: 3 секунды без касаний/зума и оверлеи уходят.
  LaunchedEffect(showFullPcScreenDialog, pcHudVisible, pcHudInteraction, pcZoom) {
    if (showFullPcScreenDialog && pcHudVisible) {
      delay(3_000L)
      pcHudVisible = false
    }
  }

  val liveFrame by PcBlockerClient.liveFrame.collectAsState()
  val streamStatus by PcBlockerClient.streamStatus.collectAsState()

  // Живой поток экрана ПК держим включённым ТОЛЬКО пока этот экран виден и приложение
  // на переднем плане: свернули приложение — поток сразу гаснет (экономия батареи и трафика).
  var isAppResumed by remember { mutableStateOf(true) }
  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      when (event) {
        Lifecycle.Event.ON_RESUME -> isAppResumed = true
        Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> isAppResumed = false
        else -> {}
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }

  // Одиночный кадр-снимок (fallback, если ПК старой версии и живого потока ещё нет).
  // Base64 декодируем в фоне, а не на UI-потоке, как было раньше.
  var snapshotBitmap by remember { mutableStateOf<Bitmap?>(null) }
  LaunchedEffect(pcState.screenBase64) {
    val raw = pcState.screenBase64
    if (raw.isBlank()) {
      // После смены ПК/очистки состояния старый снимок показывать нельзя.
      snapshotBitmap = null
      return@LaunchedEffect
    }
    snapshotBitmap = withContext(Dispatchers.Default) {
      try {
        val bytes = Base64.decode(raw.substringAfter("base64,"), Base64.DEFAULT)
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
      } catch (_: Exception) {
        null
      }
    }
  }

  val pcScreenBitmap = liveFrame?.bitmap ?: snapshotBitmap

  val currentPcSummary = remember(pcState.activeWindows, pcState.isOnline) {
    val win = pcState.activeWindows.firstOrNull()
    if (win != null) {
      val cleaned = cleanPcWindowTitle(win.title).ifBlank { win.proc }
      "ПК (${win.proc}) • Смотрит: $cleaned"
    } else if (pcState.isOnline) {
      "ПК онлайн • Ожидание открытия окна"
    } else {
      "ПК оффлайн • ожидание кадра"
    }
  }

  LaunchedEffect(
    isAppResumed,
    autoRefreshScreen,
    showFullPcScreenDialog,
    pcStreamQuality,
    // После ввода пароля LAN-сессия появляется уже при открытом экране — нужно
    // перезапустить поток, иначе MJPEG так и не стартует (оставался только P2P).
    pcState.isLanHttpAuthenticated
  ) {
    if (isAppResumed && autoRefreshScreen) {
      // На весь экран — плавнее (8 к/с), в карточке — экономные 4 к/с.
      PcBlockerClient.startScreenStream(
        fps = if (showFullPcScreenDialog) 6 else 4,
        quality = pcStreamQuality
      )
    } else {
      PcBlockerClient.stopScreenStream()
    }
  }

  DisposableEffect(Unit) {
    onDispose { PcBlockerClient.stopScreenStream() }
  }

  // Пароль уже сохранялся раньше — автоматически входим в LAN-панель. Если IP ПК
  // неизвестен (UDP-маяк не дошёл), один раз ищем компьютер по Wi-Fi подсети.
  LaunchedEffect(pcState.lanIp, pcState.isLanHttpAuthenticated) {
    if (!pcState.isLanHttpAuthenticated) {
      if (pcState.lanIp.isBlank()) {
        PcBlockerClient.discoverLanPc()
      } else {
        PcBlockerClient.ensureSavedLanLogin()
      }
    }
  }

  LaunchedEffect(isRecordingPcVideo) {
    if (isRecordingPcVideo) {
      ScreenVideoRecorder.startRecording(fps = 2)
      pcRecordingSeconds = 0
      var frameTick = 0
      while (isRecordingPcVideo) {
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val frameBmp = liveFrame?.bitmap ?: pcScreenBitmap ?: renderWatchedContentFrameBitmap(
          childName = "Компьютер (${pcState.pairingCode})",
          rawAppSummary = currentPcSummary,
          timeStr = timeNow
        )
        ScreenVideoRecorder.addFrame(frameBmp)
        delay(500)
        frameTick++
        if (frameTick % 2 == 0) pcRecordingSeconds++
      }
    }
  }

  LaunchedEffect(activeChildPairingCode) {
    PcBlockerClient.requestSync()
    PcBlockerClient.requestPcScreenshot()
  }

  val textFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = CyanLight,
    unfocusedBorderColor = Slate400.copy(alpha = 0.4f),
    focusedLabelColor = CyanLight,
    unfocusedLabelColor = Slate400
  )

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(Slate950)
      .padding(horizontal = 16.dp)
      .testTag("pc_control_screen"),
    verticalArrangement = Arrangement.spacedBy(14.dp),
    contentPadding = PaddingValues(vertical = 12.dp)
  ) {
    // 1. HEADER & STATUS BANNER
    item {
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = BorderStroke(
          1.5.dp,
          if (pcState.isOnline) EmeraldShield else CyanLight.copy(alpha = 0.5f)
        ),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              if (onBack != null) {
                IconButton(
                  onClick = onBack,
                  modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Slate800)
                    .testTag("pc_back_button")
                ) {
                  Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Назад",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
              Box(
                modifier = Modifier
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(if (pcState.isOnline) EmeraldContainer else CyanContainer),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  Icons.Default.Computer,
                  contentDescription = null,
                  tint = if (pcState.isOnline) EmeraldShieldLight else CyanLight,
                  modifier = Modifier.size(24.dp)
                )
              }
              Column {
                Text(
                  text = "💻 ${pcState.pcName}",
                  color = Color.White,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = pcState.transportLabel,
                  color = if (pcState.isOnline) EmeraldShieldLight else AmberWarning,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              IconButton(
                onClick = { PcBlockerClient.requestSync() },
                modifier = Modifier
                  .size(38.dp)
                  .clip(CircleShape)
                  .background(Slate800)
                  .testTag("pc_refresh_button")
              ) {
                Icon(
                  Icons.Default.Refresh,
                  contentDescription = "Обновить статус ПК",
                  tint = CyanLight,
                  modifier = Modifier.size(18.dp)
                )
              }
              IconButton(
                onClick = { showConnectionSetup = !showConnectionSetup },
                modifier = Modifier
                  .size(38.dp)
                  .clip(CircleShape)
                  .background(Slate800)
                  .testTag("pc_settings_button")
              ) {
                Icon(
                  Icons.Default.Settings,
                  contentDescription = "Настройка подключения к ПК",
                  tint = Color.White,
                  modifier = Modifier.size(18.dp)
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Slate950)
              .padding(horizontal = 12.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column {
              Text("Время за ПК сегодня", color = Slate400, fontSize = 11.sp)
              Text(
                text = "${pcState.computerTodaySec / 60} мин (всего: ${pcState.computerTotalSec / 60} мин)",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
            }
            Column(horizontalAlignment = Alignment.End) {
              Text("Код P2P / IP", color = Slate400, fontSize = 11.sp)
              Text(
                text = if (pcState.lanIp.isNotBlank()) "${pcState.lanIp}:${pcState.port}" else "P2P: ${pcState.pairingCode}",
                color = CyanLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }

          pcState.statusMessage?.let { msg ->
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "ℹ️ $msg",
              color = CyanLight,
              fontSize = 12.sp,
              fontWeight = FontWeight.Medium
            )
          }

          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(
            onClick = { com.example.util.ApkUpdateInstaller.saveAndShareBundledHostsBlocker(context) },
            border = BorderStroke(1.dp, EmeraldShieldLight.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(10.dp),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().testTag("pc_export_hosts_blocker_button")
          ) {
            Icon(Icons.Default.Computer, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "📥 Скачать / Отправить hosts_blocker.py на ПК (бессрочно)",
              color = EmeraldShieldLight,
              fontWeight = FontWeight.Bold,
              fontSize = 12.sp
            )
          }

          AnimatedVisibility(visible = showConnectionSetup) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Slate950)
                .padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "Подключение к Hosts Blocker на ПК (Wi-Fi или P2P)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
              )
              Text(
                text = "В домашней сети Wi-Fi укажите IP и пароль из «📱 Панель» на ПК. Для управления через Интернет укажите одинаковый Код привязки P2P на ПК и здесь.",
                color = Slate400,
                fontSize = 11.sp,
                lineHeight = 15.sp
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = ipInput,
                  onValueChange = { ipInput = it },
                  label = { Text("IP ПК (Wi-Fi)", fontSize = 11.sp) },
                  placeholder = { Text("192.168.1.15", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier
                    .weight(1.4f)
                    .testTag("pc_ip_input")
                )
                OutlinedTextField(
                  value = portInput,
                  onValueChange = { portInput = it },
                  label = { Text("Порт", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier.weight(0.7f)
                )
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = passwordInput,
                  onValueChange = { passwordInput = it },
                  label = {
                    Text(
                      if (PcBlockerClient.hasSavedPassword()) "Пароль ПК (сохранён)" else "Пароль программы ПК",
                      fontSize = 11.sp
                    )
                  },
                  placeholder = {
                    if (PcBlockerClient.hasSavedPassword()) {
                      Text("можно оставить пустым", fontSize = 10.sp)
                    }
                  },
                  visualTransformation = PasswordVisualTransformation(),
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier
                    .weight(1.2f)
                    .testTag("pc_password_input")
                )
                OutlinedTextField(
                  value = pairingCodeInput,
                  onValueChange = { pairingCodeInput = it },
                  label = { Text("Код P2P", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier
                    .weight(0.9f)
                    .testTag("pc_pairing_code_input")
                )
              }
              Button(
                onClick = {
                  PcBlockerClient.connectLanWithPassword(
                    ip = ipInput,
                    port = portInput.toIntOrNull() ?: 8765,
                    password = passwordInput,
                    pairingCode = pairingCodeInput
                  )
                  showConnectionSetup = false
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .testTag("pc_connect_submit_button")
              ) {
                Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Подключить и синхронизировать ПК", fontWeight = FontWeight.Bold, fontSize = 13.sp)
              }
              androidx.compose.material3.TextButton(
                onClick = {
                  PcBlockerClient.resetPcIdentity()
                  showConnectionSetup = false
                },
                modifier = Modifier.fillMaxWidth()
              ) {
                Text(
                  "Сбросить привязку к ПК (если компьютер не находится)",
                  fontSize = 11.sp,
                  color = Color(0xFF94A3B8)
                )
              }
            }
          }
        }
      }
    }

    if (pcState.lanIp.isNotBlank() && !pcState.isLanHttpAuthenticated &&
      !PcBlockerClient.hasSavedPassword()
    ) {
      item {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900),
          border = BorderStroke(1.dp, CyanLight.copy(alpha = 0.4f)),
          modifier = Modifier.fillMaxWidth()
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              Icons.Default.Wifi,
              contentDescription = null,
              tint = CyanLight,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              "ПК в вашем Wi-Fi. Введите пароль панели — поток пойдёт напрямую, без интернета.",
              color = Color.White,
              fontSize = 11.sp,
              modifier = Modifier.weight(1f)
            )
            androidx.compose.material3.TextButton(onClick = { showConnectionSetup = true }) {
              Text("Ввести", color = CyanLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }

    // 2. FULL PC ACCOUNT LOCK CARD (net user /active:no + WTSLogoffSession)
    item {
      val fl = pcState.fullLock
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (fl.blocked) RoseContainer.copy(alpha = 0.35f) else Slate900
        ),
        border = BorderStroke(1.5.dp, if (fl.blocked) RoseLock else Slate800),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("pc_full_lock_card")
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .clip(CircleShape)
                  .background(if (fl.blocked) RoseContainer else Slate800),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = if (fl.blocked) Icons.Default.Lock else Icons.Default.LockOpen,
                  contentDescription = null,
                  tint = if (fl.blocked) RoseLock else EmeraldShieldLight,
                  modifier = Modifier.size(20.dp)
                )
              }
              Column {
                Text(
                  text = "🔒 Полная блокировка ПК Windows",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 15.sp
                )
                val detectedWinUser = fl.childAccount.ifBlank {
                  pcState.activeWindows.firstOrNull()?.user?.ifBlank { null }
                    ?: pcState.activityByUser.firstOrNull()?.user.orEmpty()
                }
                Text(
                  text = if (detectedWinUser.isNotBlank()) {
                    "Аккаунт: $detectedWinUser • ${fl.text}"
                  } else {
                    "Статус: ${fl.text} (автоопределение активного сеанса)"
                  },
                  color = if (fl.blocked) RoseLock else Slate400,
                  fontSize = 12.sp
                )
              }
            }
            IconButton(
              onClick = { showFullLockSchedule = !showFullLockSchedule },
              modifier = Modifier.testTag("pc_full_lock_schedule_toggle")
            ) {
              Icon(
                imageVector = if (showFullLockSchedule) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = "Настроить расписание и аккаунт",
                tint = CyanLight
              )
            }
          }

          // Все учётные записи Windows: переключение одним тапом, чтобы блокировать
          // не только raufh, но и любого другого пользователя этого ПК.
          val allWindowsUsers = remember(
            pcState.windowsUsers,
            fl.childAccount,
            pcState.activeWindows,
            pcState.activityByUser
          ) {
            (
              pcState.windowsUsers +
                pcState.activeWindows.map { it.user } +
                pcState.activityByUser.map { it.user } +
                listOf(fl.childAccount)
              )
              .map { it.trim() }
              .filter {
                it.isNotBlank() &&
                  !it.equals("SYSTEM", ignoreCase = true) &&
                  !it.equals("User", ignoreCase = true)
              }
              .distinctBy { it.lowercase() }
          }
          if (allWindowsUsers.isNotEmpty()) {
            Column(modifier = Modifier.fillMaxWidth()) {
              Text(
                text = "👤 Учётные записи Windows на этом ПК — выберите, кого блокировать:",
                color = Slate400,
                fontSize = 10.sp,
                maxLines = 2
              )
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                allWindowsUsers.forEach { winUser ->
                  val selected = childAccountInput.trim().equals(winUser, ignoreCase = true) ||
                    (childAccountInput.isBlank() && fl.childAccount.equals(winUser, ignoreCase = true))
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(50))
                      .background(if (selected) CyanLight.copy(alpha = 0.22f) else Slate950)
                      .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(50))
                      .clickable {
                        childAccountInput = winUser
                        PcBlockerClient.selectFullPcLockAccount(winUser)
                      }
                      .padding(horizontal = 10.dp, vertical = 6.dp)
                      .testTag("pc_full_lock_user_$winUser")
                  ) {
                    Text(
                      text = winUser,
                      color = if (selected) CyanLight else Slate200,
                      fontSize = 11.sp,
                      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                      maxLines = 1
                    )
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Button(
            onClick = {
              val autoAcct = pcState.activeWindows.firstOrNull()?.user?.ifBlank { null }
                ?: pcState.activityByUser.firstOrNull()?.user.orEmpty()
              val targetAcct = childAccountInput.trim().ifBlank {
                fl.childAccount.trim().ifBlank { autoAcct }
              }
              if (targetAcct.isNotBlank()) {
                childAccountInput = targetAcct
              }
              PcBlockerClient.toggleFullPcLock(lockNow = !fl.now, childAccount = targetAcct)
            },
            colors = ButtonDefaults.buttonColors(
              containerColor = if (fl.now) EmeraldShield else RoseLock,
              contentColor = Color.White
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("pc_full_lock_toggle_button")
          ) {
            Icon(
              imageVector = if (fl.now) Icons.Default.LockOpen else Icons.Default.Lock,
              contentDescription = null,
              modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (fl.now) "🔓 Разблокировать вход на ПК сейчас" else "🔒 Заблокировать ПК сейчас (выход из сеанса)",
              fontWeight = FontWeight.ExtraBold,
              fontSize = 13.sp
            )
          }

          AnimatedVisibility(visible = showFullLockSchedule) {
            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Slate950)
                .padding(12.dp),
              verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Text(
                text = "Расписание доступа к ПК и учётная запись Windows",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
              )
              OutlinedTextField(
                value = childAccountInput,
                onValueChange = { childAccountInput = it },
                label = { Text("Имя аккаунта Windows (net user)", fontSize = 11.sp) },
                singleLine = true,
                colors = textFieldColors,
                modifier = Modifier.fillMaxWidth()
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = flDailyInput,
                  onValueChange = { flDailyInput = it },
                  label = { Text("В день, мин", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                  value = flTotalInput,
                  onValueChange = { flTotalInput = it },
                  label = { Text("Всего, мин", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier.weight(1f)
                )
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                  value = flFromInput,
                  onValueChange = { flFromInput = it },
                  label = { Text("Разрешено с (ЧЧ:ММ)", fontSize = 11.sp) },
                  placeholder = { Text("08:00", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier.weight(1f)
                )
                OutlinedTextField(
                  value = flToInput,
                  onValueChange = { flToInput = it },
                  label = { Text("до (ЧЧ:ММ)", fontSize = 11.sp) },
                  placeholder = { Text("21:00", fontSize = 11.sp) },
                  singleLine = true,
                  colors = textFieldColors,
                  modifier = Modifier.weight(1f)
                )
              }
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = {
                    PcBlockerClient.saveFullPcLockSchedule(
                      childAccount = childAccountInput.ifBlank { "User" },
                      daily = flDailyInput.toIntOrNull() ?: 0,
                      total = flTotalInput.toIntOrNull() ?: 0,
                      from = flFromInput,
                      to = flToInput
                    )
                    showFullLockSchedule = false
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.weight(1.3f)
                ) {
                  Text("Сохранить расписание", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
                OutlinedButton(
                  onClick = { PcBlockerClient.resetEntryCounter("__computer__") },
                  border = BorderStroke(1.dp, Slate400),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Сбросить таймер ПК", color = Color.White, fontSize = 11.sp)
                }
              }
            }
          }
        }
      }
    }

    // 3. MDM PC LIVE WATCHED CONTENT & ACTIVE WINDOW CARD
    item {
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = BorderStroke(1.5.dp, CyanLight.copy(alpha = 0.45f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.Visibility, contentDescription = null, tint = CyanLight, modifier = Modifier.size(22.dp))
              Column {
                Text(
                  text = "👁 Живой экран ПК и что смотрит ребёнок",
                  color = Color.White,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 15.sp
                )
                Text(
                  text = "Живой кадр монитора + открытое видео, вкладка или игра",
                  color = Slate400,
                  fontSize = 11.sp
                )
              }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = if (streamStatus.isActive && streamStatus.frames > 0) "${streamStatus.fps} к/с" else "LIVE",
                color = if (streamStatus.isActive) EmeraldShieldLight else Slate400,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
              Switch(
                checked = autoRefreshScreen,
                onCheckedChange = { autoRefreshScreen = it },
                colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = EmeraldShield)
              )
              IconButton(onClick = { PcBlockerClient.requestPcScreenshot() }) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить экран ПК", tint = CyanLight)
              }
            }
          }

          val fallbackMonitorBitmap = remember(pcState.pcName, currentPcSummary) {
            val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            renderWatchedContentFrameBitmap(
              childName = "Компьютер (${pcState.pcName})",
              rawAppSummary = currentPcSummary,
              timeStr = timeNow
            )
          }
          val displayMonitorBitmap = pcScreenBitmap ?: fallbackMonitorBitmap

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(210.dp)
              .clip(RoundedCornerShape(14.dp))
              .background(Slate950)
              .clickable {
                PcBlockerClient.requestPcScreenshot()
                showFullPcScreenDialog = true
              }
          ) {
            Image(
              bitmap = displayMonitorBitmap.asImageBitmap(),
              contentDescription = "Живой экран ПК",
              contentScale = ContentScale.Fit,
              modifier = Modifier.fillMaxSize()
            )
            Box(
              modifier = Modifier
                .align(Alignment.TopStart)
                .padding(8.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Black.copy(alpha = 0.72f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = when {
                  streamStatus.isActive && streamStatus.frames > 0 ->
                    "🟢 LIVE ПОТОК ${streamStatus.fps} к/с • ${streamStatus.transport} • нажмите для полного экрана"
                  pcScreenBitmap != null ->
                    "🖥 КАДР ЭКРАНА ПК • нажмите для полного экрана"
                  else -> "🖥 ПОДКЛЮЧЕНИЕ К ЭКРАНУ ПК…"
                },
                color = if (pcScreenBitmap != null) EmeraldShieldLight else CyanLight,
                fontSize = 9.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.ExtraBold
              )
            }
            streamStatus.error?.let { err ->
              Box(
                modifier = Modifier
                  .align(Alignment.BottomStart)
                  .padding(8.dp)
                  .clip(RoundedCornerShape(8.dp))
                  .background(Color.Black.copy(alpha = 0.72f))
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Text(
                  text = "⚠️ $err",
                  color = AmberWarning,
                  fontSize = 9.sp,
                  maxLines = 2,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }

          // Качество живого потока: чем меньше кадр, тем меньше трафика и нагрузки.
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text("Качество:", color = Slate400, fontSize = 10.sp)
            PcStreamQuality.values().forEach { option ->
              val selected = pcStreamQuality == option
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (selected) CyanLight.copy(alpha = 0.22f) else Slate950)
                  .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(8.dp))
                  .clickable { pcStreamQuality = option }
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Text(
                  text = "${option.label} ${option.width}×${option.height}",
                  color = if (selected) CyanLight else Slate200,
                  fontSize = 10.sp,
                  fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                  maxLines = 1
                )
              }
            }
          }

          // HD-скриншот и HD-запись выполняет сам ПК-агент: файл сохраняется на диск
          // компьютера в исходном разрешении (без сжатия в превью 800×450).
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedButton(
              onClick = { PcBlockerClient.requestPcScreenshotHd() },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .testTag("pc_screenshot_hd_button")
            ) {
              Icon(Icons.Default.CameraAlt, contentDescription = null, tint = CyanLight, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("HD-кадр на ПК", color = CyanLight, fontSize = 11.sp, maxLines = 1)
            }
            OutlinedButton(
              onClick = {
                if (pcState.pcRecordingActive) PcBlockerClient.stopPcRecording()
                else PcBlockerClient.startPcRecording()
              },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .height(40.dp)
                .testTag("pc_record_hd_button")
            ) {
              Icon(
                imageVector = if (pcState.pcRecordingActive) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                contentDescription = null,
                tint = if (pcState.pcRecordingActive) RoseLock else CyanLight,
                modifier = Modifier.size(15.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (pcState.pcRecordingActive) "Стоп ${pcState.pcRecordingSeconds}с" else "REC HD на ПК",
                color = if (pcState.pcRecordingActive) RoseLock else CyanLight,
                fontSize = 11.sp,
                maxLines = 1
              )
            }
          }
          val pcAgentSupportsHd = remember(pcState.pcScriptVersion) {
            val parts = pcState.pcScriptVersion.split(".").mapNotNull { it.trim().toIntOrNull() }
            val major = parts.getOrNull(0) ?: 0
            val minor = parts.getOrNull(1) ?: 0
            major > 5 || (major == 5 && minor >= 5)
          }
          if (!pcAgentSupportsHd) {
            Text(
              text = "⚠️ ПК-агент${pcState.pcScriptVersion.takeIf { it.isNotBlank() }?.let { " v$it" } ?: ""} " +
                "не поддерживает HD-кадр/REC HD. Обновите hosts_blocker.exe на ПК до 5.5+ " +
                "(artifact KidsGuard-PC-Agent).",
              color = AmberWarning,
              fontSize = 10.sp,
              maxLines = 3
            )
          }
          val hdMediaInfo = when {
            pcState.pcRecordingActive -> "🔴 ПК пишет экран: ${pcState.pcRecordingPath.ifBlank { "…" }} (${pcState.pcRecordingFrames} кадров)"
            pcState.pcRecordingPath.isNotBlank() -> "🎥 Последняя запись ПК: ${pcState.pcRecordingPath}"
            pcState.lastScreenshotPath.isNotBlank() -> "📸 Последний HD-кадр: ${pcState.lastScreenshotPath}"
            else -> null
          }
          if (hdMediaInfo != null) {
            Text(
              text = hdMediaInfo,
              color = if (pcState.pcRecordingActive) RoseLock else Slate400,
              fontSize = 10.sp,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }

          if (pcState.activeWindows.isEmpty()) {
            Text(
              text = "Когда ребёнок откроет видео, браузер или игру на ПК, здесь сразу появится название и кнопка быстрого просмотра.",
              color = Slate400,
              fontSize = 12.sp
            )
          } else {
            pcState.activeWindows.forEach { win ->
              val cleanedTitle = cleanPcWindowTitle(win.title).ifBlank { "Рабочий стол Windows" }
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(14.dp))
                  .background(Slate950)
                  .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Text(
                  text = "🎬 «$cleanedTitle»",
                  color = Color.White,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.ExtraBold
                )
                Text(
                  text = "Программа: ${win.proc} • Пользователь: ${win.user}",
                  color = EmeraldShieldLight,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold
                )
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Button(
                    onClick = {
                      PcBlockerClient.requestPcScreenshot()
                      showFullPcScreenDialog = true
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1.4f)
                  ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Посмотреть у себя", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                  }
                  OutlinedButton(
                    onClick = {
                      val ruleName = win.proc.substringBeforeLast(".").replaceFirstChar { it.uppercase() }
                      PcBlockerClient.addEntry(
                        name = ruleName,
                        domains = emptyList(),
                        programs = listOf(win.proc)
                      )
                    },
                    border = BorderStroke(1.dp, RoseLock),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f)
                  ) {
                    Icon(Icons.Default.Block, contentDescription = null, tint = RoseLock, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("В блок", color = RoseLock, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                  }
                }
              }
            }
          }

          // Кнопки записи видео MP4 и снимка активности ПК в галерею родителя
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = {
                val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                val bmp = pcScreenBitmap ?: renderWatchedContentFrameBitmap(
                  childName = "Компьютер (${pcState.pairingCode})",
                  rawAppSummary = currentPcSummary,
                  timeStr = timeNow
                )
                val res = ScreenShotManager.saveBitmapToGalleryWithUri(context, bmp, "PC")
                Toast.makeText(
                  context,
                  if (res != null) "📸 Снимок ПК сохранён в Галерею (DCIM/KidsGuard)!" else "Ошибка сохранения снимка",
                  Toast.LENGTH_LONG
                ).show()
              },
              colors = ButtonDefaults.buttonColors(containerColor = CyanContainer, contentColor = CyanLight),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
              modifier = Modifier.weight(1f).testTag("pc_save_snapshot_button")
            ) {
              Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Снимок в галерею", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }

            Button(
              onClick = {
                if (!isRecordingPcVideo) {
                  recordedPcVideoUri = null
                  isRecordingPcVideo = true
                  Toast.makeText(context, "🔴 Запись видео MP4 началась (HD)...", Toast.LENGTH_SHORT).show()
                } else {
                  isRecordingPcVideo = false
                  coroutineScope.launch {
                    val res = ScreenVideoRecorder.stopAndSaveToGallery(context, "PC")
                    recordedPcVideoUri = res.uri
                    Toast.makeText(
                      context,
                      if (res.success) "✅ Видео MP4 сохранено в Галерею (Movies/KidsGuard)!" else "Не удалось сохранить видео",
                      Toast.LENGTH_LONG
                    ).show()
                  }
                }
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isRecordingPcVideo) RoseLock else RoseContainer,
                contentColor = if (isRecordingPcVideo) Color.White else RoseLock
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp),
              modifier = Modifier.weight(1f).testTag("pc_record_mp4_button")
            ) {
              Icon(
                if (isRecordingPcVideo) Icons.Default.Stop else Icons.Default.Videocam,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
              )
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                if (isRecordingPcVideo) "Стоп (${pcRecordingSeconds}с) • В MP4" else "Запись MP4",
                fontWeight = FontWeight.ExtraBold,
                fontSize = 12.sp
              )
            }
          }

          if (recordedPcVideoUri != null) {
            Button(
              onClick = {
                try {
                  val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(recordedPcVideoUri, "video/mp4")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                  }
                  context.startActivity(intent)
                } catch (_: Exception) {
                  Toast.makeText(context, "Видео сохранено в Галерее -> Movies/KidsGuard", Toast.LENGTH_LONG).show()
                }
              },
              colors = ButtonDefaults.buttonColors(containerColor = EmeraldContainer, contentColor = EmeraldShieldLight),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.fillMaxWidth()
            ) {
              Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Открыть сохранённую видеозапись MP4 в Галерее", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }
      }
    }

    // 4. SECTION HEADER: BLOCK RULES (HOSTS + BROWSER POLICIES + FIREWALL + PROCESS KILLER)
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "🛡 Сайты, игры и программы на ПК",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 16.sp
          )
          Text(
            text = "Блокировка: hosts + Политики браузеров + Firewall + Закрытие .exe",
            color = Slate400,
            fontSize = 11.sp
          )
        }
        Button(
          onClick = { showAddRuleDialog = true },
          colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
          modifier = Modifier
            .height(36.dp)
            .testTag("pc_add_rule_button")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Добавить", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
      }
    }

    // 5. LIST OF PC ENTRIES
    items(pcState.entries, key = { it.name }) { entry ->
      PcEntryCard(
        entry = entry,
        isExpanded = expandedEntryName == entry.name,
        onToggleExpand = {
          expandedEntryName = if (expandedEntryName == entry.name) null else entry.name
        },
        onToggleEnabled = { enabled ->
          PcBlockerClient.toggleEntry(entry.name, enabled)
        },
        onSaveLimit = { daily, total, from, to, closeTab ->
          PcBlockerClient.updateEntryLimit(entry.name, daily, total, from, to, closeTab)
          expandedEntryName = null
        },
        onResetCounter = {
          PcBlockerClient.resetEntryCounter(entry.name)
        },
        onDelete = {
          PcBlockerClient.deleteEntry(entry.name)
        }
      )
    }

    // 6. DATE-FILTERED PC ACTIVITY HISTORY (TODAY / YESTERDAY / CUSTOM DATE RANGE)
    item {
      // Aggregate fallback from pcState.activityByDate strictly within [startDateIso .. endDateIso] if SQLite is still syncing
      val fallbackDatedUsers = remember(
        pcState.activityByDate,
        dateFilterState.startDateIso,
        dateFilterState.endDateIso
      ) {
        pcState.activityByDate
          .filterKeys { dayIso ->
            dayIso >= dateFilterState.startDateIso && dayIso <= dateFilterState.endDateIso
          }
          .values
          .flatten()
      }

      // Время запуска приходит с ПК ("started"), а SQLite-агрегат его не хранит —
      // подстраховываемся значением из последнего пакета PC_STATE.
      val fallbackProcStart = remember(fallbackDatedUsers) {
        fallbackDatedUsers
          .flatMap { it.procs }
          .filter { it.startedAtMs > 0L }
          .groupBy { it.label.lowercase().trim() }
          .mapValues { (_, items) -> items.minOf { it.startedAtMs } }
      }
      val fallbackTitleStart = remember(fallbackDatedUsers) {
        fallbackDatedUsers
          .flatMap { it.titles }
          .filter { it.startedAtMs > 0L }
          .groupBy { it.label.lowercase().trim() }
          .mapValues { (_, items) -> items.minOf { it.startedAtMs } }
      }

      val displayProcs: List<PcActivityItem> = remember(filteredSqlProcs, fallbackDatedUsers, fallbackProcStart) {
        if (filteredSqlProcs.isNotEmpty()) {
          filteredSqlProcs.map {
            PcActivityItem(
              label = it.packageName,
              seconds = it.totalDurationSeconds.toInt(),
              startedAtMs = it.startedAtMs.takeIf { s -> s > 0L }
                ?: fallbackProcStart[it.packageName.lowercase().trim()]
                ?: 0L,
              userName = it.appName
            )
          }
        } else {
          fallbackDatedUsers
            .flatMap { u -> u.procs.map { p -> p.copy(userName = u.user) } }
            .groupBy { it.userName.lowercase().trim() + "|" + it.label.lowercase().trim() }
            .map { (_, items) ->
              PcActivityItem(
                label = items.first().label,
                seconds = items.sumOf { it.seconds },
                startedAtMs = items.mapNotNull { i -> i.startedAtMs.takeIf { it > 0L } }.minOrNull() ?: 0L,
                userName = items.first().userName
              )
            }
            .filter { it.seconds > 0 }
            .sortedByDescending { it.seconds }
        }
      }

      val displayTitles: List<PcActivityItem> = remember(filteredSqlTitles, fallbackDatedUsers, fallbackTitleStart) {
        if (filteredSqlTitles.isNotEmpty()) {
          filteredSqlTitles.map {
            PcActivityItem(
              label = it.windowTitle,
              seconds = it.totalDurationSeconds.toInt(),
              startedAtMs = it.startedAtMs.takeIf { s -> s > 0L }
                ?: fallbackTitleStart[it.windowTitle.lowercase().trim()]
                ?: 0L,
              userName = it.appName
            )
          }
        } else {
          fallbackDatedUsers
            .flatMap { u -> u.titles.map { t -> t.copy(userName = u.user) } }
            .groupBy { it.userName.lowercase().trim() + "|" + it.label.lowercase().trim() }
            .map { (_, items) ->
              PcActivityItem(
                label = items.first().label,
                seconds = items.sumOf { it.seconds },
                startedAtMs = items.mapNotNull { i -> i.startedAtMs.takeIf { it > 0L } }.minOrNull() ?: 0L,
                userName = items.first().userName
              )
            }
            .filter { it.seconds > 0 }
            .sortedByDescending { it.seconds }
        }
      }

      val historyUsers = remember(
        filteredSqlProcs,
        filteredSqlTitles,
        fallbackDatedUsers,
        pcState.windowsUsers
      ) {
        (
          filteredSqlProcs.map { it.appName } +
            filteredSqlTitles.map { it.appName } +
            fallbackDatedUsers.map { it.user } +
            pcState.windowsUsers
          )
          .map { it.trim() }
          .filter { it.isNotBlank() && !it.equals("Windows", ignoreCase = true) }
          .distinctBy { it.lowercase() }
      }

      val detectedWinUserForHistory = remember(historyUsers, pcState.fullLock.childAccount) {
        historyUsers.firstOrNull()
          ?: pcState.fullLock.childAccount.takeIf { it.isNotBlank() }
          ?: "Windows"
      }

      val visibleProcs = remember(displayProcs, selectedHistoryUser) {
        if (selectedHistoryUser.isBlank()) displayProcs
        else displayProcs.filter { it.userName.equals(selectedHistoryUser, ignoreCase = true) }
      }
      val visibleTitles = remember(displayTitles, selectedHistoryUser) {
        if (selectedHistoryUser.isBlank()) displayTitles
        else displayTitles.filter { it.userName.equals(selectedHistoryUser, ignoreCase = true) }
      }

      val totalActiveSecondsForSelectedRange: Long = remember(
        filteredSqlTotalSeconds,
        displayProcs,
        displayTitles,
        visibleProcs,
        visibleTitles,
        selectedHistoryUser
      ) {
        when {
          selectedHistoryUser.isNotBlank() && visibleProcs.isNotEmpty() ->
            visibleProcs.sumOf { it.seconds.toLong() }
          selectedHistoryUser.isNotBlank() && visibleTitles.isNotEmpty() ->
            visibleTitles.sumOf { it.seconds.toLong() }
          selectedHistoryUser.isNotBlank() -> 0L
          filteredSqlTotalSeconds > 0L -> filteredSqlTotalSeconds
          displayProcs.isNotEmpty() -> displayProcs.sumOf { it.seconds.toLong() }
          displayTitles.isNotEmpty() -> displayTitles.sumOf { it.seconds.toLong() }
          else -> 0L
        }
      }

      val periodShortSuffix = when (dateFilterState.preset) {
        ActivityDateFilterPreset.TODAY -> "за сегодня"
        ActivityDateFilterPreset.YESTERDAY -> "за вчера"
        ActivityDateFilterPreset.CUSTOM_RANGE -> "за выбранный период"
      }

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = BorderStroke(1.dp, Slate800),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("pc_activity_card")
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(42.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Schedule, contentDescription = null, tint = CyanLight, modifier = Modifier.size(20.dp))
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "📊 История активности на ПК",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = "Изолированный расчёт времени строго за выбранную дату (SQLite)",
                color = Slate400,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          // DATE FILTER BAR: Today ('Сегодня') | Yesterday ('Вчера') | Custom Date Range ('Период')
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(38.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            val isToday = dateFilterState.preset == ActivityDateFilterPreset.TODAY
            val isYesterday = dateFilterState.preset == ActivityDateFilterPreset.YESTERDAY
            val isCustom = dateFilterState.preset == ActivityDateFilterPreset.CUSTOM_RANGE

            Button(
              onClick = onSelectTodayFilter,
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isToday) CyanLight else Slate800,
                contentColor = if (isToday) Color.Black else Color.White
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .testTag("pc_filter_today_button")
            ) {
              Text(
                text = "Сегодня",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Button(
              onClick = onSelectYesterdayFilter,
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isYesterday) CyanLight else Slate800,
                contentColor = if (isYesterday) Color.Black else Color.White
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1f)
                .height(36.dp)
                .testTag("pc_filter_yesterday_button")
            ) {
              Text(
                text = "Вчера",
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Button(
              onClick = { showCustomDateRangeDialog = true },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (isCustom) CyanLight else Slate800,
                contentColor = if (isCustom) Color.Black else CyanLight
              ),
              shape = RoundedCornerShape(10.dp),
              contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1.25f)
                .height(36.dp)
                .testTag("pc_filter_custom_range_button")
            ) {
              Icon(
                Icons.Default.DateRange,
                contentDescription = null,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Диапазон дат",
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          // STATIC SUMMARY BOX FOR SELECTED DATE / RANGE
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(54.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(Slate950)
              .padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "Выбранный фильтр дат",
                color = Slate400,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = dateFilterState.displayLabel,
                color = CyanLight,
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(horizontalAlignment = Alignment.End) {
              Text(
                text = "Активное время $periodShortSuffix",
                color = Slate400,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = formatDurationRu(totalActiveSecondsForSelectedRange),
                color = EmeraldShieldLight,
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          if (historyUsers.isNotEmpty()) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text("👤 Учётка:", color = Slate400, fontSize = 10.sp)
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(50))
                  .background(if (selectedHistoryUser.isBlank()) CyanLight.copy(alpha = 0.22f) else Slate950)
                  .border(1.dp, if (selectedHistoryUser.isBlank()) CyanLight else Slate800, RoundedCornerShape(50))
                  .clickable { selectedHistoryUser = "" }
                  .padding(horizontal = 9.dp, vertical = 4.dp)
              ) {
                Text(
                  text = "Все",
                  color = if (selectedHistoryUser.isBlank()) CyanLight else Slate200,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
              }
              historyUsers.forEach { winUser ->
                val selected = selectedHistoryUser.equals(winUser, ignoreCase = true)
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(if (selected) CyanLight.copy(alpha = 0.22f) else Slate950)
                    .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(50))
                    .clickable { selectedHistoryUser = winUser }
                    .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                  Text(
                    text = winUser,
                    color = if (selected) CyanLight else Slate200,
                    fontSize = 10.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                  )
                }
              }
            }
          }

          if (visibleProcs.isEmpty() && visibleTitles.isEmpty()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Slate950)
                .padding(horizontal = 12.dp),
              contentAlignment = Alignment.CenterStart
            ) {
              Text(
                text = "За выбранный период (${dateFilterState.displayLabel}) записей активности на ПК нет.",
                color = Slate400,
                fontSize = 12.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
              )
            }
          } else {
            Text(
              text = if (selectedHistoryUser.isBlank()) {
                "👤 Учётные записи: ${historyUsers.joinToString(", ").ifBlank { detectedWinUserForHistory }}"
              } else {
                "👤 Пользователь Windows: $selectedHistoryUser"
              },
              color = CyanLight,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )

            if (visibleProcs.isNotEmpty()) {
              Text(
                text = "Программы (.exe) $periodShortSuffix:",
                color = Slate400,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              visibleProcs.forEach { procItem ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Slate950)
                    .padding(horizontal = 10.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = if (selectedHistoryUser.isBlank() && procItem.userName.isNotBlank()) {
                        "${procItem.userName}: ${procItem.label}"
                      } else {
                        procItem.label
                      },
                      color = Color.White,
                      fontSize = 13.sp,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = buildString {
                        append("${formatDurationRu(procItem.seconds.toLong())} $periodShortSuffix")
                        if (procItem.startedAtMs > 0L) {
                          append(" • запуск ${formatPcTimestamp(procItem.startedAtMs)}")
                        }
                      },
                      color = if (procItem.startedAtMs > 0L) CyanLight.copy(alpha = 0.85f) else Slate400,
                      fontSize = 11.sp,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                  TextButton(
                    onClick = {
                      val ruleName = procItem.label.substringBeforeLast(".").replaceFirstChar { it.uppercase() }
                      PcBlockerClient.addEntry(
                        name = ruleName,
                        domains = emptyList(),
                        programs = listOf(procItem.label)
                      )
                    },
                    modifier = Modifier.height(36.dp)
                  ) {
                    Icon(Icons.Default.Block, contentDescription = null, tint = RoseLock, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Блок", color = RoseLock, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                  }
                }
              }
            }

            if (visibleTitles.isNotEmpty()) {
              Text(
                text = "Что смотрел в браузере и окнах $periodShortSuffix:",
                color = Slate400,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              visibleTitles.forEach { titleItem ->
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Slate950)
                    .padding(horizontal = 10.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = if (selectedHistoryUser.isBlank() && titleItem.userName.isNotBlank()) {
                        "${titleItem.userName}: ${cleanPcWindowTitle(titleItem.label)}"
                      } else {
                        cleanPcWindowTitle(titleItem.label)
                      },
                      color = Color.White,
                      fontSize = 12.sp,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = buildString {
                        append("${formatDurationRu(titleItem.seconds.toLong())} $periodShortSuffix")
                        if (titleItem.startedAtMs > 0L) {
                          append(" • открыто ${formatPcTimestamp(titleItem.startedAtMs)}")
                        }
                      },
                      color = if (titleItem.startedAtMs > 0L) CyanLight.copy(alpha = 0.85f) else Slate400,
                      fontSize = 11.sp,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                  TextButton(
                    onClick = {
                      PcBlockerClient.requestPcScreenshot()
                      showFullPcScreenDialog = true
                    },
                    modifier = Modifier.height(36.dp)
                  ) {
                    Icon(Icons.Default.Visibility, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Экран ПК", color = CyanLight, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                  }
                }
              }
            }
          }
        }
      }
    }
  }

  if (showCustomDateRangeDialog) {
    CustomPcDateRangeDialog(
      initialStartMs = dateFilterState.startTimestampMs,
      initialEndMs = dateFilterState.endTimestampMs,
      onDismiss = { showCustomDateRangeDialog = false },
      onApplyRange = { startMs, endMs ->
        onSelectCustomDateRange(startMs, endMs)
        showCustomDateRangeDialog = false
      }
    )
  }

  if (showFullPcScreenDialog) {
    androidx.compose.ui.window.Dialog(
      onDismissRequest = { showFullPcScreenDialog = false },
      properties = androidx.compose.ui.window.DialogProperties(
        usePlatformDefaultWidth = false,
        decorFitsSystemWindows = false
      )
    ) {
      val fallbackBmp = remember(pcState.pcName, currentPcSummary) {
        val timeNow = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        renderWatchedContentFrameBitmap(
          childName = "Компьютер (${pcState.pcName})",
          rawAppSummary = currentPcSummary,
          timeStr = timeNow
        )
      }
      val fullBmp = liveFrame?.bitmap ?: pcScreenBitmap ?: fallbackBmp
      val liveTs = liveFrame?.timestampMs
        ?: pcState.screenReceivedAtMs.takeIf { it > 0L }
        ?: pcState.screenTimestampMs
      val frameTimeStr = remember(liveTs) {
        if (liveTs > 0L) {
          SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(liveTs))
        } else {
          SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        }
      }

      // Полноэкранный режим: кадр ПК занимает весь экран, элементы управления —
      // полупрозрачные оверлеи. Щипок = зум 1…5x, два пальца = панорама,
      // одиночный тап = показать/скрыть меню, бездействие 3 с = автоскрытие.
      var viewportSize by remember { mutableStateOf(IntSize.Zero) }
      val closeFullscreen = {
        showFullPcScreenDialog = false
        pcZoom = 1f
        pcPanX = 0f
        pcPanY = 0f
        pcHudVisible = true
      }
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black)
          .onSizeChanged { viewportSize = it }
          .pointerInput(Unit) {
            detectTransformGestures { _, pan, zoom, _ ->
              val newScale = (pcZoom * zoom).coerceIn(1f, 5f)
              val maxX = (viewportSize.width * (newScale - 1f)) / 2f
              val maxY = (viewportSize.height * (newScale - 1f)) / 2f
              pcZoom = newScale
              if (newScale <= 1.02f) {
                pcPanX = 0f
                pcPanY = 0f
              } else {
                pcPanX = (pcPanX + pan.x).coerceIn(-maxX, maxX)
                pcPanY = (pcPanY + pan.y).coerceIn(-maxY, maxY)
              }
              pcHudInteraction++
            }
          }
          .pointerInput(Unit) {
            detectTapGestures(
              onTap = {
                pcHudVisible = !pcHudVisible
                pcHudInteraction++
              },
              onDoubleTap = {
                if (pcZoom > 1.05f) {
                  pcZoom = 1f
                  pcPanX = 0f
                  pcPanY = 0f
                } else {
                  pcZoom = 2.5f
                }
                pcHudVisible = true
                pcHudInteraction++
              }
            )
          }
      ) {
        Image(
          bitmap = fullBmp.asImageBitmap(),
          contentDescription = "Полный экран ПК",
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
              scaleX = pcZoom
              scaleY = pcZoom
              translationX = pcPanX
              translationY = pcPanY
            }
        )

        if (pcHudVisible) {
          Row(
            modifier = Modifier
              .align(Alignment.TopCenter)
              .fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.45f))
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = "🖥 ${pcState.pcName}",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = if (liveFrame != null) {
                  "🟢 ${streamStatus.fps} к/с • ${pcStreamQuality.label} • кадр $frameTimeStr • ${(pcZoom * 100).toInt()}%"
                } else {
                  "⏳ Подключение к потоку… ($frameTimeStr)"
                },
                color = if (liveFrame != null) EmeraldShieldLight else CyanLight,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            TextButton(onClick = closeFullscreen) {
              Text("✕", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
            }
          }

          Column(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.45f))
              .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text("Качество:", color = Slate200, fontSize = 10.sp)
              PcStreamQuality.values().forEach { option ->
                val selected = pcStreamQuality == option
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (selected) CyanLight.copy(alpha = 0.25f) else Color.Black.copy(alpha = 0.35f))
                    .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(8.dp))
                    .clickable {
                      pcStreamQuality = option
                      pcHudInteraction++
                    }
                    .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                  Text(
                    text = option.label,
                    color = if (selected) CyanLight else Slate200,
                    fontSize = 9.sp,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    maxLines = 1
                  )
                }
              }
            }
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                TextButton(onClick = {
                  PcBlockerClient.requestPcScreenshotHd()
                  pcHudInteraction++
                }) {
                  Text("📸 HD-кадр", color = CyanLight, fontSize = 11.sp)
                }
                TextButton(onClick = {
                  if (pcState.pcRecordingActive) {
                    PcBlockerClient.stopPcRecording()
                  } else {
                    PcBlockerClient.startPcRecording()
                  }
                  pcHudInteraction++
                }) {
                  Icon(
                    imageVector = if (pcState.pcRecordingActive) Icons.Default.Stop else Icons.Default.FiberManualRecord,
                    contentDescription = null,
                    tint = if (pcState.pcRecordingActive) RoseLock else Color.White,
                    modifier = Modifier.size(13.dp)
                  )
                  Spacer(modifier = Modifier.width(3.dp))
                  Text(
                    text = if (pcState.pcRecordingActive) "Стоп ${pcState.pcRecordingSeconds}с" else "REC HD",
                    color = if (pcState.pcRecordingActive) RoseLock else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
              Text(
                text = when {
                  pcState.pcRecordingActive -> "● REC"
                  streamStatus.isActive -> "● LIVE"
                  else -> "⏸ ПАУЗА"
                },
                color = when {
                  pcState.pcRecordingActive -> RoseLock
                  streamStatus.isActive -> EmeraldShieldLight
                  else -> AmberWarning
                },
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
            Text(
              text = "Щипок — зум • 2 пальца — панорама • 2x тап — 250% • 1 тап — меню",
              color = Slate400,
              fontSize = 9.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        if (!streamStatus.isActive) {
          Box(
            modifier = Modifier
              .align(Alignment.Center)
              .clip(RoundedCornerShape(10.dp))
              .background(AmberWarning.copy(alpha = 0.9f))
              .padding(horizontal = 12.dp, vertical = 8.dp)
          ) {
            Text(
              "Поток на паузе",
              color = Slate950,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }
    }
  }

  if (showAddRuleDialog) {
    AddPcRuleDialog(
      onDismiss = { showAddRuleDialog = false },
      onAdd = { name, domains, programs, ips ->
        PcBlockerClient.addEntry(name, domains, programs, ips)
        showAddRuleDialog = false
      }
    )
  }
}

@Composable
private fun PcEntryCard(
  entry: PcEntry,
  isExpanded: Boolean,
  onToggleExpand: () -> Unit,
  onToggleEnabled: (Boolean) -> Unit,
  onSaveLimit: (daily: Int, total: Int, from: String, to: String, closeTab: Boolean) -> Unit,
  onResetCounter: () -> Unit,
  onDelete: () -> Unit
) {
  var dailyStr by remember(entry.limit.daily) {
    mutableStateOf(if (entry.limit.daily > 0) entry.limit.daily.toString() else "")
  }
  var totalStr by remember(entry.limit.total) {
    mutableStateOf(if (entry.limit.total > 0) entry.limit.total.toString() else "")
  }
  var fromStr by remember(entry.limit.from) { mutableStateOf(entry.limit.from) }
  var toStr by remember(entry.limit.to) { mutableStateOf(entry.limit.to) }
  var closeTab by remember(entry.limit.closeTab) { mutableStateOf(entry.limit.closeTab) }

  val textFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = CyanLight,
    unfocusedBorderColor = Slate400.copy(alpha = 0.4f),
    focusedLabelColor = CyanLight,
    unfocusedLabelColor = Slate400
  )

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = BorderStroke(
      1.dp,
      if (entry.blocked) RoseLock.copy(alpha = 0.6f) else if (entry.enabled) EmeraldShield.copy(alpha = 0.5f) else Slate800
    ),
    modifier = Modifier
      .fillMaxWidth()
      .testTag("pc_entry_card_${entry.name}")
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onToggleExpand() },
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f)
        ) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(CircleShape)
              .background(if (entry.blocked) RoseContainer else if (entry.enabled) EmeraldContainer else Slate800),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = if (entry.blocked) Icons.Default.Block else if (entry.enabled) Icons.Default.Shield else Icons.Default.CheckCircle,
              contentDescription = null,
              tint = if (entry.blocked) RoseLock else if (entry.enabled) EmeraldShieldLight else Slate400,
              modifier = Modifier.size(18.dp)
            )
          }
          Column {
            Text(
              text = entry.name,
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp
            )
            Text(
              text = when {
                entry.blocked -> "🔒 Заблокировано на ПК"
                entry.enabled -> "✅ Доступно в рамках лимита"
                else -> "Блокировка выключена"
              },
              color = when {
                entry.blocked -> RoseLock
                entry.enabled -> EmeraldShieldLight
                else -> Slate400
              },
              fontSize = 11.sp,
              fontWeight = FontWeight.SemiBold
            )
            Text(
              text = "доменов: ${entry.domainsCount} · программ: ${entry.programsCount} · IP: ${entry.ipsCount}",
              color = Slate400,
              fontSize = 10.sp
            )
            if (entry.text.isNotBlank() && !entry.blocked) {
              Text(
                text = entry.text,
                color = AmberWarning,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
              )
            }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          Switch(
            checked = entry.enabled,
            onCheckedChange = onToggleEnabled,
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = EmeraldShield,
              uncheckedThumbColor = Slate400,
              uncheckedTrackColor = Slate800
            ),
            modifier = Modifier.testTag("pc_switch_${entry.name}")
          )
          IconButton(onClick = onToggleExpand) {
            Icon(
              imageVector = if (isExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
              contentDescription = "Лимиты времени",
              tint = Slate400
            )
          }
        }
      }

      AnimatedVisibility(visible = isExpanded) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Slate950)
            .padding(12.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Text(
            text = "⏱ Лимиты времени для «${entry.name}» (пусто или 0 — блокировать всегда при включённом тумблере)",
            color = Slate200,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
          )
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = dailyStr,
              onValueChange = { dailyStr = it },
              label = { Text("В день, мин", fontSize = 11.sp) },
              singleLine = true,
              colors = textFieldColors,
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = totalStr,
              onValueChange = { totalStr = it },
              label = { Text("Всего, мин", fontSize = 11.sp) },
              singleLine = true,
              colors = textFieldColors,
              modifier = Modifier.weight(1f)
            )
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
              value = fromStr,
              onValueChange = { fromStr = it },
              label = { Text("Разрешено с (ЧЧ:ММ)", fontSize = 11.sp) },
              placeholder = { Text("14:00", fontSize = 11.sp) },
              singleLine = true,
              colors = textFieldColors,
              modifier = Modifier.weight(1f)
            )
            OutlinedTextField(
              value = toStr,
              onValueChange = { toStr = it },
              label = { Text("до (ЧЧ:ММ)", fontSize = 11.sp) },
              placeholder = { Text("20:00", fontSize = 11.sp) },
              singleLine = true,
              colors = textFieldColors,
              modifier = Modifier.weight(1f)
            )
          }

          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable { closeTab = !closeTab }
          ) {
            Checkbox(
              checked = closeTab,
              onCheckedChange = { closeTab = it },
              colors = CheckboxDefaults.colors(checkedColor = CyanLight)
            )
            Text(
              text = "Автоматически закрывать открытую вкладку браузера (Ctrl+W)",
              color = Slate200,
              fontSize = 12.sp
            )
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = {
                onSaveLimit(
                  dailyStr.toIntOrNull() ?: 0,
                  totalStr.toIntOrNull() ?: 0,
                  fromStr,
                  toStr,
                  closeTab
                )
              },
              colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1.2f)
            ) {
              Text("Сохранить", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
            OutlinedButton(
              onClick = onResetCounter,
              border = BorderStroke(1.dp, Slate400),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.weight(1f)
            ) {
              Icon(Icons.Default.RestartAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Сброс", color = Color.White, fontSize = 11.sp)
            }
            IconButton(onClick = onDelete) {
              Icon(Icons.Default.Delete, contentDescription = "Удалить запись", tint = RoseLock)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AddPcRuleDialog(
  onDismiss: () -> Unit,
  onAdd: (name: String, domains: List<String>, programs: List<String>, ips: List<String>) -> Unit
) {
  var name by remember { mutableStateOf("") }
  var domainsText by remember { mutableStateOf("") }
  var programsText by remember { mutableStateOf("") }
  var ipsText by remember { mutableStateOf("") }

  val textFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = CyanLight,
    unfocusedBorderColor = Slate400.copy(alpha = 0.4f),
    focusedLabelColor = CyanLight,
    unfocusedLabelColor = Slate400
  )

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Text("Добавить блокировку на ПК", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Название (напр. Roblox или Steam)") },
          singleLine = true,
          colors = textFieldColors,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("pc_add_rule_name_input")
        )
        OutlinedTextField(
          value = domainsText,
          onValueChange = { domainsText = it },
          label = { Text("Домены (через пробел или запятую)") },
          placeholder = { Text("roblox.com, steampowered.com") },
          colors = textFieldColors,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("pc_add_rule_domains_input")
        )
        OutlinedTextField(
          value = programsText,
          onValueChange = { programsText = it },
          label = { Text("Программы .exe (через запятую)") },
          placeholder = { Text("RobloxPlayerBeta.exe, steam.exe") },
          colors = textFieldColors,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("pc_add_rule_programs_input")
        )
        OutlinedTextField(
          value = ipsText,
          onValueChange = { ipsText = it },
          label = { Text("IP / подсети CIDR (необязательно)") },
          colors = textFieldColors,
          modifier = Modifier.fillMaxWidth()
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val doms = domainsText.split(",", ";", " ", "\n").map { it.trim() }.filter { it.isNotBlank() }
          val progs = programsText.split(",", ";", "\n").map { it.trim() }.filter { it.isNotBlank() }
          val ips = ipsText.split(",", ";", " ", "\n").map { it.trim() }.filter { it.isNotBlank() }
          if (name.isNotBlank() && (doms.isNotEmpty() || progs.isNotEmpty() || ips.isNotEmpty())) {
            onAdd(name.trim(), doms, progs, ips)
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
        modifier = Modifier.testTag("pc_add_rule_confirm_button")
      ) {
        Text("Заблокировать на ПК", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена", color = Slate400)
      }
    }
  )
}

@Composable
private fun CustomPcDateRangeDialog(
  initialStartMs: Long,
  initialEndMs: Long,
  onDismiss: () -> Unit,
  onApplyRange: (startMs: Long, endMs: Long) -> Unit
) {
  val ruFormat = remember { SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()) }
  var startMs by remember(initialStartMs) { mutableStateOf(initialStartMs) }
  var endMs by remember(initialEndMs) { mutableStateOf(initialEndMs) }

  var startText by remember(startMs) { mutableStateOf(ruFormat.format(Date(startMs))) }
  var endText by remember(endMs) { mutableStateOf(ruFormat.format(Date(endMs))) }
  var errorText by remember { mutableStateOf<String?>(null) }

  val textFieldColors = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = CyanLight,
    unfocusedBorderColor = Slate400.copy(alpha = 0.4f),
    focusedLabelColor = CyanLight,
    unfocusedLabelColor = Slate400
  )

  fun shiftDays(ms: Long, deltaDays: Int): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = ms
    cal.add(Calendar.DAY_OF_YEAR, deltaDays)
    return cal.timeInMillis
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Text(
        text = "📅 Выбор диапазона дат (История ПК)",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Выберите готовый интервал или укажите точные даты начала и окончания (ДД.ММ.ГГГГ):",
          color = Slate400,
          fontSize = 11.sp
        )

        // Quick preset buttons (3 days, 7 days, 30 days)
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          OutlinedButton(
            onClick = {
              val now = System.currentTimeMillis()
              startMs = shiftDays(now, -2)
              endMs = now
              startText = ruFormat.format(Date(startMs))
              endText = ruFormat.format(Date(endMs))
              errorText = null
            },
            border = BorderStroke(1.dp, CyanLight.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = Modifier
              .weight(1f)
              .height(34.dp)
          ) {
            Text("3 дня", color = CyanLight, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }

          OutlinedButton(
            onClick = {
              val now = System.currentTimeMillis()
              startMs = shiftDays(now, -6)
              endMs = now
              startText = ruFormat.format(Date(startMs))
              endText = ruFormat.format(Date(endMs))
              errorText = null
            },
            border = BorderStroke(1.dp, CyanLight.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = Modifier
              .weight(1f)
              .height(34.dp)
          ) {
            Text("7 дней", color = CyanLight, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }

          OutlinedButton(
            onClick = {
              val now = System.currentTimeMillis()
              startMs = shiftDays(now, -29)
              endMs = now
              startText = ruFormat.format(Date(startMs))
              endText = ruFormat.format(Date(endMs))
              errorText = null
            },
            border = BorderStroke(1.dp, CyanLight.copy(alpha = 0.6f)),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
            modifier = Modifier
              .weight(1f)
              .height(34.dp)
          ) {
            Text("30 дней", color = CyanLight, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }

        // Start Date input + [-1 д] / [+1 д]
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          OutlinedTextField(
            value = startText,
            onValueChange = {
              startText = it
              errorText = null
            },
            label = { Text("Начало (ДД.ММ.ГГГГ)", fontSize = 11.sp) },
            singleLine = true,
            colors = textFieldColors,
            modifier = Modifier.weight(1f)
          )
          OutlinedButton(
            onClick = {
              startMs = shiftDays(startMs, -1)
              startText = ruFormat.format(Date(startMs))
            },
            border = BorderStroke(1.dp, Slate400),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(38.dp)
          ) {
            Text("-1д", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
          OutlinedButton(
            onClick = {
              startMs = shiftDays(startMs, 1)
              startText = ruFormat.format(Date(startMs))
            },
            border = BorderStroke(1.dp, Slate400),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(38.dp)
          ) {
            Text("+1д", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }

        // End Date input + [-1 д] / [+1 д]
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          OutlinedTextField(
            value = endText,
            onValueChange = {
              endText = it
              errorText = null
            },
            label = { Text("Конец (ДД.ММ.ГГГГ)", fontSize = 11.sp) },
            singleLine = true,
            colors = textFieldColors,
            modifier = Modifier.weight(1f)
          )
          OutlinedButton(
            onClick = {
              endMs = shiftDays(endMs, -1)
              endText = ruFormat.format(Date(endMs))
            },
            border = BorderStroke(1.dp, Slate400),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(38.dp)
          ) {
            Text("-1д", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
          OutlinedButton(
            onClick = {
              endMs = shiftDays(endMs, 1)
              endText = ruFormat.format(Date(endMs))
            },
            border = BorderStroke(1.dp, Slate400),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier.size(38.dp)
          ) {
            Text("+1д", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }

        errorText?.let { err ->
          Text(text = err, color = RoseLock, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          try {
            ruFormat.isLenient = false
            val parsedStart = ruFormat.parse(startText.trim())
            val parsedEnd = ruFormat.parse(endText.trim())
            if (parsedStart != null && parsedEnd != null) {
              onApplyRange(parsedStart.time, parsedEnd.time)
            } else {
              errorText = "Укажите даты в формате ДД.ММ.ГГГГ"
            }
          } catch (_: Exception) {
            errorText = "Неверный формат даты. Пример: 29.10.2026"
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
        modifier = Modifier.testTag("pc_apply_custom_date_range_button")
      ) {
        Text("Применить фильтр", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена", color = Slate400)
      }
    }
  )
}

