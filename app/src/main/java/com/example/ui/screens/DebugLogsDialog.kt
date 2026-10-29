package com.example.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
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
  modifier: Modifier = Modifier
) {
  val logs by DeviceControlDebugger.logs.collectAsState()

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Default.BugReport, contentDescription = null, tint = CyanLight)
          Column {
            Text("Журнал отладки и тестов", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Text("Контроль и перехват приложений в реальном времени", color = Slate400, fontSize = 10.sp)
          }
        }
        Row {
          IconButton(onClick = onRunSelfTest, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Запустить авто-тест", tint = EmeraldShield)
          }
          IconButton(onClick = { DeviceControlDebugger.clearLogs() }, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Default.Delete, contentDescription = "Очистить", tint = Slate400)
          }
        }
      }
    },
    text = {
      Column(modifier = Modifier.fillMaxWidth().height(420.dp)) {
        if (logs.isEmpty()) {
          Box(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentAlignment = Alignment.Center
          ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Text("Событий пока нет", color = Color.White, fontWeight = FontWeight.Bold)
              Text("Нажмите кнопку ▶ вверху для запуска теста самодиагностики", color = Slate400, fontSize = 11.sp)
            }
          }
        } else {
          LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            items(logs, key = { it.id }) { log ->
              val timeStr = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()).format(Date(log.timestamp))
              Card(
                shape = RoundedCornerShape(8.dp),
                colors = CardDefaults.cardColors(containerColor = Slate950),
                border = androidx.compose.foundation.BorderStroke(
                  1.dp,
                  when (log.status) {
                    "BLOCKED" -> RoseLock.copy(alpha = 0.5f)
                    "INTERCEPTED" -> Color(0xFFF59E0B).copy(alpha = 0.5f) // Amber
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
                        "BLOCKED" -> RoseLock
                        "INTERCEPTED" -> Color(0xFFFBBF24)
                        "ERROR" -> RoseLock
                        else -> CyanLight
                      },
                      fontWeight = FontWeight.Bold,
                      fontSize = 12.sp
                    )
                    Text(timeStr, color = Slate400, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                  }
                  Spacer(modifier = Modifier.height(2.dp))
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
        onClick = onRunSelfTest,
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Тестировать систему")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Закрыть", color = Slate400)
      }
    }
  )
}
