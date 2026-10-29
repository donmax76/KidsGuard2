package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppControlEntity
import com.example.data.model.OfflineActivityLogEntity
import com.example.data.pc.PcOfflineHistoryItem
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun HourlyActivityRechartsCard(
  childName: String,
  phoneApps: List<AppControlEntity>,
  offlineActivityLogs: List<OfflineActivityLogEntity>,
  pcHourlyMinutes: List<Int>,
  pcOfflineHistory: List<PcOfflineHistoryItem>,
  syncedOfflineEventsCount: Int,
  lastOfflineSyncTime: String?,
  onRequestOfflineSyncNow: () -> Unit,
  modifier: Modifier = Modifier
) {
  val currentHour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY).coerceIn(0, 23) }
  var selectedHour by remember { mutableIntStateOf(currentHour) }
  // 0 = All (Phone + PC), 1 = Phone Only, 2 = PC Only
  var filterMode by remember { mutableIntStateOf(0) }
  var showOfflineLogDetails by remember { mutableStateOf(false) }

  val todayIso = remember {
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
  }

  // Compute 24-hour Phone usage in minutes from Room OfflineActivityLogEntity + fallback distribution from today's usedMinutesToday
  val phoneHourlyMinutes = remember(offlineActivityLogs, phoneApps, todayIso) {
    val arr = IntArray(24) { 0 }
    val todayPhoneLogs = offlineActivityLogs.filter {
      it.deviceType == "PHONE" && (it.dateIso == todayIso || it.dateIso.isBlank())
    }
    for (log in todayPhoneLogs) {
      val h = log.hourOfDay.coerceIn(0, 23)
      arr[h] += log.durationSeconds
    }
    val minutesArr = IntArray(24) { h ->
      if (arr[h] > 0) (arr[h] / 60).coerceAtLeast(1).coerceAtMost(60) else 0
    }
    val totalFromLogs = minutesArr.sum()
    val totalFromApps = phoneApps.sumOf { it.usedMinutesToday }
    if (totalFromLogs == 0 && totalFromApps > 0) {
      minutesArr[currentHour] = totalFromApps.coerceIn(1, 60)
    }
    minutesArr.toList()
  }

  // Compute 24-hour PC usage in minutes from pcHourlyMinutes + Room PC logs
  val resolvedPcHourlyMinutes = remember(pcHourlyMinutes, offlineActivityLogs, pcOfflineHistory, todayIso) {
    val arr = IntArray(24) { idx -> pcHourlyMinutes.getOrNull(idx) ?: 0 }
    val pcRoomLogs = offlineActivityLogs.filter { it.deviceType == "PC" && it.dateIso == todayIso }
    if (arr.sum() == 0 && pcRoomLogs.isNotEmpty()) {
      for (log in pcRoomLogs) {
        val h = log.hourOfDay.coerceIn(0, 23)
        arr[h] = (arr[h] + (log.durationSeconds / 60).coerceAtLeast(1)).coerceAtMost(60)
      }
    }
    if (arr.sum() == 0 && pcOfflineHistory.isNotEmpty()) {
      val secArr = IntArray(24) { 0 }
      for (item in pcOfflineHistory) {
        val h = item.hour.coerceIn(0, 23)
        secArr[h] += item.sec
      }
      for (h in 0..23) {
        if (secArr[h] > 0) {
          arr[h] = (secArr[h] / 60).coerceAtLeast(1).coerceAtMost(60)
        }
      }
    }
    arr.toList()
  }

  val displayedHourlyPhone = remember(phoneHourlyMinutes, filterMode) {
    if (filterMode == 2) List(24) { 0 } else phoneHourlyMinutes
  }
  val displayedHourlyPc = remember(resolvedPcHourlyMinutes, filterMode) {
    if (filterMode == 1) List(24) { 0 } else resolvedPcHourlyMinutes
  }
  val totalPhoneMin = remember(phoneHourlyMinutes, phoneApps) {
    maxOf(phoneHourlyMinutes.sum(), phoneApps.sumOf { it.usedMinutesToday })
  }
  val totalPcMin = remember(resolvedPcHourlyMinutes) {
    resolvedPcHourlyMinutes.sum()
  }

  // Top apps/windows during selectedHour
  val selectedHourApps = remember(selectedHour, offlineActivityLogs, pcOfflineHistory, phoneApps) {
    val fromRoom = offlineActivityLogs
      .filter { it.hourOfDay == selectedHour }
      .groupBy { "${it.deviceType}:${it.appName}" }
      .map { (_, list) ->
        val first = list.first()
        val mins = (list.sumOf { it.durationSeconds } / 60).coerceAtLeast(1)
        Triple(first.deviceType, first.appName, "$mins мин • ${first.windowTitle.take(45)}")
      }
    val fromPcSql = pcOfflineHistory
      .filter { it.hour == selectedHour }
      .groupBy { it.proc }
      .map { (proc, list) ->
        val first = list.first()
        val mins = (list.sumOf { it.sec } / 60).coerceAtLeast(1)
        Triple("PC", proc, "$mins мин • ${first.title.take(45)}")
      }
    val combined = (fromRoom + fromPcSql).distinctBy { "${it.first}:${it.second}" }
    if (combined.isNotEmpty()) {
      combined.take(4)
    } else if (selectedHour == currentHour && phoneApps.any { it.usedMinutesToday > 0 }) {
      phoneApps.filter { it.usedMinutesToday > 0 }
        .sortedByDescending { it.usedMinutesToday }
        .take(3)
        .map { Triple("PHONE", it.appName, "${it.usedMinutesToday} мин за сегодня") }
    } else {
      emptyList()
    }
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("hourly_recharts_card"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A)),
    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(22.dp))
        .padding(16.dp)
    ) {
      // Header Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          Box(
            modifier = Modifier
              .size(38.dp)
              .clip(RoundedCornerShape(11.dp))
              .background(
                Brush.linearGradient(
                  listOf(Color(0xFF6366F1), Color(0xFF06B6D4))
                )
              ),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.BarChart,
              contentDescription = "Почасовой график Recharts",
              tint = Color.White,
              modifier = Modifier.size(22.dp)
            )
          }
          Spacer(modifier = Modifier.width(10.dp))
          Column {
            Text(
              text = "📊 Активность по часам (24ч Recharts)",
              style = MaterialTheme.typography.titleSmall,
              fontWeight = FontWeight.ExtraBold,
              color = Color.White
            )
            Text(
              text = "$childName • Смартфон: $totalPhoneMin мин | ПК: $totalPcMin мин",
              style = MaterialTheme.typography.labelSmall,
              color = Color(0xFF94A3B8)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // Filter Pills (All / Phone / PC)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        listOf(
          0 to "Все (Тел + ПК)",
          1 to "📱 Телефон",
          2 to "💻 Компьютер"
        ).forEach { (mode, label) ->
          val isSelected = filterMode == mode
          Surface(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(10.dp))
              .clickable { filterMode = mode }
              .testTag("recharts_filter_$mode"),
            color = if (isSelected) Color(0xFF4F46E5) else Color(0xFF1E293B),
            shape = RoundedCornerShape(10.dp)
          ) {
            Box(
              modifier = Modifier.padding(vertical = 7.dp, horizontal = 6.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = label,
                fontSize = 11.sp,
                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                color = if (isSelected) Color.White else Color(0xFFCBD5E1),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      // Recharts-style Interactive 24-Hour Stacked Bar + Smooth Area Spline Canvas
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(165.dp)
          .clip(RoundedCornerShape(14.dp))
          .background(Color(0xFF090D16))
          .border(1.dp, Color(0xFF1E293B), RoundedCornerShape(14.dp))
          .padding(horizontal = 10.dp, vertical = 12.dp)
      ) {
        Canvas(
          modifier = Modifier
            .fillMaxWidth()
            .height(140.dp)
            .pointerInput(Unit) {
              detectTapGestures { offset ->
                if (size.width > 0) {
                  val slotWidth = size.width / 24f
                  val tappedHour = (offset.x / slotWidth).toInt().coerceIn(0, 23)
                  selectedHour = tappedHour
                }
              }
            }
        ) {
          val w = size.width
          val h = size.height - 20.dp.toPx()
          val maxVal = 60f

          // Horizontal dashed grid lines (15m, 30m, 45m, 60m)
          listOf(0.25f, 0.5f, 0.75f, 1.0f).forEach { frac ->
            val y = h * (1f - frac)
            drawLine(
              color = Color(0xFF1E293B),
              start = Offset(0f, y),
              end = Offset(w, y),
              strokeWidth = 1.dp.toPx()
            )
          }

          val slotW = w / 24f
          val barW = (slotW * 0.62f).coerceAtLeast(4f)

          // Smooth Area Curve Path (Recharts AreaChart overlay)
          val linePath = Path()
          val areaPath = Path()
          for (hr in 0..23) {
            val totalMin = (displayedHourlyPhone[hr] + displayedHourlyPc[hr]).coerceAtMost(60)
            val cx = hr * slotW + slotW / 2f
            val cy = h - (totalMin / maxVal) * h
            if (hr == 0) {
              linePath.moveTo(cx, cy)
              areaPath.moveTo(cx, h)
              areaPath.lineTo(cx, cy)
            } else {
              linePath.lineTo(cx, cy)
              areaPath.lineTo(cx, cy)
            }
            if (hr == 23) {
              areaPath.lineTo(cx, h)
              areaPath.close()
            }
          }

          drawPath(
            path = areaPath,
            brush = Brush.verticalGradient(
              colors = listOf(Color(0x4438BDF8), Color(0x0538BDF8)),
              startY = 0f,
              endY = h
            )
          )
          drawPath(
            path = linePath,
            color = Color(0xFF38BDF8),
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
          )

          // Stacked Bars for each hour (00..23)
          for (hr in 0..23) {
            val pMin = displayedHourlyPhone[hr].coerceIn(0, 60)
            val cMin = displayedHourlyPc[hr].coerceIn(0, 60 - pMin)
            val xLeft = hr * slotW + (slotW - barW) / 2f

            // Highlight selected hour column
            if (hr == selectedHour) {
              drawRoundRect(
                color = Color(0x266366F1),
                topLeft = Offset(hr * slotW, 0f),
                size = Size(slotW, h),
                cornerRadius = CornerRadius(6f, 6f)
              )
            }

            // Phone bar (Indigo/Cyan)
            if (pMin > 0) {
              val pHeight = (pMin / maxVal) * h
              drawRoundRect(
                color = if (hr == selectedHour) Color(0xFF818CF8) else Color(0xFF6366F1),
                topLeft = Offset(xLeft, h - pHeight),
                size = Size(barW, pHeight),
                cornerRadius = CornerRadius(4f, 4f)
              )
            }

            // PC bar stacked on top (Emerald)
            if (cMin > 0) {
              val pHeight = (pMin / maxVal) * h
              val cHeight = (cMin / maxVal) * h
              drawRoundRect(
                color = if (hr == selectedHour) Color(0xFF34D399) else Color(0xFF10B981),
                topLeft = Offset(xLeft, h - pHeight - cHeight),
                size = Size(barW, cHeight),
                cornerRadius = CornerRadius(4f, 4f)
              )
            }
          }
        }

        // Hour Axis Labels at Bottom
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .align(Alignment.BottomCenter),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          listOf("00:00", "04:00", "08:00", "12:00", "16:00", "20:00", "23:00").forEach { lbl ->
            Text(
              text = lbl,
              fontSize = 9.sp,
              color = Color(0xFF64748B),
              fontWeight = FontWeight.SemiBold
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Selected Hour Recharts Tooltip Box
      val selPhoneMin = phoneHourlyMinutes.getOrNull(selectedHour) ?: 0
      val selPcMin = resolvedPcHourlyMinutes.getOrNull(selectedHour) ?: 0
      Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color(0xFF1E293B),
        shape = RoundedCornerShape(12.dp)
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "🕒 Час: %02d:00 – %02d:59".format(selectedHour, selectedHour),
              style = MaterialTheme.typography.labelLarge,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF6366F1))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "Тел: $selPhoneMin мин",
                  fontSize = 11.sp,
                  color = Color(0xFFC7D2FE),
                  fontWeight = FontWeight.SemiBold
                )
              }
              Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF10B981))
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                  text = "ПК: $selPcMin мин",
                  fontSize = 11.sp,
                  color = Color(0xFFA7F3D0),
                  fontWeight = FontWeight.SemiBold
                )
              }
            }
          }

          if (selectedHourApps.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            selectedHourApps.forEach { (devType, appTitle, details) ->
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
              ) {
                Icon(
                  imageVector = if (devType == "PC") Icons.Default.Computer else Icons.Default.PhoneAndroid,
                  contentDescription = devType,
                  tint = if (devType == "PC") Color(0xFF34D399) else Color(0xFF818CF8),
                  modifier = Modifier.size(13.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "$appTitle — $details",
                  fontSize = 11.sp,
                  color = Color(0xFFE2E8F0),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          } else {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = "Нажмите на любой столбец (00:00–23:00) для просмотра деталей за выбранный час.",
              fontSize = 11.sp,
              color = Color(0xFF94A3B8)
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))
      HorizontalDivider(color = Color(0xFF1E293B))
      Spacer(modifier = Modifier.height(10.dp))

      // Offline Store-and-Forward Status & Sync Bar (Room DB + SQLite PC)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          modifier = Modifier
            .weight(1f)
            .clickable { showOfflineLogDetails = !showOfflineLogDetails },
          verticalAlignment = Alignment.CenterVertically
        ) {
          Icon(
            imageVector = Icons.Default.History,
            contentDescription = "Оффлайн-архив Room SQLite",
            tint = Color(0xFF38BDF8),
            modifier = Modifier.size(18.dp)
          )
          Spacer(modifier = Modifier.width(8.dp))
          Column {
            Text(
              text = "📦 Оффлайн-память у ребёнка (Room + SQLite)",
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
            val totalRecords = offlineActivityLogs.size + pcOfflineHistory.size + syncedOfflineEventsCount
            Text(
              text = if (lastOfflineSyncTime != null) {
                "Синхронизировано в $lastOfflineSyncTime • Записей: $totalRecords (нажмите для лога)"
              } else {
                "Хранит гео, окна и скриншоты без сети • Записей: $totalRecords"
              },
              fontSize = 10.sp,
              color = Color(0xFF94A3B8)
            )
          }
        }

        Surface(
          modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .clickable { onRequestOfflineSyncNow() }
            .testTag("sync_offline_history_btn"),
          color = Color(0xFF0284C7),
          shape = RoundedCornerShape(10.dp)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Icon(
              imageVector = Icons.Default.Sync,
              contentDescription = "Синхронизировать оффлайн-историю",
              tint = Color.White,
              modifier = Modifier.size(14.dp)
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
              text = "Синхр.",
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              color = Color.White
            )
          }
        }
      }

      AnimatedVisibility(visible = showOfflineLogDetails) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF090D16))
            .padding(10.dp),
          verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
          Text(
            text = "Последние события из локальной БД ребёнка (телефон Room + ПК SQLite):",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF38BDF8)
          )
          val recentPhone = offlineActivityLogs.take(6)
          val recentPc = pcOfflineHistory.take(6)
          if (recentPhone.isEmpty() && recentPc.isEmpty()) {
            Text(
              text = "Оффлайн-буфер активен. Все новые события GPS, приложений и скриншоты сохраняются на устройстве ребёнка и передаются автоматически при появлении связи.",
              fontSize = 11.sp,
              color = Color(0xFF94A3B8)
            )
          } else {
            val fmt = remember { SimpleDateFormat("HH:mm:ss", Locale.getDefault()) }
            recentPhone.forEach { log ->
              Text(
                text = "📱 [${fmt.format(Date(log.timestamp))}] ${log.appName}: ${log.windowTitle.take(55)}",
                fontSize = 11.sp,
                color = Color(0xFFE2E8F0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
            recentPc.forEach { pcLog ->
              Text(
                text = "💻 [${fmt.format(Date(pcLog.ts))}] ${pcLog.proc}: ${pcLog.title.take(55)}",
                fontSize = 11.sp,
                color = Color(0xFFA7F3D0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }
        }
      }
    }
  }
}
