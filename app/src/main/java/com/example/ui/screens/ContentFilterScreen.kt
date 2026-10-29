package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DoneAll
import androidx.compose.material.icons.filled.Explicit
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.LocalBar
import androidx.compose.material.icons.filled.Policy
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.SmartDisplay
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ContentRuleEntity
import com.example.data.model.ParentSettingsEntity
import com.example.data.repository.ContentFilterEvaluation
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
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

private data class QuickDomainPreset(
  val domain: String,
  val label: String,
  val tag: String
)

private val POPULAR_DOMAIN_PRESETS = listOf(
  QuickDomainPreset("tiktok.com", "TikTok Web", "Соцсеть"),
  QuickDomainPreset("roblox.com", "Roblox", "Игры"),
  QuickDomainPreset("twitch.tv", "Twitch Стримы", "Стримы"),
  QuickDomainPreset("discord.com", "Discord", "Чат"),
  QuickDomainPreset("omegle.com", "Omegle / Чат-рулетка", "18+ Чат"),
  QuickDomainPreset("1xbet.com", "1xBet Ставки", "Казино"),
  QuickDomainPreset("funpay.com", "FunPay Донаты", "Покупки"),
  QuickDomainPreset("vk.com", "ВКонтакте", "Соцсеть"),
  QuickDomainPreset("t.me", "Telegram Web", "Мессенджер"),
  QuickDomainPreset("pornhub.com", "PornHub 18+", "18+")
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentFilterScreen(
  settings: ParentSettingsEntity?,
  rules: List<ContentRuleEntity>,
  testEvaluation: ContentFilterEvaluation?,
  onToggleRule: (ContentRuleEntity) -> Unit,
  onBatchSetRulesBlocked: (List<ContentRuleEntity>, Boolean) -> Unit = { _, _ -> },
  onAddRule: (type: String, value: String, displayName: String) -> Unit,
  onDeleteRule: (ContentRuleEntity) -> Unit,
  onToggleSafeSearch: (Boolean) -> Unit,
  onToggleYoutubeRestricted: (Boolean) -> Unit,
  onTestFilterQuery: (String) -> Unit,
  onClearFilterTest: () -> Unit,
  modifier: Modifier = Modifier
) {
  var isAddDialogVisible by remember { mutableStateOf(false) }
  var testInputText by remember { mutableStateOf("") }

  // Inline Blacklist Domain Add Form State
  var newDomainInput by remember { mutableStateOf("") }
  var newDomainLabel by remember { mutableStateOf("") }
  var domainSearchQuery by remember { mutableStateOf("") }
  var domainFilterMode by remember { mutableStateOf("ALL") } // "ALL", "BLOCKED", "ALLOWED"

  val categoryRules = rules.filter { it.ruleType == "CATEGORY" }
  val domainRules = rules.filter { it.ruleType == "DOMAIN" }
  val keywordRules = rules.filter { it.ruleType == "KEYWORD" }

  val filteredDomainRules = domainRules.filter { rule ->
    val matchesSearch = domainSearchQuery.isBlank() ||
      rule.value.contains(domainSearchQuery, ignoreCase = true) ||
      rule.displayName.contains(domainSearchQuery, ignoreCase = true)
    val matchesFilter = when (domainFilterMode) {
      "BLOCKED" -> rule.isBlocked
      "ALLOWED" -> !rule.isBlocked
      else -> true
    }
    matchesSearch && matchesFilter
  }

  val blockedCategoriesCount = categoryRules.count { it.isBlocked }
  val activeBlacklistedDomainsCount = domainRules.count { it.isBlocked }
  val totalHitsCount = rules.sumOf { it.hitsCount }

  LazyColumn(
    modifier = modifier
      .fillMaxSize()
      .background(Slate950)
      .padding(horizontal = 16.dp),
    verticalArrangement = Arrangement.spacedBy(16.dp)
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
            text = "Веб-фильтр и Черный список",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold
          )
          Text(
            text = "Блокировка категорий сайтов, доменов (URL) и опасных запросов в браузерах ребенка",
            color = Slate400,
            fontSize = 12.sp
          )
        }

        Button(
          onClick = { isAddDialogVisible = true },
          colors = ButtonDefaults.buttonColors(containerColor = RoseLock, contentColor = Color.White),
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.testTag("add_content_rule_button")
        ) {
          Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(6.dp))
          Text("Правило", fontWeight = FontWeight.Bold, fontSize = 12.sp)
        }
      }
    }

    // Summary Dashboard Banner
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, EmeraldShield.copy(alpha = 0.4f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
      ) {
        Column(
          modifier = Modifier.padding(14.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(10.dp)
                  .clip(CircleShape)
                  .background(EmeraldShieldLight)
              )
              Text(
                text = "АКТИВНЫЙ ПЕРЕХВАТ В БРАУЗЕРАХ (CHROME, YANDEX, OPERA, SAFARI/WEB)",
                color = EmeraldShieldLight,
                fontSize = 10.sp,
                fontWeight = FontWeight.ExtraBold
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            FilterStatTile(
              title = "Категории",
              value = "$blockedCategoriesCount / ${categoryRules.size.coerceAtLeast(8)}",
              subtitle = "запрещено",
              accentColor = CyanLight,
              modifier = Modifier.weight(1f)
            )
            FilterStatTile(
              title = "Черный список",
              value = "$activeBlacklistedDomainsCount",
              subtitle = "доменов / URL",
              accentColor = RoseLock,
              modifier = Modifier.weight(1f)
            )
            FilterStatTile(
              title = "Отражено",
              value = "$totalHitsCount",
              subtitle = "попыток входа",
              accentColor = AmberWarning,
              modifier = Modifier.weight(1f)
            )
          }
        }
      }
    }

    // 1. DOMAIN & URL BLACKLIST PANEL (NEW PRIMARY SECTION)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, RoseLock.copy(alpha = 0.45f), RoundedCornerShape(18.dp)),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
      ) {
        Column(
          modifier = Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
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
                  .clip(RoundedCornerShape(12.dp))
                  .background(RoseContainer),
                contentAlignment = Alignment.Center
              ) {
                Icon(Icons.Default.LinkOff, contentDescription = null, tint = RoseLock)
              }
              Column {
                Text(
                  text = "Черный список доменов и URL",
                  color = Color.White,
                  fontWeight = FontWeight.Bold,
                  fontSize = 16.sp
                )
                Text(
                  text = "Мгновенная блокировка конкретных сайтов и веб-адресов на телефоне ребенка",
                  color = Slate400,
                  fontSize = 11.sp
                )
              }
            }
          }

          // Direct inline add domain form
          Column(
            modifier = Modifier
              .fillMaxWidth()
              .clip(RoundedCornerShape(12.dp))
              .background(Slate950)
              .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = "ДОБАВИТЬ САЙТ ИЛИ URL В ЧЕРНЫЙ СПИСОК:",
              color = CyanLight,
              fontSize = 11.sp,
              fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
              value = newDomainInput,
              onValueChange = { newDomainInput = it },
              placeholder = { Text("Например: tiktok.com, 1xbet.com или https://site.ru", fontSize = 12.sp) },
              singleLine = true,
              leadingIcon = { Icon(Icons.Default.Language, contentDescription = null, tint = RoseLock) },
              trailingIcon = {
                if (newDomainInput.isNotEmpty()) {
                  IconButton(onClick = { newDomainInput = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Очистить", tint = Slate400)
                  }
                }
              },
              modifier = Modifier
                .fillMaxWidth()
                .testTag("blacklist_domain_input"),
              shape = RoundedCornerShape(10.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = RoseLock,
                unfocusedBorderColor = Slate800,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
              )
            )

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              OutlinedTextField(
                value = newDomainLabel,
                onValueChange = { newDomainLabel = it },
                placeholder = { Text("Описание (необязательно)", fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
                colors = OutlinedTextFieldDefaults.colors(
                  focusedBorderColor = CyanLight,
                  unfocusedBorderColor = Slate800,
                  focusedTextColor = Color.White,
                  unfocusedTextColor = Color.White
                )
              )

              Button(
                onClick = {
                  val cleanDomain = newDomainInput.trim().lowercase()
                    .removePrefix("https://")
                    .removePrefix("http://")
                    .removePrefix("www.")
                    .trimEnd('/')
                  if (cleanDomain.isNotBlank()) {
                    val label = newDomainLabel.trim().ifBlank { "Сайт $cleanDomain" }
                    onAddRule("DOMAIN", cleanDomain, label)
                    newDomainInput = ""
                    newDomainLabel = ""
                  }
                },
                enabled = newDomainInput.isNotBlank(),
                colors = ButtonDefaults.buttonColors(
                  containerColor = RoseLock,
                  contentColor = Color.White
                ),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                  .height(52.dp)
                  .testTag("add_domain_to_blacklist_btn")
              ) {
                Icon(Icons.Default.Block, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("В черный список", fontWeight = FontWeight.Bold, fontSize = 12.sp)
              }
            }
          }

          // Quick 1-Tap Popular Unwanted Domains
          Text(
            text = "Быстрая блокировка популярных сайтов в 1 касание:",
            color = Slate200,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
          )

          FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            POPULAR_DOMAIN_PRESETS.forEach { preset ->
              val existingRule = domainRules.find {
                it.value.equals(preset.domain, ignoreCase = true) ||
                  it.value.contains(preset.domain, ignoreCase = true)
              }
              val isCurrentlyBlocked = existingRule?.isBlocked == true

              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(10.dp))
                  .background(if (isCurrentlyBlocked) RoseContainer else Slate950)
                  .border(
                    1.dp,
                    if (isCurrentlyBlocked) RoseLock else Slate800,
                    RoundedCornerShape(10.dp)
                  )
                  .clickable {
                    if (existingRule != null) {
                      onToggleRule(existingRule)
                    } else {
                      onAddRule("DOMAIN", preset.domain, "${preset.label} (${preset.tag})")
                    }
                  }
                  .padding(horizontal = 10.dp, vertical = 6.dp)
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                  Icon(
                    imageVector = if (isCurrentlyBlocked) Icons.Default.Block else Icons.Default.Add,
                    contentDescription = null,
                    tint = if (isCurrentlyBlocked) RoseLock else CyanLight,
                    modifier = Modifier.size(14.dp)
                  )
                  Text(
                    text = preset.domain,
                    color = if (isCurrentlyBlocked) Color.White else Slate200,
                    fontSize = 11.sp,
                    fontWeight = if (isCurrentlyBlocked) FontWeight.Bold else FontWeight.Medium
                  )
                }
              }
            }
          }

          // Search & Batch Controls for Domain Blacklist
          if (domainRules.isNotEmpty()) {
            OutlinedTextField(
              value = domainSearchQuery,
              onValueChange = { domainSearchQuery = it },
              placeholder = { Text("Поиск по черному списку доменов...", fontSize = 12.sp) },
              singleLine = true,
              leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, tint = Slate400, modifier = Modifier.size(18.dp)) },
              trailingIcon = {
                if (domainSearchQuery.isNotEmpty()) {
                  IconButton(onClick = { domainSearchQuery = "" }) {
                    Icon(Icons.Default.Clear, contentDescription = "Сбросить", tint = Slate400, modifier = Modifier.size(16.dp))
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
              shape = RoundedCornerShape(10.dp),
              colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = CyanLight,
                unfocusedBorderColor = Slate800,
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
              )
            )

            Row(
              modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              FilterChip(
                selected = domainFilterMode == "ALL",
                onClick = { domainFilterMode = "ALL" },
                label = { Text("Все (${domainRules.size})", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = CyanContainer,
                  selectedLabelColor = CyanLight
                )
              )
              FilterChip(
                selected = domainFilterMode == "BLOCKED",
                onClick = { domainFilterMode = "BLOCKED" },
                label = { Text("Заблокированные (${domainRules.count { it.isBlocked }})", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = RoseContainer,
                  selectedLabelColor = RoseLock
                )
              )
              FilterChip(
                selected = domainFilterMode == "ALLOWED",
                onClick = { domainFilterMode = "ALLOWED" },
                label = { Text("Разрешенные (${domainRules.count { !it.isBlocked }})", fontSize = 11.sp) },
                colors = FilterChipDefaults.filterChipColors(
                  selectedContainerColor = EmeraldContainer,
                  selectedLabelColor = EmeraldShieldLight
                )
              )

              OutlinedButton(
                onClick = { onBatchSetRulesBlocked(domainRules, true) },
                shape = RoundedCornerShape(8.dp)
              ) {
                Text("Блокировать все", color = RoseLock, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
              OutlinedButton(
                onClick = { onBatchSetRulesBlocked(domainRules, false) },
                shape = RoundedCornerShape(8.dp)
              ) {
                Text("Разрешить все", color = EmeraldShieldLight, fontSize = 11.sp, fontWeight = FontWeight.Bold)
              }
            }
          }
        }
      }
    }

    // List of Blacklisted Domains
    if (filteredDomainRules.isEmpty()) {
      item {
        Card(
          modifier = Modifier.fillMaxWidth(),
          shape = RoundedCornerShape(14.dp),
          colors = CardDefaults.cardColors(containerColor = Slate900)
        ) {
          Text(
            text = if (domainRules.isEmpty()) {
              "Черный список доменов пуст. Введите адрес сайта выше или нажмите на один из популярных доменов."
            } else {
              "По вашему запросу домены в черном списке не найдены."
            },
            color = Slate400,
            fontSize = 12.sp,
            modifier = Modifier.padding(16.dp)
          )
        }
      }
    } else {
      items(filteredDomainRules, key = { "domain_${it.id}_${it.value}" }) { rule ->
        CustomRuleItemCard(
          rule = rule,
          onToggle = { onToggleRule(rule) },
          onDelete = { onDeleteRule(rule) }
        )
      }
    }

    // 2. CATEGORIES OF WEBSITES SECTION
    item {
      Spacer(modifier = Modifier.height(4.dp))
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = "Категории сайтов (${blockedCategoriesCount} из ${categoryRules.size} заблокировано)",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
          )
          Text(
            text = "Автоматическая фильтрация тысяч сайтов по тематическим базам",
            color = Slate400,
            fontSize = 11.sp
          )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          TextButton(onClick = { onBatchSetRulesBlocked(categoryRules, true) }) {
            Icon(Icons.Default.DoneAll, contentDescription = null, tint = RoseLock, modifier = Modifier.size(15.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Все 8", color = RoseLock, fontSize = 12.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }

    items(categoryRules, key = { "cat_${it.id}_${it.value}" }) { rule ->
      CategoryFilterCard(
        rule = rule,
        onToggle = { onToggleRule(rule) }
      )
    }

    // 3. SAFE SEARCH & YOUTUBE RESTRICTED MODE
    item {
      Text(
        text = "Поисковые системы и видеоплатформы",
        color = Color.White,
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        modifier = Modifier.padding(top = 4.dp)
      )
    }

    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          FilterToggleRow(
            icon = Icons.Default.Search,
            title = "Безопасный поиск (Google / Yandex / Bing SafeSearch)",
            subtitle = "Скрывать откровенные фото, видео и ссылки 18+ в результатах поиска",
            checked = settings?.safeSearchEnabled ?: true,
            onCheckedChange = onToggleSafeSearch,
            testTag = "safe_search_switch"
          )

          Box(
            modifier = Modifier
              .fillMaxWidth()
              .height(1.dp)
              .background(Slate800)
          )

          FilterToggleRow(
            icon = Icons.Default.SmartDisplay,
            title = "Строгий режим YouTube и Shorts (Restricted Mode)",
            subtitle = "Блокировать видео с возрастным ограничением и токсичные комментарии",
            checked = settings?.youtubeRestricted ?: true,
            onCheckedChange = onToggleYoutubeRestricted,
            testTag = "youtube_restricted_switch"
          )
        }
      }
    }

    // 4. KEYWORD BLACKLIST SECTION
    item {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
      ) {
        Column {
          Text(
            text = "Запрещенные поисковые слова (${keywordRules.size})",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
          )
          Text(
            text = "Перехват опасных фраз при вводе в поисковую строку браузера",
            color = Slate400,
            fontSize = 11.sp
          )
        }
        TextButton(onClick = { isAddDialogVisible = true }) {
          Icon(Icons.Default.Add, contentDescription = null, tint = CyanLight, modifier = Modifier.size(16.dp))
          Spacer(modifier = Modifier.width(4.dp))
          Text("Добавить слово", color = CyanLight, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
      }
    }

    items(keywordRules, key = { "kw_${it.id}_${it.value}" }) { rule ->
      CustomRuleItemCard(
        rule = rule,
        onToggle = { onToggleRule(rule) },
        onDelete = { onDeleteRule(rule) }
      )
    }

    // 5. LIVE WEB FILTER TESTER (URL & QUERY SIMULATOR)
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .border(1.dp, CyanLight.copy(alpha = 0.3f), RoundedCornerShape(16.dp)),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Slate900)
      ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
          ) {
            Icon(Icons.Default.Shield, contentDescription = null, tint = CyanLight)
            Column {
              Text(
                text = "Проверка веб-фильтра и черного списка",
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
              )
              Text(
                text = "Проверьте, будет ли заблокирован конкретный домен, URL или запрос",
                color = Slate400,
                fontSize = 12.sp
              )
            }
          }

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
          ) {
            OutlinedTextField(
              value = testInputText,
              onValueChange = {
                testInputText = it
                if (it.isBlank()) onClearFilterTest()
              },
              placeholder = { Text("Введите URL или запрос (напр. 1xbet.com)", fontSize = 12.sp) },
              modifier = Modifier
                .weight(1f)
                .testTag("filter_test_input"),
              singleLine = true,
              shape = RoundedCornerShape(12.dp)
            )

            Button(
              onClick = { onTestFilterQuery(testInputText) },
              colors = ButtonDefaults.buttonColors(containerColor = CyanLight, contentColor = Slate950),
              shape = RoundedCornerShape(12.dp),
              modifier = Modifier.testTag("filter_test_button")
            ) {
              Text("Проверить", fontWeight = FontWeight.Bold, fontSize = 12.sp)
            }
          }

          // Quick test chips
          Row(
            modifier = Modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
          ) {
            listOf("1xbet.com", "pornhub.com", "tiktok.com", "ru.wikipedia.org").forEach { sampleUrl ->
              OutlinedButton(
                onClick = {
                  testInputText = sampleUrl
                  onTestFilterQuery(sampleUrl)
                },
                shape = RoundedCornerShape(8.dp)
              ) {
                Text(sampleUrl, color = Slate200, fontSize = 11.sp)
              }
            }
          }

          if (testEvaluation != null) {
            val isAllowed = testEvaluation.isAllowed
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(if (isAllowed) EmeraldContainer else RoseContainer)
                .padding(12.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              Icon(
                imageVector = if (isAllowed) Icons.Default.CheckCircle else Icons.Default.Block,
                contentDescription = null,
                tint = if (isAllowed) EmeraldShieldLight else RoseLock
              )
              Column {
                Text(
                  text = if (isAllowed) "ДОСТУП РАЗРЕШЕН" else "ЗАБЛОКИРОВАНО ВЕБ-ФИЛЬТРОМ",
                  color = if (isAllowed) EmeraldShieldLight else RoseLock,
                  fontWeight = FontWeight.Bold,
                  fontSize = 13.sp
                )
                Text(
                  text = testEvaluation.reason ?: "Сайт безопасен для посещения ребенком",
                  color = Slate200,
                  fontSize = 12.sp
                )
              }
            }
          }
        }
      }
    }

    item { Spacer(modifier = Modifier.height(24.dp)) }
  }

  if (isAddDialogVisible) {
    AddContentRuleDialog(
      onDismiss = { isAddDialogVisible = false },
      onConfirm = { type, value, name ->
        onAddRule(type, value, name)
        isAddDialogVisible = false
      }
    )
  }
}

