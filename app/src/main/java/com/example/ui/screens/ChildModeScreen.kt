package com.example.ui.screens

import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Public
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import com.example.data.model.ChatMessageEntity
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
import com.example.location.LocationState
import com.example.ui.components.AppIconBadge
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
import com.example.ui.theme.Slate950

@Composable
fun ChildModeScreen(
  activeChild: ChildProfileEntity?,
  allChildren: List<ChildProfileEntity> = emptyList(),
  apps: List<AppControlEntity>,
  locationState: LocationState,
  browserUrl: String,
  browserBlockedReason: String?,
  chatMessages: List<ChatMessageEntity>,
  onSendMessage: (String) -> Unit,
  onVerifyUninstallPin: (String, () -> Unit, () -> Unit) -> Unit,
  onTriggerSos: () -> Unit,
  onRequestExtraTime: (Int, String) -> Unit,
  onNavigateBrowser: (String) -> Unit,
  onExitToParentMode: () -> Unit,
  onOpenPairingScreen: (() -> Unit)? = null,
  onBindThisDeviceToChild: ((Long) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val coroutineScope = androidx.compose.runtime.rememberCoroutineScope()
  var selectedTab by remember { mutableIntStateOf(0) } // 0 = Обзор, 1 = Чат, 2 = Приложения, 3 = Безопасный Веб
  var showBonusRequestDialog by remember { mutableStateOf(false) }
  var showSosConfirmDialog by remember { mutableStateOf(false) }
  var showUninstallPinDialog by remember { mutableStateOf(false) }
  var uninstallPinInput by remember { mutableStateOf("") }
  var uninstallPinError by remember { mutableStateOf(false) }
  var urlInput by remember { mutableStateOf(browserUrl) }
  var showSecurityPermissionsDialog by remember { mutableStateOf(false) }

  val childName = activeChild?.name ?: "Мой смартфон"
  val dailyLimit = activeChild?.dailyScreenLimitMinutes ?: 120
  val usedMinutes = activeChild?.usedScreenTimeMinutes ?: 0
  val remainingMinutes = (dailyLimit - usedMinutes).coerceAtLeast(0)
  val isLocked = activeChild?.isDeviceLocked ?: false

  androidx.compose.runtime.LaunchedEffect(Unit) {
    com.example.util.ScreenShotManager.restorePersistentLiveStreamState(context)
    (context as? com.example.MainActivity)?.requestChildLocationPermissionsIfNeeded()
  }

  androidx.compose.runtime.LaunchedEffect(isLocked) {
    if (isLocked) {
      com.example.util.DeviceTotalLockManager.enforceTotalLock(context, "Экран заблокирован родительским контролем KidsGuard")
    } else {
      com.example.util.DeviceTotalLockManager.releaseTotalLock(context)
    }
  }

  val updateProgress by com.example.util.ApkUpdateInstaller.updateProgress.collectAsState()
  val updateStatusText by com.example.util.ApkUpdateInstaller.updateStatusText.collectAsState()

  val currentLocationState by androidx.compose.runtime.rememberUpdatedState(locationState)

  // 1. Instant Version, Battery & On-Demand Live Screen Sync on Child Mode Launch (Battery-Optimized)
  androidx.compose.runtime.LaunchedEffect(activeChild?.id) {
    val childId = activeChild?.id ?: 1L
    com.example.util.ScreenShotManager.restorePersistentLiveStreamState(context)
    if (!com.example.location.LiveLocationTracker.isEmulator()) {
      com.example.service.KidsGuardBackgroundService.start(context, childId)
    }
    val cloud = com.example.data.cloud.CloudSyncManager(context)
    cloud.syncChildAppVersion(childId, com.example.BuildConfig.VERSION_NAME)

    var tick = 0
    while (true) {
      val isLiveStreamActive = com.example.service.KidsGuardAccessibilityService.isLiveStreamingActiveNow() &&
        com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()
      if (!com.example.location.LiveLocationTracker.isEmulator() && isLiveStreamActive) {
        (context as? android.app.Activity)?.let { act ->
          com.example.util.ScreenShotManager.captureActivityDecorView(act)
        }
        val currentApp = com.example.util.DeviceUsageTracker.resolveCurrentForegroundApp(context)
        com.example.util.ScreenShotManager.triggerHardwareScreenCapture(currentApp, false) { item ->
          item.base64Bitmap?.let { b64 ->
            coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
              cloud.uploadScreenSnapshot(childId, currentApp, b64)
            }
          }
        }
      }
      if (!com.example.location.LiveLocationTracker.isEmulator() &&
          com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected() &&
          tick % 8 == 0) {
        val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
        cloud.upsertBatteryLevel(childId, batt)
        val liveLoc = currentLocationState
        if (!com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(liveLoc.latitude, liveLoc.longitude) &&
            liveLoc.latitude != 0.0 && liveLoc.longitude != 0.0) {
          cloud.updateLocation(childId, liveLoc.latitude, liveLoc.longitude, liveLoc.currentAddress)
        }
      }
      tick++
      kotlinx.coroutines.delay(if (isLiveStreamActive) 2800L else 15_000L)
    }
  }

  // Real-time Cloud Command Listener in Child Mode (Instant Screenshot, GPS & Remote Updates)
  androidx.compose.runtime.LaunchedEffect(activeChild?.id) {
    val childId = activeChild?.id ?: 1L
    val cloud = com.example.data.cloud.CloudSyncManager(context)
    cloud.observeRemoteCommands(childId).collect { cmdPair ->
      cmdPair?.let { (cmd, payload) ->
        when (cmd) {
          "START_LIVE_STREAM", "TAKE_SCREENSHOT" -> {
            if (cmd == "START_LIVE_STREAM") {
              com.example.service.KidsGuardAccessibilityService.activateLiveStreamingWindow(90_000L)
            }
            (context as? android.app.Activity)?.let { act ->
              com.example.util.ScreenShotManager.captureActivityDecorView(act)
            }
            val currentApp = com.example.util.DeviceUsageTracker.resolveCurrentForegroundApp(context)
            com.example.util.ScreenShotManager.triggerHardwareScreenCapture(
              appName = currentApp,
              saveToDeviceGallery = false
            ) { item ->
              item.base64Bitmap?.let { b64 ->
                coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                  cloud.uploadScreenSnapshot(childId, currentApp, b64)
                }
              }
            }
            val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
            cloud.upsertBatteryLevel(childId, batt)
            if (cmd == "TAKE_SCREENSHOT" || cmd == "QUERY_ACTIVE_APP") {
              cloud.clearRemoteCommand(childId)
            }
          }
          "REQUEST_GPS_NOW" -> {
            if (!com.example.location.LiveLocationTracker.isEmulator()) {
              com.example.service.KidsGuardBackgroundService.start(context, childId)
              val liveLoc = currentLocationState
              if (!com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(liveLoc.latitude, liveLoc.longitude)) {
                cloud.updateLocation(childId, liveLoc.latitude, liveLoc.longitude, liveLoc.currentAddress)
              } else {
                val hwLoc = com.example.location.LiveLocationTracker.getBestImmediateLocation(context)
                if (hwLoc != null) {
                  coroutineScope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val exactAddr = com.example.location.LiveLocationTracker.reverseGeocodeExact(context, hwLoc.latitude, hwLoc.longitude)
                    cloud.updateLocation(childId, hwLoc.latitude, hwLoc.longitude, exactAddr)
                  }
                }
              }
            }
            cloud.clearRemoteCommand(childId)
          }
          "STOP_LIVE_STREAM" -> {
            com.example.service.KidsGuardAccessibilityService.stopLiveStreamingWindow()
            cloud.clearRemoteCommand(childId)
          }
          "UPDATE_APP", "APP_UPDATE" -> {
            cloud.clearRemoteCommand(childId)
            val targetVer = if (payload.contains("|")) payload.substringBefore("|").trim() else ""
            val rawUrl = if (payload.contains("|")) payload.substringAfter("|").trim() else payload.trim()
            val url = rawUrl.ifBlank { com.example.util.ApkUpdateInstaller.SHARED_APK_URL }
            com.example.util.ApkUpdateInstaller.downloadAndInstallLatestApk(
              context = context,
              customUrl = url,
              isSilentChildUpdate = true,
              targetChildId = childId,
              targetVersionOverride = targetVer
            )
          }
        }
      }
    }
  }
  val isStudy = activeChild?.studyModeActive ?: false

  Scaffold(
    bottomBar = {
      NavigationBar(
        containerColor = Slate900,
        contentColor = CyanLight,
        tonalElevation = 8.dp
      ) {
        NavigationBarItem(
          selected = selectedTab == 0,
          onClick = { selectedTab = 0 },
          icon = { Icon(Icons.Default.PhoneAndroid, contentDescription = "Экран") },
          label = { Text("Экран", fontSize = 11.sp) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Slate950,
            selectedTextColor = CyanLight,
            indicatorColor = CyanLight,
            unselectedIconColor = Slate400,
            unselectedTextColor = Slate400
          )
        )
        NavigationBarItem(
          selected = selectedTab == 1,
          onClick = { selectedTab = 1 },
          icon = { Icon(Icons.Default.Chat, contentDescription = "Чат") },
          label = { Text("Чат (${chatMessages.size})", fontSize = 11.sp) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Slate950,
            selectedTextColor = CyanLight,
            indicatorColor = CyanLight,
            unselectedIconColor = Slate400,
            unselectedTextColor = Slate400
          )
        )
        NavigationBarItem(
          selected = selectedTab == 2,
          onClick = { selectedTab = 2 },
          icon = { Icon(Icons.Default.Apps, contentDescription = "Программы") },
          label = { Text("Программы", fontSize = 11.sp) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Slate950,
            selectedTextColor = CyanLight,
            indicatorColor = CyanLight,
            unselectedIconColor = Slate400,
            unselectedTextColor = Slate400
          )
        )
        NavigationBarItem(
          selected = selectedTab == 3,
          onClick = { selectedTab = 3 },
          icon = { Icon(Icons.Default.Public, contentDescription = "Веб") },
          label = { Text("Безопасный Веб", fontSize = 10.sp) },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = Slate950,
            selectedTextColor = CyanLight,
            indicatorColor = CyanLight,
            unselectedIconColor = Slate400,
            unselectedTextColor = Slate400
          )
        )
      }
    },
    containerColor = Slate950,
    modifier = modifier.fillMaxSize()
  ) { innerPadding ->
    Column(
      modifier = Modifier
        .fillMaxSize()
        .padding(innerPadding)
        .background(Slate950)
    ) {
      // Top Status Bar (strictly fixed 50.dp height)
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp)
          .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          modifier = Modifier.weight(1f),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Box(
            modifier = Modifier
              .size(10.dp)
              .clip(CircleShape)
              .background(if (isLocked) RoseLock else EmeraldShield)
          )
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "$childName • ${if (isLocked) "Заблокирован" else "Защищен"}",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 13.sp,
              maxLines = 1,
              softWrap = false,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            val p2pStatus by com.example.data.cloud.DirectP2pSocketEngine.status.collectAsState()
            Text(
              text = "v${com.example.BuildConfig.VERSION_NAME} • P2P IP: ${p2pStatus.localDeviceIp}:49152",
              color = CyanLight,
              fontSize = 10.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              softWrap = false,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }
        }

        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
          IconButton(
            onClick = { showSecurityPermissionsDialog = true },
            modifier = Modifier.size(34.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Shield,
              contentDescription = "Системные доступы защиты",
              tint = CyanLight,
              modifier = Modifier.size(20.dp)
            )
          }

          Button(
            onClick = onExitToParentMode,
            colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = Slate200),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
            modifier = Modifier
              .width(72.dp)
              .height(28.dp)
          ) {
            Text("Родитель", fontSize = 10.sp, maxLines = 1, softWrap = false)
          }
        }
      }

      // TAB 0: DASHBOARD / SCREEN TIME & SOS
      if (selectedTab == 0) {
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          // Device Lock Banner
          if (isLocked) {
            item {
              Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF450A0A)),
                border = androidx.compose.foundation.BorderStroke(1.dp, RoseLock),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(
                  modifier = Modifier.padding(16.dp),
                  horizontalAlignment = Alignment.CenterHorizontally
                ) {
                  Icon(Icons.Default.Lock, contentDescription = null, tint = RoseLock, modifier = Modifier.size(36.dp))
                  Spacer(modifier = Modifier.height(6.dp))
                  Text("ДОСТУП ЗАБЛОКИРОВАН РОДИТЕЛЕМ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                  Text("Экран закрыт по удаленной команде родителя.", color = Slate200, fontSize = 11.sp, textAlign = TextAlign.Center)
                }
              }
            }
          }

          // Multi-Child Device Binding Selector (Ensures Phone 1 = Child 1 and Phone 2 = Child 2)
          item {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = Slate900),
              border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldShield.copy(alpha = 0.45f)),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Column(modifier = Modifier.weight(1f)) {
                    Text(
                      text = "Привязка этого телефона: $childName (ID #${activeChild?.id ?: 1L})",
                      color = Color.White,
                      fontWeight = FontWeight.Bold,
                      fontSize = 13.sp
                    )
                    Text(
                      text = "Выберите, какому ребёнку принадлежит этот смартфон:",
                      color = Slate400,
                      fontSize = 11.sp
                    )
                  }
                  if (onOpenPairingScreen != null) {
                    OutlinedButton(
                      onClick = onOpenPairingScreen,
                      shape = RoundedCornerShape(8.dp),
                      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                      modifier = Modifier.height(28.dp)
                    ) {
                      Text("По коду", fontSize = 10.sp, color = CyanLight, fontWeight = FontWeight.Bold)
                    }
                  }
                }
                if (allChildren.isNotEmpty()) {
                  Spacer(modifier = Modifier.height(8.dp))
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    allChildren.forEach { childItem ->
                      val isSelected = childItem.id == activeChild?.id
                      Button(
                        onClick = { onBindThisDeviceToChild?.invoke(childItem.id) },
                        colors = ButtonDefaults.buttonColors(
                          containerColor = if (isSelected) EmeraldShield else Slate800,
                          contentColor = if (isSelected) Slate950 else Slate200
                        ),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f).height(34.dp)
                      ) {
                        Text(
                          text = if (isSelected) "✓ ${childItem.name} (#${childItem.id})" else "${childItem.name} (#${childItem.id})",
                          fontSize = 11.sp,
                          fontWeight = FontWeight.Bold,
                          maxLines = 1
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          // Prominent Permissions & Live Screen Streaming Card + Version Check (strictly static 196.dp height)
          item {
            val activeCoreVer by com.example.util.CoreEngineHotUpdater.activeCoreVersion.collectAsState()
            val installedVer = activeCoreVer.ifBlank { com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(context) }
            val cloudVer by com.example.util.ApkUpdateInstaller.updateVersion.collectAsState()
            val isUpToDate = com.example.util.ApkUpdateInstaller.compareVersions(installedVer, cloudVer) >= 0

            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = Slate900),
              border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.5f)),
              modifier = Modifier
                .fillMaxWidth()
                .height(196.dp)
            ) {
              Column(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(14.dp),
                verticalArrangement = Arrangement.SpaceBetween
              ) {
                Column {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                  ) {
                    Icon(Icons.Default.Shield, contentDescription = null, tint = CyanLight, modifier = Modifier.size(20.dp))
                    Text(
                      text = "Разрешения: Защита 24/7, Экран и GPS",
                      color = Color.White,
                      fontWeight = FontWeight.Bold,
                      fontSize = 13.sp,
                      maxLines = 1,
                      softWrap = false,
                      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                  }
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "Проверьте активность всех системных разрешений для бесперебойной работы геолокации, контроля экранного времени и родительской защиты.",
                    color = Slate400,
                    fontSize = 11.sp,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                  )
                }

                Button(
                  onClick = { showSecurityPermissionsDialog = true },
                  colors = ButtonDefaults.buttonColors(containerColor = EmeraldShield, contentColor = Slate950),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                ) {
                  Icon(Icons.Default.Security, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text(
                    text = "Открыть все разрешения и опции (8 пунктов)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                  )
                }

                // Static-height version/update box so buttons and cards never shift
                Box(
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color(0xFF064E3B).copy(alpha = 0.45f))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                  contentAlignment = Alignment.CenterStart
                ) {
                  if (updateProgress != null || updateStatusText != null) {
                    Text(
                      text = updateStatusText ?: "Синхронизация Ядра...",
                      color = CyanLight,
                      fontSize = 11.sp,
                      fontWeight = FontWeight.Bold,
                      maxLines = 1,
                      softWrap = false,
                      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                  } else if (isUpToDate) {
                    Text(
                      text = "✓ Актуальная версия Ядра v$installedVer (обновление не требуется)",
                      color = com.example.ui.theme.EmeraldShieldLight,
                      fontWeight = FontWeight.Bold,
                      fontSize = 11.sp,
                      maxLines = 1,
                      softWrap = false,
                      overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                  } else {
                    Row(
                      modifier = Modifier.fillMaxWidth(),
                      horizontalArrangement = Arrangement.SpaceBetween,
                      verticalAlignment = Alignment.CenterVertically
                    ) {
                      Text(
                        text = "Ядро: v$installedVer → доступно v$cloudVer",
                        color = CyanLight,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                      )
                      Spacer(modifier = Modifier.width(8.dp))
                      Button(
                        onClick = {
                          com.example.util.ApkUpdateInstaller.downloadAndInstallLatestApk(
                            context = context,
                            isSilentChildUpdate = true,
                            targetChildId = activeChild?.id ?: 1L,
                            targetVersionOverride = cloudVer
                          )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = CyanContainer, contentColor = CyanLight),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 2.dp),
                        modifier = Modifier
                          .width(108.dp)
                          .height(30.dp)
                      ) {
                        Text("Обновить Ядро", fontWeight = FontWeight.Bold, fontSize = 10.sp, maxLines = 1, softWrap = false)
                      }
                    }
                  }
                }
              }
            }
          }

          // Screen Time Card (strictly static 166.dp height)
          item {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = Slate900),
              border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
              modifier = Modifier
                .fillMaxWidth()
                .height(166.dp)
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
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.Timer, contentDescription = null, tint = CyanLight, modifier = Modifier.size(20.dp))
                    Text("Экранное время", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
                  }
                  Text("$usedMinutes из $dailyLimit мин", color = Slate400, fontSize = 12.sp, maxLines = 1)
                }

                Column {
                  Text(
                    text = "$remainingMinutes минут",
                    color = if (remainingMinutes > 30) CyanLight else RoseLock,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 26.sp,
                    maxLines = 1
                  )
                  Text("Осталось на сегодня", color = Slate400, fontSize = 11.sp, maxLines = 1)
                }

                Button(
                  onClick = { showBonusRequestDialog = true },
                  colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .fillMaxWidth()
                    .height(40.dp)
                ) {
                  Text("Запросить дополнительное время", fontWeight = FontWeight.Bold, fontSize = 12.sp, maxLines = 1)
                }
              }
            }
          }

          // SOS Emergency Button (strictly static 76.dp height)
          item {
            Card(
              shape = RoundedCornerShape(16.dp),
              colors = CardDefaults.cardColors(containerColor = Color(0xFF3F0B17)),
              border = androidx.compose.foundation.BorderStroke(1.dp, RoseLock.copy(alpha = 0.6f)),
              modifier = Modifier
                .fillMaxWidth()
                .height(76.dp)
            ) {
              Row(
                modifier = Modifier
                  .fillMaxSize()
                  .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Кнопка SOS (Экстренная связь)",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                  )
                  Text(
                    text = "Мгновенно передает родителям тревогу и GPS координаты",
                    color = Slate200,
                    fontSize = 10.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                  )
                }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                  onClick = { showSosConfirmDialog = true },
                  colors = ButtonDefaults.buttonColors(containerColor = RoseLock),
                  shape = RoundedCornerShape(10.dp),
                  modifier = Modifier
                    .width(74.dp)
                    .height(36.dp)
                ) {
                  Text("SOS", fontWeight = FontWeight.ExtraBold, fontSize = 13.sp, maxLines = 1)
                }
              }
            }
          }
        }
      }

      // TAB 1: FULL CHAT VIEW FOR CHILD
      if (selectedTab == 1) {
        FamilyChatScreen(
          child = activeChild,
          messages = chatMessages,
          isParentRole = false,
          onSendMessage = onSendMessage,
          modifier = Modifier.weight(1f)
        )
      }

      // TAB 2: APPS MANAGEMENT VIEW FOR CHILD
      if (selectedTab == 2) {
        LazyColumn(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          items(apps) { app ->
            Card(
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.cardColors(containerColor = if (app.isBlocked) Color(0xFF3B1017) else Slate900),
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (app.isBlocked) RoseLock.copy(alpha = 0.5f) else Slate800
              ),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier
                  .padding(12.dp)
                  .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
              ) {
                AppIconBadge(iconType = app.iconType, isBlocked = app.isBlocked)
                Column(modifier = Modifier.weight(1f)) {
                  Text(app.appName, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                  Text(
                    text = if (app.isBlocked) "Заблокировано родителем"
                    else if (app.dailyLimitMinutes > 0) "Лимит: ${app.dailyLimitMinutes} мин/день"
                    else "Разрешено",
                    color = if (app.isBlocked) RoseLock else Slate400,
                    fontSize = 11.sp
                  )
                }
                if (app.isBlocked) {
                  Icon(Icons.Default.Lock, contentDescription = null, tint = RoseLock, modifier = Modifier.size(18.dp))
                } else {
                  Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(18.dp))
                }
              }
            }
          }
        }
      }

      // TAB 3: SAFE BROWSER VIEW FOR CHILD
      if (selectedTab == 3) {
        Column(
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedTextField(
              value = urlInput,
              onValueChange = { urlInput = it },
              placeholder = { Text("Адрес сайта...", color = Slate400, fontSize = 12.sp) },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(10.dp),
              singleLine = true
            )
            Button(
              onClick = { onNavigateBrowser(urlInput) },
              colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
              shape = RoundedCornerShape(10.dp)
            ) {
              Icon(Icons.Default.Search, contentDescription = null)
            }
          }

          Card(
            shape = RoundedCornerShape(14.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            modifier = Modifier.weight(1f).fillMaxWidth()
          ) {
            Column(
              modifier = Modifier
                .padding(16.dp)
                .fillMaxSize(),
              verticalArrangement = Arrangement.Center,
              horizontalAlignment = Alignment.CenterHorizontally
            ) {
              if (browserBlockedReason != null) {
                Icon(Icons.Default.Block, contentDescription = null, tint = RoseLock, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("САЙТ ЗАБЛОКИРОВАН", color = RoseLock, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Text(browserBlockedReason, color = Slate200, fontSize = 12.sp, textAlign = TextAlign.Center)
              } else {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(48.dp))
                Spacer(modifier = Modifier.height(10.dp))
                Text("Безопасный веб-фильтр активен", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text("Текущий сайт: $browserUrl", color = CyanLight, fontSize = 12.sp)
              }
            }
          }
        }
      }
    }
  }

  if (showSecurityPermissionsDialog) {
    ChildPermissionsSetupDialog(
      onDismiss = { showSecurityPermissionsDialog = false }
    )
  }

  if (showSosConfirmDialog) {
    AlertDialog(
      onDismissRequest = { showSosConfirmDialog = false },
      containerColor = Slate900,
      title = { Text("Отправить сигнал SOS?", color = RoseLock, fontWeight = FontWeight.Bold) },
      text = { Text("Родителям будет отправлено экстренное уведомление с вашими точными координатами.", color = Slate200) },
      confirmButton = {
        Button(
          onClick = {
            com.example.util.SosEmergencySender.sendSosToParent(context, "Экран ребёнка")
            onTriggerSos()
            showSosConfirmDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = RoseLock)
        ) {
          Text("ОТПРАВИТЬ SOS")
        }
      },
      dismissButton = {
        TextButton(onClick = { showSosConfirmDialog = false }) {
          Text("Отмена", color = Slate400)
        }
      }
    )
  }

  if (showBonusRequestDialog) {
    var reqMinutes by remember { mutableIntStateOf(15) }
    var reqReason by remember { mutableStateOf("") }

    AlertDialog(
      onDismissRequest = { showBonusRequestDialog = false },
      containerColor = Slate900,
      title = { Text("Запрос дополнительного времени", color = Color.White, fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(15, 30, 45, 60).forEach { mins ->
              Button(
                onClick = { reqMinutes = mins },
                colors = ButtonDefaults.buttonColors(
                  containerColor = if (reqMinutes == mins) CyanLight else Slate800,
                  contentColor = if (reqMinutes == mins) Slate950 else Color.White
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp)
              ) {
                Text("$mins м", fontSize = 12.sp)
              }
            }
          }
          OutlinedTextField(
            value = reqReason,
            onValueChange = { reqReason = it },
            placeholder = { Text("Причина (напр. доделать уроки)", color = Slate400, fontSize = 12.sp) },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp)
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onRequestExtraTime(reqMinutes, reqReason.trim())
            showBonusRequestDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950)
        ) {
          Text("Отправить запрос")
        }
      },
      dismissButton = {
        TextButton(onClick = { showBonusRequestDialog = false }) {
          Text("Отмена", color = Slate400)
        }
      }
    )
  }
}
