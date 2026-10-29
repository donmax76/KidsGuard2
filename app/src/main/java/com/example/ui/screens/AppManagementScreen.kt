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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Games
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.AppControlEntity
import com.example.ui.components.AppItemCard
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.RoseContainer
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950

@Composable
fun AppManagementScreen(
  apps: List<AppControlEntity>,
  isStudyModeActive: Boolean,
  onToggleAppBlocked: (AppControlEntity) -> Unit,
  onUpdateAppLimit: (Long, Int) -> Unit,
  onAddNewApp: (name: String, category: String, limit: Int, iconType: String) -> Unit,
  onSyncRealApps: (() -> Unit)? = null,
  onBatchSetBlocked: ((List<AppControlEntity>, Boolean) -> Unit)? = null,
  modifier: Modifier = Modifier
) {
  var searchQuery by remember { mutableStateOf("") }
  var selectedCategory by remember { mutableStateOf("Все") }
  var isAddDialogVisible by remember { mutableStateOf(false) }
  val selectedAppIds = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateListOf<Long>() }

  val categories = listOf("Все", "Разрешённые", "Заблокированные", "Игры", "Соцсети", "Видео", "Образование")

  val filteredApps = apps.filter { app ->
    val matchesSearch = app.appName.contains(searchQuery, ignoreCase = true)
    val matchesCategory = when (selectedCategory) {
      "Все" -> true
      "Разрешённые" -> !app.isBlocked
      "Заблокированные" -> app.isBlocked
      else -> app.category.equals(selectedCategory, ignoreCase = true)
    }
    matchesSearch && matchesCategory
  }

  val gamesCount = apps.count { it.category == "Игры" }
  val allGamesBlocked = apps.filter { it.category == "Игры" }.all { it.isBlocked }

  Scaffold(
    modifier = modifier.fillMaxSize(),
    containerColor = Slate950,
    floatingActionButton = {
      FloatingActionButton(
        onClick = { isAddDialogVisible = true },
        containerColor = CyanLight,
        contentColor = Slate950,
        modifier = Modifier.testTag("add_app_fab")
      ) {
        Icon(Icons.Default.Add, contentDescription = "Добавить приложение")
      }
    }
  ) { paddingValues ->
    LazyColumn(
      modifier = Modifier
        .fillMaxSize()
        .padding(paddingValues)
        .padding(horizontal = 16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
      item {
        Spacer(modifier = Modifier.height(8.dp))
        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
          verticalAlignment = Alignment.CenterVertically
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text(
              text = "Управление приложениями (${apps.size})",
              color = Color.White,
              fontSize = 20.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Выбирайте приложения списком для принудительной блокировки или разрешения.",
              color = Slate400,
              fontSize = 12.sp
            )
          }

          if (onSyncRealApps != null) {
            Button(
              onClick = onSyncRealApps,
              colors = ButtonDefaults.buttonColors(containerColor = Slate900),
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.padding(start = 8.dp).testTag("sync_apps_button")
            ) {
              Icon(Icons.Default.Refresh, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Сканировать", color = CyanLight, fontSize = 12.sp)
            }
          }
        }
      }

      // Study mode active notice
      if (isStudyModeActive) {
        item {
          Card(
            colors = CardDefaults.cardColors(containerColor = EmeraldContainer),
            shape = RoundedCornerShape(14.dp)
          ) {
            Row(
              modifier = Modifier.padding(14.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Icon(Icons.Default.CheckCircle, contentDescription = null, tint = EmeraldShieldLight)
              Text(
                text = "Активен «Режим учебы»: все развлекательные приложения заблокированы, доступны только Duolingo и Шахматы.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
              )
            }
          }
        }
      }

      // Multi-Selection & Batch Force Block / Allow Card
      item {
        val selectedList = apps.filter { selectedAppIds.contains(it.id) }
        val targetList = if (selectedList.isNotEmpty()) selectedList else filteredApps
        Card(
          shape = RoundedCornerShape(16.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900),
          border = androidx.compose.foundation.BorderStroke(1.dp, CyanLight.copy(alpha = 0.4f))
        ) {
          Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Text(
                text = if (selectedList.isNotEmpty()) "Выбрано приложений: ${selectedList.size}"
                else "Массовое управление (${filteredApps.size} прил.)",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
              )
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                  onClick = {
                    val allVisibleSelected = filteredApps.isNotEmpty() && filteredApps.all { selectedAppIds.contains(it.id) }
                    if (allVisibleSelected) {
                      selectedAppIds.clear()
                    } else {
                      filteredApps.forEach { if (!selectedAppIds.contains(it.id)) selectedAppIds.add(it.id) }
                    }
                  },
                  contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 6.dp, vertical = 2.dp)
                ) {
                  Text(
                    text = if (filteredApps.isNotEmpty() && filteredApps.all { selectedAppIds.contains(it.id) }) "Сбросить выбор" else "Выбрать все",
                    color = CyanLight,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                  )
                }
              }
            }

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Button(
                onClick = {
                  if (onBatchSetBlocked != null) {
                    onBatchSetBlocked(targetList, true)
                  } else {
                    targetList.forEach { if (!it.isBlocked) onToggleAppBlocked(it) }
                  }
                  selectedAppIds.clear()
                },
                enabled = targetList.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = RoseLock, contentColor = Color.White),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).height(38.dp)
              ) {
                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Заблокировать (${targetList.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }

              Button(
                onClick = {
                  if (onBatchSetBlocked != null) {
                    onBatchSetBlocked(targetList, false)
                  } else {
                    targetList.forEach { if (it.isBlocked) onToggleAppBlocked(it) }
                  }
                  selectedAppIds.clear()
                },
                enabled = targetList.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = EmeraldShield, contentColor = Color.Black),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.weight(1f).height(38.dp)
              ) {
                Icon(Icons.Default.CheckCircle, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text("Разрешить (${targetList.size})", fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }

      // Quick batch action: Block all games
      item {
        Card(
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900)
        ) {
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Icon(Icons.Default.Games, contentDescription = null, tint = CyanLight)
              Text(
                text = "Быстрое действие: Игры ($gamesCount)",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold
              )
            }

            Button(
              onClick = {
                val games = apps.filter { it.category == "Игры" }
                if (onBatchSetBlocked != null) {
                  onBatchSetBlocked(games, !allGamesBlocked)
                } else {
                  games.forEach { app ->
                    if (app.isBlocked == allGamesBlocked) {
                      onToggleAppBlocked(app)
                    }
                  }
                }
              },
              colors = ButtonDefaults.buttonColors(
                containerColor = if (allGamesBlocked) EmeraldShield else RoseLock,
                contentColor = if (allGamesBlocked) Color.Black else Color.White
              ),
              shape = RoundedCornerShape(8.dp),
              modifier = Modifier.height(34.dp)
            ) {
              Text(
                text = if (allGamesBlocked) "Разблокировать" else "Отключить все",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
              )
            }
          }
        }
      }

      // Search & Filters
      item {
        OutlinedTextField(
          value = searchQuery,
          onValueChange = { searchQuery = it },
          modifier = Modifier
            .fillMaxWidth()
            .testTag("search_app_input"),
          placeholder = { Text("Поиск приложения...", color = Slate400) },
          leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Slate400) },
          trailingIcon = {
            if (searchQuery.isNotEmpty()) {
              IconButton(onClick = { searchQuery = "" }) {
                Icon(Icons.Default.Close, contentDescription = "Очистить", tint = Slate400)
              }
            }
          },
          singleLine = true,
          shape = RoundedCornerShape(12.dp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          items(categories) { cat ->
            val isSelected = selectedCategory == cat
            FilterChip(
              selected = isSelected,
              onClick = { selectedCategory = cat },
              label = { Text(cat, fontSize = 12.sp) },
              colors = FilterChipDefaults.filterChipColors(
                selectedContainerColor = CyanLight,
                selectedLabelColor = Slate950,
                containerColor = Slate900,
                labelColor = Slate400
              ),
              shape = RoundedCornerShape(20.dp)
            )
          }
        }
      }

      // List of applications
      items(filteredApps, key = { it.id }) { app ->
        val isChecked = selectedAppIds.contains(app.id)
        AppItemCard(
          app = app,
          isSelected = isChecked,
          onSelectToggle = {
            if (isChecked) selectedAppIds.remove(app.id) else selectedAppIds.add(app.id)
          },
          onToggleBlocked = { onToggleAppBlocked(app) },
          onUpdateLimit = { newLimit -> onUpdateAppLimit(app.id, newLimit) }
        )
      }

      if (filteredApps.isEmpty()) {
        item {
          Card(
            modifier = Modifier
              .fillMaxWidth()
              .padding(vertical = 20.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Slate900),
            border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
          ) {
            Column(
              modifier = Modifier.padding(24.dp),
              horizontalAlignment = Alignment.CenterHorizontally,
              verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Icon(Icons.Default.Games, contentDescription = null, tint = CyanLight, modifier = Modifier.size(36.dp))
              Text(
                text = if (apps.isEmpty()) "Список приложений пуст" else "Приложения не найдены",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp
              )
              Text(
                text = if (apps.isEmpty()) "Вы можете просканировать все реальные программы, установленные на телефоне ребенка, либо добавить приложение вручную."
                else "По запросу «$searchQuery» ничего не найдено.",
                color = Slate400,
                fontSize = 12.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
              )
              if (apps.isEmpty() && onSyncRealApps != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Button(
                  onClick = onSyncRealApps,
                  colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
                  shape = RoundedCornerShape(10.dp)
                ) {
                  Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Сканировать программы телефона", fontWeight = FontWeight.Bold)
                }
              }
            }
          }
        }
      }

      item {
        Spacer(modifier = Modifier.height(70.dp))
      }
    }
  }

  if (isAddDialogVisible) {
    AddNewAppDialog(
      onDismiss = { isAddDialogVisible = false },
      onConfirm = { name, cat, limit, icon ->
        onAddNewApp(name, cat, limit, icon)
        isAddDialogVisible = false
      }
    )
  }
}

