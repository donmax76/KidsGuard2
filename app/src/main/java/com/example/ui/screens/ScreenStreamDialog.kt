package com.example.ui.screens

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.data.cloud.CloudSyncManager
import com.example.data.model.ChildProfileEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.util.DeviceControlDebugger
import com.example.util.DeviceUsageTracker
import com.example.util.ScreenShotManager
import com.example.util.ScreenVideoRecorder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun ScreenStreamDialog(
  child: ChildProfileEntity?,
  onDismiss: () -> Unit,
  onSendCloudCommand: (String, String) -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val cloudSync = remember { CloudSyncManager(context) }
  val currentApp by DeviceUsageTracker.currentForegroundApp.collectAsState()

  var isRecordingVideo by remember { mutableStateOf(false) }
  var isEncodingVideo by remember { mutableStateOf(false) }
  var recordingSeconds by remember { mutableIntStateOf(0) }
  var recordedVideoUri by remember { mutableStateOf<Uri?>(null) }
  var recordedVideoFileName by remember { mutableStateOf<String?>(null) }

  var isAutoStreamActive by remember { mutableStateOf(true) }
  var refreshIntervalSec by remember { mutableFloatStateOf(2.0f) }

  // Пока приложение свёрнуто — не опрашиваем телефон ребёнка и не держим его экран
  // в режиме трансляции (это зря жжёт батарею у обоих устройств).
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

  val childId = child?.id ?: 0L
  val childName = child?.name ?: "Нет добавленного ребёнка"

  // Remote cloud snapshot state strictly keyed by childId
  var remoteAppName by remember(childId) { mutableStateOf<String?>(null) }
  var remoteBitmap by remember(childId) { mutableStateOf<Bitmap?>(null) }
  var remoteTimestamp by remember(childId) { mutableStateOf(0L) }
  var frameSequenceCount by remember(childId) { mutableIntStateOf(0) }
  var isChildLockedLocal by remember(childId, child?.isDeviceLocked) {
    mutableStateOf(child?.isDeviceLocked == true)
  }

  // Fullscreen natural canvas state (default TRUE so opening fullscreen shows natural large screen!)
  var isHudVisible by remember { mutableStateOf(true) }
  var zoomScale by remember { mutableFloatStateOf(1f) }
  var panOffsetX by remember { mutableFloatStateOf(0f) }
  var panOffsetY by remember { mutableFloatStateOf(0f) }

  fun stopAndClose() {
    if (isRecordingVideo) {
      isRecordingVideo = false
    }
    if (childId > 0L) {
      onSendCloudCommand("STOP_LIVE_STREAM", "")
    }
    onDismiss()
  }

  BackHandler {
    if (zoomScale > 1.05f) {
      zoomScale = 1f
      panOffsetX = 0f
      panOffsetY = 0f
    } else {
      stopAndClose()
    }
  }

  DisposableEffect(childId) {
    onDispose {
      if (childId > 0L) {
        onSendCloudCommand("STOP_LIVE_STREAM", "")
      }
    }
  }

  // Subscribe to real-time cloud screen snapshots from child's phone
  LaunchedEffect(childId) {
    if (childId <= 0L) {
      remoteAppName = null
      remoteBitmap = null
      remoteTimestamp = 0L
      return@LaunchedEffect
    }
    onSendCloudCommand("START_LIVE_STREAM", "")
    onSendCloudCommand("TAKE_SCREENSHOT", "")
    cloudSync.observeScreenSnapshot(childId).collect { snapPair ->
      if (snapPair != null) {
        val (activeApp, b64) = snapPair
        remoteAppName = activeApp
        remoteTimestamp = System.currentTimeMillis()
        if (b64.isNotBlank()) {
          val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
              val bytes = Base64.decode(b64, Base64.DEFAULT)
              val opts = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
              }
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
            } catch (_: Exception) {
              null
            }
          }
          if (bmp != null) {
            remoteBitmap = bmp
            frameSequenceCount++
          }
        }
      }
    }
  }

  // Открытие/сворачивание: включаем и выключаем «живое окно» на телефоне ребёнка.
  LaunchedEffect(childId, isAppResumed) {
    if (childId <= 0L) return@LaunchedEffect
    if (isAppResumed) {
      onSendCloudCommand("START_LIVE_STREAM", "")
      onSendCloudCommand("TAKE_SCREENSHOT", "")
    } else {
      onSendCloudCommand("STOP_LIVE_STREAM", "")
    }
  }

  // Continuous live polling while dialog is open
  LaunchedEffect(isAutoStreamActive, refreshIntervalSec, childId, isAppResumed) {
    if (childId <= 0L || !isAppResumed) return@LaunchedEffect
    var lastStartSentMs = 0L
    while (isAutoStreamActive && isAppResumed) {
      // «Живое окно» на телефоне живёт 120 с — продлеваем его раз в минуту,
      // пока родитель реально смотрит экран.
      val nowMs = System.currentTimeMillis()
      if (nowMs - lastStartSentMs > 60_000L) {
        lastStartSentMs = nowMs
        onSendCloudCommand("START_LIVE_STREAM", "")
      }
      onSendCloudCommand("TAKE_SCREENSHOT", "")
      val fetched = cloudSync.pollLatestScreenSnapshot(childId)
      if (fetched != null) {
        val (activeApp, b64, _) = fetched
        remoteAppName = activeApp
        // Время кадра — по часам РОДИТЕЛЬСКОГО телефона: часы ребёнка могут быть
        // сдвинуты на час/пояс, и подпись «Обновлено» тогда врёт.
        remoteTimestamp = System.currentTimeMillis()
        if (b64.isNotBlank()) {
          val bmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            try {
              val bytes = Base64.decode(b64, Base64.DEFAULT)
              BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
            } catch (_: Exception) {
              null
            }
          }
          if (bmp != null) {
            remoteBitmap = bmp
            frameSequenceCount++
          }
        }
      }
      val delayMs = (refreshIntervalSec * 1000).toLong().coerceAtLeast(1200L)
      delay(delayMs)
    }
  }

  val displayedAppName = when {
    childId <= 0L -> "Нет добавленного ребёнка — добавьте профиль ребёнка на главном экране"
    !remoteAppName.isNullOrBlank() -> remoteAppName!!
    else -> "Ожидание ответа от телефона ребёнка..."
  }
  val currentBitmap = remoteBitmap
  val formattedTime = remember(remoteTimestamp) {
    if (remoteTimestamp > 0L) {
      SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(remoteTimestamp))
    } else {
      SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    }
  }

  // Video recording frame loop (records either MediaProjection bitmap or live watched-content card bitmap)
  LaunchedEffect(isRecordingVideo) {
    if (isRecordingVideo) {
      recordingSeconds = 0
      recordedVideoUri = null
      ScreenVideoRecorder.startRecording()
      while (isRecordingVideo) {
        val frameBmp = remoteBitmap ?: renderWatchedContentFrameBitmap(
          childName = childName,
          rawAppSummary = displayedAppName,
          timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        )
        ScreenVideoRecorder.addFrame(frameBmp)
        delay(1000)
        recordingSeconds++
      }
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      isRecordingVideo = false
      onSendCloudCommand("STOP_LIVE_STREAM", "")
    }
  }

  Dialog(
    onDismissRequest = { stopAndClose() },
    properties = DialogProperties(
      usePlatformDefaultWidth = false,
      decorFitsSystemWindows = false
    )
  ) {
    Surface(
      modifier = modifier
        .fillMaxSize()
        .background(Color.Black),
      color = Color.Black
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .background(Color.Black)
      ) {
        // 1. NATURAL FULLSCREEN CANVAS OF CHILD'S PHONE SCREEN
        Box(
          modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
              detectTapGestures(
                onTap = {
                  isHudVisible = !isHudVisible
                },
                onDoubleTap = {
                  if (zoomScale > 1.1f) {
                    zoomScale = 1f
                    panOffsetX = 0f
                    panOffsetY = 0f
                  } else {
                    zoomScale = 2.2f
                  }
                }
              )
            }
            .pointerInput(Unit) {
              detectTransformGestures { _, pan, zoom, _ ->
                val newScale = (zoomScale * zoom).coerceIn(1f, 4f)
                zoomScale = newScale
                if (newScale > 1.02f) {
                  panOffsetX = (panOffsetX + pan.x).coerceIn(-900f, 900f)
                  panOffsetY = (panOffsetY + pan.y).coerceIn(-1400f, 1400f)
                } else {
                  panOffsetX = 0f
                  panOffsetY = 0f
                }
              }
            },
          contentAlignment = Alignment.Center
        ) {
          if (currentBitmap != null) {
            Image(
              bitmap = currentBitmap.asImageBitmap(),
              contentDescription = "Экран телефона ребёнка $childName",
              modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(
                  scaleX = zoomScale,
                  scaleY = zoomScale,
                  translationX = panOffsetX,
                  translationY = panOffsetY
                ),
              contentScale = ContentScale.Fit
            )
          } else {
            val mainPart = displayedAppName.substringBefore(" ||").trim()
            val appTitle = mainPart.substringBefore(" •").trim().ifBlank { "Экран телефона" }
            val watchedTitle = if (mainPart.contains(" • ")) {
              mainPart.substringAfter(" • ").trim()
            } else {
              ""
            }
            val extraScreenLines = if (displayedAppName.contains(" || ")) {
              displayedAppName.substringAfter(" || ")
                .split(" | ")
                .map { it.trim() }
                .filter { it.isNotBlank() }
            } else {
              emptyList()
            }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 90.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Slate900.copy(alpha = 0.96f))
                .border(1.5.dp, CyanLight.copy(alpha = 0.45f), RoundedCornerShape(22.dp))
                .padding(20.dp),
              horizontalAlignment = Alignment.Start,
              verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth()
              ) {
                Box(
                  modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(EmeraldShieldLight)
                )
                Text(
                  text = "👁 ЧТО СЕЙЧАС СМОТРИТ «${childName.uppercase()}»",
                  color = EmeraldShieldLight,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }

              // Active App Card (fixed height)
              Column(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(104.dp)
                  .clip(RoundedCornerShape(14.dp))
                  .background(Slate950)
                  .padding(14.dp),
                verticalArrangement = Arrangement.Center
              ) {
                Text(
                  text = "Открытое приложение:",
                  color = Slate400,
                  fontSize = 11.sp,
                  maxLines = 1
                )
                Text(
                  text = "📱 $appTitle",
                  color = Color.White,
                  fontSize = 17.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = if (watchedTitle.isNotBlank()) "🎬 «$watchedTitle»" else "Экран активен • Мониторинг в реальном времени",
                  color = CyanLight,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }

              if (extraScreenLines.isNotEmpty()) {
                Column(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(Slate950)
                    .padding(12.dp),
                  verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                  Text(
                    text = "Текст и элементы на экране ребёнка сейчас:",
                    color = Slate400,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1
                  )
                  extraScreenLines.take(3).forEach { line ->
                    Text(
                      text = "• $line",
                      color = Slate200,
                      fontSize = 12.sp,
                      maxLines = 1,
                      softWrap = false,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                }
              }

              // Direct button to open & watch the exact same video/page on parent's phone
              val queryTarget = watchedTitle.ifBlank { extraScreenLines.firstOrNull().orEmpty() }
              if (queryTarget.isNotBlank()) {
                Button(
                  onClick = {
                    try {
                      val url = if (queryTarget.contains(".") && !queryTarget.contains(" ")) {
                        if (queryTarget.startsWith("http")) queryTarget else "https://$queryTarget"
                      } else if (appTitle.contains("YouTube", ignoreCase = true) ||
                                 appTitle.contains("Ютуб", ignoreCase = true)) {
                        "https://www.youtube.com/results?search_query=" + Uri.encode(queryTarget)
                      } else if (appTitle.contains("TikTok", ignoreCase = true)) {
                        "https://www.tiktok.com/search?q=" + Uri.encode(queryTarget)
                      } else {
                        "https://www.google.com/search?q=" + Uri.encode("$appTitle $queryTarget")
                      }
                      context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                    } catch (_: Exception) {}
                  },
                  colors = ButtonDefaults.buttonColors(
                    containerColor = CyanLight,
                    contentColor = Color.Black
                  ),
                  shape = RoundedCornerShape(12.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                ) {
                  Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Открыть и посмотреть это у себя",
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    maxLines = 1
                  )
                }
              }

              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .height(38.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Text(
                  text = "Обновлено: $formattedTime",
                  color = Slate400,
                  fontSize = 11.sp,
                  maxLines = 1
                )
                OutlinedButton(
                  onClick = { onSendCloudCommand("TAKE_SCREENSHOT", "") },
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier.height(36.dp)
                ) {
                  Icon(Icons.Default.Refresh, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Обновить сейчас", color = CyanLight, fontSize = 12.sp, maxLines = 1)
                }
              }
            }
          }
        }

        // 2. FLOATING MINIMAL BUTTON WHEN HUD IS HIDDEN (SO PARENT CAN ALWAYS RESTORE OR EXIT)
        if (!isHudVisible) {
          Row(
            modifier = Modifier
              .align(Alignment.TopEnd)
              .padding(top = 36.dp, end = 16.dp)
              .clip(RoundedCornerShape(20.dp))
              .background(Color.Black.copy(alpha = 0.65f))
              .border(1.dp, Color.White.copy(alpha = 0.25f), RoundedCornerShape(20.dp))
              .clickable { isHudVisible = true }
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Default.Visibility, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            Text("Показать панель", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }

        // 3. TOP FLOATING HUD BAR (BACK BUTTON, CHILD INFO, ACTIVE APP, ZOOM RESET, CLOSE)
        if (isHudVisible) {
          Column(
            modifier = Modifier
              .align(Alignment.TopCenter)
              .fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.76f))
              .padding(top = 34.dp, start = 12.dp, end = 12.dp, bottom = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              // Back / Return button
              Button(
                onClick = { stopAndClose() },
                colors = ButtonDefaults.buttonColors(
                  containerColor = Slate800.copy(alpha = 0.9f),
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .width(96.dp)
                  .height(40.dp)
                  .testTag("stream_back_button")
              ) {
                Icon(
                  imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                  contentDescription = "Вернуться назад",
                  modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text("Назад", fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }

              // Center Status Pill (LIVE / REC + Child Name + Active App)
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier
                  .weight(1f)
                  .height(40.dp)
                  .padding(horizontal = 8.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Box(
                    modifier = Modifier
                      .size(8.dp)
                      .clip(CircleShape)
                      .background(
                        when {
                          isRecordingVideo -> RoseLock
                          isAutoStreamActive -> EmeraldShieldLight
                          else -> AmberWarning
                        }
                      )
                  )
                  Text(
                    text = when {
                      isRecordingVideo -> "REC ${recordingSeconds / 60}:${(recordingSeconds % 60).toString().padStart(2, '0')} • $childName"
                      isAutoStreamActive -> "LIVE • $childName"
                      else -> "ПАУЗА • $childName"
                    },
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                  )
                }
                Text(
                  text = "$displayedAppName • $formattedTime",
                  color = CyanLight,
                  fontSize = 11.sp,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }

              // Zoom reset or Hide HUD / Close
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                if (zoomScale > 1.05f) {
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .background(CyanLight.copy(alpha = 0.2f))
                      .border(1.dp, CyanLight, RoundedCornerShape(8.dp))
                      .clickable {
                        zoomScale = 1f
                        panOffsetX = 0f
                        panOffsetY = 0f
                      }
                      .padding(horizontal = 8.dp, vertical = 6.dp)
                  ) {
                    Text(
                      text = "${(zoomScale * 100).toInt()}% Сброс",
                      color = CyanLight,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                } else {
                  IconButton(
                    onClick = { zoomScale = 2.0f },
                    modifier = Modifier
                      .size(36.dp)
                      .clip(CircleShape)
                      .background(Slate800.copy(alpha = 0.85f))
                  ) {
                    Icon(
                      imageVector = Icons.Default.ZoomIn,
                      contentDescription = "Приблизить 2x",
                      tint = CyanLight,
                      modifier = Modifier.size(18.dp)
                    )
                  }
                }

                IconButton(
                  onClick = { stopAndClose() },
                  modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(RoseLock.copy(alpha = 0.85f))
                    .testTag("stream_close_button")
                ) {
                  Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Закрыть",
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                  )
                }
              }
            }
          }
        }

        // 4. BOTTOM FLOATING CONTROL DOCK (NATURAL REMOTE CONTROLS OVER FULLSCREEN STREAM)
        if (isHudVisible) {
          Column(
            modifier = Modifier
              .align(Alignment.BottomCenter)
              .fillMaxWidth()
              .background(Color.Black.copy(alpha = 0.82f))
              .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            // If a video was just recorded, show quick Play / Open banner right above controls
            if (recordedVideoUri != null && !isRecordingVideo) {
              Card(
                modifier = Modifier
                  .fillMaxWidth()
                  .border(1.dp, EmeraldShieldLight, RoundedCornerShape(12.dp)),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = Slate900)
              ) {
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.weight(1f)
                  ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(18.dp))
                    Text(
                      text = "MP4 сохранено: ${recordedVideoFileName ?: "Запись экрана"}",
                      color = Color.White,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                  Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                      onClick = {
                        try {
                          val viewIntent = Intent(Intent.ACTION_VIEW).apply {
                            setDataAndType(recordedVideoUri, "video/mp4")
                            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                          }
                          context.startActivity(Intent.createChooser(viewIntent, "Открыть видеозапись"))
                        } catch (_: Exception) {
                          Toast.makeText(context, "Видео в папке Movies/KidsGuard", Toast.LENGTH_SHORT).show()
                        }
                      },
                      colors = ButtonDefaults.buttonColors(containerColor = EmeraldShieldLight, contentColor = Slate950),
                      shape = RoundedCornerShape(8.dp)
                    ) {
                      Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
                      Spacer(modifier = Modifier.width(4.dp))
                      Text("Открыть MP4", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(
                      onClick = { recordedVideoUri = null },
                      modifier = Modifier.size(28.dp)
                    ) {
                      Icon(Icons.Default.Close, contentDescription = "Скрыть", tint = Slate400, modifier = Modifier.size(16.dp))
                    }
                  }
                }
              }
            }

            // Polling speed + hint bar
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                Text(
                  text = "Опрос:",
                  color = Slate400,
                  fontSize = 11.sp
                )
                listOf(1.5f to "1.5с", 2.5f to "2.5с", 5.0f to "5с").forEach { (sec, label) ->
                  val selected = isAutoStreamActive && kotlin.math.abs(refreshIntervalSec - sec) < 0.3f
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .background(if (selected) CyanLight.copy(alpha = 0.22f) else Slate900)
                      .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(8.dp))
                      .clickable {
                        isAutoStreamActive = true
                        refreshIntervalSec = sec
                        onSendCloudCommand("TAKE_SCREENSHOT", "")
                      }
                      .padding(horizontal = 8.dp, vertical = 4.dp)
                  ) {
                    Text(
                      text = label,
                      color = if (selected) CyanLight else Slate200,
                      fontSize = 11.sp,
                      fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal
                    )
                  }
                }

                // Pause / Resume chip
                Box(
                  modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (!isAutoStreamActive) AmberWarning.copy(alpha = 0.22f) else Slate900)
                    .border(1.dp, if (!isAutoStreamActive) AmberWarning else Slate800, RoundedCornerShape(8.dp))
                    .clickable {
                      isAutoStreamActive = !isAutoStreamActive
                      if (isAutoStreamActive) {
                        onSendCloudCommand("TAKE_SCREENSHOT", "")
                      }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                  ) {
                    Icon(
                      imageVector = if (isAutoStreamActive) Icons.Default.Pause else Icons.Default.PlayArrow,
                      contentDescription = null,
                      tint = if (isAutoStreamActive) Slate200 else AmberWarning,
                      modifier = Modifier.size(12.dp)
                    )
                    Text(
                      text = if (isAutoStreamActive) "Пауза" else "Пуск",
                      color = if (isAutoStreamActive) Slate200 else AmberWarning,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }

              Text(
                text = "2x тап — зум • 1x тап — скрыть меню",
                color = Slate400,
                fontSize = 10.sp
              )
            }

            // Primary Action Control Buttons Row (Scrollable on narrow screens, fits cleanly)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              // 1. Refresh Frame Now
              StreamActionButton(
                icon = Icons.Default.Refresh,
                label = "Обновить кадр",
                containerColor = Slate800,
                contentColor = CyanLight,
                onClick = {
                  onSendCloudCommand("TAKE_SCREENSHOT", "")
                  Toast.makeText(context, "Запрос нового кадра отправлен", Toast.LENGTH_SHORT).show()
                }
              )

              // 2. Save Screenshot to Parent's Gallery
              StreamActionButton(
                icon = Icons.Default.CameraAlt,
                label = "Снимок в галерею",
                containerColor = CyanLight,
                contentColor = Slate950,
                onClick = {
                  onSendCloudCommand("TAKE_SCREENSHOT", "")
                  val snapBmp = currentBitmap ?: renderWatchedContentFrameBitmap(
                    childName = childName,
                    rawAppSummary = displayedAppName,
                    timeStr = formattedTime
                  )
                  val b64 = ScreenShotManager.bitmapToBase64(snapBmp)
                  ScreenShotManager.recordScreenSnapshot(
                    appName = displayedAppName,
                    note = "Снимок экрана ($childName)",
                    base64 = b64
                  )
                  val uri = ScreenShotManager.saveBitmapToGalleryWithUri(
                    context,
                    snapBmp,
                    "KidsGuard_${childName}_${System.currentTimeMillis()}"
                  )
                  DeviceControlDebugger.logEvent(
                    "СНИМОК_ЭКРАНА",
                    "Снимок экрана ($childName) сохранен в Галерею",
                    "SUCCESS"
                  )
                  Toast.makeText(
                    context,
                    if (uri != null) "Снимок сохранен в Галерею (DCIM/KidsGuard)!" else "Снимок зафиксирован!",
                    Toast.LENGTH_SHORT
                  ).show()
                }
              )

              // 3. Record / Stop MP4 Video
              if (!isRecordingVideo) {
                StreamActionButton(
                  icon = Icons.Default.Videocam,
                  label = if (isEncodingVideo) "Сохранение..." else "Запись MP4",
                  containerColor = EmeraldShield,
                  contentColor = Color.White,
                  enabled = !isEncodingVideo,
                  onClick = {
                    isRecordingVideo = true
                    DeviceControlDebugger.logEvent(
                      "ЗАПИСЬ_ВИДЕО_СТАРТ",
                      "Начата запись видео экрана для $childName",
                      "SUCCESS"
                    )
                    Toast.makeText(context, "Запись видео MP4 начата", Toast.LENGTH_SHORT).show()
                  }
                )
              } else {
                StreamActionButton(
                  icon = Icons.Default.Stop,
                  label = "Стоп (${recordingSeconds}с)",
                  containerColor = RoseLock,
                  contentColor = Color.White,
                  onClick = {
                    isRecordingVideo = false
                    isEncodingVideo = true
                    scope.launch {
                      val result = ScreenVideoRecorder.stopAndSaveToGallery(
                        context = context,
                        childName = childName
                      )
                      isEncodingVideo = false
                      if (result.success) {
                        recordedVideoUri = result.uri
                        recordedVideoFileName = result.displayPath
                        Toast.makeText(
                          context,
                          "Видео MP4 сохранено: ${result.displayPath}",
                          Toast.LENGTH_LONG
                        ).show()
                      } else {
                        Toast.makeText(
                          context,
                          result.displayPath,
                          Toast.LENGTH_SHORT
                        ).show()
                      }
                    }
                  }
                )
              }

              // 4. Lock / Unlock Child's Phone Directly from Stream
              StreamActionButton(
                icon = if (isChildLockedLocal) Icons.Default.LockOpen else Icons.Default.Lock,
                label = if (isChildLockedLocal) "Разблок. экран" else "Заблок. экран",
                containerColor = if (isChildLockedLocal) EmeraldShield else RoseLock,
                contentColor = Color.White,
                onClick = {
                  isChildLockedLocal = !isChildLockedLocal
                  onSendCloudCommand(if (isChildLockedLocal) "LOCK" else "UNLOCK", "")
                  Toast.makeText(
                    context,
                    if (isChildLockedLocal) "Команда блокировки экрана отправлена!" else "Экран ребёнка разблокирован!",
                    Toast.LENGTH_SHORT
                  ).show()
                }
              )

              // 5. Loud Siren Alert
              StreamActionButton(
                icon = Icons.Default.NotificationsActive,
                label = "Сирена",
                containerColor = AmberWarning,
                contentColor = Slate950,
                onClick = {
                  onSendCloudCommand("LOUD_RING", "")
                  Toast.makeText(context, "Громкий сигнал отправлен на телефон ребёнка!", Toast.LENGTH_SHORT).show()
                }
              )

              // 6. Close Fullscreen & Return
              StreamActionButton(
                icon = Icons.Default.FullscreenExit,
                label = "Свернуть",
                containerColor = Slate800,
                contentColor = Color.White,
                onClick = { stopAndClose() }
              )
            }
          }
        }
      }
    }
  }
}

