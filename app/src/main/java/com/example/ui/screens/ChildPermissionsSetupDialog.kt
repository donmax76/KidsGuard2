package com.example.ui.screens

import android.Manifest
import android.app.Activity
import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.QueryStats
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.service.KidsGuardBackgroundService
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.util.ApkUpdateInstaller
import com.example.util.DeviceSecurityPermissionHelper
import com.example.util.ScreenShotManager
import kotlinx.coroutines.launch

@Composable
fun ChildPermissionsSetupDialog(
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  // Manage permission setup flag so accessibility service never intercepts permission screens
  DisposableEffect(Unit) {
    DeviceSecurityPermissionHelper.isSettingUpPermissions = true
    onDispose {
      DeviceSecurityPermissionHelper.isSettingUpPermissions = false
    }
  }

  val isMediaProjectionActive by ScreenShotManager.isMediaProjectionActive.collectAsState()
  var isLiveStreamPersisted by remember { mutableStateOf(ScreenShotManager.isLiveStreamEnabled(context)) }
  var isLocationGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.hasLocationPermission(context)) }
  var isBgLocationGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.hasBackgroundLocationPermission(context)) }
  var isInstallPackagesGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.canInstallUnknownApps(context)) }
  var isAccessibilityGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.isAccessibilityServiceEnabled(context)) }
  var isOverlayGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.isOverlayPermissionGranted(context)) }
  var isUsageStatsGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.hasUsageStatsPermission(context)) }
  var isBatteryIgnored by remember { mutableStateOf(DeviceSecurityPermissionHelper.isBatteryOptimizationIgnored(context)) }
  var isDeviceAdminGranted by remember { mutableStateOf(DeviceSecurityPermissionHelper.isDeviceAdminActive(context)) }

  val installerProgress by ApkUpdateInstaller.updateProgress.collectAsState()
  val installerStatusText by ApkUpdateInstaller.updateStatusText.collectAsState()

  fun refreshStatuses() {
    isLiveStreamPersisted = ScreenShotManager.isLiveStreamEnabled(context)
    isLocationGranted = DeviceSecurityPermissionHelper.hasLocationPermission(context)
    isBgLocationGranted = DeviceSecurityPermissionHelper.hasBackgroundLocationPermission(context)
    isInstallPackagesGranted = DeviceSecurityPermissionHelper.canInstallUnknownApps(context)
    isAccessibilityGranted = DeviceSecurityPermissionHelper.isAccessibilityServiceEnabled(context)
    isOverlayGranted = DeviceSecurityPermissionHelper.isOverlayPermissionGranted(context)
    isUsageStatsGranted = DeviceSecurityPermissionHelper.hasUsageStatsPermission(context)
    isBatteryIgnored = DeviceSecurityPermissionHelper.isBatteryOptimizationIgnored(context)
    isDeviceAdminGranted = DeviceSecurityPermissionHelper.isDeviceAdminActive(context)
  }

  // Launcher for MediaProjection (Real-Time Screen Streaming & Video Capture)
  val mediaProjectionLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.StartActivityForResult()
  ) { result ->
    ScreenShotManager.setLiveStreamEnabled(context, true)
    isLiveStreamPersisted = true
    if (result.resultCode == Activity.RESULT_OK && result.data != null) {
      ScreenShotManager.onPermissionGrantedFromUi(context, result.resultCode, result.data!!)
      KidsGuardBackgroundService.startWithMediaProjection(context, result.resultCode, result.data!!)
      // Короткое окно (90 с) — только чтобы показать первый кадр родителю.
      // Постоянная трансляция НЕ включается: экран передаётся по запросу и гаснет
      // сразу после просмотра, иначе телефон ребёнка разряжается за считаные часы.
      com.example.service.KidsGuardAccessibilityService.activateLiveStreamingWindow(90_000L)
      Toast.makeText(context, "Живая трансляция экрана разрешена и сохранена! Показ — по запросу родителя.", Toast.LENGTH_SHORT).show()
    } else {
      KidsGuardBackgroundService.start(context, enableProjectionFgs = true)
      Toast.makeText(context, "Разрешение на трансляцию не выдано — экран будет недоступен для просмотра", Toast.LENGTH_SHORT).show()
    }
    refreshStatuses()
  }

  // Launcher for Background Location (Android 10+)
  val bgLocationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestPermission()
  ) { granted ->
    refreshStatuses()
    KidsGuardBackgroundService.start(context)
    if (!granted) {
      DeviceSecurityPermissionHelper.openAppLocationSettings(context)
    }
  }

  // Launcher for Foreground Location (GPS)
  val locationLauncher = rememberLauncherForActivityResult(
    contract = ActivityResultContracts.RequestMultiplePermissions()
  ) { perms ->
    val granted = perms[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
        perms[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    refreshStatuses()
    if (granted) {
      KidsGuardBackgroundService.start(context)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !DeviceSecurityPermissionHelper.hasBackgroundLocationPermission(context)) {
        bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
      } else {
        Toast.makeText(context, "Точная геолокация GPS включена!", Toast.LENGTH_SHORT).show()
      }
    } else {
      DeviceSecurityPermissionHelper.openAppLocationSettings(context)
    }
  }

  // Periodic refresh when user returns from settings
  LaunchedEffect(Unit) {
    while (true) {
      kotlinx.coroutines.delay(1000)
      refreshStatuses()
    }
  }

  AlertDialog(
    onDismissRequest = {
      DeviceSecurityPermissionHelper.isSettingUpPermissions = false
      onDismiss()
    },
    containerColor = Slate950,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Shield,
            contentDescription = null,
            tint = CyanLight,
            modifier = Modifier.size(24.dp)
          )
          Text(
            text = "Все разрешения и опции",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
          )
        }
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        Text(
          text = "Включите все пункты для прямой трансляции экрана, GPS-геоданных и обновлений без ограничений:",
          color = Slate400,
          fontSize = 11.sp
        )

        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .height(415.dp),
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          // SAMSUNG ONE UI / ANDROID 13-15 RESTRICTED SETTINGS UNLOCKER CARD
          if (!isAccessibilityGranted || !isUsageStatsGranted) {
            item {
              Card(
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF231815)),
                border = androidx.compose.foundation.BorderStroke(1.5.dp, Color(0xFFF59E0B)),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(12.dp)) {
                  Text(
                    text = "⚠️ Телефон Samsung блокирует Спец. возможности или Статистику?",
                    color = Color(0xFFFBBF24),
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 12.sp
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(
                    text = "На Samsung (One UI / Android 13–15) действует защита «Ограниченные настройки» и «Автоблокировщик». Чтобы мгновенно разблокировать тумблеры:",
                    color = Slate200,
                    fontSize = 10.sp,
                    lineHeight = 13.sp
                  )
                  Spacer(modifier = Modifier.height(8.dp))

                  OutlinedButton(
                    onClick = {
                      DeviceSecurityPermissionHelper.openAppInfoForRestrictedSettings(context)
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text("Способ 1: Открыть «О приложении» (Нажмите ⋮ вверху → Разрешить ограниченные настройки)", fontSize = 10.sp, color = CyanLight, fontWeight = FontWeight.Bold)
                  }

                  Spacer(modifier = Modifier.height(6.dp))

                  OutlinedButton(
                    onClick = {
                      DeviceSecurityPermissionHelper.openSamsungAutoBlockerSettings(context)
                    },
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                  ) {
                    Text("Способ 3: Отключить «Автоблокировщик» Samsung", fontSize = 10.sp, color = Slate200)
                  }
                }
              }
            }
          }

          // 1. Accessibility Service (Recommended for App Control & Screen Snapshots)
          item {
            PermissionItemCard(
              title = "1. Спец. возможности и мониторинг 24/7",
              description = "Контроль запущенных программ, лимитов времени и снимков экрана через службу доступности Android",
              isGranted = isAccessibilityGranted,
              icon = Icons.Default.Security,
              onClick = {
                DeviceSecurityPermissionHelper.requestAccessibilityPermission(context)
              }
            )
          }

          // 2. GPS Location + Background Location
          item {
            PermissionItemCard(
              title = "2. Точная геолокация GPS + Сеть 24/7",
              description = if (isLocationGranted && !isBgLocationGranted)
                "Геоданные включены. Нажмите, чтобы разрешить «В любом режиме»"
              else
                "Передача точного местоположения ребенка (улица и дом) на карту родителя",
              isGranted = isLocationGranted && isBgLocationGranted,
              icon = Icons.Default.MyLocation,
              onClick = {
                if (!isLocationGranted) {
                  locationLauncher.launch(
                    arrayOf(
                      Manifest.permission.ACCESS_FINE_LOCATION,
                      Manifest.permission.ACCESS_COARSE_LOCATION
                    )
                  )
                } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && !isBgLocationGranted) {
                  bgLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                } else {
                  DeviceSecurityPermissionHelper.openAppLocationSettings(context)
                }
              }
            )
          }

          // 3. Unrestricted APK Updates (REQUEST_INSTALL_PACKAGES)
          item {
            PermissionItemCard(
              title = "3. Обновление без ограничений",
              description = "Разрешает установку новых версий KidsGuard в 1 клик и из облака",
              isGranted = isInstallPackagesGranted,
              icon = Icons.Default.SystemUpdate,
              onClick = {
                DeviceSecurityPermissionHelper.requestInstallUnknownAppsPermission(context)
              }
            )
          }

          // 4. Live Screen Sharing with System Indicator (MediaProjection + Persistent Background Stream)
          item {
            val isLiveGranted = isMediaProjectionActive || isLiveStreamPersisted
            PermissionItemCard(
              title = "4. Живая трансляция экрана (LIVE с индикацией)",
              description = if (isLiveGranted)
                "Разрешение сохранено. Экран передаётся только по запросу родителя — в фоне батарея не тратится"
              else
                "Нажмите и выберите «Начать» в системном окне Android для живого показа и записи экрана",
              isGranted = isLiveGranted,
              icon = Icons.Default.Videocam,
              onClick = {
                try {
                  ScreenShotManager.setLiveStreamEnabled(context, true)
                  isLiveStreamPersisted = true
                  KidsGuardBackgroundService.start(context, enableProjectionFgs = true)
                  com.example.service.KidsGuardAccessibilityService.activateLiveStreamingWindow(90_000L)
                  val mpm = context.getSystemService(android.content.Context.MEDIA_PROJECTION_SERVICE) as? android.media.projection.MediaProjectionManager
                  if (mpm != null) {
                    mediaProjectionLauncher.launch(mpm.createScreenCaptureIntent())
                  }
                } catch (e: Exception) {
                  Toast.makeText(context, "Ошибка запуска трансляции: ${e.message}", Toast.LENGTH_SHORT).show()
                }
              }
            )
          }

          // 5. Usage Stats
          item {
            PermissionItemCard(
              title = "5. Доступ к статистике использования",
              description = "Мгновенно определяет открытую программу на экране ребёнка",
              isGranted = isUsageStatsGranted,
              icon = Icons.Default.QueryStats,
              onClick = {
                DeviceSecurityPermissionHelper.requestUsageStatsPermission(context)
              }
            )
          }

          // 6. Overlay Permission
          item {
            PermissionItemCard(
              title = "6. Поверх других окон",
              description = "Полноэкранный экран блокировки телефона",
              isGranted = isOverlayGranted,
              icon = Icons.Default.Layers,
              onClick = {
                DeviceSecurityPermissionHelper.requestOverlayPermission(context)
              }
            )
          }

          // 7. Device Admin
          item {
            PermissionItemCard(
              title = "7. Администратор устройства",
              description = "Защита от удаления и аппаратная блокировка",
              isGranted = isDeviceAdminGranted,
              icon = Icons.Default.Lock,
              onClick = {
                DeviceSecurityPermissionHelper.requestDeviceAdmin(context)
              }
            )
          }

          // 8. Battery Optimization
          item {
            PermissionItemCard(
              title = "8. Фоновая работа 24/7",
              description = "Отключает экономию заряда для бесперебойной связи",
              isGranted = isBatteryIgnored,
              icon = Icons.Default.BatteryChargingFull,
              onClick = {
                DeviceSecurityPermissionHelper.requestIgnoreBatteryOptimization(context)
              }
            )
          }

          // Direct Manual Update Card inside Permissions Dialog
          item {
            val activeCoreVer by com.example.util.CoreEngineHotUpdater.activeCoreVersion.collectAsState()
            val installedVer = activeCoreVer.ifBlank { com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(context) }
            val targetVer = ApkUpdateInstaller.CURRENT_APP_VERSION
            val isAlreadyLatest = ApkUpdateInstaller.compareVersions(installedVer, targetVer) >= 0
            Card(
              shape = RoundedCornerShape(12.dp),
              colors = CardDefaults.cardColors(containerColor = Slate900),
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (isAlreadyLatest) EmeraldShield.copy(alpha = 0.5f) else CyanLight.copy(alpha = 0.5f)
              ),
              modifier = Modifier.fillMaxWidth()
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text(
                  text = if (isAlreadyLatest) {
                    "✓ Установлена актуальная версия (v$installedVer)"
                  } else {
                    "Доступно обновление до v$targetVer (текущая: v$installedVer)"
                  },
                  color = if (isAlreadyLatest) EmeraldShieldLight else Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                if (installerProgress != null) {
                  LinearProgressIndicator(
                    progress = { (installerProgress ?: 0) / 100f },
                    color = CyanLight,
                    trackColor = Slate800,
                    modifier = Modifier.fillMaxWidth().height(6.dp)
                  )
                  Spacer(modifier = Modifier.height(4.dp))
                  Text(installerStatusText ?: "Скачивание пакета: $installerProgress%...", color = CyanLight, fontSize = 11.sp)
                } else {
                  if (installerStatusText != null) {
                    Text(installerStatusText ?: "", color = CyanLight, fontSize = 11.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                  }
                  if (!isAlreadyLatest) {
                    Button(
                      onClick = {
                        ApkUpdateInstaller.downloadAndInstallLatestApk(context)
                      },
                      colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
                      shape = RoundedCornerShape(8.dp),
                      modifier = Modifier.fillMaxWidth().height(34.dp)
                    ) {
                      Icon(Icons.Default.SystemUpdate, contentDescription = null, modifier = Modifier.size(16.dp))
                      Spacer(modifier = Modifier.width(6.dp))
                      Text("Скачать и установить v$targetVer сейчас", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                  } else {
                    Text(
                      text = "У вас уже установлена последняя версия приложения. Обновление не требуется.",
                      color = Slate400,
                      fontSize = 11.sp
                    )
                  }
                }
              }
            }
          }
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          DeviceSecurityPermissionHelper.isSettingUpPermissions = false
          onDismiss()
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Готово", fontWeight = FontWeight.Bold)
      }
    }
  )
}