@Composable
private fun FilterStatTile(
  title: String,
  value: String,
  subtitle: String,
  accentColor: Color,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .clip(RoundedCornerShape(12.dp))
      .background(Slate950)
      .border(1.dp, accentColor.copy(alpha = 0.25f), RoundedCornerShape(12.dp))
      .padding(horizontal = 10.dp, vertical = 8.dp)
  ) {
    Text(text = title, color = Slate400, fontSize = 10.sp)
    Text(text = value, color = accentColor, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
    Text(text = subtitle, color = Slate200, fontSize = 10.sp)
  }
}

@Composable
fun FilterToggleRow(
  icon: ImageVector,
  title: String,
  subtitle: String,
  checked: Boolean,
  onCheckedChange: (Boolean) -> Unit,
  testTag: String
) {
  Row(
    modifier = Modifier.fillMaxWidth(),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.SpaceBetween
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(12.dp),
      modifier = Modifier.weight(1f)
    ) {
      Box(
        modifier = Modifier
          .size(40.dp)
          .clip(CircleShape)
          .background(CyanContainer),
        contentAlignment = Alignment.Center
      ) {
        Icon(icon, contentDescription = null, tint = CyanLight, modifier = Modifier.size(20.dp))
      }

      Column {
        Text(text = title, color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
        Text(text = subtitle, color = Slate400, fontSize = 11.sp)
      }
    }

    Switch(
      checked = checked,
      onCheckedChange = onCheckedChange,
      modifier = Modifier.testTag(testTag),
      colors = SwitchDefaults.colors(
        checkedThumbColor = EmeraldShieldLight,
        checkedTrackColor = EmeraldShield.copy(alpha = 0.5f)
      )
    )
  }
}

