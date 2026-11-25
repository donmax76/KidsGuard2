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
import androidx.compose.runtime.collectAsState
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
import kotlinx.coroutines.launch

data class AppUsageChartSlice(
  val appId: Long,
  val packageName: String,
  val appName: String,
  val category: String,
  val minutes: Int,
  val limitMinutes: Int,
  val isBlocked: Boolean,
  val sharePercent: Float,
  val lastActivityTimestampMs: Long,
  val latestWindowTitle: String,
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
  childId: Long = 0L,
  onSyncUsage: (() -> Unit)? = null,
  onToggleAppBlock: ((AppControlEntity) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val dao = remember(context) { com.example.data.local.KidsGuardDatabase.getDatabase(context).kidsGuardDao() }
  val scope = androidx.compose.runtime.rememberCoroutineScope()

  // 0 = Donut / Pie Chart, 1 = Comparative Bar Chart
  var chartMode by remember { mutableIntStateOf(0) }
  var selectedSliceIndex by remember { mutableIntStateOf(-1) }

  // Фильтр даты: 0 = Сегодня, 1 = Вчера, 2 = Выбранная дата
  var dateFilterMode by remember { mutableIntStateOf(0) }
  var customSelectedTimeMs by remember { androidx.compose.runtime.mutableLongStateOf(System.currentTimeMillis()) }

  // Фильтр интервала времени внутри выбранной даты (например, с 00:00 по 02:00):
  var filterStartHour by remember { mutableIntStateOf(0) }
  var filterStartMinute by remember { mutableIntStateOf(0) }
  var filterEndHour by remember { mutableIntStateOf(23) }
  var filterEndMinute by remember { mutableIntStateOf(59) }

  // 0 = Все (программы + сайты), 1 = Только сайты браузеров, 2 = Только программы
  var logTypeTab by remember { mutableIntStateOf(0) }

  val isFullDayInterval = filterStartHour == 0 && filterStartMinute == 0 && filterEndHour == 23 && filterEndMinute == 59

  val resolvedChildId = remember(childId, apps) {
    if (childId > 0L) childId else (apps.firstOrNull()?.childId ?: 1L)
  }

  val (startDayMs, endDayMs, selectedDateLabel) = remember(
    dateFilterMode,
    customSelectedTimeMs,
    filterStartHour,
    filterStartMinute,
    filterEndHour,
    filterEndMinute
  ) {
    val cal = java.util.Calendar.getInstance()
    when (dateFilterMode) {
      1 -> cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
      2 -> cal.timeInMillis = customSelectedTimeMs
    }
    cal.set(java.util.Calendar.HOUR_OF_DAY, filterStartHour)
    cal.set(java.util.Calendar.MINUTE, filterStartMinute)
    cal.set(java.util.Calendar.SECOND, 0)
    cal.set(java.util.Calendar.MILLISECOND, 0)
    val startMs = cal.timeInMillis

    cal.set(java.util.Calendar.HOUR_OF_DAY, filterEndHour)
    cal.set(java.util.Calendar.MINUTE, filterEndMinute)
    cal.set(java.util.Calendar.SECOND, 59)
    cal.set(java.util.Calendar.MILLISECOND, 999)
    val endMs = cal.timeInMillis.coerceAtLeast(startMs)

    val fmt = java.text.SimpleDateFormat("dd.MM.yyyy", java.util.Locale.getDefault())
    val timeRangeText = "%02d:%02d–%02d:%02d".format(filterStartHour, filterStartMinute, filterEndHour, filterEndMinute)
    val baseLabel = when (dateFilterMode) {
      0 -> "Сегодня (${fmt.format(java.util.Date(startMs))})"
      1 -> "Вчера (${fmt.format(java.util.Date(startMs))})"
      else -> fmt.format(java.util.Date(startMs))
    }
    Triple(startMs, endMs, "$baseLabel • $timeRangeText")
  }

  androidx.compose.runtime.LaunchedEffect(resolvedChildId) {
    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
      runCatching {
        dao.purgeSystemGarbageFromActivityLogs()
        dao.cleanAppIconNoiseInActivityLogs()
        dao.deduplicateOfflineActivityLogs()
        dao.purgeSystemGarbageFromAppControls()
      }
    }
  }

  val historicalPhoneProcs by remember(resolvedChildId, startDayMs, endDayMs) {
    dao.getAggregatedProcsByDateRange(
      childId = resolvedChildId,
      deviceType = "PHONE",
      startTimestamp = startDayMs,
      endTimestamp = endDayMs
    )
  }.collectAsState(initial = emptyList())

  val rawIntervalLogs by remember(resolvedChildId, startDayMs, endDayMs) {
    dao.getActivityLogsByDateRange(
      childId = resolvedChildId,
      deviceType = "PHONE",
      startTimestamp = startDayMs,
      endTimestamp = endDayMs
    )
  }.collectAsState(initial = emptyList())

  val contentRules by remember(resolvedChildId) {
    dao.getAllContentRules()
  }.collectAsState(initial = emptyList())

  val blockedDomainsSet = remember(contentRules) {
    contentRules
      .filter { it.ruleType == "DOMAIN" && it.isBlocked }
      .map { com.example.util.BootPrefs.normalizeDomain(it.value) }
      .filter { it.isNotBlank() }
      .toSet()
  }

  val dateTimeFormatter = remember {
    java.text.SimpleDateFormat("dd.MM.yyyy HH:mm:ss", java.util.Locale.getDefault())
  }

  val procByPkgMap = remember(historicalPhoneProcs) {
    historicalPhoneProcs.associateBy { it.packageName.trim().lowercase() }
  }

  val effectiveAppsForDate: List<AppControlEntity> = remember(
    apps,
    dateFilterMode,
    isFullDayInterval,
    historicalPhoneProcs,
    resolvedChildId
  ) {
    val byPkg = apps.associateBy { it.packageName.trim().lowercase() }
    if (dateFilterMode == 0 && isFullDayInterval) {
      if (apps.any { it.usedMinutesToday > 0 } && historicalPhoneProcs.isEmpty()) {
        apps
      } else {
        val fromProcs = historicalPhoneProcs.mapIndexed { idx, item ->
          val pkgKey = item.packageName.trim().lowercase()
          val existing = byPkg[pkgKey]
          val isWeb = pkgKey.startsWith("web:")
          val webDomain = if (isWeb) com.example.util.BootPrefs.normalizeDomain(pkgKey.removePrefix("web:")) else ""
          val mins = maxOf(
            ((item.totalDurationSeconds + 30L) / 60L).toInt().coerceAtLeast(1),
            existing?.usedMinutesToday ?: 0
          )
          existing?.copy(usedMinutesToday = mins) ?: AppControlEntity(
            id = (idx + 1000).toLong(),
            childId = resolvedChildId,
            packageName = item.packageName,
            appName = item.appName.ifBlank { item.packageName },
            category = if (isWeb) "🌐 Сайт браузера" else "Телефон",
            dailyLimitMinutes = 0,
            usedMinutesToday = mins,
            isBlocked = if (isWeb) (webDomain in blockedDomainsSet) else false,
            iconType = if (isWeb) "web" else "generic"
          )
        }
        val procKeys = fromProcs.map { it.packageName.trim().lowercase() }.toSet()
        val extraTodayApps = apps.filter { it.usedMinutesToday > 0 && it.packageName.trim().lowercase() !in procKeys }
        fromProcs + extraTodayApps
      }
    } else {
      // Вчера, выбранная дата или заданный интервал времени (например, 00:00–02:00) — строго за интервал [startDayMs, endDayMs]
      historicalPhoneProcs.mapIndexed { idx, item ->
        val pkgKey = item.packageName.trim().lowercase()
        val existing = byPkg[pkgKey]
        val isWeb = pkgKey.startsWith("web:")
        val webDomain = if (isWeb) com.example.util.BootPrefs.normalizeDomain(pkgKey.removePrefix("web:")) else ""
        val mins = ((item.totalDurationSeconds + 30L) / 60L).toInt().coerceAtLeast(1)
        existing?.copy(usedMinutesToday = mins) ?: AppControlEntity(
          id = (idx + 2000).toLong(),
          childId = resolvedChildId,
          packageName = item.packageName,
          appName = item.appName.ifBlank { item.packageName },
          category = if (isWeb) "🌐 Сайт браузера" else "Телефон",
          dailyLimitMinutes = 0,
          usedMinutesToday = mins,
          isBlocked = if (isWeb) (webDomain in blockedDomainsSet) else false,
          iconType = if (isWeb) "web" else "generic"
        )
      }
    }
  }

  val slices: List<AppUsageChartSlice> = remember(
    effectiveAppsForDate,
    procByPkgMap,
    blockedDomainsSet,
    logTypeTab
  ) {
    val filteredByType = effectiveAppsForDate.filter { app ->
      val isWeb = app.packageName.startsWith("web:", ignoreCase = true)
      val isNoise = !isWeb && com.example.util.DeviceUsageTracker.isSystemShellOrNoiseEntry(
        app.packageName,
        app.appName
      )
      if (isNoise) return@filter false
      when (logTypeTab) {
        1 -> isWeb
        2 -> !isWeb
        else -> true
      }
    }
    val activeApps = filteredByType
      .filter { it.usedMinutesToday > 0 }
      .sortedWith(
        compareByDescending<AppControlEntity> {
          procByPkgMap[it.packageName.trim().lowercase()]?.lastTimestamp ?: 0L
        }.thenByDescending { it.usedMinutesToday }
      )
    val sourceList = activeApps.take(15)
    val sumMins = sourceList.sumOf { it.usedMinutesToday }.coerceAtLeast(1)
    sourceList.mapIndexed { index, app ->
      val pkgKey = app.packageName.trim().lowercase()
      val procMeta = procByPkgMap[pkgKey]
      val isWeb = pkgKey.startsWith("web:")
      val webDom = if (isWeb) com.example.util.BootPrefs.normalizeDomain(pkgKey.removePrefix("web:")) else ""
      val rawWinTitle = procMeta?.windowTitle.orEmpty()
      val cleanWinTitle = if (isWeb) {
        rawWinTitle
      } else {
        com.example.util.DeviceUsageTracker.sanitizeWindowEventTitle(rawWinTitle, app.appName).orEmpty()
      }
      AppUsageChartSlice(
        appId = app.id,
        packageName = app.packageName,
        appName = app.appName,
        category = if (isWeb) "🌐 Веб-сайт" else app.category,
        minutes = app.usedMinutesToday,
        limitMinutes = app.dailyLimitMinutes,
        isBlocked = if (isWeb) (webDom in blockedDomainsSet) else app.isBlocked,
        sharePercent = (app.usedMinutesToday.toFloat() / sumMins.toFloat()) * 100f,
        lastActivityTimestampMs = procMeta?.lastTimestamp ?: 0L,
        latestWindowTitle = cleanWinTitle,
        color = chartPalette[index % chartPalette.size]
      )
    }
  }

  val totalChartMinutes = remember(slices, totalChildUsedMinutes, dateFilterMode, isFullDayInterval) {
    val sum = slices.sumOf { it.minutes }
    if (dateFilterMode == 0 && isFullDayInterval) {
      if (sum > 0) sum else totalChildUsedMinutes
    } else {
      sum
    }
  }

  val animProgress by animateFloatAsState(
    targetValue = if (slices.isNotEmpty()) 1f else 0f,
    animationSpec = tween(durationMillis = 700, easing = FastOutSlowInEasing),
    label = "ChartAnimProgress"
  )

  fun toggleWebsiteDomainBlock(rawDomain: String) {
    val cleanDomain = com.example.util.BootPrefs.normalizeDomain(rawDomain)
    if (cleanDomain.isBlank() || !cleanDomain.contains(".")) return
    val currentlyBlocked = cleanDomain in blockedDomainsSet
    val nextBlocked = !currentlyBlocked
    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
      val sync = com.example.data.cloud.CloudSyncManager(context)
      if (nextBlocked) {
        val existing = dao.getAllContentRulesOnce()
          .firstOrNull { it.ruleType == "DOMAIN" && com.example.util.BootPrefs.normalizeDomain(it.value) == cleanDomain }
        if (existing == null) {
          dao.insertContentRule(
            com.example.data.model.ContentRuleEntity(
              ruleType = "DOMAIN",
              value = cleanDomain,
              displayName = "🌐 $cleanDomain",
              isBlocked = true
            )
          )
        } else {
          dao.updateContentRule(existing.copy(isBlocked = true))
        }
        sync.sendRemoteCommand(resolvedChildId, "BLOCK_DOMAIN", cleanDomain)
      } else {
        val matches = dao.getAllContentRulesOnce()
          .filter { it.ruleType == "DOMAIN" && com.example.util.BootPrefs.normalizeDomain(it.value) == cleanDomain }
        for (rule in matches) {
          dao.deleteContentRule(rule)
        }
        sync.sendRemoteCommand(resolvedChildId, "UNBLOCK_DOMAIN", cleanDomain)
      }
      sync.syncContentRulesToCloud(dao.getAllContentRulesOnce())
    }
  }

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
            text = "Активность программ и сайтов (Дата + Время)",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.ExtraBold
          )
          Text(
            text = "$selectedDateLabel • Активность: ${totalChartMinutes / 60} ч ${totalChartMinutes % 60} мин",
            color = CyanLight,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
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

      Spacer(modifier = Modifier.height(10.dp))

      // 1. Панель выбора ДАТЫ (Сегодня / Вчера / Выбор даты в календаре)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        val dateModes = listOf(0 to "Сегодня", 1 to "Вчера")
        dateModes.forEach { (modeIdx, title) ->
          val isSelected = dateFilterMode == modeIdx
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(10.dp))
              .background(if (isSelected) CyanContainer else Color(0xFF131F33))
              .border(1.dp, if (isSelected) CyanLight else Slate800, RoundedCornerShape(10.dp))
              .clickable {
                dateFilterMode = modeIdx
                selectedSliceIndex = -1
              }
              .padding(vertical = 7.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = title,
              color = if (isSelected) CyanLight else Slate200,
              fontSize = 11.sp,
              fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium,
              maxLines = 1
            )
          }
        }

        val isCustomDate = dateFilterMode == 2
        val customBtnText = if (isCustomDate) {
          "📅 " + java.text.SimpleDateFormat("dd.MM.yy", java.util.Locale.getDefault()).format(java.util.Date(customSelectedTimeMs))
        } else {
          "📅 Дата"
        }
        Box(
          modifier = Modifier
            .weight(0.95f)
            .clip(RoundedCornerShape(10.dp))
            .background(if (isCustomDate) CyanContainer else Color(0xFF131F33))
            .border(1.dp, if (isCustomDate) CyanLight else Slate800, RoundedCornerShape(10.dp))
            .clickable {
              val cal = java.util.Calendar.getInstance().apply { timeInMillis = customSelectedTimeMs }
              android.app.DatePickerDialog(
                context,
                { _, year, month, dayOfMonth ->
                  val picked = java.util.Calendar.getInstance().apply {
                    set(java.util.Calendar.YEAR, year)
                    set(java.util.Calendar.MONTH, month)
                    set(java.util.Calendar.DAY_OF_MONTH, dayOfMonth)
                  }
                  customSelectedTimeMs = picked.timeInMillis
                  dateFilterMode = 2
                  selectedSliceIndex = -1
                },
                cal.get(java.util.Calendar.YEAR),
                cal.get(java.util.Calendar.MONTH),
                cal.get(java.util.Calendar.DAY_OF_MONTH)
              ).apply {
                datePicker.maxDate = System.currentTimeMillis()
              }.show()
            }
            .padding(vertical = 7.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = customBtnText,
            color = if (isCustomDate) CyanLight else Slate200,
            fontSize = 11.sp,
            fontWeight = if (isCustomDate) FontWeight.ExtraBold else FontWeight.Medium,
            maxLines = 1
          )
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // 2. Панель выбора ИНТЕРВАЛА ВРЕМЕНИ (например, 00:00–02:00 или точный выбор часов/минут С ЧЧ:ММ по ЧЧ:ММ)
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(RoundedCornerShape(12.dp))
          .background(Color(0xFF111C2E))
          .border(1.dp, Slate800, RoundedCornerShape(12.dp))
          .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "🕒 Интервал:",
            color = Slate200,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
          )
          // Кнопка выбора начала интервала (С ЧЧ:ММ)
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(Color(0xFF16243A))
              .border(1.dp, CyanLight.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
              .clickable {
                android.app.TimePickerDialog(
                  context,
                  { _, hourOfDay, minute ->
                    filterStartHour = hourOfDay
                    filterStartMinute = minute
                    selectedSliceIndex = -1
                  },
                  filterStartHour,
                  filterStartMinute,
                  true
                ).show()
              }
              .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "С %02d:%02d".format(filterStartHour, filterStartMinute),
              color = CyanLight,
              fontSize = 11.sp,
              fontWeight = FontWeight.ExtraBold
            )
          }
          // Кнопка выбора конца интервала (По ЧЧ:ММ)
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(Color(0xFF16243A))
              .border(1.dp, CyanLight.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
              .clickable {
                android.app.TimePickerDialog(
                  context,
                  { _, hourOfDay, minute ->
                    filterEndHour = hourOfDay
                    filterEndMinute = minute
                    selectedSliceIndex = -1
                  },
                  filterEndHour,
                  filterEndMinute,
                  true
                ).show()
              }
              .padding(vertical = 5.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "По %02d:%02d".format(filterEndHour, filterEndMinute),
              color = CyanLight,
              fontSize = 11.sp,
              fontWeight = FontWeight.ExtraBold
            )
          }
        }

        // Быстрые пресеты времени (Весь день / 00:00–02:00 / 02:00–06:00 / 06:00–12:00 / 12:00–18:00 / 18:00–24:00)
        val presets = listOf(
          Triple("Весь день", Pair(0, 0), Pair(23, 59)),
          Triple("00:00–02:00", Pair(0, 0), Pair(2, 0)),
          Triple("02:00–06:00", Pair(2, 0), Pair(6, 0)),
          Triple("06:00–12:00", Pair(6, 0), Pair(12, 0)),
          Triple("12:00–18:00", Pair(12, 0), Pair(18, 0)),
          Triple("18:00–24:00", Pair(18, 0), Pair(23, 59))
        )
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          presets.forEach { (label, startHm, endHm) ->
            val isPresetActive = filterStartHour == startHm.first &&
              filterStartMinute == startHm.second &&
              filterEndHour == endHm.first &&
              filterEndMinute == endHm.second
            Box(
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(7.dp))
                .background(if (isPresetActive) CyanContainer else Color(0xFF16243A))
                .border(1.dp, if (isPresetActive) CyanLight else Slate800, RoundedCornerShape(7.dp))
                .clickable {
                  filterStartHour = startHm.first
                  filterStartMinute = startHm.second
                  filterEndHour = endHm.first
                  filterEndMinute = endHm.second
                  selectedSliceIndex = -1
                }
                .padding(vertical = 4.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = label,
                color = if (isPresetActive) CyanLight else Slate400,
                fontSize = 9.sp,
                fontWeight = if (isPresetActive) FontWeight.ExtraBold else FontWeight.Medium,
                maxLines = 1
              )
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      // 3. Фильтр типа записей: Все / 🌐 Только сайты (браузеры) / 📱 Только программы
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
      ) {
        val tabs = listOf(
          0 to "Все (${effectiveAppsForDate.size})",
          1 to "🌐 Сайты (${effectiveAppsForDate.count { it.packageName.startsWith("web:", ignoreCase = true) }})",
          2 to "📱 Программы (${effectiveAppsForDate.count { !it.packageName.startsWith("web:", ignoreCase = true) }})"
        )
        tabs.forEach { (idx, title) ->
          val isSel = logTypeTab == idx
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(9.dp))
              .background(if (isSel) CyanContainer else Color(0xFF131F33))
              .border(1.dp, if (isSel) CyanLight else Slate800, RoundedCornerShape(9.dp))
              .clickable {
                logTypeTab = idx
                selectedSliceIndex = -1
              }
              .padding(vertical = 6.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = title,
              color = if (isSel) CyanLight else Slate200,
              fontSize = 10.sp,
              fontWeight = if (isSel) FontWeight.ExtraBold else FontWeight.Medium,
              maxLines = 1
            )
          }
        }
      }

      Spacer(modifier = Modifier.height(12.dp))

      if (slices.isEmpty() && rawIntervalLogs.isEmpty()) {
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
              text = "За выбранный период ($selectedDateLabel) нет записей активности",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
            Text(
              text = "Выберите другой часовой интервал (например, «Весь день») или нажмите кнопку обновления.",
              color = Slate400,
              fontSize = 11.sp,
              lineHeight = 15.sp,
              textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
          }
        }
      } else {
        if (chartMode == 0 && slices.isNotEmpty()) {
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
                    text = "%.0f%%".format(activeSlice.sharePercent),
                    color = Slate400,
                    fontSize = 9.sp
                  )
                } else {
                  Text(
                    text = "%02d:%02d–%02d:%02d".format(filterStartHour, filterStartMinute, filterEndHour, filterEndMinute),
                    color = Slate400,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold
                  )
                  Text(
                    text = "${totalChartMinutes / 60}ч ${totalChartMinutes % 60}м",
                    color = Color.White,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.ExtraBold
                  )
                  Text(
                    text = "${slices.size} поз.",
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

        // --- DETAILED COMPARATIVE BARS PER APP / WEBSITE WITH FULL DATETIME & 1-CLICK BLOCK/UNBLOCK ---
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          slices.forEachIndexed { index, slice ->
            val isSelected = selectedSliceIndex == index
            val matchedApp = apps.find {
              it.id == slice.appId || it.packageName.equals(slice.packageName, ignoreCase = true)
            }
            val isWebsiteEntry = slice.packageName.startsWith("web:", ignoreCase = true)
            val webDomain = if (isWebsiteEntry) {
              com.example.util.BootPrefs.normalizeDomain(slice.packageName.removePrefix("web:"))
            } else ""

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
                  Column(modifier = Modifier.weight(1f)) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
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
                        fontSize = 10.sp,
                        maxLines = 1
                      )
                    }
                    // Точная дата и время последней активности (DateTime: dd.MM.yyyy HH:mm:ss)
                    val lastDtText = if (slice.lastActivityTimestampMs > 0L) {
                      "Последняя активность: ${dateTimeFormatter.format(java.util.Date(slice.lastActivityTimestampMs))}"
                    } else {
                      "Активность за выбранный период: $selectedDateLabel"
                    }
                    Text(
                      text = lastDtText,
                      color = CyanLight.copy(alpha = 0.9f),
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Medium,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    if (slice.latestWindowTitle.isNotBlank() && !slice.latestWindowTitle.equals(slice.appName, ignoreCase = true)) {
                      Text(
                        text = slice.latestWindowTitle,
                        color = Slate400,
                        fontSize = 9.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                      )
                    }
                  }
                }

                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                  Text(
                    text = "${slice.minutes} мин",
                    color = slice.color,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold
                  )

                  if (isWebsiteEntry && webDomain.isNotBlank()) {
                    val isWebBlocked = webDomain in blockedDomainsSet
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isWebBlocked) RoseLock.copy(alpha = 0.2f) else EmeraldShield.copy(alpha = 0.18f))
                        .border(1.dp, if (isWebBlocked) RoseLock else EmeraldShield, RoundedCornerShape(6.dp))
                        .clickable { toggleWebsiteDomainBlock(webDomain) }
                        .padding(horizontal = 7.dp, vertical = 3.dp)
                    ) {
                      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(
                          imageVector = if (isWebBlocked) Icons.Default.Block else Icons.Default.CheckCircle,
                          contentDescription = null,
                          tint = if (isWebBlocked) RoseLock else EmeraldShieldLight,
                          modifier = Modifier.size(11.dp)
                        )
                        Text(
                          text = if (isWebBlocked) "Заблок." else "Заблокировать",
                          color = if (isWebBlocked) RoseLock else EmeraldShieldLight,
                          fontSize = 9.sp,
                          fontWeight = FontWeight.Bold
                        )
                      }
                    }
                  } else if (onToggleAppBlock != null && matchedApp != null) {
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

        // --- ХРОНОЛОГИЧЕСКИЙ ЛОГ ПОСЕЩЁННЫХ САЙТОВ БРАУЗЕРА С ТОЧНОЙ ДАТОЙ-ВРЕМЕНЕМ (DateTime) ЗА ВЫБРАННЫЙ ИНТЕРВАЛ ---
        val websiteChronologyLogs = remember(rawIntervalLogs) {
          val seenBuckets = HashSet<String>()
          rawIntervalLogs
            .filter { log ->
              if (!log.packageName.startsWith("web:", ignoreCase = true)) return@filter false
              val dom = com.example.util.BootPrefs.normalizeDomain(log.packageName.removePrefix("web:"))
              dom.isNotBlank() && dom.contains(".")
            }
            .filter { log ->
              val dom = com.example.util.BootPrefs.normalizeDomain(log.packageName.removePrefix("web:"))
              val bucketKey = "$dom|${log.timestamp / 2000L}"
              seenBuckets.add(bucketKey)
            }
            .take(30)
        }

        if (logTypeTab != 2) {
          Spacer(modifier = Modifier.height(12.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "🌐 Хронология посещённых сайтов за интервал (${websiteChronologyLogs.size})",
              color = Color.White,
              fontSize = 12.sp,
              fontWeight = FontWeight.ExtraBold,
              modifier = Modifier.weight(1f)
            )
            if (websiteChronologyLogs.isNotEmpty()) {
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(Slate800)
                  .border(1.dp, Slate400.copy(alpha = 0.4f), RoundedCornerShape(6.dp))
                  .clickable {
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                      runCatching {
                        dao.deleteWebsiteHistoryForChild(resolvedChildId)
                        dao.purgeSystemGarbageFromActivityLogs()
                        dao.deduplicateOfflineActivityLogs()
                      }
                    }
                  }
                  .padding(horizontal = 8.dp, vertical = 3.dp)
              ) {
                Text(
                  text = "Очистить",
                  color = Slate200,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold
                )
              }
            }
          }
          Spacer(modifier = Modifier.height(6.dp))

          if (websiteChronologyLogs.isEmpty()) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF0E1726))
                .border(1.dp, Slate800, RoundedCornerShape(10.dp))
                .padding(horizontal = 12.dp, vertical = 10.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = "За выбранный интервал времени посещений сайтов в браузерах не зафиксировано",
                color = Slate400,
                fontSize = 11.sp
              )
            }
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              websiteChronologyLogs.forEach { logItem ->
                val domain = com.example.util.BootPrefs.normalizeDomain(logItem.packageName.removePrefix("web:"))
                val isDomBlocked = domain.isNotBlank() && domain in blockedDomainsSet
                Row(
                  modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF0E1726))
                    .border(1.dp, if (isDomBlocked) RoseLock.copy(alpha = 0.5f) else Slate800, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 7.dp),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = logItem.windowTitle.ifBlank { "🌐 $domain" },
                      color = Color.White,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                    Text(
                      text = "🕒 ${dateTimeFormatter.format(java.util.Date(logItem.timestamp))} • $domain",
                      color = CyanLight,
                      fontSize = 10.sp,
                      fontWeight = FontWeight.Medium
                    )
                  }
                  if (domain.isNotBlank()) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isDomBlocked) RoseLock.copy(alpha = 0.22f) else EmeraldShield.copy(alpha = 0.18f))
                        .border(1.dp, if (isDomBlocked) RoseLock else EmeraldShield, RoundedCornerShape(6.dp))
                        .clickable { toggleWebsiteDomainBlock(domain) }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                      Text(
                        text = if (isDomBlocked) "Разблок." else "Заблокировать",
                        color = if (isDomBlocked) RoseLock else EmeraldShieldLight,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.ExtraBold
                      )
                    }
                  }
                }
              }
            }
          }
        }
      }
    }
  }
}
