package com.example.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Games
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.model.AppControlEntity
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
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun HeroBannerCard(
  childName: String,
  isLocked: Boolean,
  batteryPercent: Int,
  appEnergyPercent: Int = -1,
  /** Честный онлайн по PING/PONG, а не «batteryLevel > 0». */
  isOnline: Boolean = false,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("hero_banner_card"),
    shape = RoundedCornerShape(24.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = androidx.compose.foundation.BorderStroke(
      1.5.dp,
      Brush.linearGradient(
        colors = if (isLocked) {
          listOf(RoseLock, Color(0xFFFB7185), RoseContainer)
        } else {
          listOf(CyanLight, Color(0xFF6366F1), EmeraldShieldLight)
        }
      )
    )
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .height(186.dp)
    ) {
      // Generated high-quality hero illustration
      Image(
        painter = painterResource(id = R.drawable.hero_family_safety_1791372048354),
        contentDescription = "KidsGuard защита",
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth()
      )

      // Rich commercial gradient overlay for depth and crisp typography
      Box(
        modifier = Modifier
          .matchParentSize()
          .background(
            Brush.verticalGradient(
              colors = listOf(
                Color(0xFF090D16).copy(alpha = 0.25f),
                Color(0xFF0F172A).copy(alpha = 0.78f),
                Color(0xFF090D16).copy(alpha = 0.96f)
              )
            )
          )
      )

      // Top status pills row
      Row(
        modifier = Modifier
          .align(Alignment.TopStart)
          .fillMaxWidth()
          .padding(14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .weight(1f, fill = false)
            .clip(RoundedCornerShape(50))
            .background(
              Brush.horizontalGradient(
                colors = if (isLocked) {
                  listOf(RoseLock, Color(0xFFE11D48))
                } else {
                  listOf(EmeraldShield, Color(0xFF0D9488))
                }
              )
            )
            .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            Box(
              modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(Color.White)
            )
            Text(
              text = if (isLocked) "ДОСТУП ОГРАНИЧЕН" else "ЗАЩИТА АКТИВНА • LIVE",
              color = Color.White,
              fontSize = 10.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Box(
          modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color(0xFF0F172A).copy(alpha = 0.85f))
            .border(1.dp, CyanLight.copy(alpha = 0.4f), RoundedCornerShape(50))
            .padding(horizontal = 10.dp, vertical = 5.dp)
        ) {
          Text(
            text = when {
              !isOnline && batteryPercent !in 1..100 -> "🔴 Оффлайн • нет связи"
              !isOnline -> "🔴 Оффлайн • 🔋 $batteryPercent%"
              appEnergyPercent in 0..100 -> "🟢 Онлайн • 🔋 $batteryPercent% • прил. $appEnergyPercent%"
              batteryPercent in 1..100 -> "🟢 Онлайн • 🔋 $batteryPercent%"
              else -> "🟢 Онлайн"
            },
            color = if (isOnline) EmeraldShieldLight else AmberWarning,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }
      }

      Column(
        modifier = Modifier
          .align(Alignment.BottomStart)
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 14.dp)
      ) {
        Text(
          text = "KIDSGUARD PRO • СЕМЕЙНЫЙ ЦЕНТР",
          color = CyanLight,
          fontSize = 10.sp,
          fontWeight = FontWeight.ExtraBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = childName,
          color = Color.White,
          fontSize = 20.sp,
          fontWeight = FontWeight.ExtraBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = if (isLocked) "Экран устройства заблокирован родителем" else "Геолокация GPS, контроль времени и экран в реальном времени",
          color = Slate200,
          fontSize = 11.sp,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }
    }
  }
}

@Composable
fun CommercialQuickActionDock(
  isLocked: Boolean,
  onOpenLiveScreen: () -> Unit,
  onOpenMap: () -> Unit,
  onToggleLock: () -> Unit,
  onLoudSignal: () -> Unit,
  isSirenActive: Boolean = false,
  modifier: Modifier = Modifier
) {
  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(88.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically
  ) {
    QuickActionTile(
      title = "Экран LIVE",
      subtitle = "Трансляция",
      icon = Icons.Default.Videocam,
      gradientColors = listOf(Color(0xFF0284C7), Color(0xFF4F46E5)),
      onClick = onOpenLiveScreen,
      modifier = Modifier.weight(1f)
    )
    QuickActionTile(
      title = "GPS Карта",
      subtitle = "Локация",
      icon = Icons.Default.Language,
      gradientColors = listOf(Color(0xFF059669), Color(0xFF0D9488)),
      onClick = onOpenMap,
      modifier = Modifier.weight(1f)
    )
    QuickActionTile(
      title = if (isLocked) "Открыть" else "Блок",
      subtitle = if (isLocked) "Разблок." else "В 1 клик",
      icon = if (isLocked) Icons.Default.LockOpen else Icons.Default.Lock,
      gradientColors = if (isLocked) {
        listOf(Color(0xFF10B981), Color(0xFF059669))
      } else {
        listOf(Color(0xFFE11D48), Color(0xFF9F1239))
      },
      onClick = onToggleLock,
      modifier = Modifier.weight(1f)
    )
    QuickActionTile(
      title = if (isSirenActive) "Сирена ВКЛ" else "Сирена",
      subtitle = if (isSirenActive) "Выключить" else "Включить",
      icon = Icons.Default.PlayCircle,
      gradientColors = if (isSirenActive) {
        listOf(Color(0xFFDC2626), Color(0xFF991B1B))
      } else {
        listOf(Color(0xFFD97706), Color(0xFFEA580C))
      },
      onClick = onLoudSignal,
      modifier = Modifier.weight(1f)
    )
  }
}

@Composable
private fun QuickActionTile(
  title: String,
  subtitle: String,
  icon: ImageVector,
  gradientColors: List<Color>,
  onClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Card(
    modifier = modifier
      .height(88.dp)
      .clip(RoundedCornerShape(18.dp))
      .clickable { onClick() },
    shape = RoundedCornerShape(18.dp),
    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
  ) {
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .height(88.dp)
        .background(Brush.linearGradient(gradientColors))
        .padding(vertical = 10.dp, horizontal = 4.dp),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center
    ) {
      Box(
        modifier = Modifier
          .size(32.dp)
          .clip(CircleShape)
          .background(Color.White.copy(alpha = 0.2f)),
        contentAlignment = Alignment.Center
      ) {
        Icon(
          imageVector = icon,
          contentDescription = title,
          tint = Color.White,
          modifier = Modifier.size(17.dp)
        )
      }
      Spacer(modifier = Modifier.height(5.dp))
      Text(
        text = title,
        color = Color.White,
        fontWeight = FontWeight.ExtraBold,
        fontSize = 11.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
      )
      Text(
        text = subtitle,
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 9.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
      )
    }
  }
}