@Composable
fun CategoryFilterCard(
  rule: ContentRuleEntity,
  onToggle: () -> Unit
) {
  val icon = when (rule.value) {
    "ADULT" -> Icons.Default.Explicit
    "GAMBLING" -> Icons.Default.Casino
    "VIOLENCE" -> Icons.Default.Warning
    "MALWARE" -> Icons.Default.Security
    "DRUGS" -> Icons.Default.LocalBar
    "GAMING_WEB" -> Icons.Default.SportsEsports
    "SOCIAL_DATING" -> Icons.Default.Forum
    "SHOPPING_DONATE" -> Icons.Default.ShoppingCart
    else -> Icons.Default.Policy
  }

  val examplesHint = when (rule.value) {
    "ADULT" -> "Примеры: pornhub, xvideos, onlyfans, эротика, 18+"
    "GAMBLING" -> "Примеры: 1xbet, fonbet, казино, слоты, рулетка, ставки"
    "VIOLENCE" -> "Примеры: шок-контент, оружие, насилие, экстремизм"
    "MALWARE" -> "Примеры: фишинг, взлом аккаунтов, скачивание читов/троянов"
    "DRUGS" -> "Примеры: вейпы, снюс, HQD, алкоголь, запрещенные вещества"
    "GAMING_WEB" -> "Примеры: poki.com, y8.com, roblox.com, twitch.tv"
    "SOCIAL_DATING" -> "Примеры: omegle, chatroulette, nekto.me, tinder, badoo"
    "SHOPPING_DONATE" -> "Примеры: funpay, playerok, покупка робуксов/донатов"
    else -> "Автоматическая фильтрация по базе категории"
  }

  Card(
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = if (rule.isBlocked) RoseLock.copy(alpha = 0.35f) else Slate800,
        shape = RoundedCornerShape(14.dp)
      )
      .testTag("category_rule_${rule.value}"),
    shape = RoundedCornerShape(14.dp),
    colors = CardDefaults.cardColors(containerColor = Slate900)
  ) {
    Row(
      modifier = Modifier
        .fillMaxWidth()
        .padding(14.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.SpaceBetween
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (rule.isBlocked) RoseContainer else Slate800),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (rule.isBlocked) RoseLock else Slate400,
            modifier = Modifier.size(22.dp)
          )
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
          Text(
            text = rule.displayName,
            color = Color.White,
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp
          )
          Text(
            text = examplesHint,
            color = Slate400,
            fontSize = 11.sp
          )
          Text(
            text = if (rule.isBlocked) "ЗАБЛОКИРОВАНО • Перехвачено попыток: ${rule.hitsCount}" else "РАЗРЕШЕНО",
            color = if (rule.isBlocked) RoseLock else EmeraldShieldLight,
            fontWeight = FontWeight.Bold,
            fontSize = 11.sp
          )
        }
      }

      Switch(
        checked = rule.isBlocked,
        onCheckedChange = { onToggle() },
        colors = SwitchDefaults.colors(
          checkedThumbColor = RoseLock,
          checkedTrackColor = RoseContainer,
          uncheckedThumbColor = Slate400,
          uncheckedTrackColor = Slate800
        )
      )
    }
  }
}

