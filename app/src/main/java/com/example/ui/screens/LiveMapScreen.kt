package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LocationOff
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SportsScore
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
import com.example.ui.components.AddGeofenceDialog
import com.example.ui.components.TileMapCanvas
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
fun LiveMapScreen(
  child: ChildProfileEntity?,
  locationState: LocationState,
  geofences: List<GeofenceZoneEntity>,
  isSimulationActive: Boolean,
  onToggleSimulation: () -> Unit,
  onAddGeofence: (name: String, lat: Double, lng: Double, radius: Float, iconType: String) -> Unit,
  onDeleteGeofence: (GeofenceZoneEntity) -> Unit,
  onRefreshLocation: () -> Unit = {},
  onToggleGpsTracking: (Long) -> Unit = {},
  modifier: Modifier = Modifier
) {
  var isAddZoneDialogVisible by remember { mutableStateOf(false) }
  var isBottomPanelExpanded by remember { mutableStateOf(false) }
  // 0 = История перемещений 24ч, 1 = Геозоны
  var activePanelTab by remember { mutableIntStateOf(0) }
  // Time filter in hours: 24, 6, 1
  var selectedWindowHours by remember { mutableIntStateOf(24) }
  var showRouteTrailOnMap by remember { mutableStateOf(true) }
  var selectedHistoryPoint by remember(child?.id) { mutableStateOf<LocationBreadcrumb?>(null) }
  var cloudHistory24h by remember(child?.id) { mutableStateOf<List<LocationBreadcrumb>>(emptyList()) }

  val context = LocalContext.current
  val cloudSync = remember { CloudSyncManager(context) }

  val latestCloudPoint = cloudHistory24h.lastOrNull()
  val rawLat = when {
    child != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) -> child.lastLocationLat
    latestCloudPoint != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(latestCloudPoint.latitude, latestCloudPoint.longitude) -> latestCloudPoint.latitude
    else -> 0.0
  }
  val rawLng = when {
    child != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) -> child.lastLocationLng
    latestCloudPoint != null && !LiveLocationTracker.isEmulatorSyntheticUsLocation(latestCloudPoint.latitude, latestCloudPoint.longitude) -> latestCloudPoint.longitude
    else -> 0.0
  }
  val effectiveLat = rawLat
  val effectiveLng = rawLng

  var resolvedStreetAddress by remember(effectiveLat, effectiveLng) {
    mutableStateOf<String?>(null)
  }

  // Observe 24-hour GPS route history from P2P in real-time
  LaunchedEffect(child?.id) {
    val cId = child?.id ?: run {
      cloudHistory24h = emptyList()
      return@LaunchedEffect
    }
    cloudSync.observeLocationHistory24h(cId).collect { list ->
      cloudHistory24h = list
    }
  }

  // Request immediate GPS update from Child phone when Map opens, and reverse-geocode exact street if needed
  LaunchedEffect(child?.id, effectiveLat, effectiveLng) {
    onRefreshLocation()
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
  val batteryPercent = if (child != null && child.batteryLevel in 1..100) child.batteryLevel else -1
  val effectiveLastTimestamp = when {
    child != null && child.lastLocationTime > 0 && !LiveLocationTracker.isEmulatorSyntheticUsLocation(child.lastLocationLat, child.lastLocationLng) -> child.lastLocationTime
    latestCloudPoint != null && latestCloudPoint.timestamp > 0 -> latestCloudPoint.timestamp
    else -> 0L
  }
  val lastUpdatedTime = if (effectiveLastTimestamp > 0 && effectiveLat != 0.0) {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
      timeZone = java.util.TimeZone.getDefault()
    }.format(Date(effectiveLastTimestamp))
  } else if (effectiveLat != 0.0) {
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).apply {
      timeZone = java.util.TimeZone.getDefault()
    }.format(Date())
  } else {
    "поиск GPS..."
  }

  // Merge cloud 24h history + current live Child fix, filtered by selectedWindowHours
  val filteredHistory24h = remember(cloudHistory24h, effectiveLat, effectiveLng, effectiveAddress, selectedWindowHours, effectiveLastTimestamp) {
    val now = System.currentTimeMillis()
    val cutoffMs = now - selectedWindowHours * 3600L * 1000L
    val combined = mutableListOf<LocationBreadcrumb>()
    combined.addAll(cloudHistory24h)
    if (effectiveLat != 0.0 && effectiveLng != 0.0) {
      val liveTs = effectiveLastTimestamp.takeIf { it >= cutoffMs } ?: now
      val hasNearCurrent = combined.any {
        haversineMeters(it.latitude, it.longitude, effectiveLat, effectiveLng) < 10.0 &&
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
            haversineMeters(prev.latitude, prev.longitude, item.latitude, item.longitude) >= 8.0 ||
            item.timestamp - prev.timestamp >= 60_000L) {
          acc.add(item)
        }
        acc
      }
  }

  // Calculate total distance in meters and max speed over the selected window
  val totalDistanceMeters = remember(filteredHistory24h) {
    var sum = 0.0
    for (i in 1 until filteredHistory24h.size) {
      val a = filteredHistory24h[i - 1]
      val b = filteredHistory24h[i]
      sum += haversineMeters(a.latitude, a.longitude, b.latitude, b.longitude)
    }
    sum
  }
  val maxSpeedKmh = remember(filteredHistory24h, locationState.speedKmh) {
    val histMax = filteredHistory24h.maxOfOrNull { it.speedKmh } ?: 0f
    maxOf(histMax, locationState.speedKmh)
  }

  Box(modifier = modifier.fillMaxSize()) {
    // 1. Fullscreen Map Canvas with 24-Hour Route Polyline & Waypoints
    TileMapCanvas(
      locationState = locationState,
      geofences = geofences,
      childName = child?.name,
      childLat = effectiveLat,
      childLng = effectiveLng,
      historyPoints = filteredHistory24h,
      selectedHistoryPoint = selectedHistoryPoint,
      showRouteTrail = showRouteTrailOnMap,
      onCenterClick = {
        selectedHistoryPoint = null
        onRefreshLocation()
      },
      modifier = Modifier.fillMaxSize()
    )

    // 2. Top Header HUD with clear GPS telemetry & 24h Route Toggle
    Card(
      modifier = Modifier
        .align(Alignment.TopCenter)
        .padding(horizontal = 14.dp, vertical = 10.dp)
        .fillMaxWidth()
        .testTag("map_telemetry_hud"),
      shape = RoundedCornerShape(18.dp),
      colors = CardDefaults.cardColors(containerColor = Slate900.copy(alpha = 0.94f)),
      border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.45f))
    ) {
      Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
        if (child == null) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.LocationOff, contentDescription = null, tint = CyanLight)
            Text(
              text = "Нет добавленных детей",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp
            )
          }
        } else {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(if (effectiveLat != 0.0) EmeraldShield else Color(0xFFF59E0B))
              )
              Text(
                text = "${child.name} • $lastUpdatedTime",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
              )
            }

            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
              // Toggle GPS Satellite Tracking ON/OFF (Battery Saving)
              val gpsEnabled = child.gpsTrackingEnabled
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (gpsEnabled) EmeraldShield.copy(alpha = 0.2f) else AmberWarning.copy(alpha = 0.2f))
                  .border(1.dp, if (gpsEnabled) EmeraldShield else AmberWarning, RoundedCornerShape(8.dp))
                  .clickable { onToggleGpsTracking(child.id) }
                  .padding(horizontal = 7.dp, vertical = 3.dp)
                  .testTag("map_toggle_gps_tracking_chip")
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                  Icon(
                    imageVector = if (gpsEnabled) Icons.Default.LocationOn else Icons.Default.LocationOff,
                    contentDescription = "Вкл/Выкл GPS",
                    tint = if (gpsEnabled) EmeraldShieldLight else AmberWarning,
                    modifier = Modifier.size(12.dp)
                  )
                  Text(
                    text = if (gpsEnabled) "GPS ВКЛ" else "GPS ВЫКЛ",
                    color = if (gpsEnabled) EmeraldShieldLight else AmberWarning,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                  )
                }
              }

              // Toggle 24h Route Trail on Map
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(8.dp))
                  .background(if (showRouteTrailOnMap) CyanContainer else Slate800)
                  .border(1.dp, if (showRouteTrailOnMap) CyanLight else Slate400.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                  .clickable { showRouteTrailOnMap = !showRouteTrailOnMap }
                  .padding(horizontal = 7.dp, vertical = 3.dp)
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                  Icon(
                    imageVector = Icons.Default.Timeline,
                    contentDescription = "Линия маршрута 24ч",
                    tint = if (showRouteTrailOnMap) CyanLight else Slate400,
                    modifier = Modifier.size(12.dp)
                  )
                  Text(
                    text = if (showRouteTrailOnMap) "Трек 24ч" else "Скрыт",
                    color = if (showRouteTrailOnMap) CyanLight else Slate400,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1
                  )
                }
              }

              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(if (batteryPercent > 20) EmeraldShield.copy(alpha = 0.2f) else if (batteryPercent in 1..20) RoseLock.copy(alpha = 0.2f) else Slate800)
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                  Icon(
                    imageVector = Icons.Default.BatteryChargingFull,
                    contentDescription = null,
                    tint = if (batteryPercent > 20) EmeraldShieldLight else if (batteryPercent in 1..20) RoseLock else AmberWarning,
                    modifier = Modifier.size(12.dp)
                  )
                  Text(
                    text = if (batteryPercent in 1..100) "$batteryPercent%" else "—%",
                    color = if (batteryPercent > 20) EmeraldShieldLight else if (batteryPercent in 1..20) RoseLock else AmberWarning,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }

              IconButton(
                onClick = {
                  selectedHistoryPoint = null
                  onRefreshLocation()
                },
                modifier = Modifier.size(28.dp)
              ) {
                Icon(Icons.Default.Refresh, contentDescription = "Обновить GPS", tint = CyanLight, modifier = Modifier.size(18.dp))
              }
            }
          }

          Spacer(modifier = Modifier.height(4.dp))

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(34.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            Text(
              text = if (selectedHistoryPoint != null) {
                val selTime = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(selectedHistoryPoint!!.timestamp))
                "🕒 Точка в $selTime: ${selectedHistoryPoint!!.title}"
              } else {
                effectiveAddress
              },
              color = if (selectedHistoryPoint != null) AmberWarning else Slate200,
              fontSize = 12.sp,
              lineHeight = 15.sp,
              fontWeight = FontWeight.Medium,
              maxLines = 2,
              overflow = TextOverflow.Ellipsis
            )
          }

          Spacer(modifier = Modifier.height(2.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = if (effectiveLat != 0.0) "GPS: %.5f, %.5f".format(effectiveLat, effectiveLng) else "Ожидание спутников GPS...",
              color = Slate400,
              fontSize = 10.sp
            )
            if (selectedHistoryPoint != null) {
              Text(
                text = "Вернуться к LIVE →",
                color = CyanLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.clickable { selectedHistoryPoint = null }
              )
            }
          }
        }
      }
    }

    // 3. Bottom 24-Hour Movement History & Geofences Panel
    Card(
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .padding(horizontal = 12.dp, vertical = 8.dp)
        .fillMaxWidth()
        .testTag("history_24h_panel"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Color(0xF20B1324)),
      border = androidx.compose.foundation.BorderStroke(
        1.2.dp,
        Brush.horizontalGradient(listOf(CyanLight.copy(alpha = 0.6f), Color(0xFF6366F1).copy(alpha = 0.6f), EmeraldShield.copy(alpha = 0.6f)))
      )
    ) {
      Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
        // Segmented Header Bar: [История за 24ч] | [Геозоны] + Expand/Collapse button
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f)
          ) {
            // Tab 0: 24-Hour Movement History
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                  if (activePanelTab == 0) Brush.horizontalGradient(listOf(Color(0xFF0284C7), Color(0xFF4F46E5)))
                  else Brush.horizontalGradient(listOf(Slate800, Slate800))
                )
                .clickable {
                  activePanelTab = 0
                  isBottomPanelExpanded = true
                }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("tab_history_24h")
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(
                  imageVector = Icons.Default.History,
                  contentDescription = null,
                  tint = Color.White,
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = "История 24ч (${filteredHistory24h.size})",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp
                )
              }
            }

            // Tab 1: Geofences
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(10.dp))
                .background(
                  if (activePanelTab == 1) Brush.horizontalGradient(listOf( Color(0xFF059669), Color(0xFF0D9488)))
                  else Brush.horizontalGradient(listOf(Slate800, Slate800))
                )
                .clickable {
                  activePanelTab = 1
                  isBottomPanelExpanded = true
                }
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .testTag("tab_geofences")
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(
                  imageVector = Icons.Default.Security,
                  contentDescription = null,
                  tint = Color.White,
                  modifier = Modifier.size(14.dp)
                )
                Text(
                  text = "Геозоны (${geofences.size})",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp
                )
              }
            }
          }

          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            if (activePanelTab == 1) {
              Button(
                onClick = { isAddZoneDialogVisible = true },
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldShield, contentColor = Color(0xFF020617)),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.height(28.dp),
                contentPadding = PaddingValues(horizontal = 8.dp)
              ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(12.dp))
                Spacer(modifier = Modifier.width(2.dp))
                Text("Зона", fontSize = 10.sp, fontWeight = FontWeight.Bold)
              }
            }

            IconButton(
              onClick = { isBottomPanelExpanded = !isBottomPanelExpanded },
              modifier = Modifier.size(28.dp)
            ) {
              Icon(
                imageVector = if (isBottomPanelExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = "Свернуть или развернуть панель",
                tint = CyanLight
              )
            }
          }
        }

        if (isBottomPanelExpanded) {
          Spacer(modifier = Modifier.height(8.dp))

          if (activePanelTab == 0) {
            // --- 24-HOUR GPS MOVEMENT HISTORY CONTENT ---
            // 1. 3 KPI Summary Pills (Distance, Points, Max Speed)
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              val distFormatted = if (totalDistanceMeters >= 1000.0) {
                "%.2f км".format(totalDistanceMeters / 1000.0)
              } else {
                "${totalDistanceMeters.toInt()} м"
              }
              HistoryStatTile(
                label = "Пройдено за ${selectedWindowHours}ч",
                value = distFormatted,
                icon = Icons.Default.Route,
                accent = CyanLight,
                modifier = Modifier.weight(1f)
              )
              HistoryStatTile(
                label = "Точек GPS",
                value = "${filteredHistory24h.size}",
                icon = Icons.Default.LocationOn,
                accent = EmeraldShieldLight,
                modifier = Modifier.weight(1f)
              )
              HistoryStatTile(
                label = "Макс. скорость",
                value = "%.1f км/ч".format(maxSpeedKmh),
                icon = Icons.Default.Speed,
                accent = Color(0xFFA855F7),
                modifier = Modifier.weight(1f)
              )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 2. Time Filter Chips (1ч / 6ч / 24ч) & Step-by-Step Route Scrubber
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(1 to "За 1ч", 6 to "За 6ч", 24 to "За 24ч").forEach { (hours, title) ->
                  val selected = selectedWindowHours == hours
                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(8.dp))
                      .background(if (selected) CyanContainer else Color(0xFF152033))
                      .border(1.dp, if (selected) CyanLight else Slate800, RoundedCornerShape(8.dp))
                      .clickable {
                        selectedWindowHours = hours
                        selectedHistoryPoint = null
                      }
                      .padding(horizontal = 8.dp, vertical = 4.dp)
                  ) {
                    Text(
                      text = title,
                      color = if (selected) CyanLight else Slate400,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }

              if (filteredHistory24h.size > 1) {
                Row(
                  horizontalArrangement = Arrangement.spacedBy(4.dp),
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  val currentIdx = selectedHistoryPoint?.let { sel ->
                    filteredHistory24h.indexOfFirst { it.timestamp == sel.timestamp }
                  } ?: filteredHistory24h.lastIndex

                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(7.dp))
                      .background(Color(0xFF152033))
                      .border(1.dp, Slate800, RoundedCornerShape(7.dp))
                      .clickable {
                        val prevIdx = (currentIdx - 1).coerceAtLeast(0)
                        selectedHistoryPoint = filteredHistory24h.getOrNull(prevIdx)
                      }
                      .padding(horizontal = 7.dp, vertical = 4.dp)
                  ) {
                    Text("◀ Пред.", color = Slate200, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                  }

                  Box(
                    modifier = Modifier
                      .clip(RoundedCornerShape(7.dp))
                      .background(Color(0xFF152033))
                      .border(1.dp, Slate800, RoundedCornerShape(7.dp))
                      .clickable {
                        val nextIdx = (currentIdx + 1).coerceAtMost(filteredHistory24h.lastIndex)
                        selectedHistoryPoint = if (nextIdx == filteredHistory24h.lastIndex) null else filteredHistory24h.getOrNull(nextIdx)
                      }
                      .padding(horizontal = 7.dp, vertical = 4.dp)
                  ) {
                    Text("След. ▶", color = Slate200, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                  }

                  if (selectedHistoryPoint != null) {
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(7.dp))
                        .background(AmberWarning.copy(alpha = 0.2f))
                        .border(1.dp, AmberWarning, RoundedCornerShape(7.dp))
                        .clickable { selectedHistoryPoint = null }
                        .padding(horizontal = 7.dp, vertical = 4.dp)
                    ) {
                      Text("LIVE", color = AmberWarning, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                    }
                  }
                }
              } else if (selectedHistoryPoint != null) {
                Text(
                  text = "Показать LIVE",
                  color = AmberWarning,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  modifier = Modifier.clickable { selectedHistoryPoint = null }
                )
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Chronological Timeline List of 24-Hour Movements
            if (filteredHistory24h.isEmpty()) {
              Box(
                modifier = Modifier
                  .fillMaxWidth()
                  .clip(RoundedCornerShape(12.dp))
                  .background(Color(0xFF111C2D))
                  .padding(12.dp),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = "Ожидание первых точек маршрута GPS с телефона ребёнка...\nКак только телефон передаст координаты, здесь появится хронология всех перемещений и остановок за 24 часа.",
                  color = Slate400,
                  fontSize = 11.sp,
                  lineHeight = 15.sp
                )
              }
            } else {
              val displayReversed = remember(filteredHistory24h) { filteredHistory24h.reversed() }
              LazyColumn(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 165.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
              ) {
                itemsIndexed(displayReversed) { idx, point ->
                  val isLatest = idx == 0
                  val isStart = idx == displayReversed.lastIndex && displayReversed.size > 1
                  val isSelected = selectedHistoryPoint?.timestamp == point.timestamp
                  val timeFormatted = remember(point.timestamp) {
                    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(point.timestamp))
                  }
                  val relMinutes = ((System.currentTimeMillis() - point.timestamp) / 60_000L).coerceAtLeast(0L)
                  val relLabel = when {
                    relMinutes < 1L -> "только что"
                    relMinutes < 60L -> "$relMinutes мин назад"
                    else -> "${relMinutes / 60L} ч ${relMinutes % 60L} мин назад"
                  }

                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(12.dp))
                      .background(
                        when {
                          isSelected -> Color(0xFF282112)
                          isLatest -> Color(0xFF10263B)
                          else -> Color(0xFF121D2F)
                        }
                      )
                      .border(
                        1.dp,
                        when {
                          isSelected -> AmberWarning
                          isLatest -> CyanLight.copy(alpha = 0.6f)
                          else -> Slate800
                        },
                        RoundedCornerShape(12.dp)
                      )
                      .clickable {
                        selectedHistoryPoint = if (isSelected) null else point
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
                          .background(
                            when {
                              isSelected -> AmberWarning.copy(alpha = 0.22f)
                              isLatest -> CyanContainer
                              isStart -> EmeraldShield.copy(alpha = 0.22f)
                              else -> Slate800
                            }
                          ),
                        contentAlignment = Alignment.Center
                      ) {
                        Icon(
                          imageVector = if (point.speedKmh > 1.5f) Icons.Default.DirectionsWalk else Icons.Default.LocationOn,
                          contentDescription = null,
                          tint = when {
                            isSelected -> AmberWarning
                            isLatest -> CyanLight
                            isStart -> EmeraldShieldLight
                            else -> Slate200
                          },
                          modifier = Modifier.size(15.dp)
                        )
                      }

                      Column(modifier = Modifier.weight(1f)) {
                        Row(
                          verticalAlignment = Alignment.CenterVertically,
                          horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                          Text(
                            text = timeFormatted,
                            color = Color.White,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 11.sp
                          )
                          Text(
                            text = "• $relLabel",
                            color = if (isLatest) EmeraldShieldLight else Slate400,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold
                          )
                          if (isLatest) {
                            Text(
                              text = "ТЕКУЩАЯ",
                              color = CyanLight,
                              fontSize = 9.sp,
                              fontWeight = FontWeight.ExtraBold
                            )
                          } else if (isStart) {
                            Text(
                              text = "СТАРТ",
                              color = EmeraldShieldLight,
                              fontSize = 9.sp,
                              fontWeight = FontWeight.ExtraBold
                            )
                          }
                        }

                        Text(
                          text = point.title,
                          color = Slate200,
                          fontSize = 11.sp,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                    }

                    Column(horizontalAlignment = Alignment.End) {
                      Text(
                        text = if (point.speedKmh >= 1f) "%.1f км/ч".format(point.speedKmh) else "На месте",
                        color = if (point.speedKmh >= 1f) CyanLight else Slate400,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                      )
                      Text(
                        text = "%.4f, %.4f".format(point.latitude, point.longitude),
                        color = Slate400,
                        fontSize = 9.sp
                      )
                    }
                  }
                }
              }
            }
          } else {
            // --- GEOFENCES TAB CONTENT ---
            if (geofences.isEmpty()) {
              Text(
                text = "Нет добавленных зон. Нажмите «+ Зона», чтобы настроить уведомления (Дом, Школа, Секция).",
                color = Slate400,
                fontSize = 11.sp,
                modifier = Modifier.padding(vertical = 6.dp)
              )
            } else {
              LazyColumn(
                modifier = Modifier
                  .fillMaxWidth()
                  .heightIn(max = 150.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
              ) {
                itemsIndexed(geofences) { _, zone ->
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .clip(RoundedCornerShape(10.dp))
                      .background(Color(0xFF121D2F))
                      .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                      val icon = when (zone.iconType) {
                        "school" -> Icons.Default.School
                        "gym" -> Icons.Default.SportsScore
                        else -> Icons.Default.Home
                      }
                      Icon(icon, contentDescription = null, tint = CyanLight, modifier = Modifier.size(15.dp))
                      Text(zone.name, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                      Text("• радиус ${zone.radiusMeters.toInt()}м", color = Slate400, fontSize = 10.sp)
                    }
                    IconButton(
                      onClick = { onDeleteGeofence(zone) },
                      modifier = Modifier.size(24.dp)
                    ) {
                      Icon(Icons.Default.Delete, contentDescription = "Удалить зону", tint = RoseLock, modifier = Modifier.size(14.dp))
                    }
                  }
                }
              }
            }
          }
        }
      }
    }

    // Add Geofence Dialog
    if (isAddZoneDialogVisible) {
      AddGeofenceDialog(
        currentLat = effectiveLat,
        currentLng = effectiveLng,
        onDismiss = { isAddZoneDialogVisible = false },
        onConfirm = { name, lat, lng, radius, iconType ->
          onAddGeofence(name, lat, lng, radius, iconType)
          isAddZoneDialogVisible = false
        }
      )
    }
  }
}

@Composable
private fun HistoryStatTile(
  label: String,
  value: String,
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  accent: Color,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(Color(0xFF121D2F))
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
    Column {
      Text(text = value, color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 11.sp, maxLines = 1)
      Text(text = label, color = Slate400, fontSize = 9.sp, maxLines = 1)
    }
  }
}

private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
  val r = 6371000.0
  val dLat = Math.toRadians(lat2 - lat1)
  val dLon = Math.toRadians(lon2 - lon1)
  val a = sin(dLat / 2) * sin(dLat / 2) +
      cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
      sin(dLon / 2) * sin(dLon / 2)
  val c = 2 * atan2(sqrt(a), sqrt(1 - a))
  return r * c
}