@Composable
fun CommercialKpiBentoGrid(
  usedMinutes: Int,
  dailyLimit: Int,
  blockedAppsCount: Int,
  totalAppsCount: Int,
  locationAddress: String,
  batteryPercent: Int,
  appEnergyPercent: Int = -1,
  onScreenTimeClick: () -> Unit,
  onAppsClick: () -> Unit,
  onMapClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  val remaining = (dailyLimit - usedMinutes).coerceAtLeast(0)
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(10.dp)
  ) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // Tile 1: Screen Time
      Card(
        modifier = Modifier
          .weight(1f)
          .height(106.dp)
          .clickable { onScreenTimeClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF131C31)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF38BDF8).copy(alpha = 0.35f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .padding(12.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF0EA5E9), Color(0xFF6366F1)))),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Timer, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Text("ЛИМИТ ДНЯ", color = CyanLight, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
          }
          Column {
            Text(
              text = "${remaining / 60}ч ${remaining % 60}м",
              color = Color.White,
              fontSize = 17.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "Потрачено: $usedMinutes мин",
              color = Slate400,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      // Tile 2: GPS Location
      Card(
        modifier = Modifier
          .weight(1f)
          .height(106.dp)
          .clickable { onMapClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF0F2427)),
        border = androidx.compose.foundation.BorderStroke(1.dp, EmeraldShieldLight.copy(alpha = 0.35f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .padding(12.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF10B981), Color(0xFF0D9488)))),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Language, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Text("ГЕОЛОКАЦИЯ", color = EmeraldShieldLight, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
          }
          val cleanTileAddr = if (com.example.location.LiveLocationTracker.isStaleOrDemoAddress(locationAddress)) {
            "Ожидание GPS..."
          } else {
            locationAddress.substringBefore(",").ifBlank { "GPS активен" }
          }
          Column {
            Text(
              text = cleanTileAddr,
              color = Color.White,
              fontSize = 15.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = if (cleanTileAddr == "Ожидание GPS...") "Открыть карту LIVE" else locationAddress,
              color = EmeraldShieldLight,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
      // Tile 3: Apps Control
      Card(
        modifier = Modifier
          .weight(1f)
          .height(106.dp)
          .clickable { onAppsClick() },
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1D1632)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFFA855F7).copy(alpha = 0.35f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .padding(12.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Color(0xFF8B5CF6), Color(0xFFEC4899)))),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Smartphone, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Text("ПРОГРАММЫ", color = Color(0xFFC084FC), fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
          }
          Column {
            Text(
              text = if (blockedAppsCount > 0) "$blockedAppsCount заблок." else "Все открыты",
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "Под контролем: $totalAppsCount прог.",
              color = Slate400,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }

      // Tile 4: Device Telemetry
      Card(
        modifier = Modifier
          .weight(1f)
          .height(106.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF231B12)),
        border = androidx.compose.foundation.BorderStroke(1.dp, AmberWarning.copy(alpha = 0.35f))
      ) {
        Column(
          modifier = Modifier
            .fillMaxWidth()
            .height(106.dp)
            .padding(12.dp),
          verticalArrangement = Arrangement.SpaceBetween
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Box(
              modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFF59E0B), Color(0xFFEA580C)))),
              contentAlignment = Alignment.Center
            ) {
              Icon(Icons.Default.Shield, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Text("СТАТУС СЕТИ", color = AmberWarning, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
          }
          Column {
            Text(
              text = if (batteryPercent in 1..100) "Заряд $batteryPercent%" else "Ожидание",
              color = Color.White,
              fontSize = 16.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = if (batteryPercent in 1..100) {
                if (appEnergyPercent in 0..100) "Приложение: $appEnergyPercent% расхода" else "Связь активна"
              } else {
                "Ждём телеметрию"
              },
              color = if (batteryPercent in 1..100) EmeraldShieldLight else AmberWarning,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }
  }
}

@Composable
fun AppItemCard(
  app: AppControlEntity,
  onToggleBlocked: () -> Unit,
  onUpdateLimit: (Int) -> Unit,
  isSelected: Boolean = false,
  onSelectToggle: (() -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var showLimitDialog by remember { mutableStateOf(false) }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .padding(vertical = 4.dp)
      .then(
        if (onSelectToggle != null) {
          Modifier.clickable { onSelectToggle() }
        } else {
          Modifier
        }
      )
      .testTag("app_item_${app.id}"),
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(
      containerColor = when {
        isSelected -> Color(0xFF162842)
        app.isBlocked -> Color(0xFF24141E)
        else -> Slate900
      }
    ),
    border = when {
      isSelected -> androidx.compose.foundation.BorderStroke(1.5.dp, CyanLight)
      app.isBlocked -> androidx.compose.foundation.BorderStroke(1.dp, RoseLock.copy(alpha = 0.4f))
      else -> null
    }
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        if (onSelectToggle != null) {
          Box(
            modifier = Modifier
              .size(22.dp)
              .clip(RoundedCornerShape(6.dp))
              .background(if (isSelected) CyanLight else Slate800)
              .border(1.dp, if (isSelected) CyanLight else Slate400, RoundedCornerShape(6.dp))
              .clickable { onSelectToggle() },
            contentAlignment = Alignment.Center
          ) {
            if (isSelected) {
              Icon(
                imageVector = Icons.Default.Check,
                contentDescription = "Выбрано",
                tint = Slate950,
                modifier = Modifier.size(15.dp)
              )
            }
          }
        }

        // App icon badge
        AppIconBadge(iconType = app.iconType, isBlocked = app.isBlocked)

        Column(modifier = Modifier.weight(1f)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.height(22.dp)
          ) {
            Text(
              text = app.appName,
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 15.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (app.isBlocked) "БЛОК" else "РАЗРЕШЕНО",
              color = if (app.isBlocked) RoseLock else EmeraldShieldLight,
              fontSize = 9.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              softWrap = false,
              modifier = Modifier
                .background(
                  if (app.isBlocked) RoseContainer else EmeraldContainer,
                  RoundedCornerShape(4.dp)
                )
                .padding(horizontal = 5.dp, vertical = 2.dp)
            )
            if (app.isEducational) {
              Spacer(modifier = Modifier.width(6.dp))
              Text(
                text = "Учеба",
                color = EmeraldShieldLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier
                  .background(EmeraldContainer, RoundedCornerShape(4.dp))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              )
            }
          }

          Text(
            text = "${app.category} • Сегодня: ${app.usedMinutesToday} мин",
            color = Slate400,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
          )
        }

        // Remote block switch
        Switch(
          checked = !app.isBlocked,
          onCheckedChange = { onToggleBlocked() },
          colors = SwitchDefaults.colors(
            checkedThumbColor = EmeraldShield,
            checkedTrackColor = EmeraldContainer,
            uncheckedThumbColor = RoseLock,
            uncheckedTrackColor = RoseContainer
          ),
          modifier = Modifier.testTag("toggle_app_${app.id}")
        )
      }

      Spacer(modifier = Modifier.height(10.dp))

      // Limits and usage bar
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        val limitText = if (app.dailyLimitMinutes == 0) "Без ограничений" else "Лимит: ${app.dailyLimitMinutes} мин"
        Text(
          text = limitText,
          color = if (app.dailyLimitMinutes > 0 && app.usedMinutesToday >= app.dailyLimitMinutes) RoseLock else Slate400,
          fontSize = 12.sp
        )

        TextButton(
          onClick = { showLimitDialog = true },
          modifier = Modifier.height(32.dp)
        ) {
          Icon(Icons.Default.Timer, contentDescription = null, modifier = Modifier.size(14.dp), tint = CyanLight)
          Spacer(modifier = Modifier.width(4.dp))
          Text("Настроить лимит", fontSize = 12.sp, color = CyanLight)
        }
      }

      if (app.dailyLimitMinutes > 0) {
        val progress = (app.usedMinutesToday.toFloat() / app.dailyLimitMinutes).coerceIn(0f, 1f)
        LinearProgressIndicator(
          progress = { progress },
          modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
          color = if (progress >= 1f) RoseLock else if (progress >= 0.8f) AmberWarning else CyanLight,
          trackColor = Slate800
        )
      }
    }
  }

  if (showLimitDialog) {
    AppLimitDialog(
      appName = app.appName,
      currentLimit = app.dailyLimitMinutes,
      onDismiss = { showLimitDialog = false },
      onConfirm = { minutes ->
        onUpdateLimit(minutes)
        showLimitDialog = false
      }
    )
  }
}