@Composable
fun CustomRuleItemCard(
  rule: ContentRuleEntity,
  onToggle: () -> Unit,
  onDelete: () -> Unit
) {
  Card(
    modifier = Modifier
      .fillMaxWidth()
      .border(
        width = 1.dp,
        color = if (rule.isBlocked) RoseLock.copy(alpha = 0.35f) else Slate800,
        shape = RoundedCornerShape(12.dp)
      ),
    shape = RoundedCornerShape(12.dp),
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.weight(1f)
      ) {
        Box(
          modifier = Modifier
            .size(36.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (rule.isBlocked) RoseContainer else Slate800),
          contentAlignment = Alignment.Center
        ) {
          Icon(
            imageVector = if (rule.ruleType == "DOMAIN") Icons.Default.Language else Icons.Default.Policy,
            contentDescription = null,
            tint = if (rule.isBlocked) RoseLock else Slate400,
            modifier = Modifier.size(18.dp)
          )
        }
        Column {
          Text(
            text = rule.value,
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
          )
          Text(
            text = buildString {
              append(if (rule.ruleType == "DOMAIN") "Домен в черном списке" else "Ключевое слово")
              if (rule.displayName.isNotBlank() && !rule.displayName.equals(rule.value, ignoreCase = true)) {
                append(" • ${rule.displayName}")
              }
              append(" • Перехватов: ${rule.hitsCount}")
            },
            color = if (rule.isBlocked) RoseLock else Slate400,
            fontSize = 11.sp
          )
        }
      }

      Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
          checked = rule.isBlocked,
          onCheckedChange = { onToggle() },
          colors = SwitchDefaults.colors(
            checkedThumbColor = RoseLock,
            checkedTrackColor = RoseContainer
          )
        )
        IconButton(onClick = onDelete) {
          Icon(Icons.Default.Delete, contentDescription = "Удалить", tint = Slate400, modifier = Modifier.size(18.dp))
        }
      }
    }
  }
}

