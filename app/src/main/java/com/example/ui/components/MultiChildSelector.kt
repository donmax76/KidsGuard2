package com.example.ui.components

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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.QrCode
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChildProfileEntity
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
import com.example.ui.theme.Slate950

@Composable
fun MultiChildSelector(
  children: List<ChildProfileEntity>,
  activeChildId: Long?,
  onSelectChild: (Long) -> Unit,
  onShowPairingCode: (ChildProfileEntity) -> Unit,
  onDeleteChild: (ChildProfileEntity) -> Unit,
  onAddChildClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  var childToDelete by remember { mutableStateOf<ChildProfileEntity?>(null) }

  Column(modifier = modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Text(
        text = "Дети в семье (${children.size})",
        color = Slate200,
        fontWeight = FontWeight.Bold,
        fontSize = 15.sp
      )

      TextButton(onClick = onAddChildClick, modifier = Modifier.testTag("add_child_header_button")) {
        Icon(Icons.Default.Add, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(4.dp))
        Text("Добавить ребенка", color = CyanLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    if (children.isEmpty()) {
      // Clean Empty State when no children added yet
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900),
        border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
      ) {
        Column(
          modifier = Modifier.padding(20.dp),
          horizontalAlignment = Alignment.CenterHorizontally
        ) {
          Icon(Icons.Default.PersonAdd, contentDescription = null, tint = CyanLight, modifier = Modifier.size(36.dp))
          Spacer(modifier = Modifier.height(10.dp))
          Text("У вас пока нет добавленных детей", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "Нажмите «Добавить ребенка», чтобы создать профиль, настроить лимиты и получить персональный код для телефона ребенка.",
            color = Slate400,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(14.dp))
          Button(
            onClick = onAddChildClick,
            colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.testTag("empty_add_child_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Добавить первого ребенка", fontWeight = FontWeight.Bold)
          }
        }
      }
    } else {
      LazyRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp)
      ) {
        items(children, key = { it.id }) { child ->
          val isSelected = child.id == activeChildId

          Card(
            modifier = Modifier
              .clickable { onSelectChild(child.id) }
              .testTag("child_card_${child.id}"),
            shape = RoundedCornerShape(18.dp),
            colors = CardDefaults.cardColors(
              containerColor = if (isSelected) Color(0xFF152238) else Slate900.copy(alpha = 0.65f)
            ),
            border = if (isSelected) {
              androidx.compose.foundation.BorderStroke(
                2.dp,
                androidx.compose.ui.graphics.Brush.linearGradient(
                  listOf(CyanLight, Color(child.colorHex), EmeraldShieldLight)
                )
              )
            } else {
              androidx.compose.foundation.BorderStroke(1.dp, Slate800)
            }
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(42.dp)
                  .clip(CircleShape)
                  .background(
                    androidx.compose.ui.graphics.Brush.linearGradient(
                      listOf(Color(child.colorHex), CyanLight.copy(alpha = 0.7f))
                    )
                  ),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = child.name.take(1).uppercase(),
                  color = Color.White,
                  fontWeight = FontWeight.ExtraBold,
                  fontSize = 17.sp
                )
              }

              Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(
                    text = child.name,
                    color = Color.White,
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 14.sp
                  )
                  Text(
                    text = " • ${child.age} лет",
                    color = Slate200,
                    fontSize = 11.sp
                  )
                }

                val batLabel = if (child.batteryLevel in 1..100) "${child.batteryLevel}%" else "ожидание связи"
                Text(
                  text = if (isSelected) "🟢 Активный профиль • $batLabel" else "📱 Нажмите для выбора ($batLabel)",
                  color = if (isSelected) EmeraldShieldLight else CyanLight,
                  fontSize = 11.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
              }

              // Pairing Code Button
              Box(
                modifier = Modifier
                  .size(32.dp)
                  .clip(CircleShape)
                  .background(Slate800)
                  .clickable { onShowPairingCode(child) },
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  Icons.Default.QrCode,
                  contentDescription = "Код привязки",
                  tint = CyanLight,
                  modifier = Modifier.size(16.dp)
                )
              }

              // Delete Child Button
              Box(
                modifier = Modifier
                  .size(32.dp)
                  .clip(CircleShape)
                  .background(RoseContainer.copy(alpha = 0.35f))
                  .clickable { childToDelete = child },
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  Icons.Default.Delete,
                  contentDescription = "Удалить ребенка",
                  tint = RoseLock,
                  modifier = Modifier.size(16.dp)
                )
              }
            }
          }
        }
      }
    }
  }

  // Delete Confirmation Dialog
  childToDelete?.let { child ->
    AlertDialog(
      onDismissRequest = { childToDelete = null },
      title = {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          Icon(Icons.Default.RestartAlt, contentDescription = null, tint = EmeraldShield)
          Text("Восстановить и удалить?", color = Color.White)
        }
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
            text = "Перед удалением профиля «${child.name}» на устройстве ребенка будут автоматически восстановлены все исходные параметры и разрешения:",
            color = Slate200,
            fontSize = 13.sp
          )
          Text(
            text = "• Полная разблокировка всех приложений (YouTube, игры, соцсети)\n• Снятие лимитов экранного времени\n• Отключение ночного режима и фильтрации\n• Снятие родительского надзора",
            color = Slate400,
            fontSize = 12.sp
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            onDeleteChild(child)
            childToDelete = null
          },
          colors = ButtonDefaults.buttonColors(containerColor = RoseLock, contentColor = Color.White)
        ) {
          Text("Восстановить и удалить")
        }
      },
      dismissButton = {
        TextButton(onClick = { childToDelete = null }) {
          Text("Отмена", color = Slate400)
        }
      },
      containerColor = Slate900
    )
  }
}

