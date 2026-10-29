package com.example.ui.screens

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.util.DeviceSecurityPermissionHelper
import kotlinx.coroutines.delay

@Composable
fun ChildPairingScreen(
  onPairWithCode: (code: String, onResult: (Boolean, String) -> Unit) -> Unit,
  onCancel: () -> Unit,
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  var codeField by remember { mutableStateOf(TextFieldValue("")) }
  var errorMessage by remember { mutableStateOf<String?>(null) }
  var isSuccess by remember { mutableStateOf(false) }
  var successText by remember { mutableStateOf("") }
  val focusManager = LocalFocusManager.current

  // Real-time system permission state on Child's phone
  var canInstallUnknownApps by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.canInstallUnknownApps(context))
  }
  var isAccessibilityEnabled by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.isAccessibilityServiceEnabled(context))
  }
  var isDeviceAdminActive by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.isDeviceAdminActive(context))
  }
  var isOverlayGranted by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.isOverlayPermissionGranted(context))
  }
  var hasUsageStats by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.hasUsageStatsPermission(context))
  }
  var hasAllLocation by remember {
    mutableStateOf(
      DeviceSecurityPermissionHelper.hasLocationPermission(context) &&
        DeviceSecurityPermissionHelper.hasBackgroundLocationPermission(context)
    )
  }
  var isBatteryIgnored by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.isBatteryOptimizationIgnored(context))
  }
  var autoConfirmSilentUpdates by remember {
    mutableStateOf(DeviceSecurityPermissionHelper.autoConfirmUpdatesViaAccessibility)
  }

  LaunchedEffect(Unit) {
    DirectP2pSocketEngine.requestPairingCodeDiscovery("")
    while (true) {
      canInstallUnknownApps = DeviceSecurityPermissionHelper.canInstallUnknownApps(context)
      isAccessibilityEnabled = DeviceSecurityPermissionHelper.isAccessibilityServiceEnabled(context)
      isDeviceAdminActive = DeviceSecurityPermissionHelper.isDeviceAdminActive(context)
      isOverlayGranted = DeviceSecurityPermissionHelper.isOverlayPermissionGranted(context)
      hasUsageStats = DeviceSecurityPermissionHelper.hasUsageStatsPermission(context)
      hasAllLocation = DeviceSecurityPermissionHelper.hasLocationPermission(context) &&
        DeviceSecurityPermissionHelper.hasBackgroundLocationPermission(context)
      isBatteryIgnored = DeviceSecurityPermissionHelper.isBatteryOptimizationIgnored(context)
      delay(1200L)
    }
  }

  fun formatPairingCode(raw: String): String {
    val digits = raw.filter { it.isLetterOrDigit() }.uppercase().take(6)
    return if (digits.length > 3) {
      "${digits.take(3)}-${digits.drop(3).take(3)}"
    } else {
      digits
    }
  }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(Slate950)
      .padding(horizontal = 20.dp, vertical = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Header
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
      ) {
        IconButton(onClick = onCancel) {
          Icon(Icons.Default.ArrowBack, contentDescription = "Назад", tint = Slate400)
        }
        Spacer(modifier = Modifier.weight(1f))
      }

      Box(
        modifier = Modifier
          .size(64.dp)
          .clip(CircleShape)
          .background(EmeraldContainer),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          Icons.Default.PhoneAndroid,
          contentDescription = null,
          tint = EmeraldShieldLight,
          modifier = Modifier.size(34.dp)
        )
      }
      Spacer(modifier = Modifier.height(12.dp))
      Text(
        text = "Первичная настройка телефона ребёнка",
        fontSize = 21.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        textAlign = TextAlign.Center
      )
      Spacer(modifier = Modifier.height(4.dp))
      Text(
        text = "Один раз выдайте системные разрешения ниже и введите 6-значный код родителя — все привилегии сохранятся в памяти даже после скрытых удалённых автообновлений",
        color = Slate400,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
      )
    }

    // Code Input Card
    item {
      Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.3f)),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Text(
            text = "КОД СОПРЯЖЕНИЯ С РОДИТЕЛЕМ",
            color = Slate400,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
          )
          Spacer(modifier = Modifier.height(12.dp))

          OutlinedTextField(
            value = codeField,
            onValueChange = { input ->
              val cleaned = input.text.filter { it.isLetterOrDigit() }.uppercase().take(6)
              val formatted = formatPairingCode(cleaned)
              codeField = TextFieldValue(
                text = formatted,
                selection = TextRange(formatted.length)
              )
              errorMessage = null
            },
            placeholder = {
              Text(
                "000-000",
                color = Slate400.copy(alpha = 0.5f),
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center,
                fontSize = 28.sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.ExtraBold
              )
            },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(
              color = CyanLight,
              fontSize = 28.sp,
              fontWeight = FontWeight.ExtraBold,
              textAlign = TextAlign.Center,
              fontFamily = FontFamily.Monospace,
              letterSpacing = 4.sp
            ),
            keyboardOptions = KeyboardOptions(
              keyboardType = KeyboardType.Number,
              imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
              onDone = { focusManager.clearFocus() }
            ),
            colors = OutlinedTextFieldDefaults.colors(
              focusedBorderColor = CyanLight,
              unfocusedBorderColor = Slate800,
              focusedContainerColor = Slate950,
              unfocusedContainerColor = Slate950
            ),
            shape = RoundedCornerShape(14.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("pairing_code_input"),
            isError = errorMessage != null
          )

          if (errorMessage != null) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = errorMessage ?: "",
              color = RoseLock,
              fontSize = 12.sp,
              textAlign = TextAlign.Center,
              fontWeight = FontWeight.SemiBold
            )
          }

          Spacer(modifier = Modifier.height(14.dp))
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Icon(Icons.Default.Info, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
            Text(
              text = "Код можно узнать на телефоне родителя (иконка QR рядом с именем ребенка)",
              color = Slate400,
              fontSize = 11.sp
            )
          }
        }
      }
    }

    // 1. CRITICAL SILENT REMOTE UPDATE PERMISSION CARD (Установка неизвестных приложений + Автоклик)
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.5.dp,
          if (canInstallUnknownApps && isAccessibilityEnabled) EmeraldShield else AmberWarning
        ),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(
              Icons.Default.SystemUpdate,
              contentDescription = null,
              tint = if (canInstallUnknownApps && isAccessibilityEnabled) EmeraldShieldLight else AmberWarning
            )
            Text(
              "1. Разрешения для скрытого автообновления от родителя",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp
            )
          }
          Spacer(modifier = Modifier.height(6.dp))
          Text(
            text = "Обязательно включите оба пункта ниже при первой установке: тогда родитель сможет удалённо обновлять программу ребёнка в фоне без участия ребёнка, а все разрешения сохранятся автоматически.",
            color = Slate200,
            fontSize = 11.sp,
            lineHeight = 15.sp
          )
          Spacer(modifier = Modifier.height(10.dp))

          RealPermissionActionRow(
            stepTitle = "Установка неизвестных приложений (APK)",
            stepSubtitle = if (canInstallUnknownApps) {
              "✓ Разрешено: приём пакетов обновления от родителя активен"
            } else {
              "Нажмите «Включить» и переведите тумблер «Разрешить из этого источника»"
            },
            isGranted = canInstallUnknownApps,
            buttonText = if (canInstallUnknownApps) "Готово" else "Включить",
            onClick = { DeviceSecurityPermissionHelper.requestInstallUnknownAppsPermission(context) }
          )

          Spacer(modifier = Modifier.height(8.dp))

          // Samsung / Android 13-15 Restricted Settings helper
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !isAccessibilityEnabled) {
            Box(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFF281A0B))
                .border(1.dp, AmberWarning.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                .padding(10.dp)
            ) {
              Column {
                Text(
                  text = "💡 Если на Samsung / Android 13–15 пункт «Специальные возможности» серый (неактивен):",
                  color = AmberWarning,
                  fontWeight = FontWeight.Bold,
                  fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                  text = "Нажмите кнопку ниже → в правом верхнем углу экрана «О приложении» нажмите ⋮ (три точки) → «Разрешить ограниченные настройки».",
                  color = Slate200,
                  fontSize = 10.sp
                )
                Spacer(modifier = Modifier.height(6.dp))
                OutlinedButton(
                  onClick = { DeviceSecurityPermissionHelper.openAppInfoForRestrictedSettings(context) },
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.fillMaxWidth().height(34.dp)
                ) {
                  Text("Открыть «О приложении» (⋮ → Разрешить ограниченные настройки)", fontSize = 10.sp, color = AmberWarning)
                }
              }
            }
            Spacer(modifier = Modifier.height(8.dp))
          }

          RealPermissionActionRow(
            stepTitle = "Служба Accessibility (Автоклик «Обновить» и защита)",
            stepSubtitle = if (isAccessibilityEnabled) {
              "✓ Включено: сама нажимает «Обновить» и «Готово» в скрытом режиме"
            } else {
              "Установленные приложения → KidsGuard → Включить"
            },
            isGranted = isAccessibilityEnabled,
            buttonText = if (isAccessibilityEnabled) "Готово" else "Включить",
            onClick = { DeviceSecurityPermissionHelper.requestAccessibilityPermission(context) }
          )

          Spacer(modifier = Modifier.height(8.dp))

          Row(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(10.dp))
              .background(Slate950)
              .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                "Автоматически подтверждать скрытые обновления",
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
              )
              Text(
                "Служба Accessibility сама нажмёт «Обновить» и свернёт окно без участия ребёнка",
                color = Slate400,
                fontSize = 10.sp
              )
            }
            Switch(
              checked = autoConfirmSilentUpdates,
              onCheckedChange = {
                autoConfirmSilentUpdates = it
                DeviceSecurityPermissionHelper.autoConfirmUpdatesViaAccessibility = it
              },
              colors = SwitchDefaults.colors(
                checkedThumbColor = EmeraldShield,
                checkedTrackColor = EmeraldContainer
              )
            )
          }
        }
      }
    }

    // 2. Full System Permissions & Anti-Uninstall Checklist
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        modifier = Modifier.fillMaxWidth()
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = CyanLight)
            Text(
              "2. Системные привилегии защиты (сохраняются навсегда)",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp
            )
          }
          Text(
            text = "Нажмите на каждую кнопку ниже, чтобы выдать привилегии один раз:",
            color = Slate400,
            fontSize = 11.sp
          )

          RealPermissionActionRow(
            stepTitle = "Защита от удаления (Администратор устройства)",
            stepSubtitle = if (isDeviceAdminActive) "✓ Защита от удаления активна" else "Запрещает ребёнку удалить KidsGuard",
            isGranted = isDeviceAdminActive,
            buttonText = if (isDeviceAdminActive) "Готово" else "Активировать",
            onClick = { DeviceSecurityPermissionHelper.requestDeviceAdmin(context) }
          )

          RealPermissionActionRow(
            stepTitle = "Поверх других окон (Блокировка экрана)",
            stepSubtitle = if (isOverlayGranted) "✓ Разрешено" else "Для мгновенного экрана блокировки",
            isGranted = isOverlayGranted,
            buttonText = if (isOverlayGranted) "Готово" else "Разрешить",
            onClick = { DeviceSecurityPermissionHelper.requestOverlayPermission(context) }
          )

          RealPermissionActionRow(
            stepTitle = "Статистика использования (Экранное время)",
            stepSubtitle = if (hasUsageStats) "✓ Разрешено" else "Точный учёт открытых программ и минут",
            isGranted = hasUsageStats,
            buttonText = if (hasUsageStats) "Готово" else "Разрешить",
            onClick = { DeviceSecurityPermissionHelper.requestUsageStatsPermission(context) }
          )

          RealPermissionActionRow(
            stepTitle = "Фоновая геолокация GPS («Разрешить в любом режиме»)",
            stepSubtitle = if (hasAllLocation) "✓ GPS в любом режиме активен" else "Разрешения → Местоположение → В любом режиме",
            isGranted = hasAllLocation,
            buttonText = if (hasAllLocation) "Готово" else "Настроить GPS",
            onClick = { DeviceSecurityPermissionHelper.openAppLocationSettings(context) }
          )

          RealPermissionActionRow(
            stepTitle = "Работа в фоне 24/7 без экономии батареи",
            stepSubtitle = if (isBatteryIgnored) "✓ Без ограничений батареи" else "Чтобы система не закрывала защиту в фоне",
            isGranted = isBatteryIgnored,
            buttonText = if (isBatteryIgnored) "Готово" else "Отключить сон",
            onClick = { DeviceSecurityPermissionHelper.requestIgnoreBatteryOptimization(context) }
          )
        }
      }
    }

    // Confirm button
    item {
      Button(
        onClick = {
          val rawCode = codeField.text
          val cleanCode = rawCode.trim().replace(" ", "").replace("-", "")
          if (cleanCode.isBlank()) {
            errorMessage = "Пожалуйста, введите код сопряжения из профиля ребенка"
            return@Button
          }
          onPairWithCode(rawCode) { success, msg ->
            if (success) {
              isSuccess = true
              successText = msg
            } else {
              errorMessage = msg
            }
          }
        },
        colors = ButtonDefaults.buttonColors(
          containerColor = EmeraldShield,
          contentColor = Color.Black
        ),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
          .fillMaxWidth()
          .height(52.dp)
          .testTag("submit_pairing_button")
      ) {
        Icon(Icons.Default.Check, contentDescription = null)
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          "ПОДКЛЮЧИТЬ И АКТИВИРОВАТЬ ЗАЩИТУ",
          fontWeight = FontWeight.Bold,
          fontSize = 13.sp
        )
      }
      Spacer(modifier = Modifier.height(8.dp))
      OutlinedButton(
        onClick = onCancel,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
      ) {
        Text("Назад к выбору роли", color = Slate400)
      }
      Spacer(modifier = Modifier.height(20.dp))
    }
  }
}