@Composable
fun AppIconBadge(iconType: String, isBlocked: Boolean) {
  val (icon, bg) = when (iconType) {
    "youtube" -> Icons.Default.Videocam to Color(0xFFE50914)
    "tiktok" -> Icons.Default.PlayCircle to Color(0xFF00F2FE)
    "roblox", "brawlstars" -> Icons.Default.Games to Color(0xFF8B5CF6)
    "telegram" -> Icons.Default.Send to Color(0xFF0088CC)
    "duolingo", "chess" -> Icons.Default.School to EmeraldShield
    "browser" -> Icons.Default.Language to CyanLight
    else -> Icons.Default.Smartphone to Slate700
  }

  Box(
    modifier = Modifier
      .size(44.dp)
      .clip(RoundedCornerShape(12.dp))
      .background(if (isBlocked) Slate800 else bg.copy(alpha = 0.2f)),
    contentAlignment = Alignment.Center
  ) {
    Icon(
      imageVector = if (isBlocked) Icons.Default.Block else icon,
      contentDescription = null,
      tint = if (isBlocked) RoseLock else bg,
      modifier = Modifier.size(24.dp)
    )
  }
}

@Composable
fun AppLimitDialog(
  appName: String,
  currentLimit: Int,
  onDismiss: () -> Unit,
  onConfirm: (Int) -> Unit
) {
  var sliderMinutes by remember { mutableFloatStateOf(currentLimit.toFloat().coerceIn(0f, 180f)) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Лимит для $appName", color = Color.White) },
    text = {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        val mins = sliderMinutes.toInt()
        Text(
          text = if (mins == 0) "Безлимитно" else "$mins мин / в день",
          fontSize = 22.sp,
          fontWeight = FontWeight.Bold,
          color = CyanLight,
          modifier = Modifier.padding(vertical = 12.dp)
        )

        Slider(
          value = sliderMinutes.coerceIn(0f, 180f),
          onValueChange = { sliderMinutes = it.coerceIn(0f, 180f) },
          valueRange = 0f..180f,
          steps = 11, // 0, 15, 30, 45, 60...
          colors = SliderDefaults.colors(
            thumbColor = CyanLight,
            activeTrackColor = CyanLight,
            inactiveTrackColor = Slate800
          )
        )

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween
        ) {
          Text("Без лимита", fontSize = 11.sp, color = Slate400)
          Text("1 час", fontSize = 11.sp, color = Slate400)
          Text("3 часа", fontSize = 11.sp, color = Slate400)
        }
      }
    },
    confirmButton = {
      Button(
        onClick = { onConfirm(sliderMinutes.toInt()) },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950)
      ) {
        Text("Сохранить")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена", color = Slate400)
      }
    },
    containerColor = Slate900
  )
}

