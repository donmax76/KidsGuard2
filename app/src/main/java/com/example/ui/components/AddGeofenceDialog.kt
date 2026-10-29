package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.SportsScore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@Composable
fun AddGeofenceDialog(
  currentLat: Double,
  currentLng: Double,
  onDismiss: () -> Unit,
  onConfirm: (name: String, lat: Double, lng: Double, radius: Float, iconType: String) -> Unit
) {
  var zoneName by remember { mutableStateOf("Дом") }
  var radius by remember { mutableFloatStateOf(200f) }
  var selectedIcon by remember { mutableStateOf("home") }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate900,
    title = {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        Icon(Icons.Default.Security, contentDescription = null, tint = CyanLight)
        Text("Создать зону безопасности", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
      }
    },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
          value = zoneName,
          onValueChange = { zoneName = it },
          label = { Text("Название зоны (Дом, Школа, Секция)") },
          singleLine = true,
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = CyanLight,
            unfocusedBorderColor = Slate400,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White
          ),
          modifier = Modifier.fillMaxWidth()
        )

        Text("Иконка зоны:", color = Slate200, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          IconButton(
            onClick = { selectedIcon = "home" },
            modifier = Modifier.background(if (selectedIcon == "home") CyanLight.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(8.dp))
          ) {
            Icon(Icons.Default.Home, contentDescription = "Дом", tint = if (selectedIcon == "home") CyanLight else Slate400)
          }

          IconButton(
            onClick = { selectedIcon = "school" },
            modifier = Modifier.background(if (selectedIcon == "school") CyanLight.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(8.dp))
          ) {
            Icon(Icons.Default.School, contentDescription = "Школа", tint = if (selectedIcon == "school") CyanLight else Slate400)
          }

          IconButton(
            onClick = { selectedIcon = "gym" },
            modifier = Modifier.background(if (selectedIcon == "gym") CyanLight.copy(alpha = 0.3f) else Color.Transparent, RoundedCornerShape(8.dp))
          ) {
            Icon(Icons.Default.SportsScore, contentDescription = "Секция", tint = if (selectedIcon == "gym") CyanLight else Slate400)
          }
        }

        Text("Радиус зоны: ${radius.toInt()} метров", color = Slate200, fontSize = 12.sp)
        Slider(
          value = radius,
          onValueChange = { radius = it },
          valueRange = 50f..1000f,
          steps = 19,
          colors = SliderDefaults.colors(thumbColor = CyanLight, activeTrackColor = CyanLight, inactiveTrackColor = Slate800)
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (zoneName.isNotBlank()) {
            onConfirm(zoneName, currentLat, currentLng, radius, selectedIcon)
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Color.Black),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Сохранить", fontWeight = FontWeight.Bold)
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Отмена", color = Slate400)
      }
    }
  )
}