@Composable
private fun RealPermissionActionRow(
  stepTitle: String,
  stepSubtitle: String,
  isGranted: Boolean,
  buttonText: String,
  onClick: () -> Unit
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .background(if (isGranted) Color(0xFF0D231E) else Slate950)
      .border(
        1.dp,
        if (isGranted) EmeraldShield.copy(alpha = 0.5f) else Slate800,
        RoundedCornerShape(12.dp)
      )
      .padding(horizontal = 12.dp, vertical = 10.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f)
    ) {
      Icon(
        imageVector = if (isGranted) Icons.Default.CheckCircle else Icons.Default.Warning,
        contentDescription = null,
        tint = if (isGranted) EmeraldShieldLight else AmberWarning,
        modifier = Modifier.size(18.dp)
      )
      Column(modifier = Modifier.weight(1f)) {
        Text(stepTitle, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 12.sp)
        Text(
          stepSubtitle,
          color = if (isGranted) EmeraldShieldLight else Slate400,
          fontSize = 10.sp
        )
      }
    }
    Spacer(modifier = Modifier.width(8.dp))
    Button(
      onClick = onClick,
      colors = ButtonDefaults.buttonColors(
        containerColor = if (isGranted) EmeraldContainer else CyanLight,
        contentColor = if (isGranted) EmeraldShieldLight else Color.Black
      ),
      shape = RoundedCornerShape(8.dp),
      contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
      modifier = Modifier.height(32.dp)
    ) {
      Text(buttonText, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
  }
}