@Composable
fun SecurityPinDialog(
  onDismiss: () -> Unit,
  onVerify: (String) -> Boolean
) {
  var pinInput by remember { mutableStateOf("") }
  var isError by remember { mutableStateOf(false) }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Lock, contentDescription = null, tint = CyanLight)
        Spacer(modifier = Modifier.width(8.dp))
        Text("Родительский PIN-код", color = Color.White)
      }
    },
    text = {
      Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
          text = "Для доступа к настройкам родителя введите PIN-код (по умолчанию 1234):",
          color = Slate400,
          fontSize = 14.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        OutlinedTextField(
          value = pinInput,
          onValueChange = {
            if (it.length <= 6) {
              pinInput = it
              isError = false
            }
          },
          visualTransformation = PasswordVisualTransformation(),
          singleLine = true,
          isError = isError,
          modifier = Modifier
            .fillMaxWidth()
            .testTag("pin_input_field"),
          placeholder = { Text("••••") }
        )

        if (isError) {
          Text(
            text = "Неверный PIN-код!",
            color = RoseLock,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp)
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (!onVerify(pinInput)) {
            isError = true
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        modifier = Modifier.testTag("verify_pin_button")
      ) {
        Text("Войти")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена", color = Slate400)
      }
    },
    containerColor = Slate900
  )
}
