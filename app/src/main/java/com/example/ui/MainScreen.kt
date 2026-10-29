package com.example.ui

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Pin
import com.example.ui.screens.ChangeMasterPinDialog
import com.example.ui.screens.DebugLogsDialog
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SupervisorAccount
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.ui.components.SecurityPinDialog
import com.example.ui.screens.AlertsScreen
import com.example.ui.screens.AppManagementScreen
import com.example.ui.screens.ChildModeScreen
import com.example.ui.screens.ChildPairingScreen
import com.example.ui.screens.ContentFilterScreen
import com.example.ui.screens.FamilyChatScreen
import com.example.ui.screens.LiveMapScreen
import com.example.ui.screens.ParentDashboardScreen
import com.example.ui.screens.PcControlScreen
import com.example.ui.screens.RoleSelectionScreen
import com.example.ui.screens.ScreenTimeScreen
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(viewModel: ParentControlViewModel = viewModel()) {
  val context = LocalContext.current
  val deviceRole by viewModel.deviceRole.collectAsState()
  val activeTab by viewModel.activeParentTab.collectAsState()
  val allChildren by viewModel.allChildren.collectAsState()
  val activeChild by viewModel.activeChild.collectAsState()
  val apps by viewModel.allApps.collectAsState()
  val geofences by viewModel.allGeofences.collectAsState()
  val contentRules by viewModel.allContentRules.collectAsState()
  val parentSettings by viewModel.parentSettings.collectAsState()
  val alerts by viewModel.allAlerts.collectAsState()
  val locationState by viewModel.locationState.collectAsState()
  val testEvaluation by viewModel.testFilterEvaluation.collectAsState()
  val isPinDialogVisible by viewModel.isPinDialogVisible.collectAsState()
  val isSimActive by viewModel.isSimulationActive.collectAsState()
  val browserUrl by viewModel.childBrowserUrl.collectAsState()
  val browserBlockedReason by viewModel.childBrowserBlockedReason.collectAsState()
  val uiToast by viewModel.uiToast.collectAsState()
  val chatMessages by viewModel.chatMessages.collectAsState()
  val isRemoteUpdating by viewModel.isRemoteUpdating.collectAsState()
  val isSirenActive by viewModel.isSirenActive.collectAsState()
  val networkState by viewModel.networkState.collectAsState()
  val updateReport by viewModel.updateCompletedReport.collectAsState()
  val activityDateFilter by viewModel.activityDateFilter.collectAsState()
  val filteredPcProcs by viewModel.filteredPcProcs.collectAsState()
  val filteredPcTitles by viewModel.filteredPcTitles.collectAsState()
  val filteredPcTotalSeconds by viewModel.filteredPcTotalSeconds.collectAsState()

  var isPairingScreenVisible by remember { mutableStateOf(false) }
  var isChangePinDialogVisible by remember { mutableStateOf(false) }
  var isDebugLogsDialogVisible by remember { mutableStateOf(false) }

  val snackbarHostState = remember { SnackbarHostState() }

  LaunchedEffect(uiToast) {
    uiToast?.let { toast ->
      Toast.makeText(context, toast.message, Toast.LENGTH_SHORT).show()
      viewModel.clearToast()
    }
  }

  // Handle Android back button
  BackHandler(enabled = deviceRole == AppRole.PARENT && activeTab != ParentTab.DASHBOARD) {
    viewModel.setActiveParentTab(ParentTab.DASHBOARD)
  }

  BackHandler(enabled = deviceRole == AppRole.CHILD) {
    viewModel.requestSwitchToParentRole()
  }

  // 1. Pairing Screen Overlay (can be opened anytime from unconfigured state, or from Child mode, or from Parent mode)
  if (isPairingScreenVisible) {
    ChildPairingScreen(
      onPairWithCode = { code, callback ->
        viewModel.pairChildDeviceWithCode(code) { success, msg ->
          if (success) {
            isPairingScreenVisible = false
          }
          callback(success, msg)
        }
      },
      onCancel = { isPairingScreenVisible = false }
    )
    return
  }

  // 2. Initial Role Selection if unconfigured
  if (deviceRole == AppRole.UNCONFIGURED) {
    RoleSelectionScreen(
      onSelectParent = { viewModel.setDeviceRole(AppRole.PARENT) },
      onSelectChild = { isPairingScreenVisible = true }
    )
    return
  }

  // 3. Child Mode View
  if (deviceRole == AppRole.CHILD) {
    ChildModeScreen(
      activeChild = activeChild,
      allChildren = allChildren,
      apps = apps,
      locationState = locationState,
      browserUrl = browserUrl,
      browserBlockedReason = browserBlockedReason,
      chatMessages = chatMessages,
      onSendMessage = { viewModel.sendChatMessage(it) },
      onVerifyUninstallPin = { pin, onSuccess, onError -> viewModel.verifyChildUninstallPin(pin, onSuccess, onError) },
      onTriggerSos = { viewModel.triggerChildSos() },
      onRequestExtraTime = { mins, reason -> viewModel.requestExtraTimeFromChild(mins, reason) },
      onNavigateBrowser = { viewModel.openChildBrowserUrl(it) },
      onExitToParentMode = { viewModel.requestSwitchToParentRole() },
      onOpenPairingScreen = { isPairingScreenVisible = true },
      onBindThisDeviceToChild = { childId -> viewModel.bindThisChildDeviceToProfile(childId) }
    )

    if (isPinDialogVisible) {
      SecurityPinDialog(
        onDismiss = { viewModel.dismissPinDialog() },
        onVerify = { pin -> viewModel.verifyPin(pin) }
      )
    }

    if (isChangePinDialogVisible) {
      val currentMasterPin = viewModel.familyConfig.value?.parentMasterPin ?: "1234"
      ChangeMasterPinDialog(
        currentMasterPin = currentMasterPin,
        onDismiss = { isChangePinDialogVisible = false },
        onPinChanged = { newPin ->
          viewModel.changeMasterPin(newPin)
        }
      )
    }

    if (isDebugLogsDialogVisible) {
      DebugLogsDialog(
        onDismiss = { isDebugLogsDialogVisible = false },
        onRunSelfTest = {
          viewModel.runControlSelfTest()
        }
      )
    }
    return
  }

  // 3. Parent Mode View
  Scaffold(
    snackbarHost = { SnackbarHost(snackbarHostState) },
    topBar = {
      TopAppBar(
        title = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(end = 4.dp)
          ) {
            Box(
              modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(CyanContainer),
              contentAlignment = Alignment.Center
            ) {
              Icon(
                Icons.Default.Shield,
                contentDescription = null,
                tint = CyanLight,
                modifier = Modifier.size(19.dp)
              )
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column {
              Text(
                text = "KidsGuard Pro",
                fontSize = 16.sp,
                fontWeight = FontWeight.ExtraBold,
                color = Color.White,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
              Text(
                text = "v${com.example.BuildConfig.VERSION_NAME} • Родитель",
                fontSize = 10.sp,
                color = Slate400,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
            }
          }
        },
        actions = {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            modifier = Modifier.padding(end = 6.dp)
          ) {
            // Debug & Logs button
            IconButton(
              onClick = { isDebugLogsDialogVisible = true },
              modifier = Modifier.size(34.dp)
            ) {
              Icon(
                Icons.Default.BugReport,
                contentDescription = "Отладка и тесты",
                tint = CyanLight,
                modifier = Modifier.size(18.dp)
              )
            }

            // Change Master PIN button
            IconButton(
              onClick = { isChangePinDialogVisible = true },
              modifier = Modifier.size(34.dp)
            ) {
              Icon(
                Icons.Default.Pin,
                contentDescription = "Сменить PIN-код",
                tint = Color.White,
                modifier = Modifier.size(18.dp)
              )
            }

            // Chat with active child button with unread badge
            val unreadChildChatCount = chatMessages.count { !it.isRead && it.senderRole == "CHILD" }
            IconButton(
              onClick = { viewModel.setActiveParentTab(ParentTab.CHAT) },
              modifier = Modifier
                .size(34.dp)
                .testTag("top_bar_chat_button")
            ) {
              if (unreadChildChatCount > 0) {
                BadgedBox(badge = { Badge { Text("$unreadChildChatCount") } }) {
                  Icon(Icons.Default.Chat, contentDescription = "Чат", tint = CyanLight, modifier = Modifier.size(18.dp))
                }
              } else {
                Icon(Icons.Default.Chat, contentDescription = "Чат", tint = CyanLight, modifier = Modifier.size(18.dp))
              }
            }

            // Windows PC Remote Control (Hosts Blocker) shortcut
            IconButton(
              onClick = { viewModel.setActiveParentTab(ParentTab.PC_CONTROL) },
              modifier = Modifier
                .size(34.dp)
                .testTag("top_bar_pc_button")
            ) {
              Icon(
                Icons.Default.Computer,
                contentDescription = "Управление компьютером ребёнка (Windows ПК)",
                tint = CyanLight,
                modifier = Modifier.size(18.dp)
              )
            }

            // Pair Child Device Code shortcut
            IconButton(
              onClick = { isPairingScreenVisible = true },
              modifier = Modifier
                .size(34.dp)
                .testTag("top_bar_pair_code_button")
            ) {
              Icon(
                Icons.Default.PhoneAndroid,
                contentDescription = "Подключить телефон ребенка",
                tint = EmeraldShieldLight,
                modifier = Modifier.size(18.dp)
              )
            }

            // Switch to Child Device / Companion preview
            Button(
              onClick = { viewModel.setDeviceRole(AppRole.CHILD) },
              colors = ButtonDefaults.buttonColors(containerColor = Slate800, contentColor = CyanLight),
              shape = RoundedCornerShape(50),
              contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
              modifier = Modifier
                .height(32.dp)
                .testTag("switch_to_child_mode_button")
            ) {
              Icon(
                Icons.Default.Smartphone,
                contentDescription = null,
                tint = CyanLight,
                modifier = Modifier.size(14.dp)
              )
              Spacer(modifier = Modifier.width(4.dp))
              Text(
                text = "Ребёнок",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = CyanLight,
                maxLines = 1,
                softWrap = false
              )
            }
          }
        },
        colors = TopAppBarDefaults.topAppBarColors(
          containerColor = Slate950,
          titleContentColor = Color.White
        )
      )
    },
    bottomBar = {
      val unreadAlertsCount = alerts.count { !it.isRead }
      val navItemColors = NavigationBarItemDefaults.colors(
        selectedIconColor = Slate950,
        selectedTextColor = CyanLight,
        indicatorColor = CyanLight,
        unselectedIconColor = Slate400,
        unselectedTextColor = Slate400
      )

      NavigationBar(
        containerColor = Slate950,
        tonalElevation = 8.dp,
        modifier = Modifier.windowInsetsPadding(WindowInsets.navigationBars)
      ) {
        NavigationBarItem(
          selected = activeTab == ParentTab.DASHBOARD,
          onClick = { viewModel.setActiveParentTab(ParentTab.DASHBOARD) },
          icon = { Icon(Icons.Default.Dashboard, contentDescription = "Обзор", modifier = Modifier.size(20.dp)) },
          label = { Text("Обзор", fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
          alwaysShowLabel = true,
          colors = navItemColors,
          modifier = Modifier.testTag("nav_tab_dashboard")
        )

        NavigationBarItem(
          selected = activeTab == ParentTab.APPS,
          onClick = { viewModel.setActiveParentTab(ParentTab.APPS) },
          icon = { Icon(Icons.Default.Apps, contentDescription = "Программы", modifier = Modifier.size(20.dp)) },
          label = { Text("Программы", fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
          alwaysShowLabel = true,
          colors = navItemColors,
          modifier = Modifier.testTag("nav_tab_apps")
        )

        NavigationBarItem(
          selected = activeTab == ParentTab.SCREEN_TIME,
          onClick = { viewModel.setActiveParentTab(ParentTab.SCREEN_TIME) },
          icon = { Icon(Icons.Default.Timer, contentDescription = "Время", modifier = Modifier.size(20.dp)) },
          label = { Text("Время", fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
          alwaysShowLabel = true,
          colors = navItemColors,
          modifier = Modifier.testTag("nav_tab_screen_time")
        )

        NavigationBarItem(
          selected = activeTab == ParentTab.CONTENT_FILTER,
          onClick = { viewModel.setActiveParentTab(ParentTab.CONTENT_FILTER) },
          icon = { Icon(Icons.Default.Public, contentDescription = "Фильтр", modifier = Modifier.size(20.dp)) },
          label = { Text("Фильтр", fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
          alwaysShowLabel = true,
          colors = navItemColors,
          modifier = Modifier.testTag("nav_tab_filter")
        )

        NavigationBarItem(
          selected = activeTab == ParentTab.LOCATION_MAP,
          onClick = { viewModel.setActiveParentTab(ParentTab.LOCATION_MAP) },
          icon = { Icon(Icons.Default.Map, contentDescription = "Карта", modifier = Modifier.size(20.dp)) },
          label = { Text("Карта", fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
          alwaysShowLabel = true,
          colors = navItemColors,
          modifier = Modifier.testTag("nav_tab_map")
        )

        NavigationBarItem(
          selected = activeTab == ParentTab.ALERTS,
          onClick = { viewModel.setActiveParentTab(ParentTab.ALERTS) },
          icon = {
            if (unreadAlertsCount > 0) {
              BadgedBox(badge = { Badge { Text("$unreadAlertsCount") } }) {
                Icon(Icons.Default.Notifications, contentDescription = "Тревоги", modifier = Modifier.size(20.dp))
              }
            } else {
              Icon(Icons.Default.Notifications, contentDescription = "Тревоги", modifier = Modifier.size(20.dp))
            }
          },
          label = { Text("Тревоги", fontSize = 9.sp, maxLines = 1, softWrap = false, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis) },
          alwaysShowLabel = true,
          colors = navItemColors,
          modifier = Modifier.testTag("nav_tab_alerts")
        )
      }
    },
    containerColor = Slate950
  ) { innerPadding ->
    Box(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding),
      contentAlignment = Alignment.TopCenter
    ) {
      Box(
        modifier = Modifier
          .fillMaxSize()
          .widthIn(max = 640.dp)
      ) {
      when (activeTab) {
        ParentTab.DASHBOARD -> {
          ParentDashboardScreen(
            children = allChildren,
            activeChild = activeChild,
            locationState = locationState,
            apps = apps,
            contentRules = contentRules,
            geofences = geofences,
            alerts = alerts,
            onSelectChild = { viewModel.selectChild(it) },
            onAddNewChild = { name, age, limit -> viewModel.addNewChild(name, age, limit) },
            onDeleteChild = { viewModel.deleteChild(it) },
            onToggleRemoteLock = { viewModel.toggleRemoteLockForActiveChild() },
            onToggleStudyMode = { viewModel.toggleStudyModeForActiveChild() },
            onNavigateTab = { viewModel.setActiveParentTab(it) },
            onGrantBonusTime = { viewModel.grantExtraTimeForActiveChild(it) },
            onSetDailyLimit = { viewModel.setDailyLimitForActiveChild(it) },
            onToggleAppBlocked = { viewModel.toggleAppBlocked(it) },
            onBatchSetAppsBlocked = { list, blocked -> viewModel.setAppsBlockedBatch(list, blocked) },
            onSyncRealApps = { viewModel.syncRealAppsForActiveChild() },
            onSendLoudSignal = { viewModel.sendLoudAttentionSignal() },
            onTriggerSilentUpdate = { viewModel.triggerRemoteSilentUpdate(it) },
            onToggleMobileData = { viewModel.toggleChildMobileData(it) },
            onToggleGpsTracking = { viewModel.toggleChildGpsTracking(it) },
            onTakeScreenshot = { viewModel.takeRemoteScreenshot(it) },
            onSendCloudCommand = { childId, cmd, payload -> viewModel.sendCustomCloudCommand(childId, cmd, payload) },
            networkState = networkState,
            trafficEstimates = viewModel.trafficEstimates,
            isRemoteUpdating = isRemoteUpdating,
            isSirenActive = isSirenActive
          )
        }

        ParentTab.APPS -> {
          AppManagementScreen(
            apps = apps,
            isStudyModeActive = activeChild?.studyModeActive ?: false,
            onToggleAppBlocked = { viewModel.toggleAppBlocked(it) },
            onUpdateAppLimit = { id, limit -> viewModel.setAppLimit(id, limit) },
            onAddNewApp = { name, cat, limit, icon -> viewModel.addNewApp(name, cat, limit, icon) },
            onSyncRealApps = { viewModel.syncRealAppsForActiveChild() },
            onBatchSetBlocked = { list, blocked -> viewModel.setAppsBlockedBatch(list, blocked) }
          )
        }

        ParentTab.SCREEN_TIME -> {
          ScreenTimeScreen(
            child = activeChild,
            apps = apps,
            alerts = alerts,
            onSetDailyLimit = { viewModel.setDailyLimitForActiveChild(it) },
            onGrantBonusTime = { viewModel.grantExtraTimeForActiveChild(it) },
            onDismissAlert = { viewModel.deleteAlert(it) }
          )
        }

        ParentTab.CONTENT_FILTER -> {
          ContentFilterScreen(
            settings = parentSettings,
            rules = contentRules,
            testEvaluation = testEvaluation,
            onToggleRule = { viewModel.toggleContentRule(it) },
            onBatchSetRulesBlocked = { rulesList, blocked -> viewModel.setBatchContentRulesBlocked(rulesList, blocked) },
            onAddRule = { type, value, display -> viewModel.addContentRule(type, value, display) },
            onDeleteRule = { viewModel.deleteContentRule(it) },
            onToggleSafeSearch = { viewModel.toggleSafeSearch(it) },
            onToggleYoutubeRestricted = { viewModel.toggleYoutubeRestricted(it) },
            onTestFilterQuery = { viewModel.testContentFilter(it) },
            onClearFilterTest = { viewModel.clearFilterTest() }
          )
        }

        ParentTab.LOCATION_MAP -> {
          LiveMapScreen(
            child = activeChild,
            locationState = locationState,
            geofences = geofences,
            isSimulationActive = isSimActive,
            onToggleSimulation = { viewModel.toggleLocationSimulation() },
            onAddGeofence = { name, lat, lng, radius, icon -> viewModel.addGeofence(name, lat, lng, radius, icon) },
            onDeleteGeofence = { viewModel.deleteGeofence(it) },
            onToggleGpsTracking = { viewModel.toggleChildGpsTracking(it) },
            onRefreshLocation = {
              viewModel.requestFreshChildGps()
              activeChild?.let { viewModel.sendCustomCloudCommand(it.id, "QUERY_ACTIVE_APP", "") }
            }
          )
        }

        ParentTab.ALERTS -> {
          AlertsScreen(
            alerts = alerts,
            onMarkRead = { viewModel.markAlertRead(it) },
            onClearAll = { viewModel.clearAllAlerts() },
            onDeleteAlert = { viewModel.deleteAlert(it) }
          )
        }

        ParentTab.CHAT -> {
          FamilyChatScreen(
            child = activeChild,
            messages = chatMessages,
            isParentRole = true,
            onSendMessage = { viewModel.sendChatMessage(it) },
            onSendAttentionSignal = { viewModel.sendLoudAttentionSignal() },
            onTriggerSilentUpdate = { viewModel.triggerRemoteSilentUpdate(it) },
            isRemoteUpdating = isRemoteUpdating
          )
        }

        ParentTab.PC_CONTROL -> {
          PcControlScreen(
            activeChildPairingCode = activeChild?.pairingCode ?: "101-701",
            dateFilterState = activityDateFilter,
            filteredSqlProcs = filteredPcProcs,
            filteredSqlTitles = filteredPcTitles,
            filteredSqlTotalSeconds = filteredPcTotalSeconds,
            onSelectTodayFilter = { viewModel.selectTodayActivityFilter() },
            onSelectYesterdayFilter = { viewModel.selectYesterdayActivityFilter() },
            onSelectCustomDateRange = { startMs, endMs ->
              viewModel.selectCustomActivityDateRange(startMs, endMs)
            },
            onBack = { viewModel.setActiveParentTab(ParentTab.DASHBOARD) }
          )
        }
      }
      }
    }
  }

  // Security PIN Dialog
  if (isPinDialogVisible) {
    SecurityPinDialog(
      onDismiss = { viewModel.dismissPinDialog() },
      onVerify = { pin -> viewModel.verifyPin(pin) }
    )
  }

  if (isChangePinDialogVisible) {
    val currentMasterPin = viewModel.familyConfig.value?.parentMasterPin ?: "1234"
    com.example.ui.screens.ChangeMasterPinDialog(
      currentMasterPin = currentMasterPin,
      onDismiss = { isChangePinDialogVisible = false },
      onPinChanged = { newPin ->
        viewModel.changeMasterPin(newPin)
      }
    )
  }

  if (isDebugLogsDialogVisible) {
    com.example.ui.screens.DebugLogsDialog(
      onDismiss = { isDebugLogsDialogVisible = false },
      onRunSelfTest = {
        viewModel.runControlSelfTest()
      }
    )
  }

  // Remote Update Success and Changelog Dialog
  updateReport?.let { report ->
    AlertDialog(
      onDismissRequest = { viewModel.dismissUpdateReport() },
      containerColor = Slate900,
      title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Default.Security, contentDescription = null, tint = EmeraldShield)
          Text("Команда обновления отправлена!", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Box(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(EmeraldContainer)
              .padding(12.dp)
          ) {
            Column {
              Text("Устройство: ${report.childName}", color = EmeraldShield, fontWeight = FontWeight.Bold, fontSize = 14.sp)
              Text("Целевая версия: ${report.newVersion} (Актуальная)", color = EmeraldShieldLight, fontSize = 12.sp)
            }
          }

          Text("Что включено в обновление ${report.newVersion}:", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)

          report.changes.forEach { change ->
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("✓", color = CyanLight, fontWeight = FontWeight.Bold, fontSize = 13.sp)
              Text(change, color = Slate400, fontSize = 12.sp, lineHeight = 16.sp)
            }
          }
        }
      },
      confirmButton = {
        Button(
          onClick = { viewModel.dismissUpdateReport() },
          colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Отлично, закрыть", fontWeight = FontWeight.Bold)
        }
      }
    )
  }
}