@Composable
private fun PermissionItemCard(
  title: String,
  description: String,
  isGranted: Boolean,
  icon: ImageVector,
  onClick: () -> Unit
) {
  Card(
    shape = RoundedCornerShape(12.dp),
    colors = CardDefaults.cardColors(
      containerColor = if (isGranted) Color(0xFF064E3B).copy(alpha = 0.4f) else Slate900
    ),
    border = androidx.compose.foundation.BorderStroke(
      1.dp,
      if (isGranted) EmeraldShield.copy(alpha = 0.6f) else Slate800
    ),
    modifier = Modifier
      .fillMaxWidth()
      .height(84.dp)
  ) {
    Row(
      modifier = Modifier
        .fillMaxSize()
        .padding(horizontal = 12.dp, vertical = 10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
          .weight(1f)
          .padding(end = 8.dp)
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (isGranted) EmeraldShield.copy(alpha = 0.2f) else Color(0xFF1E293B)),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (isGranted) EmeraldShieldLight else Slate400,
            modifier = Modifier.size(18.dp)
          )
        }

        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = title,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
          Text(
            text = description,
            color = Slate400,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            maxLines = 2,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
      }

      if (isGranted) {
        Box(
          modifier = Modifier
            .width(86.dp)
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF065F46)),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "АКТИВНО",
            color = EmeraldShieldLight,
            fontWeight = FontWeight.Bold,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
      } else {
        Button(
          onClick = onClick,
          colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
          shape = RoundedCornerShape(8.dp),
          contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 4.dp),
          modifier = Modifier
            .width(86.dp)
            .height(34.dp)
        ) {
          Text(
            text = "Включить",
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }
      }
    }
  }
}
