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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.SignalCellularAlt
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChildScreenGraphicCard(
  appName: String,
  batteryPercent: Int = 100,
  modifier: Modifier = Modifier
) {
  val currentTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())

  val appThemeColor = when {
    appName.contains("youtube", ignoreCase = true) -> Color(0xFFEF4444)
    appName.contains("telegram", ignoreCase = true) -> Color(0xFF38BDF8)
    appName.contains("tiktok", ignoreCase = true) -> Color(0xFFF43F5E)
    appName.contains("chrome", ignoreCase = true) || appName.contains("browser", ignoreCase = true) -> Color(0xFF3B82F6)
    appName == "Рабочий стол" -> Color(0xFF10B981)
    else -> Color(0xFF8B5CF6)
  }

  val appIcon = when {
    appName.contains("youtube", ignoreCase = true) -> Icons.Default.PlayArrow
    appName.contains("telegram", ignoreCase = true) -> Icons.Default.Send
    appName == "Рабочий стол" -> Icons.Default.PhoneAndroid
    else -> Icons.Default.SportsEsports
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Slate950)
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // 1. Android Status Bar Mock
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF020617))
          .padding(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(currentTime, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          Icon(Icons.Default.Wifi, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
          Icon(Icons.Default.SignalCellularAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(13.dp))
          Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$batteryPercent%", color = EmeraldShieldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            Icon(Icons.Default.BatteryFull, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(13.dp))
          }
        }
      }

      // 2. Mock Application Display Interface
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .background(Color(0xFF0B1120))
          .padding(12.dp),
        contentAlignment = Alignment.Center
      ) {
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900),
          border = androidx.compose.foundation.BorderStroke(1.5.dp, Slate800),
          modifier = Modifier.fillMaxWidth().height(160.dp)
        ) {
          Column(
            modifier = Modifier.fillMaxSize().padding(14.dp),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                  modifier = Modifier
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(appThemeColor.copy(alpha = 0.2f)),
                  contentAlignment = Alignment.Center
                ) {
                  Icon(appIcon, contentDescription = null, tint = appThemeColor, modifier = Modifier.size(24.dp))
                }

                Column {
                  Text(
                    text = if (appName == "Рабочий стол") "Рабочий стол (Главный экран)" else appName,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                  )
                  Text("Экран активен сейчас", color = EmeraldShieldLight, fontSize = 11.sp)
                }
              }

              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(6.dp))
                  .background(EmeraldShield.copy(alpha = 0.2f))
                  .padding(horizontal = 6.dp, vertical = 2.dp)
              ) {
                Text("LIVE", color = EmeraldShield, fontWeight = FontWeight.Bold, fontSize = 10.sp)
              }
            }

            // Simulated active screen content bars
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
              Box(
                modifier = Modifier
                  .fillMaxWidth(0.85f)
                  .height(10.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(Slate800)
              )
              Box(
                modifier = Modifier
                  .fillMaxWidth(0.6f)
                  .height(8.dp)
                  .clip(RoundedCornerShape(4.dp))
                  .background(Slate800.copy(alpha = 0.7f))
              )
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldShield, modifier = Modifier.size(12.dp))
                Text("Защита KidsGuard активна", color = Slate400, fontSize = 10.sp)
              }
              Text(currentTime, color = Slate400, fontSize = 10.sp)
            }
          }
        }
      }

      // 3. Android Navigation Bar Mock
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .background(Color(0xFF020617))
          .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Box(
          modifier = Modifier
            .width(60.dp)
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(Slate400)
        )
      }
    }
  }
}
