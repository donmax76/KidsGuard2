package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppControlEntity
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900

@Composable
fun AppSelectionForceBlockPanel(
  childName: String,
  apps: List<AppControlEntity>,
  onToggleAppBlocked: (AppControlEntity) -> Unit,
  onBatchSetBlocked: (List<AppControlEntity>, Boolean) -> Unit,
  onSyncApps: () -> Unit,
  onOpenAllAppsScreen: () -> Unit,
  modifier: Modifier = Modifier
) {
  // 0 = Все, 1 = Разрешённые, 2 = Заблокированные
  var statusFilter by remember { mutableIntStateOf(0) }
  val selectedAppIds = remember { mutableStateListOf<Long>() }

  val filteredApps = remember(apps, statusFilter) {
    when (statusFilter) {
      1 -> apps.filter { !it.isBlocked }
      2 -> apps.filter { it.isBlocked }
      else -> apps
    }
  }

  val blockedCount = apps.count { it.isBlocked }
  val allowedCount = apps.size - blockedCount
  val selectedApps = apps.filter { selectedAppIds.contains(it.id) }

  Card(
    modifier = modifier
      .fillMaxWidth()
      .testTag("app_selection_force_block_panel"),
    shape = RoundedCornerShape(22.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900),
    border = BorderStroke(
      1.2.dp,
      Brush.horizontalGradient(
        listOf(
          RoseLock.copy(alpha = 0.55f),
          CyanLight.copy(alpha = 0.55f),
          EmeraldShield.copy(alpha = 0.55f)
        )
      )
    )
  ) {
    Column(modifier = Modifier.padding(16.dp)) {
      // 1. Header
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.weight(1f)
        ) {
          Box(
            modifier = Modifier
              .size(40.dp)
              .clip(CircleShape)
              .background(CyanContainer),
            contentAlignment = Alignment.Center
          ) {
            Icon(
              imageVector = Icons.Default.Apps,
              contentDescription = null,
              tint = CyanLight,
              modifier = Modifier.size(22.dp)
            )
          }
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Блокировка приложений",
              color = Color.White,
              fontSize = 14.sp,
              fontWeight = FontWeight.ExtraBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
            Text(
              text = "$childName • Разрешено: $allowedCount • Блок: $blockedCount",
              color = Slate400,
              fontSize = 11.sp,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
          IconButton(
            onClick = onSyncApps,
            modifier = Modifier.size(32.dp)
          ) {
            Icon(
              imageVector = Icons.Default.Refresh,
              contentDescription = "Сканировать приложения устройства",
              tint = CyanLight,
              modifier = Modifier.size(17.dp)
            )
          }

          Box(
            modifier = Modifier
              .clip(RoundedCornerShape(10.dp))
              .background(CyanContainer)
              .clickable { onOpenAllAppsScreen() }
              .padding(horizontal = 9.dp, vertical = 5.dp)
          ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
              Text("Все (${apps.size})", color = CyanLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = null, tint = CyanLight, modifier = Modifier.size(12.dp))
            }
          }
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 2. Filter Tabs + Quick Selection Helpers
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          listOf(
            0 to "Все (${apps.size})",
            1 to "Разрешены ($allowedCount)",
            2 to "В блоке ($blockedCount)"
          ).forEach { (idx, label) ->
            val sel = statusFilter == idx
            Box(
              modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(if (sel) CyanContainer else Color(0xFF131F33))
                .border(1.dp, if (sel) CyanLight else Slate800, RoundedCornerShape(8.dp))
                .clickable { statusFilter = idx }
                .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
              Text(
                text = label,
                color = if (sel) CyanLight else Slate400,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          val allVisibleSelected = filteredApps.isNotEmpty() && filteredApps.all { selectedAppIds.contains(it.id) }
          Text(
            text = if (allVisibleSelected) "Сбросить" else "Выбрать все",
            color = CyanLight,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable {
              if (allVisibleSelected) {
                selectedAppIds.clear()
              } else {
                filteredApps.forEach { app ->
                  if (!selectedAppIds.contains(app.id)) {
                    selectedAppIds.add(app.id)
                  }
                }
              }
            }
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 3. Batch Force Block / Allow Action Bar (always visible so parent can act on selected apps or games)
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
      ) {
        val targetListForBlock = if (selectedApps.isNotEmpty()) selectedApps else filteredApps
        val countLabel = if (selectedApps.isNotEmpty()) "(${selectedApps.size})" else "все (${targetListForBlock.size})"

        Button(
          onClick = {
            onBatchSetBlocked(targetListForBlock, true)
            selectedAppIds.clear()
          },
          enabled = targetListForBlock.isNotEmpty(),
          colors = ButtonDefaults.buttonColors(
            containerColor = RoseLock,
            contentColor = Color.White,
            disabledContainerColor = Slate800,
            disabledContentColor = Slate400
          ),
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .testTag("batch_force_block_apps_button")
        ) {
          Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(5.dp))
          Text(
            text = "Блокировать $countLabel",
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
          )
        }

        Button(
          onClick = {
            onBatchSetBlocked(targetListForBlock, false)
            selectedAppIds.clear()
          },
          enabled = targetListForBlock.isNotEmpty(),
          colors = ButtonDefaults.buttonColors(
            containerColor = EmeraldShield,
            contentColor = Color(0xFF020617),
            disabledContainerColor = Slate800,
            disabledContentColor = Slate400
          ),
          shape = RoundedCornerShape(10.dp),
          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
          modifier = Modifier
            .weight(1f)
            .height(38.dp)
            .testTag("batch_force_allow_apps_button")
        ) {
          Icon(Icons.Default.LockOpen, contentDescription = null, modifier = Modifier.size(14.dp))
          Spacer(modifier = Modifier.width(5.dp))
          Text(
            text = "Разрешить $countLabel",
            fontSize = 11.sp,
            fontWeight = FontWeight.ExtraBold,
            maxLines = 1
          )
        }
      }

      Spacer(modifier = Modifier.height(10.dp))

      // 4. Selectable List of Child Device Applications
      if (filteredApps.isEmpty()) {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF111C2E))
            .padding(14.dp),
          contentAlignment = Alignment.Center
        ) {
          Text(
            text = if (apps.isEmpty()) "Нажмите ↻ для сканирования установленных приложений с телефона ребёнка."
            else "В выбранной категории нет приложений.",
            color = Slate400,
            fontSize = 11.sp
          )
        }
      } else {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          filteredApps.take(6).forEach { app ->
            val isChecked = selectedAppIds.contains(app.id)
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(
                  when {
                    isChecked -> Color(0xFF172740)
                    app.isBlocked -> Color(0xFF241219)
                    else -> Color(0xFF121D2F)
                  }
                )
                .border(
                  1.dp,
                  when {
                    isChecked -> CyanLight
                    app.isBlocked -> RoseLock.copy(alpha = 0.55f)
                    else -> Slate800
                  },
                  RoundedCornerShape(12.dp)
                )
                .clickable {
                  if (isChecked) selectedAppIds.remove(app.id) else selectedAppIds.add(app.id)
                }
                .padding(horizontal = 10.dp, vertical = 7.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.SpaceBetween
            ) {
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
              ) {
                Icon(
                  imageVector = if (isChecked) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                  contentDescription = "Выбрать ${app.appName}",
                  tint = if (isChecked) CyanLight else Slate400,
                  modifier = Modifier.size(19.dp)
                )

                Column(modifier = Modifier.weight(1f)) {
                  Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                      text = app.appName,
                      color = Color.White,
                      fontWeight = FontWeight.Bold,
                      fontSize = 12.sp,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f, fill = false)
                    )
                    Box(
                      modifier = Modifier
                        .clip(RoundedCornerShape(5.dp))
                        .background(if (app.isBlocked) RoseLock.copy(alpha = 0.22f) else EmeraldShield.copy(alpha = 0.18f))
                        .padding(horizontal = 5.dp, vertical = 1.dp)
                    ) {
                      Text(
                        text = if (app.isBlocked) "ЗАБЛОКИРОВАНО" else "РАЗРЕШЕНО",
                        color = if (app.isBlocked) RoseLock else EmeraldShieldLight,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.ExtraBold
                      )
                    }
                  }
                  Text(
                    text = "${app.category} • Сегодня: ${app.usedMinutesToday} мин (лимит ${app.dailyLimitMinutes} мин)",
                    color = Slate400,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                  )
                }
              }

              Switch(
                checked = !app.isBlocked,
                onCheckedChange = { onToggleAppBlocked(app) },
                colors = SwitchDefaults.colors(
                  checkedThumbColor = Color.Black,
                  checkedTrackColor = EmeraldShield,
                  uncheckedThumbColor = Color.White,
                  uncheckedTrackColor = RoseLock
                )
              )
            }
          }
        }
      }
    }
  }
}
