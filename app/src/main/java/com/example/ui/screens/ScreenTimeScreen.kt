package com.example.ui.screens

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
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
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

@Composable
fun ScreenTimeScreen(
  child: ChildProfileEntity?,
  apps: List<AppControlEntity>,
  alerts: List<AlertNotificationEntity>,
  onSetDailyLimit: (Int) -> Unit,
  onGrantBonusTime: (Int) -> Unit,
  onDismissAlert: (Long) -> Unit,
  modifier: Modifier = Modifier
) {
  val currentLimit = child?.dailyScreenLimitMinutes ?: 150
  val usedMinutes = child?.usedScreenTimeMinutes ?: 0
  val childName = child?.name ?: "Ребенок"

  var sliderMinutes by remember(currentLimit) {
    mutableFloatStateOf(currentLimit.coerceIn(30, 360).toFloat())
  }
  var bedtimeEnabled by remember { mutableStateOf(child?.bedtimeEnabled ?: true) }

  val bonusRequests = alerts.filter { it.type == "BONUS_REQUEST" && !it.isRead }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(8.dp))
      Text(
        text = if (child != null) "Экранное время: $childName" else "Экранное время и расписание",
        color = Color.White,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold
      )
      Text(
        text = if (child != null) "Управление лимитом и расписанием сна для «$childName»" else "Выберите ребенка на главной вкладке, чтобы настроить его персональный лимит.",
        color = Slate400,
        fontSize = 13.sp
      )
    }

    // Pending Child Bonus Requests
    if (bonusRequests.isNotEmpty()) {
      item {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = CyanContainer),
          border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight)
        ) {
          Column(modifier = Modifier.padding(16.dp)) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(Icons.Default.NotificationsActive, contentDescription = null, tint = CyanLight)
              Text("Запрос от ребенка на доп. время", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }

            Spacer(modifier = Modifier.height(6.dp))

            bonusRequests.forEach { req ->
              Text(text = req.message, color = Slate200, fontSize = 13.sp)

              Spacer(modifier = Modifier.height(10.dp))

              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = {
                    val requestedMins = req.extraPayload.toIntOrNull() ?: 15
                    onGrantBonusTime(requestedMins)
                    onDismissAlert(req.id)
                  },
                  colors = ButtonDefaults.buttonColors(containerColor = EmeraldShield, contentColor = Color.Black),
                  shape = RoundedCornerShape(8.dp)
                ) {
                  Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Одобрить", fontWeight = FontWeight.Bold)
                }

                OutlinedButton(
                  onClick = { onDismissAlert(req.id) },
                  shape = RoundedCornerShape(8.dp)
                ) {
                  Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp), tint = Slate400)
                  Spacer(modifier = Modifier.width(4.dp))
                  Text("Отклонить", color = Slate400)
                }
              }
            }
          }
        }
      }
    }

    // Real-Time Remaining Time & Live Countdown Panel
    item {
      val effectiveLimit = sliderMinutes.toInt()
      val remainingMinutes = (effectiveLimit - usedMinutes).coerceAtLeast(0)
      val liveSecondsTick = 0

      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(
          1.2.dp,
          if (remainingMinutes <= 15) AmberWarning.copy(alpha = 0.7f) else EmeraldShield.copy(alpha = 0.5f)
        )
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(if (remainingMinutes > 0) EmeraldShield else RoseLock)
              )
              Text(
                text = "Остаток времени (LIVE)",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
              )
            }
            Text(
              text = "Использовано: ${usedMinutes / 60}ч ${usedMinutes % 60}м",
              color = Slate400,
              fontSize = 12.sp
            )
          }

          Spacer(modifier = Modifier.height(10.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.Bottom
          ) {
            Column {
              Text(
                text = if (remainingMinutes > 0) {
                  "%02d:%02d:%02d".format(
                    (remainingMinutes - 1).coerceAtLeast(0) / 60,
                    (remainingMinutes - 1).coerceAtLeast(0) % 60,
                    liveSecondsTick
                  )
                } else {
                  "00:00:00 (Лимит исчерпан)"
                },
                color = if (remainingMinutes <= 15) AmberWarning else EmeraldShieldLight,
                fontSize = 28.sp,
                fontWeight = FontWeight.ExtraBold
              )
              Text(
                text = "Оставшееся экранное время на сегодня из лимита ${effectiveLimit / 60} ч ${effectiveLimit % 60} мин",
                color = Slate400,
                fontSize = 11.sp
              )
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          val usedRatio = (usedMinutes.toFloat() / effectiveLimit.coerceAtLeast(1).toFloat()).coerceIn(0f, 1f)
          LinearProgressIndicator(
            progress = { usedRatio },
            modifier = Modifier
              .fillMaxWidth()
              .height(8.dp)
              .clip(RoundedCornerShape(4.dp)),
            color = if (usedRatio >= 1f) RoseLock else if (usedRatio >= 0.8f) AmberWarning else CyanLight,
            trackColor = Slate800
          )

          Spacer(modifier = Modifier.height(12.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            OutlinedButton(
              onClick = { onGrantBonusTime(15) },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+15 мин", fontSize = 12.sp, color = CyanLight, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
              onClick = { onGrantBonusTime(30) },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+30 мин", fontSize = 12.sp, color = CyanLight, fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
              onClick = { onGrantBonusTime(60) },
              modifier = Modifier.weight(1f),
              shape = RoundedCornerShape(10.dp)
            ) {
              Text("+1 час", fontSize = 12.sp, color = CyanLight, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }

    // Daily Limit Slider Card
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Icon(Icons.Default.Timer, contentDescription = null, tint = CyanLight)
              Text("Установить дневной лимит", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
            Text(
              text = "${sliderMinutes.toInt() / 60} ч ${sliderMinutes.toInt() % 60} мин",
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold,
              color = CyanLight
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          Slider(
            value = sliderMinutes.coerceIn(30f, 360f),
            onValueChange = { sliderMinutes = it.coerceIn(30f, 360f) },
            onValueChangeFinished = { onSetDailyLimit(sliderMinutes.toInt().coerceIn(30, 360)) },
            valueRange = 30f..360f,
            steps = 10,
            colors = SliderDefaults.colors(
              thumbColor = CyanLight,
              activeTrackColor = CyanLight,
              inactiveTrackColor = Slate800
            ),
            modifier = Modifier.testTag("daily_limit_slider")
          )

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Text("30 мин", color = Slate400, fontSize = 12.sp)
            Text("2 ч 30 мин", color = Slate400, fontSize = 12.sp)
            Text("6 часов", color = Slate400, fontSize = 12.sp)
          }

          Spacer(modifier = Modifier.height(14.dp))

          // Preset buttons
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(60 to "1 ч", 120 to "2 ч", 150 to "2.5 ч", 180 to "3 ч").forEach { (mins, label) ->
              val isSelected = sliderMinutes.toInt() == mins
              OutlinedButton(
                onClick = {
                  sliderMinutes = mins.toFloat()
                  onSetDailyLimit(mins)
                },
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.5.dp, CyanLight) else null
              ) {
                Text(label, fontSize = 12.sp, color = if (isSelected) CyanLight else Slate400)
              }
            }
          }
        }
      }
    }

    // Interactive Chart of Screen Time Distribution by App Today
    item {
      com.example.ui.components.AppUsageDistributionChartCard(
        apps = apps,
        totalChildUsedMinutes = usedMinutes
      )
    }

    // Bedtime Schedule Card
    item {
      Card(
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              Icon(Icons.Default.Bedtime, contentDescription = null, tint = Color(0xFF818CF8))
              Column {
                Text("Ночной сон (Downtime)", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text("Телефон выключается по расписанию", color = Slate400, fontSize = 12.sp)
              }
            }

            Switch(
              checked = bedtimeEnabled,
              onCheckedChange = { bedtimeEnabled = it },
              colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF818CF8),
                checkedTrackColor = Color(0xFF312E81)
              )
            )
          }

          Spacer(modifier = Modifier.height(14.dp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
          ) {
            Card(
              modifier = Modifier.weight(1f),
              colors = CardDefaults.cardColors(containerColor = Slate800),
              shape = RoundedCornerShape(12.dp)
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text("Начало сна", color = Slate400, fontSize = 11.sp)
                Text("21:30", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Каждый день", color = Slate400, fontSize = 11.sp)
              }
            }

            Card(
              modifier = Modifier.weight(1f),
              colors = CardDefaults.cardColors(containerColor = Slate800),
              shape = RoundedCornerShape(12.dp)
            ) {
              Column(modifier = Modifier.padding(12.dp)) {
                Text("Подъем", color = Slate400, fontSize = 11.sp)
                Text("07:00", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text("Будни", color = Slate400, fontSize = 11.sp)
              }
            }
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(20.dp))
    }
  }
}
