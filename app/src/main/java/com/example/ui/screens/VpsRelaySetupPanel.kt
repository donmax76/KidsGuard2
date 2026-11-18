package com.example.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.cloud.DirectP2pSocketEngine

/**
 * Панель настройки VPS / Cloudflare Relay. Сохраняет настройки локально (в движке)
 * и, если задан onSave, отдаёт их наружу (родитель шлёт CONFIGURE_VPS ребёнку).
 */
@Composable
fun VpsRelaySetupPanel(onSave: (enabled: Boolean, host: String, port: Int, password: String, roomCode: String) -> Unit) {
  val ctx = LocalContext.current
  val prefs = remember { ctx.getSharedPreferences("kidsguard_vps_prefs", android.content.Context.MODE_PRIVATE) }
  var enabled by remember { mutableStateOf(prefs.getBoolean("enabled", false)) }
  var host by remember { mutableStateOf(prefs.getString("host", "").orEmpty()) }
  var port by remember { mutableStateOf(prefs.getInt("port", 443).toString()) }
  var password by remember { mutableStateOf(prefs.getString("password", "1234").orEmpty()) }
  var roomCode by remember { mutableStateOf(prefs.getString("roomCode", "101-701").orEmpty()) }
  var saved by remember { mutableStateOf(false) }

  Card(
    modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
    colors = CardDefaults.cardColors(containerColor = androidx.compose.ui.graphics.Color(0xFF0F172A))
  ) {
    Column(modifier = Modifier.padding(14.dp)) {
      Text("☁️ VPS / Cloudflare Релей", color = androidx.compose.ui.graphics.Color.White, fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
      Spacer(Modifier.height(6.dp))
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Включить VPS Релей", color = androidx.compose.ui.graphics.Color(0xFFCBD5E1), fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Switch(checked = enabled, onCheckedChange = { enabled = it })
      }
      OutlinedTextField(
        value = host, onValueChange = { host = it },
        label = { Text("Домен Cloudflare или IP VPS") },
        placeholder = { Text("relay.donmaxai.trade или IP") },
        singleLine = true, modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = port, onValueChange = { port = it.filter { c -> c.isDigit() } },
        label = { Text("Порт") },
        placeholder = { Text("443 (Cloudflare) или 18830 (IP)") },
        singleLine = true, modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = password, onValueChange = { password = it },
        label = { Text("Пароль VPS") },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true, modifier = Modifier.fillMaxWidth()
      )
      OutlinedTextField(
        value = roomCode, onValueChange = { roomCode = it },
        label = { Text("Room Number (Код P2P комнаты)") },
        placeholder = { Text("101-701") },
        singleLine = true, modifier = Modifier.fillMaxWidth()
      )
      Spacer(Modifier.height(8.dp))
      Button(onClick = {
        val cleanHost = host.trim()
        val effectiveEnabled = enabled && cleanHost.isNotBlank()
        enabled = effectiveEnabled
        val p = port.toIntOrNull() ?: 443
        onSave(effectiveEnabled, cleanHost, p, password, roomCode.trim())
        DirectP2pSocketEngine.configureVpsRelay(effectiveEnabled, cleanHost, p, password, roomCode.trim())
        saved = true
      }, modifier = Modifier.fillMaxWidth()) {
        Text(
          if (saved && host.trim().isBlank() && !enabled) "✓ VPS выключен (работают P2P-брокеры)"
          else if (saved) "✓ Сохранено и отправлено ребёнку"
          else "Сохранить и подключить VPS"
        )
      }
    }
  }
}
