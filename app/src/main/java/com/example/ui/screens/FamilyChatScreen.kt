package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import com.example.ui.theme.CyanContainer
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShield
import com.example.ui.theme.RoseLock
import com.example.ui.theme.Slate200
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate800
import com.example.ui.theme.Slate900
import com.example.ui.theme.Slate950
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun FamilyChatScreen(
  child: ChildProfileEntity?,
  messages: List<ChatMessageEntity>,
  isParentRole: Boolean,
  onSendMessage: (String) -> Unit,
  onSendAttentionSignal: () -> Unit = {},
  onTriggerSilentUpdate: (Long) -> Unit = {},
  isRemoteUpdating: Boolean = false,
  modifier: Modifier = Modifier
) {
  var messageInput by remember { mutableStateOf("") }
  val listState = rememberLazyListState()

  // Auto-scroll to bottom when new messages arrive
  LaunchedEffect(messages.size) {
    if (messages.isNotEmpty()) {
      listState.animateScrollToItem(messages.size - 1)
    }
  }

  Column(
    modifier = modifier
      .fillMaxSize()
      .background(Slate950)
  ) {
    // Top Chat Status Bar
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 12.dp, vertical = 6.dp),
      shape = RoundedCornerShape(14.dp),
      colors = CardDefaults.cardColors(containerColor = Slate900),
      border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Box(
            modifier = Modifier
              .size(10.dp)
              .clip(CircleShape)
              .background(EmeraldShield)
          )
          Column {
            Text(
              text = if (isParentRole) "Чат с: ${child?.name ?: "Ребёнок"}" else "Связь с родителями",
              color = Color.White,
              fontWeight = FontWeight.Bold,
              fontSize = 14.sp
            )
            Text(
              text = if (isParentRole) "Прямой облачный канал связи" else "Защищенный семейный чат",
              color = Slate400,
              fontSize = 11.sp
            )
          }
        }

        if (isParentRole) {
          Button(
            onClick = onSendAttentionSignal,
            colors = ButtonDefaults.buttonColors(containerColor = RoseLock),
            shape = RoundedCornerShape(8.dp),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
            modifier = Modifier.height(32.dp)
          ) {
            Icon(Icons.Default.NotificationsActive, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text("Звук", fontSize = 11.sp, fontWeight = FontWeight.Bold)
          }
        }
      }
    }

    // Messages List
    if (messages.isEmpty()) {
      Box(
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(24.dp),
        contentAlignment = Alignment.Center
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
          Icon(
            imageVector = Icons.Default.Chat,
            contentDescription = null,
            tint = Slate800,
            modifier = Modifier.size(56.dp)
          )
          Text(
            text = "Нет сообщений",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 15.sp
          )
          Text(
            text = if (isParentRole) "Напишите ребенку сообщение или отправьте сигнал."
            else "Напишите родителям. Они сразу увидят ваше сообщение.",
            color = Slate400,
            fontSize = 12.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
          )
        }
      }
    } else {
      LazyColumn(
        state = listState,
        modifier = Modifier
          .weight(1f)
          .fillMaxWidth()
          .padding(horizontal = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 12.dp)
      ) {
        items(messages, key = { it.id }) { msg ->
          val isMyMessage = if (isParentRole) msg.senderRole == "PARENT" else msg.senderRole == "CHILD"
          val timeStr = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(msg.timestamp))

          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = if (isMyMessage) Arrangement.End else Arrangement.Start
          ) {
            Card(
              shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isMyMessage) 16.dp else 4.dp,
                bottomEnd = if (isMyMessage) 4.dp else 16.dp
              ),
              colors = CardDefaults.cardColors(
                containerColor = when {
                  msg.isAttentionSignal -> Color(0xFF7F1D1D)
                  isMyMessage -> Color(0xFF0369A1) // Deep Cyan / Blue for me
                  else -> Slate900 // Dark Slate for other
                }
              ),
              border = androidx.compose.foundation.BorderStroke(
                1.dp,
                if (msg.isAttentionSignal) RoseLock else if (isMyMessage) CyanLight.copy(alpha = 0.4f) else Slate800
              ),
              modifier = Modifier.fillMaxWidth(0.82f)
            ) {
              Column(modifier = Modifier.padding(10.dp)) {
                Row(
                  modifier = Modifier.fillMaxWidth(),
                  horizontalArrangement = Arrangement.SpaceBetween,
                  verticalAlignment = Alignment.CenterVertically
                ) {
                  Text(
                    text = msg.senderName,
                    color = if (isMyMessage) CyanLight else EmeraldShield,
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp
                  )
                  Text(
                    text = timeStr,
                    color = Slate400,
                    fontSize = 10.sp
                  )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                  text = msg.text,
                  color = Color.White,
                  fontSize = 13.sp,
                  lineHeight = 17.sp
                )
              }
            }
          }
        }
      }
    }

    // Input Box Area
    Card(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = 10.dp, vertical = 8.dp),
      shape = RoundedCornerShape(20.dp),
      colors = CardDefaults.cardColors(containerColor = Slate900),
      border = androidx.compose.foundation.BorderStroke(1.dp, Slate800)
    ) {
      Row(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        OutlinedTextField(
          value = messageInput,
          onValueChange = { messageInput = it },
          placeholder = {
            Text(
              if (isParentRole) "Сообщение для ребенка..." else "Сообщение родителям...",
              color = Slate400,
              fontSize = 13.sp
            )
          },
          colors = OutlinedTextFieldDefaults.colors(
            focusedBorderColor = Color.Transparent,
            unfocusedBorderColor = Color.Transparent,
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = CyanLight
          ),
          modifier = Modifier
            .weight(1f)
            .testTag("chat_input_field"),
          maxLines = 3
        )

        IconButton(
          onClick = {
            if (messageInput.isNotBlank()) {
              onSendMessage(messageInput.trim())
              messageInput = ""
            }
          },
          enabled = messageInput.isNotBlank(),
          modifier = Modifier
            .size(40.dp)
            .background(
              if (messageInput.isNotBlank()) CyanLight else Slate800,
              CircleShape
            )
            .testTag("chat_send_button")
        ) {
          Icon(
            imageVector = Icons.AutoMirrored.Filled.Send,
            contentDescription = "Отправить",
            tint = if (messageInput.isNotBlank()) Slate950 else Slate400,
            modifier = Modifier.size(18.dp)
          )
        }
      }
    }
  }
}
