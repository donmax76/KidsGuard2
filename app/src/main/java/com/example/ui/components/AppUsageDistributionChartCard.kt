package com.example.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.PieChart
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppControlEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import kotlin.math.atan2
import kotlin.math.hypot

data class AppUsageChartSlice(
  val appId: Long,
  val appName: String,
  val category: String,
  val minutes: Int,
  val limitMinutes: Int,
  val isBlocked: Boolean,
  val sharePercent: Float,
  val color: Color
)

private val chartPalette = listOf(
  Color(0xFF38BDF8), // Cyan
  Color(0xFF818CF8), // Indigo
  Color(0xFF34D399), // Emerald
  Color(0xFFF59E0B), // Amber
  Color(0xFFF43F5E), // Rose
  Color(0xFFA855F7), // Purple
  Color(0xFF2DD4BF), // Teal
  Color(0xFFFB923C)  // Orange
)

@Composable
fun AppUsageDistributionChartCard(
  apps: List<AppControlEntity>,
  totalChildUsedMinutes: Int = 0,
  onSyncUsage: (() -> Unit)? = null,
  onToggleAppBlock: ((AppControlEntity) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  // 0 = Donut / Pie Chart, 1 = Comparative Bar Chart
  var chartMode by remember { mutableIntStateOf(0) }
  var selectedSliceIndex by remember { mutableIntStateOf(-1) }

  val slices: List<AppUsageChartSlice> = remember(apps, totalChildUsedMinutes) {
    val activeApps = apps.filter { it.usedMinutesToday > 0 }.sortedByDescending { it.usedMinutesToday }
    val sourceList = if (activeApps.isNotEmpty()) {
      activeApps.take(7)
    } else {
      emptyList()
    }

    val sumMins = sourceList.sumOf { it.usedMinutesToday }.coerceAtLeast(1)
    sourceList.mapIndexed { index, app ->
      AppUsageChartSlice(
        appId = app.id,
        appName = app.appName,
        category = app.category,
        minutes = app.usedMinutesToday,
        limitMinutes = app.dailyLimitMinutes,
        isBlocked = app.isBlocked,
        sharePercent = (app.usedMinutesToday.toFloat() / sumMins.toFloat()) * 100f,
        color = chartPalette[index % chartPalette.size]
      )
    }
  }

  val totalChartMinutes = remember(slices, totalChildUsedMinutes) {
    val sum = slices.sumOf { it.minutes }
    if (sum > 0) sum else totalChildUsedMinutes
  }

  val animProgress by animateFloatAsState(
    targetValue = if (slices.isNotEmpty()) 1f else 0f,
    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
    label = "ChartAnimProgress"
  )

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("app_usage_distribution_chart_card"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = BorderStroke(
      1.2.dp,
      Brush.horizontalGradient(
        listOf(
          CyanLight.copy(alpha = 0.5f),
          Color(0xFF818CF8).copy(alpha = 0.5f),
          EmeraldShield.copy(alpha = 0.5f)
        )
      )
    )
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // Header Row
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Распределение времени по приложениям",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "Статистика за текущий день • Всего: ${totalChartMinutes / 60} ч ${totalChartMinutes % 60} мин",
            color = Slate400,
            fontSize = 11.sp
          )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          if (onSyncUsage != null) {
            IconButton(
              onClick = onSyncUsage,
              modifier = Modifier.size(30.dp)
            ) {
              Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = "Обновить статистику приложений",
                tint = CyanLight,
                modifier = Modifier.size(16.dp)
              )
            }
          }

          // Toggle Donut / Bar chart
          Row(
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF131F33))
              .border(1.dp, Slate800, RoundedCornerShape(10.dp))
              .padding(2.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp)
          ) {
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (chartMode == 0) CyanContainer else Color.Transparent)
                .clickable { chartMode = 0 }
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.PieChart,
                contentDescription = "Кольцевой график",
                tint = if (chartMode == 0) CyanLight else Slate400,
                modifier = Modifier.size(15.dp)
              )
            }
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (chartMode == 1) CyanContainer else Color.Transparent)
                .clickable { chartMode = 1 }
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Icon(
                imageVector = Icons.Default.BarChart,
                contentDescription = "Столбчатый график",
                tint = if (chartMode == 1) CyanLight else Slate400,
                modifier = Modifier.size(15.dp)
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(14.dp))

      if (slices.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF111C2E))
            .padding(16.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.PieChart, contentDescription = null, tint = CyanLight, modifier = Modifier.size(28.dp))
            Spacer(modifier = Modifier.height(6.dp))
            Text(
              text = "За сегодня ещё нет активности в приложениях",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp
            )
            Text(
              text = "Как только ребёнок откроет игры, видео или соцсети на своём телефоне, график автоматически покажет долю каждого приложения.",
              color = Slate400,
              fontSize = 11.sp,
              lineHeight = 15.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      } else {
        if (chartMode == 0) {
          // --- MODE 0: INTERACTIVE DONUT CHART + LEGEND ---
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp)
          ) {
            Box(
              modifier = Modifier
                .size(156.dp)
                .pointerInput(slices) {
                  detectTapGestures { tapOffset ->
                    val cx = size.width / 2f
                    val cy = size.height / 2f
                    val dx = tapOffset.x - cx
                    val dy = tapOffset.y - cy
                    val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()
                    val outerR = size.width / 2f
                    val innerR = outerR * 0.52f
                    if (dist in innerR..outerR) {
                      var angleDeg = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
                      angleDeg = (angleDeg + 90f + 360f) % 360f
                      var accDeg = 0f
                      var hitIdx = -1
                      slices.forEachIndexed { idx, s ->
                        val sweep = (s.sharePercent / 100f) * 360f
                        if (angleDeg >= accDeg && angleDeg <= accDeg + sweep) {
                          hitIdx = idx
                        }
                        accDeg += sweep
                      }
                      selectedSliceIndex = if (selectedSliceIndex == hitIdx) -1 else hitIdx
                    } else {
                      selectedSliceIndex = -1
                    }
                  }
                },
              contentAlignment = Alignment.Center
            ) {
              Canvas(modifier = Modifier.fillMaxSize().padding(10.dp)) {
                val strokeWidth = 24.dp.toPx()
                val selectedStrokeWidth = 30.dp.toPx()
                if (size.width <= selectedStrokeWidth + 4f || size.height <= selectedStrokeWidth + 4f) return@Canvas
                var startAngle = -90f
                val gapAngle = if (slices.size > 1) 3f else 0f

                slices.forEachIndexed { index, slice ->
                  val safeShare = if (slice.sharePercent.isFinite()) slice.sharePercent.coerceIn(0f, 100f) else 0f
                  val rawSweep = (safeShare / 100f) * 360f * animProgress
                  val sweep = (rawSweep - gapAngle).coerceAtLeast(1.5f)
                  val isSel = selectedSliceIndex == index
                  val curStroke = if (isSel) selectedStrokeWidth else strokeWidth
                  val arcW = (size.width - curStroke).coerceAtLeast(2f)
                  val arcH = (size.height - curStroke).coerceAtLeast(2f)

                  drawArc(
                    color = if (selectedSliceIndex == -1 || isSel) slice.color else slice.color.copy(alpha = 0.35f),
                    startAngle = startAngle + gapAngle / 2f,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = Offset(curStroke / 2f, curStroke / 2f),
                    size = Size(arcW, arcH),
                    style = Stroke(width = curStroke, cap = StrokeCap.Butt)
                  )
                  startAngle += rawSweep
                }
              }

              // Center label inside Donut
              val activeSlice = slices.getOrNull(selectedSliceIndex)
              Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.padding(horizontal = 18.dp)
              ) {
                if (activeSlice != null) {
                  Text(
                    text = activeSlice.appName,
                    color = activeSlice.color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = "${activeSlice.minutes} мин",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                  )
                  Text(
                    text = "%.0f%% дня".format(activeSlice.sharePercent),
                    color = Slate400,
                    fontSize = 9.sp
                  )
                } else {
                  Text(
                    text = "СЕГОДНЯ",
                    color = Slate400,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "${totalChartMinutes / 60}ч ${totalChartMinutes % 60}м",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                  )
                  Text(
                    text = "${slices.size} прил.",
                    color = CyanLight,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.SemiBold
                  )
                }
              }
            }

            // Legend column next to Donut
            Column(
              modifier = Modifier.weight(1f),
              verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              slices.take(5).forEachIndexed { idx, slice ->
                val isSelected = selectedSliceIndex == idx
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isSelected) Color(0xFF17263C) else Color.Transparent)
                    .clickable {
                      selectedSliceIndex = if (isSelected) -1 else idx
                    }
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                  ) {
                    Box(
                      modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(slice.color)
                    )
                    Text(
                      text = slice.appName,
                      color = Color.White,
                      fontSize = 11.sp,
                      fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                  Text(
                    text = "${slice.minutes}м (${slice.sharePercent.toInt()}%)",
                    color = slice.color,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }
          }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // --- DETAILED COMPARATIVE BARS PER APP WITH QUICK BLOCK/ALLOW ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          slices.forEachIndexed { index, slice ->
            val isSelected = selectedSliceIndex == index
            val matchedApp = apps.find { it.id == slice.appId }

            Column(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isSelected) Color(0xFF16243A) else Color(0xFF111C2E))
                .border(
                  1.dp,
                  if (isSelected) slice.color else Slate800,
                  RoundedCornerShape(12.dp)
                )
                .clickable {
                  selectedSliceIndex = if (isSelected) -1 else index
                }
                .padding(horizontal = 10.dp, vertical = 8.dp)
            ) {
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
                  Box(
                    modifier = Modifier
                      .size(10.dp)
                      .clip(CircleShape)
                      .background(slice.color)
                  )
                  Text(
                    text = slice.appName,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = "• ${slice.category}",
                    color = Slate400,
                    fontSize = 10.sp
                  )
                }

                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Text(
                    text = "${slice.minutes} мин (%.0f%%)".format(slice.sharePercent),
                    color = slice.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                  )

                  if (onToggleAppBlock != null && matchedApp != null) {
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (slice.isBlocked) RoseLock.copy(alpha = 0.2f) else EmeraldShield.copy(alpha = 0.18f))
                        .border(1.dp, if (slice.isBlocked) RoseLock else EmeraldShield, RoundedCornerShape(6.dp))
                        .clickable { onToggleAppBlock(matchedApp) }
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                          imageVector = if (slice.isBlocked) Icons.Default.Block else Icons.Default.CheckCircle,
                          contentDescription = null,
                          tint = if (slice.isBlocked) RoseLock else EmeraldShieldLight,
                          modifier = Modifier.size(11.dp)
                        )
                        Text(
                          text = if (slice.isBlocked) "Блок" else "Разреш.",
                          color = if (slice.isBlocked) RoseLock else EmeraldShieldLight,
                          fontSize = 9.sp,
                          fontWeight = FontWeight.Bold
                        )
                      }
                    }
                  }
                }
              }

              Spacer(modifier = Modifier.height(5.dp))

              LinearProgressIndicator(
                progress = { ((slice.sharePercent / 100f) * animProgress).coerceIn(0.03f, 1f) },
                modifier = Modifier
                  .fillMaxWidth()
                  .height(6.dp)
                  .clip(RoundedCornerShape(3.dp)),
                color = if (slice.isBlocked) RoseLock else slice.color,
                trackColor = Slate800
              )
            }
          }
        }
      }
    }
  }
}
