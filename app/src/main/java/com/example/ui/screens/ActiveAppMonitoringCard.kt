package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.util.DeviceUsageTracker
import com.example.util.ScreenShotManager

@Composable
fun ActiveAppMonitoringCard(
  child: ChildProfileEntity?,
  apps: List<AppControlEntity>,
  onTakeScreenshot: () -> Unit,
  onOpenLiveStream: () -> Unit,
  onViewScreenshots: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val cloudSync = androidx.compose.runtime.remember { com.example.data.cloud.CloudSyncManager(context) }
  val localForegroundApp by DeviceUsageTracker.currentForegroundApp.collectAsState()
  val snapshots by ScreenShotManager.capturedScreens.collectAsState()

  val scope = androidx.compose.runtime.rememberCoroutineScope()
  var cloudActiveApp by androidx.compose.runtime.remember(child?.id) {
    androidx.compose.runtime.mutableStateOf<String?>(null)
  }
  var cloudThumbBitmap by androidx.compose.runtime.remember(child?.id) {
    androidx.compose.runtime.mutableStateOf<android.graphics.Bitmap?>(null)
  }
  var lastFrameTimeStr by androidx.compose.runtime.remember(child?.id) {
    androidx.compose.runtime.mutableStateOf("ожидание кадра...")
  }
  var pollCount by androidx.compose.runtime.remember(child?.id) {
    androidx.compose.runtime.mutableStateOf(0)
  }
  var pollingIntervalMs by androidx.compose.runtime.remember {
    androidx.compose.runtime.mutableStateOf(5000L)
  }
  var isPollingActive by androidx.compose.runtime.remember {
    androidx.compose.runtime.mutableStateOf(false)
  }

  val applyIncomingFrame: suspend (String?, String?, Long?) -> Unit = { remoteApp, remoteB64, ts ->
    if (!remoteApp.isNullOrBlank() && !remoteApp.contains("Определение")) {
      cloudActiveApp = remoteApp
    }
    if (!remoteB64.isNullOrBlank()) {
      val decodedBmp = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
        try {
          val bytes = android.util.Base64.decode(remoteB64, android.util.Base64.DEFAULT)
          val opts = android.graphics.BitmapFactory.Options().apply {
            inPreferredConfig = android.graphics.Bitmap.Config.RGB_565
          }
          android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        } catch (_: Throwable) {
          null
        }
      }
      if (decodedBmp != null) {
        cloudThumbBitmap = decodedBmp
        val displayDate = if (ts != null && ts > 0L) java.util.Date(ts) else java.util.Date()
        lastFrameTimeStr = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(displayDate)
        val existingTop = ScreenShotManager.capturedScreens.value.firstOrNull()?.base64Bitmap
        if (existingTop != remoteB64 && child != null) {
          ScreenShotManager.addCapturedItem(
            com.example.util.CapturedScreenItem(
              childId = child.id,
              activeAppName = remoteApp ?: "Активный экран",
              statusNote = "Реальный снимок экрана (${child.name})",
              base64Bitmap = remoteB64
            )
          )
        }
      }
    }
  }

  // 1. Real-time push stream observer (requests lightweight active app name on load; full bitmap stream only when user activates LIVE)
  androidx.compose.runtime.LaunchedEffect(child?.id) {
    val cId = child?.id ?: run {
      cloudActiveApp = null
      cloudThumbBitmap = null
      lastFrameTimeStr = "нет добавленного ребёнка"
      return@LaunchedEffect
    }
    try {
      cloudSync.sendRemoteCommand(cId, "QUERY_ACTIVE_APP", "")
    } catch (_: Exception) {}
    cloudSync.observeScreenSnapshot(cId).collect { pair ->
      applyIncomingFrame(pair?.first, pair?.second, null)
    }
  }

  // 2. Active polling loop ONLY when user explicitly enables LIVE streaming
  androidx.compose.runtime.LaunchedEffect(child?.id, isPollingActive, pollingIntervalMs) {
    val cId = child?.id ?: return@LaunchedEffect
    if (!isPollingActive) {
      return@LaunchedEffect
    }
    try {
      cloudSync.sendRemoteCommand(cId, "START_LIVE_STREAM", "")
    } catch (_: Exception) {}
    while (isPollingActive) {
      try {
        val polled = cloudSync.pollLatestScreenSnapshot(cId)
        pollCount++
        if (polled != null) {
          applyIncomingFrame(polled.first, polled.second, polled.third)
        }
        if (pollCount % 6 == 0) {
          cloudSync.sendRemoteCommand(cId, "START_LIVE_STREAM", "")
        }
      } catch (_: Exception) {}
      kotlinx.coroutines.delay(pollingIntervalMs)
    }
  }

  val rawForegroundApp = when {
    child == null -> "Нет добавленного ребёнка — нажмите «+ Добавить»"
    !cloudActiveApp.isNullOrBlank() -> cloudActiveApp!!
    else -> "Ожидание ответа от телефона ребёнка..."
  }
  val foregroundApp = rawForegroundApp
    .substringBefore(" ||")
    .replace("\n", " ")
    .replace("\r", " ")
    .trim()
    .ifBlank { "Активный экран" }

  val usedMinutes = child?.usedScreenTimeMinutes ?: 0
  val dailyLimit = child?.dailyScreenLimitMinutes ?: 120
  val remainingMinutes = (dailyLimit - usedMinutes).coerceAtLeast(0)

  Card(
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF111A2E)),
    border = androidx.compose.foundation.BorderStroke(
      1.5.dp,
      androidx.compose.ui.graphics.Brush.linearGradient(
        listOf(CyanLight, Color(0xFF6366F1), EmeraldShieldLight)
      )
    ),
    modifier = modifier
      .fillMaxWidth()
      .height(584.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(16.dp)
    ) {
      // 1. Header: Live active app + Continuous Polling Status (strictly fixed height)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(40.dp),
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
              .size(10.dp)
              .clip(CircleShape)
              .background(if (cloudThumbBitmap != null) EmeraldShield else Color(0xFFF59E0B))
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Мониторинг экрана LIVE: ${child?.name ?: "Ребёнок"}",
              color = Color.White,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 15.sp,
              maxLines = 1,
              softWrap = false,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
              text = if (cloudThumbBitmap != null) "Кадр: $lastFrameTimeStr • Поток активен"
              else "Ожидание видеопотока с устройства",
              color = Slate400,
              fontSize = 11.sp,
              maxLines = 1,
              softWrap = false,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
          modifier = Modifier
            .width(98.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(50))
            .background(
              if (isPollingActive) {
                androidx.compose.ui.graphics.Brush.horizontalGradient(
                  listOf(Color(0xFF0284C7), Color(0xFF6366F1))
                )
              } else {
                androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Slate800, Slate800))
              }
            )
            .clickable { isPollingActive = !isPollingActive },
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = if (isPollingActive) "● LIVE ВКЛ" else "ЭКО-РЕЖИМ",
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 10.sp,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Polling interval selector row (3.5s / 5s / 10s + manual poll button) - fixed height
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(28.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically,
          modifier = Modifier.weight(1f)
        ) {
          Text("Опрос:", color = Slate400, fontSize = 11.sp, maxLines = 1, softWrap = false)
          listOf(3500L to "3.5с", 5000L to "5с", 10000L to "10с").forEach { (ms, label) ->
            val selected = isPollingActive && pollingIntervalMs == ms
            Box(
              modifier = Modifier
                .width(44.dp)
                .height(24.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) Color(0xFF0C4A6E) else Color(0xFF0B1324))
                .clickable {
                  pollingIntervalMs = ms
                  isPollingActive = true
                },
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = label,
                color = if (selected) CyanLight else Slate400,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
              )
            }
          }
        }

        Box(
          modifier = Modifier
            .width(96.dp)
            .height(26.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF0B1324))
            .clickable {
              val cId = child?.id ?: return@clickable
              scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                cloudSync.sendRemoteCommand(cId, "TAKE_SCREENSHOT", "")
                cloudSync.sendRemoteCommand(cId, "START_LIVE_STREAM", "")
                val polled = cloudSync.pollLatestScreenSnapshot(cId)
                if (polled != null) {
                  pollCount++
                  applyIncomingFrame(polled.first, polled.second, polled.third)
                }
              }
            },
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "Обновить ↻",
            color = CyanLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            softWrap = false,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // 2. Embedded Live Screen Preview Window — ALWAYS static 240.dp height so layout NEVER jumps!
      val liveBmp = cloudThumbBitmap
      Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (liveBmp != null) Color(0xFF050811) else Color(0xFF0B1324)
        ),
        border = androidx.compose.foundation.BorderStroke(
          if (liveBmp != null) 1.5.dp else 1.dp,
          if (liveBmp != null) CyanLight.copy(alpha = 0.6f) else Color(0xFF1E293B)
        ),
        modifier = Modifier
          .fillMaxWidth()
          .height(240.dp)
          .clickable {
            val cId = child?.id ?: 1L
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
              cloudSync.sendRemoteCommand(cId, "TAKE_SCREENSHOT", "")
              cloudSync.sendRemoteCommand(cId, "START_LIVE_STREAM", "")
            }
            onOpenLiveStream()
          }
      ) {
        Box(modifier = Modifier.fillMaxSize()) {
          if (liveBmp != null) {
            val composeBmp = androidx.compose.runtime.remember(liveBmp) {
              liveBmp.asImageBitmap()
            }
            androidx.compose.foundation.Image(
              bitmap = composeBmp,
              contentDescription = "Реальный экран телефона ребёнка",
              contentScale = androidx.compose.ui.layout.ContentScale.Fit,
              modifier = Modifier
                .fillMaxSize()
                .padding(4.dp)
            )
            Row(
              modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xD9020617))
                .padding(horizontal = 10.dp, vertical = 5.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(8.dp)
                  .clip(CircleShape)
                  .background(EmeraldShieldLight)
              )
              Text(
                text = "$foregroundApp • $lastFrameTimeStr",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 11.sp,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
            }
            Box(
              modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(10.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xD90284C7))
                .padding(horizontal = 10.dp, vertical = 5.dp)
            ) {
              Text(
                text = "Нажмите для полного экрана ⤢",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                maxLines = 1,
                softWrap = false
              )
            }
          } else {
            Column(
              modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.Center
            ) {
              Icon(
                imageVector = Icons.Default.Videocam,
                contentDescription = null,
                tint = CyanLight,
                modifier = Modifier.size(32.dp)
              )
              Spacer(modifier = Modifier.height(6.dp))
              Text(
                text = "Ожидание первого кадра с телефона ребёнка...",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                softWrap = false,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
              Spacer(modifier = Modifier.height(4.dp))
              Text(
                text = "Убедитесь, что на телефоне ребёнка включены «Спец. возможности» KidsGuard. Нажмите, чтобы запросить экран.",
                color = Slate400,
                fontSize = 11.sp,
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Current running app banner — strictly fixed 62.dp height and 1-line ellipsized text
      Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0B1324)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E293B)),
        modifier = Modifier
          .fillMaxWidth()
          .height(62.dp)
      ) {
        Row(
          modifier = Modifier
            .padding(horizontal = 12.dp)
            .fillMaxSize(),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                  listOf(Color(0xFF0EA5E9), Color(0xFF4F46E5))
                )
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
          }
          Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center
          ) {
            Text(
              text = foregroundApp,
              color = Color.White,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 14.sp,
              maxLines = 1,
              softWrap = false,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Text(
              text = "Активное окно на устройстве ребёнка • Без индикатора записи",
              color = CyanLight,
              fontSize = 11.sp,
              maxLines = 1,
              softWrap = false,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Screen time breakdown — strictly fixed 72.dp height
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(72.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF17223B)),
          modifier = Modifier
            .weight(1f)
            .fillMaxSize()
        ) {
          Column(
            modifier = Modifier
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .fillMaxSize(),
            verticalArrangement = Arrangement.Center
          ) {
            Text("Экранное время", color = Slate400, fontSize = 10.sp, maxLines = 1, softWrap = false)
            Text(
              text = "${usedMinutes / 60} ч ${usedMinutes % 60} мин",
              color = Color.White,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 16.sp,
              maxLines = 1,
              softWrap = false
            )
            Text("за сегодня", color = CyanLight, fontSize = 10.sp, maxLines = 1, softWrap = false)
          }
        }

        Card(
          shape = RoundedCornerShape(12.dp),
          colors = CardDefaults.cardColors(containerColor = Color(0xFF17223B)),
          modifier = Modifier
            .weight(1f)
            .fillMaxSize()
        ) {
          Column(
            modifier = Modifier
              .padding(horizontal = 12.dp, vertical = 8.dp)
              .fillMaxSize(),
            verticalArrangement = Arrangement.Center
          ) {
            Text("Остаток лимита", color = Slate400, fontSize = 10.sp, maxLines = 1, softWrap = false)
            Text(
              text = "${remainingMinutes / 60} ч ${remainingMinutes % 60} мин",
              color = if (remainingMinutes > 30) EmeraldShieldLight else RoseLock,
              fontWeight = FontWeight.ExtraBold,
              fontSize = 16.sp,
              maxLines = 1,
              softWrap = false
            )
            Text("из ${dailyLimit / 60} ч лимита", color = Slate400, fontSize = 10.sp, maxLines = 1, softWrap = false)
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Direct Live Monitoring & Recording Controls
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        Button(
          onClick = {
            val cId = child?.id ?: 1L
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
              cloudSync.sendRemoteCommand(cId, "TAKE_SCREENSHOT", "")
              cloudSync.sendRemoteCommand(cId, "START_LIVE_STREAM", "")
            }
            onOpenLiveStream()
          },
          colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF0284C7), contentColor = Color.White),
          shape = RoundedCornerShape(12.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          modifier = Modifier
            .weight(1f)
            .height(44.dp)
        ) {
          Icon(Icons.Default.Videocam, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Экран LIVE",
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }

        OutlinedButton(
          onClick = {
            val cId = child?.id ?: 1L
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
              cloudSync.sendRemoteCommand(cId, "TAKE_SCREENSHOT", "")
            }
            onViewScreenshots()
          },
          shape = RoundedCornerShape(12.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
          modifier = Modifier
            .weight(1f)
            .height(44.dp)
        ) {
          Icon(Icons.Default.Visibility, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyanLight)
          Spacer(modifier = Modifier.width(6.dp))
          Text(
            text = "Снимки (${snapshots.size})",
            fontSize = 12.sp,
            color = Slate200,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}
