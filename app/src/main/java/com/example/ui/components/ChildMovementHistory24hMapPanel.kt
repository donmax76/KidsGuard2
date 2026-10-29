package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.CloudSyncManager
import com.example.data.model.ChildProfileEntity
import com.example.data.model.GeofenceZoneEntity
import com.example.data.model.LocationBreadcrumb
import com.example.location.LiveLocationTracker
import com.example.location.LocationState
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@Composable
fun ChildMovementHistory24hMapPanel(
  child: ChildProfileEntity?,
  locationState: LocationState,
  geofences: List<GeofenceZoneEntity>,
  onOpenFullMap: () -> Unit,
  onRefreshGps: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val cloudSync = remember { CloudSyncManager(context) }

  var selectedWindowHours by remember { mutableIntStateOf(24) }
  var showRouteTrail by remember { mutableStateOf(true) }
  var selectedPoint by remember(child?.id) { mutableStateOf<LocationBreadcrumb?>(null) }
  var cloudHistory24h by remember(child?.id) { mutableStateOf<List<LocationBreadcrumb>>(emptyList()) }

  val latestCloudPoint = cloudHistory24h.lastOrNull()
  val effectiveLat = when {
    child != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) -> child.lastLocationLat
    latestCloudPoint != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(latestCloudPoint.latitude, latestCloudPoint.longitude) -> latestCloudPoint.latitude
    else -> 0.0
  }

  val effectiveLng = when {
    child != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) -> child.lastLocationLng
    latestCloudPoint != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(latestCloudPoint.latitude, latestCloudPoint.longitude) -> latestCloudPoint.longitude
    else -> 0.0
  }

  var resolvedStreetAddress by remember(effectiveLat, effectiveLng) {
    mutableStateOf<String?>(null)
  }

  LaunchedEffect(child?.id) {
    val cId = child?.id ?: run {
      cloudHistory24h = emptyList()
      return@LaunchedEffect
    }
    cloudSync.observeLocationHistory24h(cId).collect { list ->
      cloudHistory24h = list
    }
  }

  LaunchedEffect(effectiveLat, effectiveLng) {
    if (effectiveLat != 0.0 && effectiveLng != 0.0) {
      val existingAddr = child?.lastLocationAddress.orEmpty()
      if (LiveLocationTracker.isStaleOrDemoAddress(existingAddr) || existingAddr.startsWith("Точные координаты")) {
        withContext(Dispatchers.IO) {
          val geocoded = LiveLocationTracker.reverseGeocodeExact(context, effectiveLat, effectiveLng)
          if (geocoded.isNotBlank()) {
            resolvedStreetAddress = geocoded
          }
        }
      }
    }
  }

  val rawAddr = when {
    child != null && child.lastLocationAddress.isNotBlank() && !LiveLocationTracker.isStaleOrDemoAddress(child.lastLocationAddress) -> child.lastLocationAddress
    latestCloudPoint != null && latestCloudPoint.title.isNotBlank() -> latestCloudPoint.title
    else -> ""
  }
  val effectiveAddress = when {
    !resolvedStreetAddress.isNullOrBlank() -> resolvedStreetAddress!!
    effectiveLat != 0.0 && rawAddr.isNotBlank() && !LiveLocationTracker.isStaleOrDemoAddress(rawAddr) -> rawAddr
    effectiveLat != 0.0 -> "Точные координаты GPS: %.5f, %.5f".format(effectiveLat, effectiveLng)
    else -> "Ожидание точного GPS-сигнала с телефона ребёнка..."
  }

  val effectiveLastTimestamp = when {
    child != null && child.lastLocationTime > 0 && !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) -> child.lastLocationTime
    latestCloudPoint != null && latestCloudPoint.timestamp > 0 -> latestCloudPoint.timestamp
    else -> 0L
  }

  val filteredHistory = remember(
    cloudHistory24h,
    effectiveLat,
    effectiveLng,
    effectiveAddress,
    selectedWindowHours,
    effectiveLastTimestamp
  ) {
    val now = System.currentTimeMillis()
    val cutoffMs = now - selectedWindowHours * 3600L * 1000L
    val combined = mutableListOf<LocationBreadcrumb>()
    combined.addAll(cloudHistory24h)
    if (effectiveLat != 0.0 && effectiveLng != 0.0) {
      val liveTs = effectiveLastTimestamp.takeIf { it >= cutoffMs } ?: now
      val hasNearCurrent = combined.any {
        panelHaversineMeters(it.latitude, it.longitude, effectiveLat, effectiveLng) < 10.0 &&
            kotlin.math.abs(it.timestamp - liveTs) < 60_000L
      }
      if (!hasNearCurrent) {
        combined.add(
          LocationBreadcrumb(
            latitude = effectiveLat,
            longitude = effectiveLng,
            timestamp = liveTs,
            title = effectiveAddress,
            speedKmh = latestCloudPoint?.speedKmh ?: 0f
          )
        )
      }
    }
    combined
      .filter { it.timestamp >= cutoffMs && !LiveLocationTracker.isEmulatorSyntheticUsLocation(it.latitude, it.longitude) }
      .sortedBy { it.timestamp }
      .fold(mutableListOf<LocationBreadcrumb>()) { acc, item ->
        val prev = acc.lastOrNull()
        if (prev == null ||
            panelHaversineMeters(prev.latitude, prev.longitude, item.latitude, item.longitude) >= 8.0 ||
            item.timestamp - prev.timestamp >= 60_000L) {
          acc.add(item)
        }
        acc
      }
  }

  val totalDistanceMeters = remember(filteredHistory) {
    var sum = 0.0
    for (i in 1 until filteredHistory.size) {
      val a = filteredHistory[i - 1]
      val b = filteredHistory[i]
      sum += panelHaversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    sum
  }

  val maxSpeedKmh = remember(filteredHistory, locationState.speedKmh) {
    val histMax = filteredHistory.maxOfOrNull { it.speedKmh } ?: 0f
    maxOf(histMax, locationState.speedKmh)
  }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("location_widget_card"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = BorderStroke(
      1.2.dp,
      Brush.horizontalGradient(
        listOf(
          CyanLight.copy(alpha = 0.55f),
          Color(0xFF6366F1).copy(alpha = 0.5f),
          EmeraldShield.copy(alpha = 0.55f)
        )
      )
    )
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // 1. Header Row
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
              .background(CyanContainer),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.History,
              contentDescription = null,
              tint = CyanLight,
              modifier = Modifier.size(22.dp)
            )
          }
          Column {
            Text(
              text = "Карта и история за 24 часа",
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.ExtraBold
            )
            val subText = if (child != null) {
              "${child.name} • GPS-трекинг маршрута"
            } else {
              "Добавьте ребёнка для записи GPS-маршрута"
            }
            Text(
              text = subText,
              color = EmeraldShieldLight,
              fontSize = 11.sp,
              fontWeight = FontWeight.Medium
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          IconButton(
            onClick = {
              selectedPoint = null
              onRefreshGps()
            },
            modifier = Modifier.size(34.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Запросить свежий GPS",
              tint = CyanLight,
              modifier = Modifier.size(18.dp)
            )
          }

          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(CyanContainer)
              .clickable { onOpenFullMap() }
              .padding(horizontal = 10.dp, vertical = 6.dp)
              .testTag("open_full_24h_map_button")
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Text(
                text = "Карта",
                color = CyanLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
              Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = CyanLight,
                modifier = Modifier.size(13.dp)
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // 2. Embedded Interactive Google Map with 24h Route Polyline & Waypoints
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .height(350.dp)
          .clip(RoundedCornerShape(16.dp))
          .border(1.dp, Slate800, RoundedCornerShape(16.dp))
          .testTag("dashboard_embedded_24h_map")
      ) {
        TileMapCanvas(
          locationState = locationState,
          geofences = geofences,
          childName = child?.name,
          childLat = effectiveLat,
          childLng = effectiveLng,
          historyPoints = filteredHistory,
          selectedHistoryPoint = selectedPoint,
          showRouteTrail = showRouteTrail,
          compactControls = true,
          onCenterClick = {
            selectedPoint = null
            onRefreshGps()
          },
          modifier = Modifier.fillMaxSize()
        )

        // Top-Left Address & Timestamp Overlay Pill inside embedded map
        Box(
          modifier = Modifier
            .align(Alignment.TopStart)
            .padding(8.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xE60B1324))
            .border(1.dp, CyanLight.copy(alpha = 0.35f), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
          Column {
            val activeTitle = if (selectedPoint != null) {
              val tStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(selectedPoint!!.timestamp))
              "🕒 Точка $tStr: ${selectedPoint!!.title}"
            } else {
              "📍 $effectiveAddress"
            }
            Text(
              text = activeTitle,
              color = if (selectedPoint != null) AmberWarning else Color.White,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            if (effectiveLat != 0.0) {
              val batText = if (child != null && child.batteryLevel in 1..100) "${child.batteryLevel}%" else "ожидание"
              val gpsCoordsText = "GPS: %.5f, %.5f".format(effectiveLat, effectiveLng)
              Text(
                text = "$gpsCoordsText • Батарея: $batText",
                color = Slate400,
                fontSize = 9.sp
              )
            }
          }
        }

        // Top-Right Toggle 24h Route Trail Pill
        Box(
          modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(8.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(if (showRouteTrail) Color(0xE6082F49) else Color(0xE60F172A))
            .border(1.dp, if (showRouteTrail) CyanLight else Slate800, RoundedCornerShape(9.dp))
            .clickable { showRouteTrail = !showRouteTrail }
            .padding(horizontal = 8.dp, vertical = 5.dp)
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Icon(
              imageVector = Icons.Default.Timeline,
              contentDescription = null,
              tint = if (showRouteTrail) CyanLight else Slate400,
              modifier = Modifier.size(12.dp)
            )
            Text(
              text = if (showRouteTrail) "Трек 24ч ВКЛ" else "Трек скрыт",
              color = if (showRouteTrail) CyanLight else Slate400,
              fontSize = 10.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      // 3. Summary KPI Row (Distance, GPS Points, Max Speed)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val distLabel = if (totalDistanceMeters >= 1000.0) {
          "%.2f км".format(totalDistanceMeters / 1000.0)
        } else {
          "${totalDistanceMeters.toInt()} м"
        }
        MiniRouteMetricTile(
          label = "Дистанция (${selectedWindowHours}ч)",
          value = distLabel,
          icon = Icons.Default.Route,
          accent = CyanLight,
          modifier = Modifier.weight(1f)
        )
        MiniRouteMetricTile(
          label = "Точек маршрута",
          value = "${filteredHistory.size}",
          icon = Icons.Default.LocationOn,
          accent = EmeraldShieldLight,
          modifier = Modifier.weight(1f)
        )
        MiniRouteMetricTile(
          label = "Макс. скорость",
          value = "%.1f км/ч".format(maxSpeedKmh),
          icon = Icons.Default.Speed,
          accent = Color(0xFFA855F7),
          modifier = Modifier.weight(1f)
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 4. Time Window Selector Chips (1ч / 6ч / 24ч) & Step-by-Step Scrubber
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(1 to "За 1 час", 6 to "За 6 часов", 24 to "За 24 часа").forEach { (hrs, label) ->
            val selected = selectedWindowHours == hrs
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) CyanContainer else Color(0xFF131F33))
                .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(8.dp))
                .clickable {
                  selectedWindowHours = hrs
                  selectedPoint = null
                }
                .padding(horizontal = 9.dp, vertical = 5.dp)
            ) {
              Text(
                text = label,
                color = if (selected) CyanLight else Slate400,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }

        if (selectedPoint != null) {
          Text(
            text = "Вернуть LIVE",
            color = AmberWarning,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { selectedPoint = null }
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 5. Chronological List of Recent 24-Hour Waypoints (tap to focus on map)
      if (filteredHistory.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111C2E))
            .padding(12.dp)
        ) {
          Text(
            text = "Ожидание точек GPS-трекинга с телефона ребёнка. Все перемещения за последние 24 часа автоматически сохраняются и строят маршрут на карте.",
            color = Slate400,
            fontSize = 11.sp,
            lineHeight = 15.sp
          )
        }
      } else {
        val recentPoints = remember(filteredHistory) { filteredHistory.reversed().take(4) }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          recentPoints.forEachIndexed { idx, pt ->
            val isLatest = idx == 0
            val isSelected = selectedPoint?.timestamp == pt.timestamp
            val timeStr = remember(pt.timestamp) {
              SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(pt.timestamp))
            }
            val relMin = ((System.currentTimeMillis() - pt.timestamp) / 60_000L).coerceAtLeast(0L)
            val relStr = when {
              relMin < 1L -> "только что"
              relMin < 60L -> "$relMin мин назад"
              else -> "${relMin / 60L} ч ${relMin % 60L} мин назад"
            }

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                  when {
                    isSelected -> Color(0xFF282112)
                    isLatest -> Color(0xFF10263B)
                    else -> Color(0xFF131F33)
                  }
                )
                .border(
                  1.dp,
                  when {
                    isSelected -> AmberWarning
                    isLatest -> CyanLight.copy(alpha = 0.55f)
                    else -> Slate800
                  },
                  RoundedCornerShape(12.dp)
                )
                .clickable {
                  selectedPoint = if (isSelected) null else pt
                }
                .padding(horizontal = 10.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                Box(
                  modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(if (isSelected) AmberWarning.copy(alpha = 0.2f) else if (isLatest) CyanContainer else Slate800),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(
                    imageVector = if (pt.speedKmh > 1.5f) Icons.Default.DirectionsWalk else Icons.Default.LocationOn,
                    contentDescription = null,
                    tint = if (isSelected) AmberWarning else if (isLatest) CyanLight else Slate200,
                    modifier = Modifier.size(15.dp)
                  )
                }
                Column(modifier = Modifier.weight(1f)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                      text = timeStr,
                      color = Color.White,
                      fontWeight = FontWeight.ExtraBold,
                      fontSize = 11.sp
                    )
                    Text(
                      text = "• $relStr",
                      color = if (isLatest) EmeraldShieldLight else Slate400,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.SemiBold
                    )
                  }
                  Text(
                    text = pt.title,
                    color = Slate200,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }

              Column(horizontalAlignment = Alignment.End) {
                Text(
                  text = if (pt.speedKmh >= 1f) "%.1f км/ч".format(pt.speedKmh) else "На месте",
                  color = if (pt.speedKmh >= 1f) CyanLight else Slate400,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
                Text(
                  text = "%.4f, %.4f".format(pt.latitude, pt.longitude),
                  color = Slate400,
                  fontSize = 9.sp
                )
              }
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      Button(
        onClick = onOpenFullMap,
        modifier = Modifier
          .fillMaxWidth()
          .height(42.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF15263D), contentColor = CyanLight),
        shape = RoundedCornerShape(12.dp)
      ) {
        Icon(Icons.Default.Route, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Полная карта маршрута за 24 часа (${filteredHistory.size} точ.)",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
private fun MiniRouteMetricTile(
  label: String,
  value: String,
  icon: ImageVector,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .height(50.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF131F33))
      .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(12.dp))
      .padding(horizontal = 8.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp)
  ) {
    Box(
      modifier = Modifier
        .size(24.dp)
        .clip(RoundedCornerShape(7.dp))
        .background(accent.copy(alpha = 0.16f)),
      contentAlignment = Alignment.Center
    ) {
      Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(13.dp))
    }
    Column(modifier = Modifier.weight(1f)) {
      Text(text = value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      Text(text = label, color = Slate400, fontSize = 9.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
  }
}

private fun panelHaversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val r = 6371000.0
  val dLat = Math.toRadians(lat2 - lat1)
  val dLon = Math.toRadians(lon2 - lon1)
  val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
      sin(dLon / 2) * sin(dLon / 2)
  val c = 2 * atan2(sqrt(a), sqrt(1 - a))
  return r * c
}