fun renderWatchedContentFrameBitmap(
  childName: String,
  rawAppSummary: String,
  timeStr: String
): Bitmap {
  val w = 720
  val h = 1280
  val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
  val canvas = android.graphics.Canvas(bmp)
  canvas.drawColor(android.graphics.Color.parseColor("#0F172A"))

  val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

  // Card background
  paint.color = android.graphics.Color.parseColor("#1E293B")
  canvas.drawRoundRect(40f, 120f, (w - 40).toFloat(), (h - 120).toFloat(), 36f, 36f, paint)

  // Header
  paint.color = android.graphics.Color.parseColor("#34D399")
  paint.textSize = 28f
  paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
  canvas.drawText("KIDSGUARD LIVE • ${childName.uppercase()}", 80f, 200f, paint)

  paint.color = android.graphics.Color.parseColor("#94A3B8")
  paint.textSize = 22f
  paint.typeface = android.graphics.Typeface.DEFAULT
  canvas.drawText("Время кадра: $timeStr", 80f, 245f, paint)

  val mainPart = rawAppSummary.substringBefore(" ||").trim()
  val appTitle = mainPart.substringBefore(" •").trim().ifBlank { "Активный экран" }
  val watchedTitle = if (mainPart.contains(" • ")) mainPart.substringAfter(" • ").trim() else ""
  val extraLines = if (rawAppSummary.contains(" || ")) {
    rawAppSummary.substringAfter(" || ").split(" | ").map { it.trim() }.filter { it.isNotBlank() }
  } else {
    emptyList()
  }

  paint.color = android.graphics.Color.parseColor("#38BDF8")
  paint.textSize = 24f
  paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
  canvas.drawText("Открыто сейчас:", 80f, 330f, paint)

  paint.color = android.graphics.Color.WHITE
  paint.textSize = 36f
  canvas.drawText(appTitle.take(28), 80f, 385f, paint)

  var y = 460f
  if (watchedTitle.isNotBlank()) {
    paint.color = android.graphics.Color.parseColor("#38BDF8")
    paint.textSize = 24f
    canvas.drawText("Что смотрит ребёнок:", 80f, y, paint)
    y += 45f
    paint.color = android.graphics.Color.WHITE
    paint.textSize = 28f
    watchedTitle.chunked(32).take(3).forEach { chunk ->
      canvas.drawText(chunk, 80f, y, paint)
      y += 40f
    }
    y += 25f
  }

  if (extraLines.isNotEmpty()) {
    paint.color = android.graphics.Color.parseColor("#94A3B8")
    paint.textSize = 22f
    canvas.drawText("Элементы на экране:", 80f, y, paint)
    y += 40f
    paint.color = android.graphics.Color.parseColor("#E2E8F0")
    paint.textSize = 24f
    paint.typeface = android.graphics.Typeface.DEFAULT
    extraLines.take(8).forEach { line ->
      canvas.drawText("• ${line.take(36)}", 80f, y, paint)
      y += 38f
    }
  }

  return bmp
}

@Composable
private fun StreamActionButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  label: String,
  containerColor: Color,
  contentColor: Color,
  enabled: Boolean = true,
  onClick: () -> Unit
) {
  Button(
    onClick = onClick,
    enabled = enabled,
    colors = ButtonDefaults.buttonColors(
      containerColor = containerColor,
      contentColor = contentColor
    ),
    shape = RoundedCornerShape(12.dp),
    modifier = Modifier.height(44.dp)
  ) {
    Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
    Spacer(modifier = Modifier.width(6.dp))
    Text(
      text = label,
      fontSize = 12.sp,
      fontWeight = FontWeight.Bold,
      maxLines = 1
    )
  }
}
