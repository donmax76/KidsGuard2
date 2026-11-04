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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
import com.example.data.model.ContentRuleEntity
import com.example.data.model.GeofenceZoneEntity
import com.example.location.LocationState
import com.example.ui.ParentTab
import com.example.ui.components.AddChildDialog
import com.example.ui.components.CommercialKpiBentoGrid
import com.example.ui.components.CommercialQuickActionDock
import com.example.ui.components.HeroBannerCard
import com.example.ui.components.MultiChildSelector
import com.example.ui.components.PairingCodeDialog
import com.example.util.NetworkState
import com.example.util.TrafficConsumptionEstimate
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
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun ParentDashboardScreen(
  children: List<ChildProfileEntity>,
  activeChild: ChildProfileEntity?,
  locationState: LocationState,
  apps: List<AppControlEntity>,
  contentRules: List<ContentRuleEntity>,
  geofences: List<GeofenceZoneEntity>,
  alerts: List<AlertNotificationEntity>,
  onSelectChild: (Long) -> Unit,
  onAddNewChild: (name: String, age: Int, dailyLimit: Int) -> Unit,
  onDeleteChild: (ChildProfileEntity) -> Unit,
  onToggleRemoteLock: () -> Unit,
  onToggleStudyMode: () -> Unit,
  onNavigateTab: (ParentTab) -> Unit,
  onGrantBonusTime: (Int) -> Unit,
  onSetDailyLimit: (Int) -> Unit = {},
  onToggleAppBlocked: (AppControlEntity) -> Unit = {},
  onBatchSetAppsBlocked: (List<AppControlEntity>, Boolean) -> Unit = { _, _ -> },
  onSyncRealApps: () -> Unit = {},
  onTakeScreenshot: (Long) -> Unit = {},
  onSendCloudCommand: (Long, String, String) -> Unit = { _, _, _ -> },
  onSendLoudSignal: () -> Unit = {},
  onTriggerSilentUpdate: (Long) -> Unit = {},
  onToggleMobileData: (Long) -> Unit = {},
  onToggleGpsTracking: (Long) -> Unit = {},
  networkState: NetworkState = NetworkState(),
  trafficEstimates: List<TrafficConsumptionEstimate> = emptyList(),
  isRemoteUpdating: Boolean = false,
  isSirenActive: Boolean = false,
  modifier: Modifier = Modifier
) {
  var selectedPairingChild by remember { mutableStateOf<ChildProfileEntity?>(null) }
  var isAddChildDialogVisible by remember { mutableStateOf(false) }
  var isTrafficDialogVisible by remember { mutableStateOf(false) }
  var isScreenshotsViewerVisible by remember { mutableStateOf(false) }
  var isLiveStreamVisible by remember { mutableStateOf(false) }

  val currentChild = activeChild ?: children.firstOrNull()
  val hasChildren = children.isNotEmpty()
  val childName = currentChild?.name ?: "Добавьте ребенка"
  val isLocked = currentChild?.isDeviceLocked ?: false
  val studyMode = currentChild?.studyModeActive ?: false
  val dailyLimit = currentChild?.dailyScreenLimitMinutes ?: 150
  val usedMinutes = currentChild?.usedScreenTimeMinutes ?: 0
  val remainingMinutes = (dailyLimit - usedMinutes).coerceAtLeast(0)
  val blockedAppsCount = apps.count { it.isBlocked }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    // Hero Banner
    item {
      Spacer(modifier = Modifier.height(4.dp))
      HeroBannerCard(
        childName = if (hasChildren) "$childName (${currentChild?.age ?: 10} лет)" else "Семейный контроль активен",
        isLocked = isLocked,
        batteryPercent = currentChild?.batteryLevel ?: -1
      )
    }

    // MULTI-CHILD SELECTOR ROW
    item {
      MultiChildSelector(
        children = children,
        activeChildId = currentChild?.id,
        onSelectChild = onSelectChild,
        onShowPairingCode = { selectedPairingChild = it },
        onDeleteChild = onDeleteChild,
        onAddChildClick = { isAddChildDialogVisible = true }
      )
    }

    // COMMERCIAL 4-TILE QUICK ACTION DOCK (1-TAP ERGONOMIC PARENT CONTROLS)
    item {
      CommercialQuickActionDock(
        isLocked = isLocked,
        onOpenLiveScreen = { isLiveStreamVisible = true },
        onOpenMap = { onNavigateTab(ParentTab.LOCATION_MAP) },
        onToggleLock = onToggleRemoteLock,
        onLoudSignal = onSendLoudSignal,
        isSirenActive = isSirenActive
      )
    }

    // COMMERCIAL BENTO KPI SUMMARY GRID
    item {
      val effectiveAddr = currentChild?.lastLocationAddress?.takeIf {
        it.isNotBlank() && !com.example.location.LiveLocationTracker.isStaleOrDemoAddress(it)
      } ?: "Ожидание GPS от телефона ребёнка..."
      CommercialKpiBentoGrid(
        usedMinutes = usedMinutes,
        dailyLimit = dailyLimit,
        blockedAppsCount = blockedAppsCount,
        totalAppsCount = apps.size,
        locationAddress = effectiveAddr,
        batteryPercent = currentChild?.batteryLevel ?: -1,
        onScreenTimeClick = { onNavigateTab(ParentTab.SCREEN_TIME) },
        onAppsClick = { onNavigateTab(ParentTab.APPS) },
        onMapClick = { onNavigateTab(ParentTab.LOCATION_MAP) }
      )
    }

    // WINDOWS PC REMOTE CONTROL CARD (HOSTS BLOCKER V5 INTEGRATION + LIVE DESKTOP STREAM)
    item {
      val pcState by com.example.data.pc.PcBlockerClient.state.collectAsState()
      val enabledRules = pcState.entries.count { it.enabled }
      // Живой кадр с ПК (поток включается только пока дашборд на экране и приложение активно).
      val pcLiveFrame by com.example.data.pc.PcBlockerClient.liveFrame.collectAsState()
      var pcLiveBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
      androidx.compose.runtime.LaunchedEffect(pcState.screenBase64) {
        val raw = pcState.screenBase64
        pcLiveBitmap = if (raw.isBlank()) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
          try {
            val bytes = android.util.Base64.decode(raw, android.util.Base64.DEFAULT)
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
          } catch (_: Exception) {
            null
          }
        }
      }
      var isDashboardResumed by remember { mutableStateOf(true) }
      val dashboardLifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
      androidx.compose.runtime.DisposableEffect(dashboardLifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
          when (event) {
            androidx.lifecycle.Lifecycle.Event.ON_RESUME -> isDashboardResumed = true
            androidx.lifecycle.Lifecycle.Event.ON_PAUSE,
            androidx.lifecycle.Lifecycle.Event.ON_STOP -> isDashboardResumed = false
            else -> {}
          }
        }
        dashboardLifecycleOwner.lifecycle.addObserver(observer)
        onDispose { dashboardLifecycleOwner.lifecycle.removeObserver(observer) }
      }
      androidx.compose.runtime.LaunchedEffect(isDashboardResumed, pcState.pairingCode) {
        if (isDashboardResumed) {
          // 2 к/с и «Эконом» — превью на главном экране не должно сажать батарею.
          com.example.data.pc.PcBlockerClient.startScreenStream(
            fps = 2,
            quality = com.example.data.pc.PcStreamQuality.ECONOMY
          )
        } else {
          com.example.data.pc.PcBlockerClient.stopScreenStream()
        }
      }
      androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose { com.example.data.pc.PcBlockerClient.stopScreenStream() }
      }
      val activePcWin = pcState.activeWindows.firstOrNull()
      val dashboardPcBitmap = pcLiveFrame?.bitmap ?: pcLiveBitmap

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.5.dp,
          if (pcState.fullLock.blocked) RoseLock else if (pcState.isOnline) EmeraldShield else CyanLight.copy(alpha = 0.65f)
        ),
        modifier = Modifier
          .fillMaxWidth()
          .clickable { onNavigateTab(ParentTab.PC_CONTROL) }
          .testTag("dashboard_pc_control_card")
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                  .size(42.dp)
                  .clip(CircleShape)
                  .background(if (pcState.fullLock.blocked) RoseContainer else CyanContainer),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Computer,
                  contentDescription = null,
                  tint = if (pcState.fullLock.blocked) RoseLock else CyanLight,
                  modifier = Modifier.size(22.dp)
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "💻 Компьютер ребёнка (Windows ПК)",
                  color = Color.White,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "${pcState.transportLabel} • Активно правил: $enabledRules из ${pcState.entries.size}",
                  color = if (pcState.isOnline) EmeraldShieldLight else Slate400,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }

          // Live PC Desktop Screen Preview or Static Window Bar directly on Main Dashboard
          if (dashboardPcBitmap != null) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .clickable { onNavigateTab(ParentTab.PC_CONTROL) }
            ) {
              androidx.compose.foundation.Image(
                bitmap = dashboardPcBitmap.asImageBitmap(),
                contentDescription = "Живой экран ПК ребёнка",
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
              )
              Box(
                modifier = Modifier
                  .align(Alignment.TopStart)
                  .padding(8.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(Color(0xCC0F172A))
                  .padding(horizontal = 8.dp, vertical = 4.dp)
              ) {
                Text(
                  text = if (pcLiveFrame != null) "🟢 ЖИВОЙ ЭКРАН ПК • 2 к/с" else "🟢 ПОСЛЕДНИЙ КАДР ЭКРАНА ПК",
                  color = EmeraldShieldLight,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1
                )
              }
              Box(
                modifier = Modifier
                  .align(Alignment.BottomStart)
                  .fillMaxWidth()
                  .height(28.dp)
                  .background(Color(0xCC090D16))
                  .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart
              ) {
                Text(
                  text = if (activePcWin != null && activePcWin.title.isNotBlank()) {
                    "Окно: ${activePcWin.title} (${activePcWin.proc})"
                  } else {
                    "Экран рабочего стола активен"
                  },
                  color = Color.White,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          } else {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Slate800)
                .padding(horizontal = 12.dp),
              contentAlignment = Alignment.CenterStart
            ) {
              Text(
                text = if (activePcWin != null && activePcWin.title.isNotBlank()) {
                  "🖥️ Открыто на ПК: ${activePcWin.title} (${activePcWin.proc})"
                } else {
                  "🖥️ Статус ПК: ожидание активного окна hosts_blocker.py..."
                },
                color = if (activePcWin != null && activePcWin.title.isNotBlank()) Color.White else Slate400,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Button(
              onClick = {
                com.example.data.pc.PcBlockerClient.requestPcScreenshot()
                onNavigateTab(ParentTab.PC_CONTROL)
              },
              colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1.2f)
                .height(42.dp)
                .testTag("dashboard_open_pc_control_button")
            ) {
              Icon(Icons.Default.Computer, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text("Пульт и Экран ПК", fontWeight = FontWeight.ExtraBold, fontSize = 12.sp, maxLines = 1)
            }

            OutlinedButton(
              onClick = {
                val acct = pcState.fullLock.childAccount.ifBlank { "User" }
                com.example.data.pc.PcBlockerClient.toggleFullPcLock(!pcState.fullLock.now, acct)
              },
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (pcState.fullLock.now) EmeraldShield else RoseLock
              ),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .testTag("dashboard_quick_lock_pc_button")
            ) {
              Icon(
                imageVector = if (pcState.fullLock.now) Icons.Default.LockOpen else Icons.Default.Lock,
                contentDescription = null,
                tint = if (pcState.fullLock.now) EmeraldShieldLight else RoseLock,
                modifier = Modifier.size(15.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = if (pcState.fullLock.now) "Разблок. ПК" else "Блок ПК",
                color = if (pcState.fullLock.now) EmeraldShieldLight else RoseLock,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
              )
            }
          }
        }
      }
    }

    // RECHARTS 24-HOUR ACTIVITY CHART & OFFLINE STORE-AND-FORWARD HISTORY (ROOM + SQLITE)
    item {
      val context = androidx.compose.ui.platform.LocalContext.current
      val pcState by com.example.data.pc.PcBlockerClient.state.collectAsState()
      val syncStats by com.example.data.cloud.OfflineStoreAndForwardManager.syncStats.collectAsState()
      val dao = remember(context) {
        com.example.data.local.KidsGuardDatabase.getDatabase(context).kidsGuardDao()
      }
      val allOfflineLogs by dao.getAllOfflineActivityLogs().collectAsState(initial = emptyList())
      val todayIso = remember {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
      }
      val childOfflineLogs = remember(allOfflineLogs, currentChild?.id, todayIso) {
        val cid = currentChild?.id ?: 0L
        val forChild = if (cid > 0L) allOfflineLogs.filter { it.childId == cid } else allOfflineLogs
        // Strict Per-Day Isolation: only show today's logs in the Today 24h chart
        forChild.filter { it.dateIso == todayIso }
      }
      val todayPcOfflineHistory = remember(pcState.offlineHistory, todayIso) {
        pcState.offlineHistory.filter { it.day == todayIso }
      }
      val lastSyncTimeFormatted = remember(syncStats.lastSyncTimestampMs) {
        if (syncStats.lastSyncTimestampMs > 0L) {
          java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(syncStats.lastSyncTimestampMs))
        } else null
      }
      val totalSyncedCount = syncStats.totalSyncedLogsOnParent + syncStats.totalSyncedLocationsOnParent + syncStats.totalSyncedScreenshotsOnParent

      com.example.ui.components.HourlyActivityRechartsCard(
        childName = if (hasChildren) childName else "Устройства",
        phoneApps = apps,
        offlineActivityLogs = childOfflineLogs,
        pcHourlyMinutes = pcState.hourlyUsageMinutes,
        pcOfflineHistory = todayPcOfflineHistory,
        syncedOfflineEventsCount = totalSyncedCount,
        lastOfflineSyncTime = lastSyncTimeFormatted,
        onRequestOfflineSyncNow = {
          val cid = currentChild?.id ?: 0L
          if (cid > 0L) {
            com.example.data.cloud.OfflineStoreAndForwardManager.requestImmediateOfflineSyncFromChild(cid)
            onSendCloudCommand(cid, "REQUEST_OFFLINE_SYNC", "")
          }
          com.example.data.pc.PcBlockerClient.requestSync()
          com.example.data.pc.PcBlockerClient.requestPcScreenshot()
        }
      )
    }

    // DIRECT P2P SOCKET & WEBSOCKET TRANSPORT CARD (БЕЗ ЗАВИСИМОСТИ ОТ FIREBASE)
    item {
      val p2pStatus by com.example.data.cloud.DirectP2pSocketEngine.status.collectAsState()
      var manualChildIp by androidx.compose.runtime.remember(p2pStatus.childLanIp) {
        androidx.compose.runtime.mutableStateOf(p2pStatus.childLanIp)
      }
      var showIpInput by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
      val hasChildTelemetry = (currentChild?.batteryLevel ?: -1) in 1..100

      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.5.dp,
          if (p2pStatus.isLanSocketConnected) EmeraldShield else CyanLight.copy(alpha = 0.55f)
        ),
        modifier = Modifier
          .fillMaxWidth()
          .height(if (showIpInput) 216.dp else 156.dp)
          .testTag("direct_p2p_socket_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp),
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
                  .background(if (p2pStatus.isLanSocketConnected) EmeraldContainer else CyanContainer),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.Wifi,
                  contentDescription = null,
                  tint = if (p2pStatus.isLanSocketConnected) EmeraldShieldLight else CyanLight,
                  modifier = Modifier.size(20.dp)
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Прямой P2P-Сокет • Без Firebase",
                  color = Color.White,
                  fontSize = 14.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (p2pStatus.isLanSocketConnected) "Локальный LAN P2P активен" else "Защищенный TCP/P2P канал активен",
                  color = if (p2pStatus.isLanSocketConnected) EmeraldShieldLight else CyanLight,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Box(
              modifier = Modifier
                .width(88.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Slate800)
                .clickable { showIpInput = !showIpInput },
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = if (showIpInput) "Скрыть IP" else "По IP (LAN)",
                color = CyanLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(72.dp)
              .clip(RoundedCornerShape(10.dp))
              .background(Slate950)
              .padding(horizontal = 10.dp, vertical = 8.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            Text(
              text = if (hasChildTelemetry) {
                "✅ Прямой обмен данными с телефоном «$childName» активен (порт TCP ${p2pStatus.tcpPort}). Статический канал связи работает стабильно без скачков."
              } else {
                "⚠️ Ожидание телеметрии от телефона «$childName» по прямому P2P-сокету (v${com.example.BuildConfig.VERSION_NAME}). Статический буфер связи готов."
              },
              color = if (hasChildTelemetry) EmeraldShieldLight else AmberWarning,
              fontSize = 11.sp,
              lineHeight = 15.sp,
              maxLines = 3,
              overflow = TextOverflow.Ellipsis
            )
          }

          if (showIpInput) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(50.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              androidx.compose.material3.OutlinedTextField(
                value = manualChildIp,
                onValueChange = { manualChildIp = it },
                placeholder = { Text("IP ребёнка (192.168.1.45)", fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                singleLine = true,
                modifier = Modifier
                  .weight(1f)
                  .height(50.dp)
              )
              Button(
                onClick = {
                  com.example.data.cloud.DirectP2pSocketEngine.connectToChildLanIp(manualChildIp)
                },
                colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .width(108.dp)
                  .height(44.dp)
              ) {
                Text("Подключить", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
              }
            }
          }
        }
      }
    }

    // NETWORK MODE & MOBILE TRAFFIC CONTROL (ВКЛ/ОТКЛ WI-FI И МОБИЛЬНЫЙ ТРАФИК)
    item {
      val allowMobile = currentChild?.allowMobileData ?: true
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.5.dp,
          if (allowMobile) CyanLight.copy(alpha = 0.5f) else AmberWarning.copy(alpha = 0.6f)
        ),
        modifier = Modifier
          .fillMaxWidth()
          .height(362.dp)
          .testTag("network_mode_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp),
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
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(if (allowMobile) CyanContainer else AmberWarning.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = if (allowMobile) Icons.Default.SignalCellularAlt else Icons.Default.Wifi,
                  contentDescription = null,
                  tint = if (allowMobile) CyanLight else AmberWarning,
                  modifier = Modifier.size(24.dp)
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Сетевой режим: $childName",
                  color = Color.White,
                  fontSize = 16.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (allowMobile) "🟢 Wi-Fi + Мобильный интернет" else "🟡 Только Wi-Fi (экономия 0 МБ)",
                  color = if (allowMobile) EmeraldShieldLight else AmberWarning,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            Switch(
              checked = allowMobile,
              onCheckedChange = {
                onToggleMobileData(currentChild?.id ?: 0L)
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = CyanLight,
                uncheckedThumbColor = Slate400,
                uncheckedTrackColor = Slate800
              ),
              modifier = Modifier.testTag("toggle_mobile_network_switch")
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Big prominent direct action buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Button(
              onClick = {
                if (allowMobile) onToggleMobileData(currentChild?.id ?: 0L)
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (!allowMobile) AmberWarning.copy(alpha = 0.25f) else Slate800,
                contentColor = if (!allowMobile) AmberWarning else Slate400
              ),
              shape = RoundedCornerShape(12.dp),
              border = if (!allowMobile) androidx.compose.foundation.BorderStroke(1.dp, AmberWarning) else null,
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .testTag("select_wifi_only_button")
            ) {
              Icon(Icons.Default.Wifi, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Только Wi-Fi (0 МБ)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }

            Button(
              onClick = {
                if (!allowMobile) onToggleMobileData(currentChild?.id ?: 0L)
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (allowMobile) CyanContainer else Slate800,
                contentColor = if (allowMobile) CyanLight else Slate400
              ),
              shape = RoundedCornerShape(12.dp),
              border = if (allowMobile) androidx.compose.foundation.BorderStroke(1.dp, CyanLight) else null,
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .testTag("select_mobile_network_button")
            ) {
              Icon(Icons.Default.SignalCellularAlt, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Wi-Fi + 4G",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(98.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(if (allowMobile) Color(0xFF0F1E2E) else Color(0xFF261908))
              .padding(12.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            Text(
              text = if (allowMobile) {
                "🌐 Мобильный интернет РАЗРЕШЕН: KidsGuard обменивается координатами и сообщениями в пути. Расход сотового тарифа всего ~18–35 КБ в час (менее 1 МБ за сутки)."
              } else {
                "💡 Режим «ТОЛЬКО WI-FI» АКТИВЕН: Мобильный интернет для KidsGuard отключен, трафик тарифа расходуется строго 0 КБ! Связь работает при подключении к домашнему или школьному Wi-Fi. Если предстоит выход на улицу, включите мобильный интернет заранее в зоне Wi-Fi!"
              },
              color = Slate200,
              fontSize = 12.sp,
              lineHeight = 16.sp,
              maxLines = 5,
              overflow = TextOverflow.Ellipsis
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          // 3 Stat tiles for traffic consumption per hour
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Card(
              colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2D)),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .height(68.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
              ) {
                Text("В покое / Школа", fontSize = 10.sp, color = Slate400, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text("8–14 КБ/ч", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = EmeraldShieldLight, maxLines = 1)
                Text("~0.25 МБ/день", fontSize = 9.sp, color = Slate400, maxLines = 1)
              }
            }

            Card(
              colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2D)),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .height(68.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
              ) {
                Text("На улице (GPS)", fontSize = 10.sp, color = Slate400, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text("18–35 КБ/ч", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = CyanLight, maxLines = 1)
                Text("~0.55 МБ/день", fontSize = 9.sp, color = Slate400, maxLines = 1)
              }
            }

            Card(
              colors = CardDefaults.cardColors(containerColor = Color(0xFF131D2D)),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier
                .weight(1f)
                .height(68.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(horizontal = 6.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
              ) {
                Text("Только Wi-Fi", fontSize = 10.sp, color = Slate400, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(modifier = Modifier.height(2.dp))
                Text("0 КБ/ч", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = AmberWarning, maxLines = 1)
                Text("0 МБ сотовой", fontSize = 9.sp, color = Slate400, maxLines = 1)
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          OutlinedButton(
            onClick = { isTrafficDialogVisible = true },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(40.dp)
              .testTag("open_traffic_details_button")
          ) {
            Icon(Icons.Default.Info, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyanLight)
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = "Детальный расчет расхода по протоколам",
              fontSize = 12.sp,
              color = CyanLight,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    // GPS NAVIGATION & SATELLITE TRACKING TOGGLE (ВКЛ/ВЫКЛ GPS ДЛЯ ЭКОНОМИИ БАТАРЕИ)
    item {
      val gpsEnabled = currentChild?.gpsTrackingEnabled ?: true
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.5.dp,
          if (gpsEnabled) EmeraldShield.copy(alpha = 0.55f) else AmberWarning.copy(alpha = 0.65f)
        ),
        modifier = Modifier
          .fillMaxWidth()
          .height(232.dp)
          .testTag("gps_tracking_battery_card")
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp),
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
                  .size(44.dp)
                  .clip(CircleShape)
                  .background(if (gpsEnabled) EmeraldContainer else AmberWarning.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  imageVector = Icons.Default.LocationOn,
                  contentDescription = null,
                  tint = if (gpsEnabled) EmeraldShieldLight else AmberWarning,
                  modifier = Modifier.size(24.dp)
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "GPS-навигация и трекинг",
                  color = Color.White,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (gpsEnabled) "🟢 Включено (Умный эко-опрос)" else "🌙 Выключено (Спутники в спящем режиме)",
                  color = if (gpsEnabled) EmeraldShieldLight else AmberWarning,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.SemiBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Switch(
              checked = gpsEnabled,
              onCheckedChange = {
                onToggleGpsTracking(currentChild?.id ?: 0L)
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = EmeraldShield,
                uncheckedThumbColor = Slate400,
                uncheckedTrackColor = Slate800
              ),
              modifier = Modifier.testTag("toggle_gps_tracking_switch")
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            Button(
              onClick = {
                if (!gpsEnabled) onToggleGpsTracking(currentChild?.id ?: 0L)
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (gpsEnabled) EmeraldContainer else Slate800,
                contentColor = if (gpsEnabled) EmeraldShieldLight else Slate400
              ),
              shape = RoundedCornerShape(12.dp),
              border = if (gpsEnabled) androidx.compose.foundation.BorderStroke(1.dp, EmeraldShield) else null,
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .testTag("enable_gps_tracking_button")
            ) {
              Icon(Icons.Default.LocationOn, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Включить GPS",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }

            Button(
              onClick = {
                if (gpsEnabled) onToggleGpsTracking(currentChild?.id ?: 0L)
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (!gpsEnabled) AmberWarning.copy(alpha = 0.25f) else Slate800,
                contentColor = if (!gpsEnabled) AmberWarning else Slate400
              ),
              shape = RoundedCornerShape(12.dp),
              border = if (!gpsEnabled) androidx.compose.foundation.BorderStroke(1.dp, AmberWarning) else null,
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
              modifier = Modifier
                .weight(1f)
                .height(42.dp)
                .testTag("disable_gps_tracking_button")
            ) {
              Icon(Icons.Default.Bedtime, contentDescription = null, modifier = Modifier.size(15.dp))
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Выключить GPS (Эко)",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(86.dp)
              .clip(RoundedCornerShape(12.dp))
              .background(if (gpsEnabled) Color(0xFF0D231E) else Color(0xFF261908))
              .padding(12.dp),
            contentAlignment = Alignment.CenterStart
          ) {
            Text(
              text = if (gpsEnabled) {
                "🔋 Умная защита аккумулятора активна: координаты запрашиваются только при реальном перемещении ребёнка (смещение ≥ 15 м), а в покое (дома или на уроке) GPS-приёмник переходит в режим сна и не сажает батарею."
              } else {
                "🛑 Навигация и спутниковый трекинг ОТКЛЮЧЕНЫ родителем: телефон ребёнка полностью прекратил опрос спутников GPS. Расход батареи на геолокацию равен 0%. Вы можете включить GPS обратно в любой момент одним нажатием."
              },
              color = Slate200,
              fontSize = 11.sp,
              lineHeight = 15.sp,
              maxLines = 4,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    // ACTIVE APP LIVE MONITORING, SCREEN STREAM & SCREENSHOTS
    item {
      ActiveAppMonitoringCard(
        child = currentChild,
        apps = apps,
        onTakeScreenshot = {
          currentChild?.let { onTakeScreenshot(it.id) }
        },
        onOpenLiveStream = {
          isLiveStreamVisible = true
        },
        onViewScreenshots = {
          isScreenshotsViewerVisible = true
        }
      )
    }

    // 1. MASTER REMOTE LOCK BUTTON
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .height(146.dp)
          .testTag("remote_lock_card"),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (isLocked) Color(0xFF2C131A) else Color(0xFF132238)
        ),
        border = androidx.compose.foundation.BorderStroke(
          1.5.dp,
          if (isLocked) RoseLock else CyanLight.copy(alpha = 0.5f)
        )
      ) {
        Column(
          modifier = Modifier
            .fillMaxSize()
            .padding(18.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .height(46.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Box(
              modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(if (isLocked) RoseContainer else CyanContainer),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                imageVector = if (isLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                contentDescription = null,
                tint = if (isLocked) RoseLock else CyanLight,
                modifier = Modifier.size(24.dp)
              )
            }

            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = if (isLocked) "Телефон «$childName» заблокирован" else "Удаленная блокировка телефона",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
              Text(
                text = if (isLocked) "Все приложения и экран телефона выключены" else "Мгновенно приостановите работу телефона ребенка",
                color = Slate400,
                fontSize = 12.sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
              )
            }
          }

          Button(
            onClick = onToggleRemoteLock,
            modifier = Modifier
              .fillMaxWidth()
              .height(48.dp)
              .testTag("toggle_remote_lock_button"),
            colors = ButtonDefaults.buttonColors(
              containerColor = if (isLocked) EmeraldShield else RoseLock,
              contentColor = if (isLocked) Color.Black else Color.White
            ),
            shape = RoundedCornerShape(14.dp)
          ) {
            Icon(
              imageVector = if (isLocked) Icons.Default.LockOpen else Icons.Default.Block,
              contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (isLocked) "РАЗБЛОКИРОВАТЬ ТЕЛЕФОН" else "ЗАБЛОКИРОВАТЬ ТЕЛЕФОН СЕЙЧАС",
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              maxLines = 1,
              softWrap = false,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    // COMMUNICATION WITH CHILD (ЧАТ И ЗВУКОВОЙ СИГНАЛ ВКЛ/ВЫКЛ)
    if (hasChildren && currentChild != null) {
      item {
        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900),
          border = if (isSirenActive) androidx.compose.foundation.BorderStroke(1.5.dp, RoseLock) else null,
          modifier = Modifier
            .fillMaxWidth()
            .height(114.dp)
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(26.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(Icons.Default.Chat, contentDescription = null, tint = CyanLight)
                Text(
                  text = "Связь с «$childName»",
                  color = Color.White,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Text(
                text = if (isSirenActive) "🔊 СИРЕНА АКТИВНА" else "Защищено",
                color = if (isSirenActive) RoseLock else EmeraldShield,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
              )
            }

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
              horizontalArrangement = Arrangement.spacedBy(10.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Button(
                onClick = { onNavigateTab(ParentTab.CHAT) },
                colors = ButtonDefaults.buttonColors(containerColor = CyanContainer, contentColor = CyanLight),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(44.dp)
                  .testTag("open_child_chat_button")
              ) {
                Icon(Icons.Default.Chat, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = "Открыть чат",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }

              Button(
                onClick = onSendLoudSignal,
                colors = ButtonDefaults.buttonColors(
                  containerColor = if (isSirenActive) RoseLock else Color(0xFF3B1E08),
                  contentColor = if (isSirenActive) Color.White else AmberWarning
                ),
                shape = RoundedCornerShape(12.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(44.dp)
                  .testTag("send_siren_button")
              ) {
                Icon(Icons.Default.VolumeUp, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                  text = if (isSirenActive) "Выкл. сирену" else "Сирена ВКЛ",
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }
      }

      // REMOTE SILENT CORE ENGINE UPDATE CARD (ОБОЛОЧКА HOST SHELL + ГОРЯЧЕЕ ОБНОВЛЕНИЕ ЯДРА БЕЗ УСТАНОВКИ APK)
      item {
        val localCtx = androidx.compose.ui.platform.LocalContext.current
        val latestVer = com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(localCtx)
        val rawChildVer = com.example.data.cloud.RealtimeCloudRelay.normalizeAppVersion(currentChild?.appVersion)
        val displayVersion = com.example.util.CoreEngineHotUpdater.resolveHighestChildVersion(
          localCtx,
          currentChild?.id ?: 0L,
          rawChildVer
        )
        val isChildUpToDate = com.example.util.ApkUpdateInstaller.compareVersions(displayVersion, latestVer) >= 0 ||
          com.example.util.ApkUpdateInstaller.isSameVersion(displayVersion, latestVer)
        val isUpdatePendingOnChild = (currentChild?.updatePending == true && !isChildUpToDate) || isRemoteUpdating

        Card(
          shape = RoundedCornerShape(20.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900),
          border = androidx.compose.foundation.BorderStroke(
            1.dp,
            when {
              isUpdatePendingOnChild -> CyanLight.copy(alpha = 0.75f)
              isChildUpToDate -> EmeraldShield.copy(alpha = 0.45f)
              else -> AmberWarning.copy(alpha = 0.65f)
            }
          ),
          modifier = Modifier.fillMaxWidth()
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(44.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(
                  Icons.Default.SystemUpdate,
                  contentDescription = null,
                  tint = when {
                    isUpdatePendingOnChild -> CyanLight
                    isChildUpToDate -> EmeraldShieldLight
                    else -> AmberWarning
                  }
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Оболочка + Ядро (Hot-Swap)",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                  )
                  Text(
                    text = when {
                      isUpdatePendingOnChild -> "⏳ Горячая замена ядра: v$displayVersion → v$latestVer"
                      isChildUpToDate -> "Ядро актуально на обоих телефонах (v$latestVer)"
                      else -> "Доступно обновление ядра: v$displayVersion → v$latestVer"
                    },
                    color = when {
                      isUpdatePendingOnChild -> CyanLight
                      isChildUpToDate -> EmeraldShieldLight
                      else -> AmberWarning
                    },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }

              Spacer(modifier = Modifier.width(8.dp))

              Box(
                modifier = Modifier
                  .width(114.dp)
                  .height(28.dp)
                  .clip(RoundedCornerShape(6.dp))
                  .background(
                    when {
                      isUpdatePendingOnChild -> CyanContainer
                      isChildUpToDate -> EmeraldContainer
                      else -> Color(0xFF3B1E08)
                    }
                  ),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = when {
                    isUpdatePendingOnChild -> "⏳ ЗАМЕНА..."
                    isChildUpToDate -> "✓ АКТУАЛЬНО"
                    else -> "ОБНОВИТЬ ЯДРО"
                  },
                  color = when {
                    isUpdatePendingOnChild -> CyanLight
                    isChildUpToDate -> EmeraldShieldLight
                    else -> AmberWarning
                  },
                  fontSize = 10.sp,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  softWrap = false,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Version comparison badge strip
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Slate950)
                .padding(horizontal = 12.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Column {
                Text("Ядро у ребёнка", color = Slate400, fontSize = 10.sp, maxLines = 1)
                Text(
                  text = "v$displayVersion",
                  color = if (isChildUpToDate) EmeraldShieldLight else AmberWarning,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 14.sp,
                  maxLines = 1
                )
              }
              Text(
                text = when {
                  isUpdatePendingOnChild -> "───► Замена ядра... ───►"
                  isChildUpToDate -> "══ Совпадает ══"
                  else -> "───► Hot-Core ───►"
                },
                color = if (isChildUpToDate) EmeraldShieldLight else CyanLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
              )
              Column(horizontalAlignment = Alignment.End) {
                Text(
                  text = "Версия у родителя",
                  color = Slate400,
                  fontSize = 10.sp,
                  maxLines = 1
                )
                Text(
                  text = "v$latestVer",
                  color = EmeraldShieldLight,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 14.sp,
                  maxLines = 1
                )
              }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Static-height description box so switching states never shifts buttons
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .height(80.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Slate950)
                .padding(horizontal = 10.dp, vertical = 8.dp),
              contentAlignment = Alignment.CenterStart
            ) {
              Text(
                text = when {
                  isChildUpToDate -> "✓ Архитектура 2-в-1 (Оболочка + Ядро): оболочка устанавливается на телефон ребёнка 1 раз, а ядро (v$latestVer, Build ${com.example.BuildConfig.VERSION_CODE}) заменяется внутри памяти приложения без системного окна «Обновить?» и без разрешений."
                  isUpdatePendingOnChild -> "⏳ Идёт горячая замена ядра: телефон ребёнка скачивает модуль ядра v$latestVer в закрытую внутреннюю память и подменяет его на лету через DexClassLoader без вызова установщика Android и без запроса разрешений."
                  else -> "У ребёнка ядро v$displayVersion (у вас — v$latestVer). Нажмите кнопку ниже: оболочка на телефоне ребёнка незаметно скачает только новое ядро и заменит его в памяти без всплывающего окна разрешения на обновление."
                },
                color = Slate200,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                maxLines = 4,
                overflow = TextOverflow.Ellipsis
              )
            }

            Spacer(modifier = Modifier.height(10.dp))

            if (isChildUpToDate) {
              OutlinedButton(
                onClick = { currentChild?.let { onTriggerSilentUpdate(it.id) } },
                enabled = !isRemoteUpdating && currentChild != null,
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.5f)),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(44.dp)
                  .testTag("trigger_silent_update_button")
              ) {
                if (isRemoteUpdating) {
                  CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanLight, strokeWidth = 2.dp)
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Горячая замена ядра v$latestVer у ребёнка...", fontSize = 12.sp, color = CyanLight, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                  Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp), tint = CyanLight)
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Принудительно перезаписать ядро v$latestVer у ребёнка",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = CyanLight,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            } else {
              Button(
                onClick = { currentChild?.let { onTriggerSilentUpdate(it.id) } },
                enabled = !isRemoteUpdating && currentChild != null,
                colors = ButtonDefaults.buttonColors(
                  containerColor = CyanLight,
                  contentColor = Color.Black,
                  disabledContainerColor = Slate800,
                  disabledContentColor = Slate400
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                  .fillMaxWidth()
                  .height(44.dp)
                  .testTag("trigger_silent_update_button")
              ) {
                if (isRemoteUpdating) {
                  CircularProgressIndicator(modifier = Modifier.size(16.dp), color = CyanLight, strokeWidth = 2.dp)
                  Spacer(modifier = Modifier.width(8.dp))
                  Text("Горячая замена ядра v$latestVer у ребёнка...", fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                  Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = if (isUpdatePendingOnChild) {
                      "Повторить замену ядра: v$displayVersion → v$latestVer"
                    } else {
                      "Обновить ядро у ребёнка (без окон): v$displayVersion → v$latestVer"
                    },
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }
            }

            Spacer(modifier = Modifier.height(10.dp))
            val localCtx = androidx.compose.ui.platform.LocalContext.current
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedButton(
                onClick = {
                  com.example.util.ApkUpdateInstaller.downloadAndInstallLatestApk(localCtx)
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(40.dp)
              ) {
                Text(
                  text = "Скачать APK v$latestVer",
                  fontSize = 11.sp,
                  color = CyanLight,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              OutlinedButton(
                onClick = {
                  val clipboard = localCtx.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                  val apkUrl = com.example.util.ApkUpdateInstaller.SHARED_APK_URL
                  clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("KidsGuard APK", apkUrl))
                  android.widget.Toast.makeText(localCtx, "Ссылка на APK скопирована: $apkUrl", android.widget.Toast.LENGTH_LONG).show()
                },
                shape = RoundedCornerShape(10.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(40.dp)
              ) {
                Text(
                  text = "Скопировать ссылку",
                  fontSize = 11.sp,
                  color = Slate200,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedButton(
                onClick = {
                  com.example.util.ApkUpdateInstaller.saveAndShareInstalledApk(localCtx)
                },
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.6f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(40.dp)
              ) {
                Text(
                  text = "📤 Поделиться APK (файл)",
                  fontSize = 11.sp,
                  color = EmeraldShieldLight,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              OutlinedButton(
                onClick = {
                  com.example.util.ApkUpdateInstaller.saveAndShareBundledHostsBlocker(localCtx)
                },
                shape = RoundedCornerShape(10.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.5f)),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                modifier = Modifier
                  .weight(1f)
                  .height(40.dp)
              ) {
                Text(
                  text = "💻 Файл hosts_blocker.py",
                  fontSize = 11.sp,
                  color = CyanLight,
                  fontWeight = FontWeight.Bold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
          }
        }
      }
    }

    // 2. INTERACTIVE SCREEN TIME MANAGEMENT PANEL (Daily Limit Setter + Real-Time Remaining Countdown)
    item {
      var sliderLimitMins by androidx.compose.runtime.remember(dailyLimit) {
        androidx.compose.runtime.mutableFloatStateOf(dailyLimit.coerceIn(30, 360).toFloat())
      }
      val effectiveDailyLimit = sliderLimitMins.toInt().coerceAtLeast(15)
      val liveRemainingMins = (effectiveDailyLimit - usedMinutes).coerceAtLeast(0)
      val liveSecondsCountdown = 0

      Card(
        modifier = Modifier
          .fillMaxWidth()
          .testTag("screen_time_card"),
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.2.dp,
          if (liveRemainingMins <= 15) AmberWarning.copy(alpha = 0.65f) else CyanLight.copy(alpha = 0.45f)
        )
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
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
              Icon(Icons.Default.Timer, contentDescription = null, tint = CyanLight)
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = "Контроль времени: $childName",
                  color = Color.White,
                  fontSize = 15.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = "Ежедневный лимит и остаток LIVE",
                  color = Slate400,
                  fontSize = 11.sp,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(CyanContainer)
                .clickable { onNavigateTab(ParentTab.SCREEN_TIME) }
                .padding(horizontal = 8.dp, vertical = 5.dp)
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                Text("Расписание", color = CyanLight, fontSize = 10.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                Icon(
                  Icons.AutoMirrored.Filled.ArrowForward,
                  contentDescription = null,
                  tint = CyanLight,
                  modifier = Modifier.size(12.dp)
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          // Real-Time Remaining Time Banner
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(14.dp))
              .background(Color(0xFF121E31))
              .padding(horizontal = 14.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                  modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(if (liveRemainingMins > 0 && !isLocked) EmeraldShield else RoseLock)
                )
                Text(
                  text = "ОСТАЛОСЬ ВРЕМЕНИ (LIVE)",
                  color = Slate400,
                  fontSize = 10.sp,
                  fontWeight = FontWeight.ExtraBold,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
              }
              Spacer(modifier = Modifier.height(2.dp))
              Text(
                text = if (liveRemainingMins > 0) {
                  "%02d:%02d:%02d".format(
                    (liveRemainingMins - 1).coerceAtLeast(0) / 60,
                    (liveRemainingMins - 1).coerceAtLeast(0) % 60,
                    if (isLocked) 0 else liveSecondsCountdown
                  )
                } else {
                  "00:00:00 (Исчерпан)"
                },
                color = if (liveRemainingMins <= 15) AmberWarning else EmeraldShieldLight,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 22.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
              )
            }

            Spacer(modifier = Modifier.width(8.dp))

            Column(horizontalAlignment = Alignment.End) {
              Text(
                text = "Потрачено сегодня",
                color = Slate400,
                fontSize = 10.sp,
                maxLines = 1
              )
              Text(
                text = "${usedMinutes / 60} ч ${usedMinutes % 60} мин",
                fontSize = 15.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1
              )
              Text(
                text = "Лимит: ${effectiveDailyLimit / 60} ч ${effectiveDailyLimit % 60} мин",
                color = CyanLight,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          val progress = (usedMinutes.toFloat() / effectiveDailyLimit.coerceAtLeast(1)).coerceIn(0f, 1f)
          LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier
              .fillMaxWidth()
              .height(8.dp)
              .clip(RoundedCornerShape(4.dp)),
            color = if (progress >= 1f) RoseLock else if (progress >= 0.8f) AmberWarning else CyanLight,
            trackColor = Slate800
          )

          Spacer(modifier = Modifier.height(12.dp))

          // Interactive Daily Screen Time Limit Slider + Presets
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Дневной лимит:",
              color = Slate200,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1
            )
            Text(
              text = "${effectiveDailyLimit / 60} ч ${effectiveDailyLimit % 60} мин ($effectiveDailyLimit мин)",
              color = CyanLight,
              fontSize = 12.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1
            )
          }

          androidx.compose.material3.Slider(
            value = sliderLimitMins.coerceIn(30f, 360f),
            onValueChange = { sliderLimitMins = it.coerceIn(30f, 360f) },
            onValueChangeFinished = { onSetDailyLimit(sliderLimitMins.toInt().coerceIn(30, 360)) },
            valueRange = 30f..360f,
            steps = 10,
            colors = androidx.compose.material3.SliderDefaults.colors(
              thumbColor = CyanLight,
              activeTrackColor = CyanLight,
              inactiveTrackColor = Slate800
            ),
            modifier = Modifier.testTag("dashboard_daily_limit_slider")
          )

          // Quick Daily Limit Presets
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf(60 to "1 ч", 90 to "1.5 ч", 120 to "2 ч", 150 to "2.5 ч", 180 to "3 ч").forEach { (mins, label) ->
              val isPresetSelected = effectiveDailyLimit == mins
              OutlinedButton(
                onClick = {
                  sliderLimitMins = mins.toFloat()
                  onSetDailyLimit(mins)
                },
                modifier = Modifier
                  .weight(1f)
                  .height(34.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp),
                shape = RoundedCornerShape(8.dp),
                border = if (isPresetSelected) androidx.compose.foundation.BorderStroke(1.5.dp, CyanLight) else null
              ) {
                Text(
                  text = label,
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = if (isPresetSelected) CyanLight else Slate400,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Bonus Time Buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { onGrantBonusTime(15) },
              modifier = Modifier
                .weight(1f)
                .height(38.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+15 мин", fontSize = 12.sp, color = EmeraldShieldLight, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
              onClick = { onGrantBonusTime(30) },
              modifier = Modifier
                .weight(1f)
                .height(38.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+30 мин", fontSize = 12.sp, color = EmeraldShieldLight, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
            OutlinedButton(
              onClick = { onGrantBonusTime(60) },
              modifier = Modifier
                .weight(1f)
                .height(38.dp),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 4.dp, vertical = 0.dp),
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+1 час", fontSize = 12.sp, color = EmeraldShieldLight, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
            }
          }
        }
      }
    }

    // 2B. INTERACTIVE CHART OF SCREEN TIME DISTRIBUTION BY APPS FOR TODAY
    item {
      com.example.ui.components.AppUsageDistributionChartCard(
        apps = apps,
        totalChildUsedMinutes = usedMinutes,
        onSyncUsage = {
          onSyncRealApps()
          currentChild?.let { onSendCloudCommand(it.id, "QUERY_ACTIVE_APP", "") }
        },
        onToggleAppBlock = onToggleAppBlocked
      )
    }

    // 2C. SELECTABLE APP LIST FOR FORCE BLOCKING / ALLOWING ON CHILD DEVICE
    item {
      com.example.ui.components.AppSelectionForceBlockPanel(
        childName = childName,
        apps = apps,
        onToggleAppBlocked = onToggleAppBlocked,
        onBatchSetBlocked = onBatchSetAppsBlocked,
        onSyncApps = onSyncRealApps,
        onOpenAllAppsScreen = { onNavigateTab(ParentTab.APPS) }
      )
    }

    // 3. STUDY MODE & SLEEP SCHEDULE
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        QuickToggleCard(
          title = "Режим учебы",
          subtitle = if (studyMode) "Игры заблокированы" else "Обычный доступ",
          icon = Icons.Default.School,
          isActive = studyMode,
          activeColor = EmeraldShield,
          onClick = onToggleStudyMode,
          modifier = Modifier.weight(1f)
        )

        QuickToggleCard(
          title = "Ночной сон",
          subtitle = "${currentChild?.bedtimeStartHour ?: 21}:${currentChild?.bedtimeStartMinute ?: 30} - ${currentChild?.bedtimeEndHour ?: 7}:00",
          icon = Icons.Default.Bedtime,
          isActive = currentChild?.bedtimeEnabled ?: true,
          activeColor = Color(0xFF818CF8),
          onClick = { onNavigateTab(ParentTab.SCREEN_TIME) },
          modifier = Modifier.weight(1f)
        )
      }
    }

    // 4. INTERACTIVE 24-HOUR GPS MOVEMENT HISTORY & LIVE MAP PANEL
    item {
      com.example.ui.components.ChildMovementHistory24hMapPanel(
        child = currentChild,
        locationState = locationState,
        geofences = geofences,
        onOpenFullMap = { onNavigateTab(ParentTab.LOCATION_MAP) },
        onRefreshGps = {
          currentChild?.let { onSendCloudCommand(it.id, "REQUEST_GPS_NOW", "REFRESH") }
        }
      )
    }

    // 5. PROTECTION MODULES
    item {
      Text(
        text = "Статус защиты для $childName",
        color = Slate200,
        fontSize = 15.sp,
        fontWeight = FontWeight.Bold
      )

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        PillarMetricCard(
          title = "Приложения",
          value = "$blockedAppsCount заблок.",
          icon = Icons.Default.Apps,
          color = CyanLight,
          onClick = { onNavigateTab(ParentTab.APPS) },
          modifier = Modifier.weight(1f)
        )
        PillarMetricCard(
          title = "Веб-фильтр",
          value = "${contentRules.count { it.isBlocked }} правил",
          icon = Icons.Default.Public,
          color = EmeraldShield,
          onClick = { onNavigateTab(ParentTab.CONTENT_FILTER) },
          modifier = Modifier.weight(1f)
        )
        PillarMetricCard(
          title = "Геозоны",
          value = "${geofences.size} зоны",
          icon = Icons.Default.Security,
          color = AmberWarning,
          onClick = { onNavigateTab(ParentTab.LOCATION_MAP) },
          modifier = Modifier.weight(1f)
        )
      }
    }

    // 6. ALERTS FEED
    if (alerts.isNotEmpty()) {
      item {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = "События и тревоги (${alerts.count { !it.isRead }})",
            color = Slate200,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Все →",
            color = CyanLight,
            fontSize = 13.sp,
            modifier = Modifier.clickable { onNavigateTab(ParentTab.ALERTS) }
          )
        }

        Spacer(modifier = Modifier.height(8.dp))

        alerts.take(2).forEach { alert ->
          AlertCompactCard(alert = alert, onClick = { onNavigateTab(ParentTab.ALERTS) })
          Spacer(modifier = Modifier.height(6.dp))
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(20.dp))
    }
  }

  // Modals for Pairing Code and Adding New Child
  selectedPairingChild?.let { child ->
    PairingCodeDialog(child = child, onDismiss = { selectedPairingChild = null })
  }

  if (isAddChildDialogVisible) {
    AddChildDialog(
      onDismiss = { isAddChildDialogVisible = false },
      onConfirm = { name, age, limit ->
        onAddNewChild(name, age, limit)
        isAddChildDialogVisible = false
      }
    )
  }

  if (isLiveStreamVisible) {
    ScreenStreamDialog(
      child = currentChild,
      onDismiss = { isLiveStreamVisible = false },
      onSendCloudCommand = { cmd, payload ->
        currentChild?.let { onSendCloudCommand(it.id, cmd, payload) }
      }
    )
  }

  if (isScreenshotsViewerVisible) {
    ScreenshotsViewerDialog(
      childId = currentChild?.id ?: 1L,
      childName = currentChild?.name ?: "Ребёнок",
      onDismiss = { isScreenshotsViewerVisible = false },
      onTakeNew = {
        currentChild?.let { onTakeScreenshot(it.id) }
      }
    )
  }

  if (isTrafficDialogVisible) {
    TrafficCalculationDialog(
      estimates = trafficEstimates,
      onDismiss = { isTrafficDialogVisible = false }
    )
  }
}

@Composable
fun TrafficCalculationDialog(
  estimates: List<TrafficConsumptionEstimate>,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = CyanLight)
        Text("Потребление трафика в час", color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold)
      }
    },
    text = {
      LazyColumn(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
      ) {
        item {
          Text(
            text = "Протокол KidsGuard использует легковесные JSON/бинарные пакеты и сжатие. Фоновое потребление мобильного интернета сведено к минимуму.",
            color = Slate400,
            fontSize = 12.sp,
            lineHeight = 16.sp
          )
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF132238)),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.4f)),
            shape = RoundedCornerShape(12.dp)
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text("1. В движении на улице (Активный GPS):", color = CyanLight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Spacer(modifier = Modifier.height(4.dp))
              Text("• GPS фикс (1 раз/мин): ~240 байт × 60 = 14.4 КБ/ч", color = Slate200, fontSize = 12.sp)
              Text("• Heartbeat пинг правил (каждые 60 сек): ~3.6 КБ/ч", color = Slate200, fontSize = 12.sp)
              Text("• Чат и экстренный канал: ~4.5 КБ/ч", color = Slate200, fontSize = 12.sp)
              Text("• Синхронизация лимитов (раз в 5 мин): ~4.2 КБ/ч", color = Slate200, fontSize = 12.sp)
              Spacer(modifier = Modifier.height(4.dp))
              Text("ИТОГО: 18–35 КБ/час (~0.55 МБ/сутки, ~16.5 МБ/мес)", color = EmeraldShieldLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF111E2E)),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
            shape = RoundedCornerShape(12.dp)
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text("2. В покое / Уроки в школе (Смарт-режим):", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Spacer(modifier = Modifier.height(4.dp))
              Text("• Акселерометр снижает частоту GPS до 1 раза в 5 мин", color = Slate200, fontSize = 12.sp)
              Text("• Heartbeat поддержания связи: ~3.6 КБ/ч", color = Slate200, fontSize = 12.sp)
              Spacer(modifier = Modifier.height(4.dp))
              Text("ИТОГО: 8–14 КБ/час (~0.25 МБ/сутки, ~7.5 МБ/мес)", color = CyanLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }
        }

        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF261908)),
            border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.5f)),
            shape = RoundedCornerShape(12.dp)
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Text("3. В режиме «Только Wi-Fi»:", color = AmberWarning, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Spacer(modifier = Modifier.height(4.dp))
              Text("• Сотовый трафик ребенка: РОВНО 0 КБ/час", color = Slate200, fontSize = 12.sp)
              Text("• Связь и координаты накапливаются в локальной очереди и передаются только при появлении Wi-Fi", color = Slate400, fontSize = 11.sp)
            }
          }
        }

        item {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(Color(0xFF192419))
              .padding(10.dp)
          ) {
            Text(
              text = "📌 Наглядное сравнение: 1 минута ролика на YouTube тратит ~25 МБ трафика — это больше, чем KidsGuard расходует за целый месяц непрерывного отслеживания!",
              color = EmeraldShieldLight,
              fontSize = 11.sp,
              lineHeight = 15.sp
            )
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Понятно", fontWeight = FontWeight.Bold)
      }
    }
  )
}

@Composable
fun QuickToggleCard(
  title: String,
  subtitle: String,
  icon: ImageVector,
  isActive: Boolean,
  activeColor: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier
      .height(94.dp)
      .clickable { onClick() },
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = if (isActive) androidx.compose.foundation.BorderStroke(1.dp, activeColor.copy(alpha = 0.5f)) else null
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(14.dp),
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = if (isActive) activeColor else Slate400,
          modifier = Modifier.size(22.dp)
        )
        Box(
          modifier = Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(if (isActive) activeColor else Slate800)
        )
      }

      Column {
        Text(
          text = title,
          color = Color.White,
          fontWeight = FontWeight.Bold,
          fontSize = 14.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Text(
          text = subtitle,
          color = Slate400,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
fun PillarMetricCard(
  title: String,
  value: String,
  icon: ImageVector,
  color: Color,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier
      .height(86.dp)
      .clickable { onClick() },
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900)
  ) {
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(12.dp),
      horizontalAlignment = Alignment.Start,
      verticalArrangement = Arrangement.SpaceBetween
    ) {
      Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(20.dp))
      Column {
        Text(text = title, color = Slate400, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(text = value, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}

@Composable
fun AlertCompactCard(alert: AlertNotificationEntity, onClick: () -> Unit) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .clickable { onClick() },
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (alert.type == "SOS") Color(0xFF3B121C) else Slate900
    )
  ) {
    Row(
      modifier = Modifier.padding(12.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      Icon(
        imageVector = if (alert.type == "SOS") Icons.Default.Warning else Icons.Default.NotificationsActive,
        contentDescription = null,
        tint = if (alert.type == "SOS") RoseLock else CyanLight,
        modifier = Modifier.size(20.dp)
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(text = alert.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        Text(text = alert.message, color = Slate400, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
      }
    }
  }
}
