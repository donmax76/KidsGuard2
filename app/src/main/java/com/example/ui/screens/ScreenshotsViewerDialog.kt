package com.example.ui.screens

import android.graphics.BitmapFactory
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import com.example.util.CapturedScreenItem
import com.example.util.ScreenShotManager

@Composable
fun ScreenshotsViewerDialog(
  childId: Long = 1L,
  childName: String = "Ребёнок",
  onDismiss: () -> Unit,
  onTakeNew: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val cloudSync = remember { com.example.data.cloud.CloudSyncManager(context) }
  val allSnapshots by ScreenShotManager.capturedScreens.collectAsState()
  val snapshots = remember(allSnapshots, childId) {
    allSnapshots.filter { it.childId == childId && !it.base64Bitmap.isNullOrBlank() }
  }

  androidx.compose.runtime.LaunchedEffect(childId) {
    try {
      cloudSync.sendRemoteCommand(childId, "TAKE_SCREENSHOT", "")
    } catch (_: Exception) {}
    cloudSync.observeScreenSnapshot(childId).collect { pair ->
      val app = pair?.first.orEmpty()
      val b64 = pair?.second.orEmpty()
      if (b64.isNotBlank()) {
        val currentTop = ScreenShotManager.capturedScreens.value.firstOrNull { it.childId == childId }?.base64Bitmap
        if (currentTop != b64) {
          ScreenShotManager.addCapturedItem(
            CapturedScreenItem(
              childId = childId,
              activeAppName = app.ifBlank { "Активный экран" },
              statusNote = "Скрытый снимок экрана ($childName)",
              base64Bitmap = b64
            )
          )
        }
      }
    }
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    containerColor = Slate950,
    title = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(Icons.Default.CameraAlt, contentDescription = null, tint = CyanLight)
          Column {
            Text("Снимки экрана: $childName", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text("Сохранение в Галерею: DCIM/KidsGuard", color = Slate400, fontSize = 10.sp)
          }
        }
        Text("Всего: ${snapshots.size}", color = EmeraldShield, fontWeight = FontWeight.Bold, fontSize = 12.sp)
      }
    },
    text = {
      if (snapshots.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(180.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = "Получение скрытого снимка с телефона «$childName»...\nНажмите «Новый снимок» ниже для мгновенного захвата экрана.",
            color = Slate400,
            fontSize = 13.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      } else {
        LazyColumn(
          modifier = Modifier
            .fillMaxWidth()
            .height(420.dp),
          verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
          items(snapshots) { item ->
            ScreenshotItemCard(item)
          }
        }
      }
    },
    dismissButton = {
      Button(
        onClick = {
          onTakeNew()
          Toast.makeText(context, "Запрос скрытого снимка отправлен...", Toast.LENGTH_SHORT).show()
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
        shape = RoundedCornerShape(8.dp)
      ) {
        Icon(Icons.Default.CameraAlt, contentDescription = null, modifier = Modifier.size(16.dp))
        Spacer(modifier = Modifier.width(6.dp))
        Text("Новый снимок", fontWeight = FontWeight.Bold)
      }
    },
    confirmButton = {
      Button(
        onClick = onDismiss,
        colors = ButtonDefaults.buttonColors(containerColor = Slate900, contentColor = Slate200),
        shape = RoundedCornerShape(8.dp)
      ) {
        Text("Закрыть")
      }
    }
  )
}

@Composable
private fun ScreenshotItemCard(item: CapturedScreenItem) {
  val context = LocalContext.current
  val rawBitmap = remember(item.base64Bitmap) {
    if (!item.base64Bitmap.isNullOrBlank()) {
      try {
        val decoded = Base64.decode(item.base64Bitmap, Base64.DEFAULT)
        BitmapFactory.decodeByteArray(decoded, 0, decoded.size)
      } catch (_: Exception) {
        null
      }
    } else {
      null
    }
  }

  val imageBitmap = remember(rawBitmap) { rawBitmap?.asImageBitmap() }

  Card(
    shape = RoundedCornerShape(16.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = androidx.compose.foundation.BorderStroke(1.dp, Slate800),
    modifier = Modifier.fillMaxWidth()
  ) {
    Column(modifier = Modifier.padding(12.dp)) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
          Icon(Icons.Default.PhoneAndroid, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
          Text(
            text = item.activeAppName,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp
          )
        }

        Text(
          text = item.timeFormatted,
          color = EmeraldShield,
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold
        )
      }

      Spacer(modifier = Modifier.height(8.dp))

      if (imageBitmap != null) {
        Image(
          bitmap = imageBitmap,
          contentDescription = "Снимок экрана дисплея",
          contentScale = ContentScale.Fit,
          modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF020617))
            .border(1.dp, Slate800, RoundedCornerShape(10.dp))
        )
      } else {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .height(100.dp)
            .background(Color(0xFF020617), RoundedCornerShape(10.dp)),
          contentAlignment = Alignment.Center
        ) {
          Text("Ожидание кадра: ${item.activeAppName}", color = Slate400, fontSize = 12.sp)
        }
      }

      Spacer(modifier = Modifier.height(8.dp))

      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Text(
          text = item.statusNote,
          color = Slate400,
          fontSize = 11.sp,
          modifier = Modifier.weight(1f)
        )

        if (rawBitmap != null) {
          Button(
            onClick = {
              val uri = ScreenShotManager.saveBitmapToGalleryWithUri(
                context,
                rawBitmap,
                "KidsGuard_${System.currentTimeMillis()}"
              )
              val msg = if (uri != null) {
                "Сохранено в Галерею: DCIM/KidsGuard"
              } else {
                "Ошибка сохранения"
              }
              Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            },
            colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
            shape = RoundedCornerShape(8.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(30.dp)
          ) {
            Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("В Галерею", fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }
  }
}
