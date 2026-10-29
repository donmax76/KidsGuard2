package com.example.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Pin
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun ChangeMasterPinDialog(
  currentMasterPin: String,
  onDismiss: () -> Unit,
  onPinChanged: (String) -> Unit,
  modifier: Modifier = Modifier
) {
  var oldPinInput by remember { mutableStateOf("") }
  var newPinInput by remember { mutableStateOf("") }
  var confirmPinInput by remember { mutableStateOf("") }
  var errorMessage by remember { mutableStateOf<String?>(null) }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Icon(Icons.Default.Pin, contentDescription = null, tint = CyanLight)
        Text("Изменение мастер PIN-кода", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
          text = "Мастер PIN-код используется для переключения режимов, смены настроек и защиты приложения от удаления.",
          color = Slate400,
          fontSize = 12.sp
        )

        OutlinedTextField(
          value = oldPinInput,
          onValueChange = {
            if (it.length <= 8) oldPinInput = it.filter { ch -> ch.isDigit() }
            errorMessage = null
          },
          label = { Text("Текущий PIN-код (по умолч. 1234)", color = Slate400, fontSize = 12.sp) },
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = newPinInput,
          onValueChange = {
            if (it.length <= 8) newPinInput = it.filter { ch -> ch.isDigit() }
            errorMessage = null
          },
          label = { Text("Новый PIN-код (от 4 до 8 цифр)", color = Slate400, fontSize = 12.sp) },
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        OutlinedTextField(
          value = confirmPinInput,
          onValueChange = {
            if (it.length <= 8) confirmPinInput = it.filter { ch -> ch.isDigit() }
            errorMessage = null
          },
          label = { Text("Повторите новый PIN-код", color = Slate400, fontSize = 12.sp) },
          visualTransformation = PasswordVisualTransformation(),
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
          singleLine = true,
          modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage != null) {
          Text(
            text = errorMessage ?: "",
            color = RoseLock,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold
          )
        }
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (oldPinInput != currentMasterPin) {
            errorMessage = "Неверный текущий PIN-код!"
          } else if (newPinInput.length < 4) {
            errorMessage = "Новый PIN-код должен содержать минимум 4 цифры!"
          } else if (newPinInput != confirmPinInput) {
            errorMessage = "Новые PIN-коды не совпадают!"
          } else {
            onPinChanged(newPinInput)
            onDismiss()
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Сохранить новый PIN", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена", color = Slate400)
      }
    }
  )
}
