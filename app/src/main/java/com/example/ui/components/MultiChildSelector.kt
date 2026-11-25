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
import androidx.compose.material.icons.filled.Edit
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
  onConnectExistingChildClick: () -> Unit = onAddChildClick,
  onEditChildProfile: (childId: Long, newName: String, newAge: Int, newDailyLimitMinutes: Int, newPairingCode: String) -> Unit = { _, _, _, _, _ -> },
  modifier: Modifier = Modifier
) {
  var childToDelete by remember { mutableStateOf<ChildProfileEntity?>(null) }
  var childToEdit by remember { mutableStateOf<ChildProfileEntity?>(null) }

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

      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onConnectExistingChildClick, modifier = Modifier.testTag("connect_existing_child_header_button")) {
          Icon(Icons.Default.QrCode, contentDescription = null, tint = EmeraldShieldLight, modifier = Modifier.size(15.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("По коду", color = EmeraldShieldLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
        TextButton(onClick = onAddChildClick, modifier = Modifier.testTag("add_child_header_button")) {
          Icon(Icons.Default.Add, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(3.dp))
          Text("Новый", color = CyanLight, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
      }
    }

    Spacer(modifier = Modifier.height(4.dp))

    if (children.isEmpty()) {
      // Clean Empty State when no children added yet or after Parent app reinstall
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
          Text("Подключите или добавьте ребёнка", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
          Spacer(modifier = Modifier.height(4.dp))
          Text(
            "Если телефон ребёнка уже был настроен ранее, нажмите «Подключиться по 6-значному коду ребёнка», чтобы восстановить связь без смены кода на телефоне ребёнка.",
            color = Slate400,
            fontSize = 12.sp,
            textAlign = TextAlign.Center
          )
          Spacer(modifier = Modifier.height(14.dp))
          Button(
            onClick = onConnectExistingChildClick,
            colors = ButtonDefaults.buttonColors(containerColor = EmeraldShieldLight, contentColor = Slate950),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("empty_connect_existing_child_button")
          ) {
            Icon(Icons.Default.QrCode, contentDescription = null)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Подключиться по 6-значному коду ребёнка", fontWeight = FontWeight.Bold, fontSize = 12.sp)
          }
          Spacer(modifier = Modifier.height(8.dp))
          OutlinedButton(
            onClick = onAddChildClick,
            shape = RoundedCornerShape(12.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight),
            modifier = Modifier
              .fillMaxWidth()
              .testTag("empty_add_child_button")
          ) {
            Icon(Icons.Default.Add, contentDescription = null, tint = CyanLight)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Создать новый профиль ребёнка", color = CyanLight, fontWeight = FontWeight.Bold, fontSize = 12.sp)
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
              horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                  text = if (isSelected) "🟢 Код ${child.pairingCode} • $batLabel" else "📱 Код ${child.pairingCode} ($batLabel)",
                  color = if (isSelected) EmeraldShieldLight else CyanLight,
                  fontSize = 11.sp,
                  fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
              }

              // Edit Child Profile Button (Name, Age, Daily Limit, 6-Digit Pairing Code)
              Box(
                modifier = Modifier
                  .size(32.dp)
                  .clip(CircleShape)
                  .background(CyanContainer.copy(alpha = 0.45f))
                  .clickable { childToEdit = child }
                  .testTag("edit_child_button_${child.id}"),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  Icons.Default.Edit,
                  contentDescription = "Редактировать профиль и код",
                  tint = CyanLight,
                  modifier = Modifier.size(16.dp)
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

  // Edit Child Profile Dialog
  childToEdit?.let { child ->
    EditChildProfileDialog(
      child = child,
      onDismiss = { childToEdit = null },
      onSave = { newName, newAge, newDailyLimit, newPairingCode ->
        onEditChildProfile(child.id, newName, newAge, newDailyLimit, newPairingCode)
        childToEdit = null
      }
    )
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
fun EditChildProfileDialog(
  child: ChildProfileEntity,
  onDismiss: () -> Unit,
  onSave: (newName: String, newAge: Int, newDailyLimitMinutes: Int, newPairingCode: String) -> Unit
) {
  var name by remember(child.id) { mutableStateOf(child.name) }
  var ageText by remember(child.id) { mutableStateOf(child.age.toString()) }
  var limitMinutesText by remember(child.id) { mutableStateOf(child.dailyScreenLimitMinutes.toString()) }
  var pairingCodeInput by remember(child.id) { mutableStateOf(child.pairingCode) }

  val cleanCodeDigits = pairingCodeInput.filter { it.isDigit() }
  val isValid = name.trim().isNotBlank() && cleanCodeDigits.length == 6

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Edit, contentDescription = null, tint = CyanLight)
        Text("Редактировать профиль: ${child.name}", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Вы можете изменить имя, возраст, дневной лимит времени и 6-значный код подключения (если на телефоне ребёнка уже введён другой код или нужно задать свой).",
          color = Slate400,
          fontSize = 12.sp,
          lineHeight = 16.sp
        )

        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Имя ребенка") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_child_name_input")
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
          OutlinedTextField(
            value = ageText,
            onValueChange = { ageText = it.filter { ch -> ch.isDigit() }.take(2) },
            label = { Text("Возраст (лет)") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("edit_child_age_input")
          )

          OutlinedTextField(
            value = limitMinutesText,
            onValueChange = { limitMinutesText = it.filter { ch -> ch.isDigit() }.take(3) },
            label = { Text("Лимит (мин/день)") },
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("edit_child_limit_input")
          )
        }

        OutlinedTextField(
          value = pairingCodeInput,
          onValueChange = { raw ->
            val digits = raw.filter { it.isDigit() }.take(6)
            pairingCodeInput = if (digits.length > 3) "${digits.take(3)}-${digits.substring(3)}" else digits
          },
          label = { Text("6-значный код подключения (XXX-XXX)") },
          placeholder = { Text("Например: 102-702") },
          singleLine = true,
          modifier = Modifier.fillMaxWidth().testTag("edit_child_code_input")
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          val parsedAge = ageText.toIntOrNull()?.coerceIn(3, 18) ?: child.age
          val parsedLimit = limitMinutesText.toIntOrNull()?.coerceIn(15, 720) ?: child.dailyScreenLimitMinutes
          val formattedCode = "${cleanCodeDigits.take(3)}-${cleanCodeDigits.substring(3)}"
          onSave(name.trim(), parsedAge, parsedLimit, formattedCode)
        },
        enabled = isValid,
        colors = ButtonDefaults.buttonColors(containerColor = EmeraldShieldLight, contentColor = Slate950),
        modifier = Modifier.testTag("save_edited_child_button")
      ) {
        Text("Сохранить изменения", fontWeight = FontWeight.Bold)
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
fun PairingCodeDialog(
  child: ChildProfileEntity,
  onDismiss: () -> Unit,
  onEditClick: (() -> Unit)? = null
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
          text = "Откройте KidsGuard на телефоне ребенка, нажмите «По коду» и введите этот 6-значный код:",
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

        if (onEditClick != null) {
          Spacer(modifier = Modifier.height(12.dp))
          OutlinedButton(
            onClick = onEditClick,
            shape = RoundedCornerShape(10.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight)
          ) {
            Icon(Icons.Default.Edit, contentDescription = null, tint = CyanLight, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text("Изменить имя, возраст или код", color = CyanLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
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
  onConfirm: (name: String, age: Int, dailyLimit: Int) -> Unit,
  onConnectExistingByCode: (pairingCode: String, customName: String) -> Unit = { _, _ -> },
  initialConnectByCodeMode: Boolean = false
) {
  var isSecondParentMode by remember(initialConnectByCodeMode) { mutableStateOf(initialConnectByCodeMode) }
  var name by remember { mutableStateOf("") }
  var ageText by remember { mutableStateOf("10") }
  var limitMinutesText by remember { mutableStateOf("120") }
  var pairingCodeInput by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = {
      Text(
        text = if (isSecondParentMode) "Подключиться к текущему ребёнку по коду" else "Добавить нового ребёнка",
        color = Color.White,
        fontSize = 17.sp,
        fontWeight = FontWeight.Bold
      )
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // Переключатель режима: Новый профиль ИЛИ подключение к существующему коду ребёнка
        Row(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(Slate950)
            .padding(4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(if (!isSecondParentMode) CyanLight else Color.Transparent)
              .clickable { isSecondParentMode = false }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "Новый профиль",
              color = if (!isSecondParentMode) Slate950 else Slate200,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
          Box(
            modifier = Modifier
              .weight(1f)
              .clip(RoundedCornerShape(8.dp))
              .background(if (isSecondParentMode) EmeraldShieldLight else Color.Transparent)
              .clickable { isSecondParentMode = true }
              .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
          ) {
            Text(
              text = "По коду ребёнка",
              color = if (isSecondParentMode) Slate950 else Slate200,
              fontSize = 12.sp,
              fontWeight = FontWeight.Bold
            )
          }
        }

        if (isSecondParentMode) {
          Text(
            text = "Введите 6-значный код комнаты уже настроенного телефона ребёнка (после переустановки программы у родителя или для второго родителя). Менять код на телефоне ребёнка не нужно — все блокировки и приложения загрузятся автоматически.",
            color = Slate400,
            fontSize = 12.sp,
            lineHeight = 16.sp
          )

          OutlinedTextField(
            value = pairingCodeInput,
            onValueChange = { raw ->
              val digits = raw.filter { it.isDigit() }.take(6)
              pairingCodeInput = if (digits.length > 3) "${digits.take(3)}-${digits.substring(3)}" else digits
            },
            label = { Text("6-значный код привязки ребенка") },
            placeholder = { Text("Например: 102-702") },
            modifier = Modifier
              .fillMaxWidth()
              .testTag("second_parent_code_input"),
            singleLine = true
          )

          OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Имя ребенка (необязательно)") },
            placeholder = { Text("Подтянется автоматически или введите имя") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
        } else {
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
      }
    },
    confirmButton = {
      if (isSecondParentMode) {
        val cleanDigits = pairingCodeInput.filter { it.isDigit() }
        Button(
          onClick = {
            if (cleanDigits.length == 6) {
              onConnectExistingByCode(pairingCodeInput.trim(), name.trim())
            }
          },
          enabled = cleanDigits.length == 6,
          colors = ButtonDefaults.buttonColors(containerColor = EmeraldShieldLight, contentColor = Slate950),
          modifier = Modifier.testTag("submit_second_parent_code_button")
        ) {
          Text("Подключить и загрузить блокировки", fontWeight = FontWeight.Bold)
        }
      } else {
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
          Text("Создать профиль", fontWeight = FontWeight.Bold)
        }
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