@Composable
fun PairingCodeDialog(
  child: ChildProfileEntity,
  onDismiss: () -> Unit
) {
  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = CyanLight)
        Text("Код привязки: ${child.name}", color = Color.White, fontSize = 17.sp)
      }
    },
    text = {
      Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Text(
          text = "Откройте KidsGuard на телефоне ребенка, выберите «Это телефон ребенка» и введите этот код:",
          color = Slate400,
          fontSize = 13.sp,
          textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(18.dp))

        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Slate950),
          border = androidx.compose.foundation.BorderStroke(2.dp, CyanLight)
        ) {
          Text(
            text = child.pairingCode,
            fontSize = 32.sp,
            fontWeight = FontWeight.ExtraBold,
            color = CyanLight,
            letterSpacing = 4.sp,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 14.dp)
          )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
          text = if (child.isPaired) "Статус: Телефон ${child.deviceModel} подключен!" else "Статус: Ожидание ввода кода на телефоне ребенка...",
          color = if (child.isPaired) EmeraldShieldLight else Slate400,
          fontSize = 12.sp,
          textAlign = TextAlign.Center
        )
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950)
      ) {
        Text("Понятно")
      }
    },
    containerColor = Slate900
  )
}

@Composable
fun AddChildDialog(
  onDismiss: () -> Unit,
  onConfirm: (name: String, age: Int, dailyLimit: Int) -> Unit
) {
  var name by remember { mutableStateOf("") }
  var ageText by remember { mutableStateOf("10") }
  var limitMinutesText by remember { mutableStateOf("120") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Добавить ребенка в семью", color = Color.White) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Имя ребенка") },
          placeholder = { Text("Введите имя ребенка") },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("add_child_name_input"),
          singleLine = true
        )

        OutlinedTextField(
          value = ageText,
          onValueChange = { ageText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Возраст (лет)") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        OutlinedTextField(
          value = limitMinutesText,
          onValueChange = { limitMinutesText = it.filter { ch -> ch.isDigit() } },
          label = { Text("Дневной лимит (минут)") },
          placeholder = { Text("например: 120 (2 часа)") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (name.isNotBlank()) {
            val age = ageText.toIntOrNull() ?: 10
            val limit = limitMinutesText.toIntOrNull() ?: 120
            onConfirm(name.trim(), age, limit)
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        modifier = Modifier.testTag("submit_add_child_button")
      ) {
        Text("Создать профиль")
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
