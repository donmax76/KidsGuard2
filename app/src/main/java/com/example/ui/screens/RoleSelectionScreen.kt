package com.example.ui.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChildCare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.SupervisorAccount
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.AppRole
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun RoleSelectionScreen(
  onSelectParent: () -> Unit,
  onSelectChild: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Slate950)
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center
  ) {
    Box(
      modifier = Modifier
        .size(64.dp)
        .clip(CircleShape)
        .background(CyanContainer),
      contentAlignment = Alignment.Center
    ) {
      Icon(Icons.Default.Security, contentDescription = null, tint = CyanLight, modifier = Modifier.size(36.dp))
    }

    Spacer(modifier = Modifier.height(16.dp))

    Text(
      text = "KidsGuard",
      fontSize = 28.sp,
      fontWeight = FontWeight.ExtraBold,
      color = Color.White
    )

    Text(
      text = "Комплексная система родительского контроля",
      fontSize = 14.sp,
      color = Slate400,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(32.dp))

    Text(
      text = "Чей это телефон?",
      fontSize = 18.sp,
      fontWeight = FontWeight.Bold,
      color = Slate200
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Card 1: Parent Device
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onSelectParent() }
        .testTag("select_parent_role_card"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Slate900),
      border = androidx.compose.foundation.BorderStroke(1.5.dp, CyanLight.copy(alpha = 0.6f))
    ) {
      Row(
        modifier = Modifier.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Box(
          modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(CyanContainer),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.SupervisorAccount, contentDescription = null, tint = CyanLight, modifier = Modifier.size(26.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
          Text("Это телефон РОДИТЕЛЯ", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "Управление семьей, подключение нескольких детей, удаленная блокировка приложений, лимиты времени и GPS-карта.",
            color = Slate400,
            fontSize = 12.sp
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Card 2: Child Device
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .clickable { onSelectChild() }
        .testTag("select_child_role_card"),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Slate900),
      border = androidx.compose.foundation.BorderStroke(1.5.dp, EmeraldShield.copy(alpha = 0.6f))
    ) {
      Row(
        modifier = Modifier.padding(18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
      ) {
        Box(
          modifier = Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(EmeraldContainer),
          contentAlignment = Alignment.Center
        ) {
          Icon(Icons.Default.Smartphone, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(26.dp))
        }

        Column(modifier = Modifier.weight(1f)) {
          Text("Это телефон РЕБЕНКА", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "Устройство для отслеживания. Введите код сопряжения от родителя для привязки и активации фоновой защиты.",
            color = Slate400,
            fontSize = 12.sp
          )
        }
      }
    }
  }
}
