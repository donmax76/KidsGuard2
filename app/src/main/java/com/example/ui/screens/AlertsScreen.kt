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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AlertNotificationEntity
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun AlertsScreen(
  alerts: List<AlertNotificationEntity>,
  onMarkRead: (Long) -> Unit,
  onClearAll: () -> Unit,
  onDeleteAlert: (Long) -> Unit = onMarkRead,
  modifier: Modifier = Modifier
) {
  val dateFormat = SimpleDateFormat("dd.MM HH:mm", Locale.getDefault())

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    item {
      Spacer(modifier = Modifier.height(8.dp))
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .height(50.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Журнал оповещений (${alerts.size})",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
          Text(
            text = "История событий, экстренные вызовы и геозоны",
            color = Slate400,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
          )
        }

        if (alerts.isNotEmpty()) {
          Button(
            onClick = onClearAll,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4C0519)),
            shape = RoundedCornerShape(10.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier
              .height(36.dp)
              .padding(start = 8.dp)
              .testTag("clear_alerts_button")
          ) {
            Icon(
              Icons.Default.DeleteSweep,
              contentDescription = "Очистить все",
              tint = RoseLock,
              modifier = Modifier.size(16.dp)
            )
            Text(
              text = " Очистить все",
              color = Color.White,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold,
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    if (alerts.isEmpty()) {
      item {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(220.dp),
          contentAlignment = Alignment.Center
        ) {
          Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Notifications, contentDescription = null, tint = Slate800, modifier = Modifier.size(54.dp))
            Spacer(modifier = Modifier.height(8.dp))
            Text(
              text = "Нет новых оповещений",
              color = Slate400,
              fontSize = 15.sp,
              maxLines = 1,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    items(alerts, key = { it.id }) { alert ->
      val (icon, tint, bg) = when (alert.type) {
        "SOS" -> Triple(Icons.Default.Warning, RoseLock, RoseContainer)
        "UNINSTALL_ATTEMPT_BLOCKED" -> Triple(Icons.Default.Shield, RoseLock, RoseContainer)
        "RESTORE_DEFAULTS" -> Triple(Icons.Default.RestartAlt, EmeraldShield, EmeraldContainer)
        "REMOTE_UPDATE_SUCCESS" -> Triple(Icons.Default.SystemUpdate, CyanLight, CyanContainer)
        "ATTENTION_SIGNAL" -> Triple(Icons.Default.VolumeUp, AmberWarning, Color(0xFF33200B))
        "CHAT_MESSAGE" -> Triple(Icons.Default.Chat, CyanLight, Slate800)
        "GEOFENCE_ENTER", "GEOFENCE_EXIT" -> Triple(Icons.Default.LocationOn, EmeraldShield, EmeraldContainer)
        "WEB_BLOCKED" -> Triple(Icons.Default.Block, AmberWarning, Color(0xFF33200B))
        "LIMIT_REACHED" -> Triple(Icons.Default.Timer, CyanLight, CyanContainer)
        else -> Triple(Icons.Default.NotificationsActive, CyanLight, Slate800)
      }

      Card(
        modifier = Modifier
          .fillMaxWidth()
          .height(98.dp)
          .clickable { onMarkRead(alert.id) }
          .testTag("alert_item_${alert.id}"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
          containerColor = if (!alert.isRead && alert.type == "SOS") Color(0xFF3B121C) else Slate900
        ),
        border = if (!alert.isRead) androidx.compose.foundation.BorderStroke(1.dp, tint.copy(alpha = 0.5f)) else null
      ) {
        Row(
          modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 10.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(bg),
            contentAlignment = Alignment.Center
          ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
          }

          Column(modifier = Modifier.weight(1f)) {
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .height(20.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = alert.title,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                modifier = Modifier
                  .weight(1f)
                  .padding(end = 6.dp)
              )
              Text(
                text = dateFormat.format(Date(alert.timestamp)),
                color = Slate400,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
              )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
              text = alert.message,
              color = Slate200,
              fontSize = 12.sp,
              lineHeight = 15.sp,
              maxLines = 2,
              overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
          }

          androidx.compose.material3.IconButton(
            onClick = { onDeleteAlert(alert.id) },
            modifier = Modifier
              .size(34.dp)
              .clip(CircleShape)
              .background(Slate800)
              .testTag("delete_alert_${alert.id}")
          ) {
            Icon(
              imageVector = Icons.Default.DeleteSweep,
              contentDescription = "Удалить",
              tint = RoseLock,
              modifier = Modifier.size(18.dp)
            )
          }
        }
      }
    }

    item {
      Spacer(modifier = Modifier.height(24.dp))
    }
  }
}