@Composable
fun AddNewAppDialog(
  onDismiss: () -> Unit,
  onConfirm: (name: String, category: String, limit: Int, iconType: String) -> Unit
) {
  var appName by remember { mutableStateOf("") }
  var category by remember { mutableStateOf("Игры") }
  var limitMinutes by remember { mutableStateOf("30") }
  var selectedIcon by remember { mutableStateOf("games") }

  val catOptions = listOf("Игры", "Соцсети", "Видео", "Образование", "Интернет")

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Добавить приложение", color = Color.White) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = appName,
          onValueChange = { appName = it },
          label = { Text("Название приложения") },
          placeholder = { Text("например, Standoff 2, VK, Discord") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        Text("Категория:", color = Slate400, fontSize = 12.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          items(catOptions) { cat ->
            FilterChip(
              selected = category == cat,
              onClick = { category = cat },
              label = { Text(cat, fontSize = 11.sp) }
            )
          }
        }

        OutlinedTextField(
          value = limitMinutes,
          onValueChange = { limitMinutes = it.filter { ch -> ch.isDigit() } },
          label = { Text("Дневной лимит (минут)") },
          placeholder = { Text("0 для безлимита") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (appName.isNotBlank()) {
            val mins = limitMinutes.toIntOrNull() ?: 0
            val icon = when (category) {
              "Игры" -> "roblox"
              "Соцсети" -> "telegram"
              "Видео" -> "youtube"
              "Образование" -> "duolingo"
              else -> "browser"
            }
            onConfirm(appName.trim(), category, mins, icon)
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950)
      ) {
        Text("Добавить")
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