@Composable
fun AddContentRuleDialog(
  onDismiss: () -> Unit,
  onConfirm: (type: String, value: String, displayName: String) -> Unit
) {
  var ruleType by remember { mutableStateOf("DOMAIN") }
  var inputVal by remember { mutableStateOf("") }
  var displayLabel by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Добавить в черный список", color = Color.White, fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
          RadioButton(selected = ruleType == "DOMAIN", onClick = { ruleType = "DOMAIN" })
          Text("Сайт / URL", color = Color.White, fontSize = 13.sp)
          Spacer(modifier = Modifier.width(16.dp))
          RadioButton(selected = ruleType == "KEYWORD", onClick = { ruleType = "KEYWORD" })
          Text("Ключевое слово", color = Color.White, fontSize = 13.sp)
        }

        OutlinedTextField(
          value = inputVal,
          onValueChange = { inputVal = it },
          label = { Text(if (ruleType == "DOMAIN") "Домен или URL (напр. tiktok.com)" else "Слово или фраза для блокировки") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )

        OutlinedTextField(
          value = displayLabel,
          onValueChange = { displayLabel = it },
          label = { Text("Название правила (необязательно)") },
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (inputVal.isNotBlank()) {
            val clean = inputVal.trim().lowercase()
              .removePrefix("https://")
              .removePrefix("http://")
              .removePrefix("www.")
              .trimEnd('/')
            val name = displayLabel.trim().ifBlank { clean }
            onConfirm(ruleType, clean, name)
          }
        },
        colors = ButtonDefaults.buttonColors(containerColor = RoseLock, contentColor = Color.White)
      ) {
        Text("Заблокировать", fontWeight = FontWeight.Bold)
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
