package com.example.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.util.DeviceControlDebugger
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun DebugLogsDialog(
  onDismiss: () -> Unit,
  onRunSelfTest: () -> Unit,
  onRequestChildLogs: () -> Unit = {},
  onClearChildLogs: () -> Unit = {},
  onToggleRemoteLogging: (Boolean) -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val logs by DeviceControlDebugger.logs.collectAsState()
  val isLoggingEnabled by DeviceControlDebugger.isLoggingEnabled.collectAsState()
  var selectedFilter by remember { mutableStateOf("ALL") } // ALL, ERRORS, P2P, CHILD

  androidx.compose.runtime.LaunchedEffect(Unit) {
    if (DirectP2pSocketEngine.currentRole != "CHILD") {
      onRequestChildLogs()
    }
  }

  val filteredLogs = remember(logs, selectedFilter) {
    when (selectedFilter) {
      "ERRORS" -> logs.filter { it.status == "ERROR" || it.status == "BLOCKED" || it.status == "WARNING" || it.status == "INTERCEPTED" }
      "P2P" -> logs.filter { it.source == "P2P" || it.action.contains("P2P") || it.action.contains("КОМАНДА") || it.action.contains("СТАТУС") || it.action.contains("КАДР") || it.action.contains("СРЕЗ") || it.action.contains("БУДИЛЬНИК") }
      "CHILD" -> logs.filter { it.source == "CHILD" || it.action.contains("РЕБЁНОК") }
      else -> logs
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.weight(1f)) {
            Icon(Icons.Default.BugReport, contentDescription = null, tint = if (isLoggingEnabled) EmeraldShield else Slate400)
            Column {
              Text(
                text = "Логи и Параметры (${logs.size}) • v${BuildConfig.VERSION_NAME}",
                color = Color.White,
                fontWeight = FontWeight.ExtraBold,
                fontSize = 14.sp
              )
              Text(
                text = if (isLoggingEnabled) "🟢 Запись активна (Родитель + Ребёнок + Офлайн-буфер)" else "⚪ Логирование выключено",
                color = if (isLoggingEnabled) EmeraldShield else Slate400,
                fontSize = 10.sp
              )
            }
          }
          Switch(
            checked = isLoggingEnabled,
            onCheckedChange = { enabled ->
              DeviceControlDebugger.setLoggingEnabled(context, enabled)
              onToggleRemoteLogging(enabled)
            },
            colors = SwitchDefaults.colors(
              checkedThumbColor = Color.White,
              checkedTrackColor = EmeraldShield,
              uncheckedThumbColor = Slate400,
              uncheckedTrackColor = Slate800
            )
          )
        }

        // Action bar: Request Child Logs + Full Snapshot, Copy All, Share, Self-Test
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(Slate800)
              .clickable {
                DeviceControlDebugger.logFullDeviceSnapshot(
                  context = context,
                  role = DirectP2pSocketEngine.currentRole,
                  childId = 1L,
                  triggerReason = "Ручной запрос среза параметров"
                )
                onRequestChildLogs()
                Toast.makeText(context, "Срез параметров записан, запрос отправлен ребёнку...", Toast.LENGTH_SHORT).show()
              }
              .padding(vertical = 6.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
              Icon(Icons.Default.Sync, contentDescription = null, tint = CyanLight, modifier = Modifier.size(14.dp))
              Text("С телефона ребёнка + Срез", color = CyanLight, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
          }

          IconButton(
            onClick = {
              val text = DeviceControlDebugger.formatAllLogsAsText()
              val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
              cm?.setPrimaryClip(ClipData.newPlainText("KidsGuard Diagnostic Logs", text))
              Toast.makeText(context, "Лог скопирован в буфер обмена (${logs.size} зап.)", Toast.LENGTH_SHORT).show()
            },
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.Default.ContentCopy, contentDescription = "Скопировать весь лог", tint = CyanLight, modifier = Modifier.size(18.dp))
          }

          IconButton(
            onClick = {
              val text = DeviceControlDebugger.formatAllLogsAsText()
              val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, "KidsGuard Diagnostic Log v${BuildConfig.VERSION_NAME}")
                putExtra(Intent.EXTRA_TEXT, text)
              }
              context.startActivity(Intent.createChooser(shareIntent, "Отправить диагностический лог"))
            },
            modifier = Modifier.size(32.dp)
          ) {
            Icon(Icons.Default.Share, contentDescription = "Поделиться", tint = EmeraldShield, modifier = Modifier.size(18.dp))
          }

          IconButton(onClick = onRunSelfTest, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Запустить авто-тест", tint = EmeraldShield, modifier = Modifier.size(20.dp))
          }
        }

        // Row of 3 Clear Log options: Both / Parent Only / Child Only
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Box(
            modifier = Modifier
              .weight(1.3f)
              .clip(RoundedCornerShape(7.dp))
              .background(RoseLock.copy(alpha = 0.22f))
              .clickable {
                DeviceControlDebugger.clearLogs()
                onClearChildLogs()
                Toast.makeText(context, "Очищен лог у Родителя и отправлена очистка Ребёнку!", Toast.LENGTH_SHORT).show()
              }
              .padding(vertical = 5.dp, horizontal = 6.dp),
            contentAlignment = Alignment.Center
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
              Icon(Icons.Default.Delete, contentDescription = null, tint = RoseLock, modifier = Modifier.size(12.dp))
              Text("Очистить у обоих", color = RoseLock, fontSize = 9.5.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(7.dp))
              .background(Slate800)
              .clickable {
                DeviceControlDebugger.clearParentOnlyLogs()
                Toast.makeText(context, "Лог Родителя очищен", Toast.LENGTH_SHORT).show()
              }
              .padding(vertical = 5.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("🗑️ У Родителя", color = Slate200, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }

          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(7.dp))
              .background(Slate800)
              .clickable {
                DeviceControlDebugger.clearChildLogsFromParentView()
                onClearChildLogs()
                Toast.makeText(context, "Отправлена очистка лога Ребёнку!", Toast.LENGTH_SHORT).show()
              }
              .padding(vertical = 5.dp, horizontal = 4.dp),
            contentAlignment = Alignment.Center
          ) {
            Text("🗑️ У Ребёнка", color = AmberWarning, fontSize = 9.5.sp, fontWeight = FontWeight.Bold, maxLines = 1)
          }
        }

        // Filter chips
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          val filters = listOf(
            "ALL" to "Все (${logs.size})",
            "ERRORS" to "Сбои/Отсев",
            "P2P" to "Сеть/Срез",
            "CHILD" to "Ребёнок"
          )
          for ((key, label) in filters) {
            val selected = selectedFilter == key
            Box(
              modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(if (selected) CyanLight.copy(alpha = 0.22f) else Slate950)
                .clickable { selectedFilter = key }
                .padding(vertical = 5.dp),
              contentAlignment = Alignment.Center
            ) {
              Text(
                text = label,
                color = if (selected) CyanLight else Slate400,
                fontSize = 9.5.sp,
                fontWeight = if (selected) FontWeight.ExtraBold else FontWeight.Medium,
                maxLines = 1
              )
            }
          }
        }
      }
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth().height(400.dp)) {
        if (filteredLogs.isEmpty()) {
          Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text(
                text = if (isLoggingEnabled) "Событий в выбранной категории пока нет" else "Логирование сейчас ВЫКЛЮЧЕНО",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp
              )
              Text(
                text = if (isLoggingEnabled) "Нажмите «С телефона ребёнка + Срез» для полной сводки параметров" else "Включите тумблер сверху, чтобы записывать все действия и параметры",
                color = Slate400,
                fontSize = 11.sp
              )
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            items(filteredLogs, key = { it.id }) { log ->
              val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
              val verBadge = "v${log.appVersion.ifBlank { BuildConfig.VERSION_NAME }}"
              Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Slate950),
                border = BorderStroke(
                  1.dp,
                  when (log.status) {
                    "BLOCKED" -> RoseLock.copy(alpha = 0.55f)
                    "INTERCEPTED", "WARNING" -> AmberWarning.copy(alpha = 0.55f)
                    "ERROR" -> RoseLock
                    else -> EmeraldShield.copy(alpha = 0.4f)
                  }
                ),
                modifier = Modifier.fillMaxWidth()
              ) {
                Column(modifier = Modifier.padding(8.dp)) {
                  Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                  ) {
                    Text(
                      text = log.action,
                      color = when (log.status) {
                        "BLOCKED", "ERROR" -> RoseLock
                        "INTERCEPTED", "WARNING" -> AmberWarning
                        else -> CyanLight
                      },
                      fontWeight = FontWeight.Bold,
                      fontSize = 11.sp,
                      modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(Slate800)
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                      Text(
                        text = verBadge,
                        color = EmeraldShield,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                      )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(timeStr, color = Slate400, fontSize = 9.5.sp, fontFamily = FontFamily.Monospace)
                  }
                  Spacer(modifier = Modifier.height(3.dp))
                  Text(
                    text = log.details,
                    color = Slate200,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 14.sp
                  )
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
          val text = DeviceControlDebugger.formatAllLogsAsText()
          val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
          cm?.setPrimaryClip(ClipData.newPlainText("KidsGuard Diagnostic Logs", text))
          Toast.makeText(context, "Весь журнал скопирован в буфер обмена!", Toast.LENGTH_SHORT).show()
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("📋 Скопировать весь лог", fontWeight = FontWeight.Bold, fontSize = 12.sp)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Закрыть", color = Slate400)
      }
    }
  )
}
