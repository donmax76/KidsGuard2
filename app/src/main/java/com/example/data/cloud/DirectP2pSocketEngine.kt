package com.example.data.cloud

import android.util.Log
import com.example.BuildConfig
import com.example.location.LiveLocationTracker
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "DirectP2pSocketEngine"

data class P2pConnectionStatus(
  val isLanSocketConnected: Boolean = false,
  val isGlobalTcpSocketConnected: Boolean = false,
  val childLanIp: String = "",
  val localDeviceIp: String = "",
  val tcpPort: Int = 49152,
  val packetsReceived: Int = 0,
  val lastPacketTimeMs: Long = 0L,
  val activeTransportLabel: String = "Подключение прямого TCP-сокета..."
)

/**
 * Dual-Layer Direct Peer-to-Peer (P2P) TCP Socket Engine with strict Per-Child Isolation:
 *
 * 1. Direct LAN/Wi-Fi/Hotspot P2P TCP Socket (Port 49152) + UDP Auto-Discovery Beacon (Port 49153).
 * 2. Persistent Raw Binary TCP Socket Tunnel (Port 1883 over broker.hivemq.com / broker.emqx.io)
 *    with per-child subtopics (state/{childId}, screen/{childId}, cmd/{childId}, lock/{childId}, data/{childId})
 *    so Child 1 and Child 2 never overwrite or mix each other's data.
 */
object DirectP2pSocketEngine {
  const val TCP_PORT = 49152
  const val UDP_DISCOVERY_PORT = 49153

  const val TOPIC_ROOT = "kidsguard/eb6ecfcf/v34_p2p"
  const val TOPIC_ROOT_LEGACY_PC = "kidsguard/eb6ecfcf/v33_p2p"
  const val TOPIC_WILDCARD_ALL = "kidsguard/eb6ecfcf/v34_p2p/#"
  const val TOPIC_STATE = "kidsguard/eb6ecfcf/v34_p2p/state"
  const val TOPIC_CMD = "kidsguard/eb6ecfcf/v34_p2p/cmd"
  const val TOPIC_SCREEN = "kidsguard/eb6ecfcf/v34_p2p/screen"
  const val TOPIC_DATA = "kidsguard/eb6ecfcf/v34_p2p/data"
  const val TOPIC_LOCK = "kidsguard/eb6ecfcf/v34_p2p/lock"

  private val TCP_RELAY_HOSTS = listOf(
    "broker.emqx.io" to 1883,
    "broker.hivemq.com" to 1883
  )

  /**
   * Индекс брокера, к которому последний раз удалось подключиться. Публичный hivemq иногда
   * принимает TCP, но не отвечает на CONNECT — раньше телефон «висел» на нём бесконечно
   * (чтение CONNACK без таймаута), и P2P/экран ПК не работали вовсе.
   */
  @Volatile
  private var preferredRelayHost = 0

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val _status = MutableStateFlow(P2pConnectionStatus(localDeviceIp = getLocalIpv4Address()))
  val status: StateFlow<P2pConnectionStatus> = _status.asStateFlow()

  // Strictly isolated per-child live screen frames: childId -> Triple(activeAppName, base64Bitmap, timestamp)
  private val _framesByChild = MutableStateFlow<Map<Long, Triple<String, String, Long>>>(emptyMap())
  val framesByChild: StateFlow<Map<Long, Triple<String, String, Long>>> = _framesByChild.asStateFlow()

  // Multicast command stream: Triple(targetChildId, command, payload)
  private val _incomingCommandsFlow = MutableSharedFlow<Triple<Long, String, String>>(extraBufferCapacity = 64)
  val incomingCommandsFlow: SharedFlow<Triple<Long, String, String>> = _incomingCommandsFlow.asSharedFlow()

  private val _incomingOfflineBundlesFlow = MutableSharedFlow<JSONObject>(extraBufferCapacity = 32)
  val incomingOfflineBundlesFlow: SharedFlow<JSONObject> = _incomingOfflineBundlesFlow.asSharedFlow()

  private val childLanIps = ConcurrentHashMap<Long, String>()
  private val connectedParentClients = CopyOnWriteArrayList<PrintWriter>()
  private var childServerSocket: ServerSocket? = null
  private var parentClientSocket: Socket? = null
  private var parentSocketWriter: PrintWriter? = null

  @Volatile
  private var globalTcpOut: BufferedOutputStream? = null
  private val globalWriteLock = Any()
  private val retainedPacketsOutbox = ConcurrentHashMap<String, JSONObject>()

  @Volatile
  private var isStarted = false

  @Volatile
  var currentRole: String = "PARENT"

  @Volatile
  var thisDeviceBoundChildId: Long = 0L

  @Volatile
  var appContext: android.content.Context? = null

  // Tracks when a Parent app was last actively connected (viewing child profile via LAN or Cloud)
  @Volatile
  private var lastParentPresenceTimestampMs: Long = 0L
  private const val PARENT_PRESENCE_TIMEOUT_MS = 95_000L

  // Отдельный сигнал «родитель открыл карту»: только тогда ребёнку нужен
  // высокоточный GPS. Обычное присутствие родителя (дашборд/чат) — ещё не повод
  // жечь GNSS-радио каждые 12 секунд.
  @Volatile
  private var lastParentMapViewMs: Long = 0L

  fun isParentMapViewing(): Boolean {
    if (currentRole != "CHILD") return false
    val ts = lastParentMapViewMs
    return ts > 0L && (System.currentTimeMillis() - ts) in 0..60_000L
  }

  /**
   * Returns true ONLY if the Parent app is actively connected (either via direct LAN socket
   * or via a recent Parent heartbeat/command within the last 95 seconds).
   * When false on the Child device, real-time network packets are suppressed to save battery,
   * and all events are cached locally in SQLite until the Parent reconnects.
   */
  fun isParentActivelyConnected(): Boolean {
    if (currentRole != "CHILD") return true
    if (connectedParentClients.isNotEmpty() || _status.value.isLanSocketConnected) return true
    val elapsed = System.currentTimeMillis() - lastParentPresenceTimestampMs
    return lastParentPresenceTimestampMs > 0L && elapsed in 0..PARENT_PRESENCE_TIMEOUT_MS
  }

  /**
   * Marks the Parent app as actively connected and triggers a bulk SQLite store-and-forward flush
   * if transitioning from offline to online on the Child device.
   */
  fun markParentPresenceActive(childId: Long = 0L) {
    val wasOffline = !isParentActivelyConnected()
    lastParentPresenceTimestampMs = System.currentTimeMillis()
    if (wasOffline && currentRole == "CHILD") {
      val ctx = appContext ?: return
      val targetId = if (childId > 0L) childId else (thisDeviceBoundChildId.takeIf { it > 0L } ?: 1L)
      scope.launch {
        try {
          OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(ctx, targetId)
          val dao = com.example.data.local.KidsGuardDatabase.getDatabase(ctx).kidsGuardDao()
          val child = dao.getChildByIdOnce(targetId)
          if (child != null) {
            val realBatt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(ctx)
            val updated = if (realBatt in 1..100) child.copy(batteryLevel = realBatt) else child
            dao.updateChild(updated)
            RealtimeCloudRelay.publishChildProfile(updated, isChildDevice = true)
            val installedPkgSet = com.example.util.DeviceAppsProvider.getInstalledPackageSet(ctx)
            val rawApps = dao.getAppsForChildOnce(targetId)
            val verifiedChildApps = if (installedPkgSet.isNotEmpty()) {
              rawApps.filter { it.packageName.trim().lowercase() in installedPkgSet }
            } else {
              rawApps
            }
            if (verifiedChildApps.size != rawApps.size && verifiedChildApps.isNotEmpty()) {
              dao.replaceChildAppsStrictlyFromRemote(targetId, verifiedChildApps)
            }
            if (verifiedChildApps.isNotEmpty()) {
              RealtimeCloudRelay.publishChildApps(targetId, verifiedChildApps)
            }
          }
        } catch (_: Exception) {}
      }
    }
  }

  fun getLatestDirectFrameForChild(childId: Long): Triple<String, String, Long>? {
    val map = _framesByChild.value
    map[childId]?.let { return it }
    val canonicalId = RealtimeCloudRelay.resolveCanonicalChildId(childId)
    map[canonicalId]?.let { return it }
    // Никакого «взять любой последний кадр»: иначе при переключении на ребёнка без
    // кадров в диалоге мог показаться экран ДРУГОГО ребёнка.
    return null
  }

  fun clearDirectFrameForChild(childId: Long) {
    val currentMap = _framesByChild.value.toMutableMap()
    currentMap.remove(childId)
    _framesByChild.value = currentMap
    childLanIps.remove(childId)
  }

  fun clearAllFrames() {
    _framesByChild.value = emptyMap()
    childLanIps.clear()
  }

  /** Wi-Fi сеть телефона: LAN-сокеты и UDP-обнаружение должны идти по Wi-Fi,
   *  даже если мобильный интернет выбран системой сетью по умолчанию. */
  private fun wifiNetwork(): android.net.Network? {
    val ctx = appContext ?: return null
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
      as? android.net.ConnectivityManager ?: return null
    return cm.allNetworks.firstOrNull { n ->
      cm.getNetworkCapabilities(n)
        ?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }
  }

  private fun wifiIpv4(): String? {
    val ctx = appContext ?: return null
    val cm = ctx.getSystemService(android.content.Context.CONNECTIVITY_SERVICE)
      as? android.net.ConnectivityManager ?: return null
    val net = wifiNetwork() ?: return null
    val lp = cm.getLinkProperties(net) ?: return null
    return lp.linkAddresses
      .map { it.address }
      .filterIsInstance<Inet4Address>()
      .firstOrNull { !it.isLoopbackAddress }
      ?.hostAddress
  }

  fun getLocalIpv4Address(): String {
    // Сначала именно Wi-Fi: иначе при включённом мобильном интернете можно
    // «найти» себя в сотовой сети и решить, что ребёнок в другой подсети.
    wifiIpv4()?.let { return it }
    try {
      val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
      for (intf in interfaces) {
        if (!intf.isUp || intf.isLoopback) continue
        val addrs = intf.inetAddresses
        for (addr in addrs) {
          if (!addr.isLoopbackAddress && addr is Inet4Address) {
            val ip = addr.hostAddress ?: continue
            if (!ip.startsWith("169.254.")) {
              return ip
            }
          }
        }
      }
    } catch (_: Exception) {}
    return "0.0.0.0"
  }

  fun startEngine(role: String, boundChildId: Long = 0L) {
    currentRole = role
    if (boundChildId > 0L) {
      thisDeviceBoundChildId = boundChildId
    }
    val myIp = getLocalIpv4Address()
    _status.value = _status.value.copy(localDeviceIp = myIp)

    if (!isStarted) {
      isStarted = true
      startGlobalBinaryTcpSocketLoop()
      startUdpDiscoveryListener()
      startChildTcpServerIfNeeded()
      startChildUdpBeaconLoop()
    }
  }

  fun updateRole(role: String, boundChildId: Long = 0L) {
    currentRole = role
    if (boundChildId > 0L) {
      thisDeviceBoundChildId = boundChildId
    }
    val myIp = getLocalIpv4Address()
    _status.value = _status.value.copy(localDeviceIp = myIp)
    if (!isStarted) {
      startEngine(role, boundChildId)
    }
  }

  fun switchParentLanTargetForChild(childId: Long) {
    val ip = childLanIps[childId]
    if (!ip.isNullOrBlank()) {
      connectToChildLanIp(ip, childId)
    }
  }

  private val isConnectingLan = java.util.concurrent.atomic.AtomicBoolean(false)
  private val lastLanConnectAttemptByIp = ConcurrentHashMap<String, Long>()

  private fun isSameLocalSubnet(ipA: String, ipB: String): Boolean {
    val partsA = ipA.trim().split(".")
    val partsB = ipB.trim().split(".")
    if (partsA.size != 4 || partsB.size != 4) return false
    if (ipA == "0.0.0.0" || ipB == "0.0.0.0" || ipA == "127.0.0.1" || ipB == "127.0.0.1") return false
    return partsA[0] == partsB[0] && partsA[1] == partsB[1] && partsA[2] == partsB[2]
  }

  /**
   * Connects Parent phone directly via TCP socket to Child phone's local Wi-Fi / Hotspot IP.
   * Guarded by /24 subnet check and 30s cooldown per IP to prevent socket connection storms
   * when Parent and Child are on different networks.
   */
  fun connectToChildLanIp(ip: String, targetChildId: Long = 0L) {
    val cleanIp = ip.trim()
    if (cleanIp.isBlank() || cleanIp == "0.0.0.0" || cleanIp == "127.0.0.1") return
    if (targetChildId > 0L) {
      childLanIps[targetChildId] = cleanIp
    }
    val myIp = getLocalIpv4Address()
    if (!isSameLocalSubnet(myIp, cleanIp)) {
      // Child is on a different network/cellular; use Global Binary TCP Relay instead of spamming unreachable LAN sockets
      return
    }
    if (_status.value.isLanSocketConnected && _status.value.childLanIp == cleanIp && parentClientSocket?.isConnected == true) {
      return
    }
    val now = System.currentTimeMillis()
    val lastAttempt = lastLanConnectAttemptByIp[cleanIp] ?: 0L
    if (now - lastAttempt < 30_000L) return
    if (!isConnectingLan.compareAndSet(false, true)) return
    lastLanConnectAttemptByIp[cleanIp] = now

    scope.launch {
      try {
        if (_status.value.isLanSocketConnected && _status.value.childLanIp == cleanIp && parentClientSocket?.isConnected == true) {
          return@launch
        }
        try { parentClientSocket?.close() } catch (_: Exception) {}
        val wifi = wifiNetwork()
        val sock = if (wifi != null) {
          try {
            wifi.socketFactory.createSocket() as Socket
          } catch (_: Exception) {
            Socket()
          }
        } else Socket()
        sock.connect(InetSocketAddress(cleanIp, TCP_PORT), 2500)
        sock.keepAlive = true
        sock.tcpNoDelay = true
        parentClientSocket = sock
        val writer = PrintWriter(OutputStreamWriter(sock.getOutputStream(), Charsets.UTF_8), true)
        parentSocketWriter = writer

        _status.value = _status.value.copy(
          isLanSocketConnected = true,
          childLanIp = cleanIp,
          activeTransportLabel = "🟢 Прямой P2P Сокет LAN ($cleanIp:$TCP_PORT)"
        )
        Log.d(TAG, "Connected direct P2P TCP socket to Child at $cleanIp:$TCP_PORT")

        writer.println(JSONObject().apply {
          put("type", "CMD")
          if (targetChildId > 0L) put("childId", targetChildId)
          put("command", "REQUEST_GPS_NOW")
          put("payload", "P2P")
          put("timestamp", System.currentTimeMillis())
        }.toString())

        val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
        while (sock.isConnected && !sock.isClosed) {
          val line = reader.readLine() ?: break
          handleIncomingJsonPacket(TOPIC_STATE, line, isFromLan = true)
        }
      } catch (e: Exception) {
        Log.d(TAG, "Direct LAN socket connection to $cleanIp ended: ${e.message}")
      } finally {
        isConnectingLan.set(false)
        _status.value = _status.value.copy(
          isLanSocketConnected = false,
          activeTransportLabel = if (_status.value.isGlobalTcpSocketConnected) {
            "⚡ Прямой бинарный TCP-Сокет (Реальное время • 88 мс)"
          } else {
            "Переподключение P2P TCP-сокета..."
          }
        )
      }
    }
  }

  private fun startChildTcpServerIfNeeded() {
    scope.launch {
      try {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(TCP_PORT))
        childServerSocket = server
        while (isActive && !server.isClosed) {
          val client = server.accept()
          client.keepAlive = true
          client.tcpNoDelay = true
          val clientIp = client.inetAddress?.hostAddress ?: "Parent"
          val writer = PrintWriter(OutputStreamWriter(client.getOutputStream(), Charsets.UTF_8), true)
          connectedParentClients.add(writer)
          _status.value = _status.value.copy(
            isLanSocketConnected = true,
            activeTransportLabel = "🟢 Прямой P2P Сокет LAN ($clientIp)"
          )
          markParentPresenceActive(thisDeviceBoundChildId.takeIf { it > 0L } ?: 1L)

          scope.launch {
            try {
              val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.UTF_8))
              while (client.isConnected && !client.isClosed) {
                val line = reader.readLine() ?: break
                handleIncomingJsonPacket(TOPIC_CMD, line, isFromLan = true)
              }
            } catch (_: Exception) {
            } finally {
              connectedParentClients.remove(writer)
              try { client.close() } catch (_: Exception) {}
            }
          }
        }
      } catch (e: Exception) {
        Log.d(TAG, "ServerSocket bind notice: ${e.message}")
      }
    }
  }

  fun broadcastUdpJson(json: JSONObject, targetIp: String? = null) {
    scope.launch {
      var udpSocket: DatagramSocket? = null
      try {
        val localIp = wifiIpv4()
        udpSocket = if (localIp != null) {
          DatagramSocket(0, InetAddress.getByName(localIp))
        } else {
          DatagramSocket()
        }
        udpSocket.broadcast = true
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        if (bytes.size > 16000) return@launch
        val myIp = getLocalIpv4Address()

        if (!targetIp.isNullOrBlank() && targetIp != "0.0.0.0" && targetIp != "127.0.0.1") {
          try {
            val unicastAddr = InetAddress.getByName(targetIp.trim())
            udpSocket.send(DatagramPacket(bytes, bytes.size, unicastAddr, UDP_DISCOVERY_PORT))
          } catch (_: Exception) {}
        }

        try {
          val broadcastAddr = InetAddress.getByName("255.255.255.255")
          udpSocket.send(DatagramPacket(bytes, bytes.size, broadcastAddr, UDP_DISCOVERY_PORT))
        } catch (_: Exception) {}

        val parts = myIp.split(".")
        if (parts.size == 4) {
          try {
            val subnetBroadcast = InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
            udpSocket.send(DatagramPacket(bytes, bytes.size, subnetBroadcast, UDP_DISCOVERY_PORT))
          } catch (_: Exception) {}
        }
      } catch (_: Exception) {
      } finally {
        try { udpSocket?.close() } catch (_: Exception) {}
      }
    }
  }

  fun requestPairingCodeDiscovery(code: String) {
    val cleanCode = code.trim().replace(" ", "")
    val req = JSONObject().apply {
      put("type", "PAIR_LOOKUP_REQ")
      put("pairingCode", cleanCode)
      put("requesterIp", getLocalIpv4Address())
      put("ts", System.currentTimeMillis())
    }
    broadcastUdpJson(req)
    publishTcpSocketPacket("$TOPIC_STATE/pair_lookup", req, retain = false)
  }

  private fun startChildUdpBeaconLoop() {
    scope.launch {
      var udpSocket: DatagramSocket? = null
      try {
        val localIp = wifiIpv4()
        udpSocket = if (localIp != null) {
          DatagramSocket(0, InetAddress.getByName(localIp))
        } else {
          DatagramSocket()
        }
        udpSocket.broadcast = true
        while (isActive) {
          val myIp = getLocalIpv4Address()
          _status.value = _status.value.copy(localDeviceIp = myIp)
          if (myIp != "0.0.0.0" && myIp != "127.0.0.1") {
            if (currentRole == "CHILD" && !LiveLocationTracker.isEmulator()) {
              val cachedChild = RealtimeCloudRelay.getCachedChild(thisDeviceBoundChildId)
              val beacon = JSONObject().apply {
                put("type", "P2P_BEACON")
                put("childId", thisDeviceBoundChildId)
                if (cachedChild != null && cachedChild.pairingCode.isNotBlank()) {
                  put("pairingCode", cachedChild.pairingCode)
                  put("name", cachedChild.name)
                }
                put("lanIp", myIp)
                put("port", TCP_PORT)
                put("appVersion", BuildConfig.VERSION_NAME)
                put("ts", System.currentTimeMillis())
              }.toString().toByteArray(Charsets.UTF_8)

              try {
                val broadcastAddr = InetAddress.getByName("255.255.255.255")
                udpSocket.send(DatagramPacket(beacon, beacon.size, broadcastAddr, UDP_DISCOVERY_PORT))
              } catch (_: Exception) {}

              val parts = myIp.split(".")
              if (parts.size == 4) {
                try {
                  val subnetBroadcast = InetAddress.getByName("${parts[0]}.${parts[1]}.${parts[2]}.255")
                  udpSocket.send(DatagramPacket(beacon, beacon.size, subnetBroadcast, UDP_DISCOVERY_PORT))
                } catch (_: Exception) {}
              }
            } else if (currentRole != "CHILD") {
              // Parent role: broadcast known child profiles over LAN UDP so Child phones on same Wi-Fi can pair immediately!
              RealtimeCloudRelay.broadcastCachedChildrenOverLanUdp()
            }
          }
          // Пока LAN не найден — маяк раз в 10 с, чтобы не ждать 45 с и не уходить
          // в P2P при том, что оба телефона в одной Wi-Fi. После соединения — реже.
          val isAlreadyConnected = _status.value.isLanSocketConnected ||
            _status.value.isGlobalTcpSocketConnected ||
            connectedParentClients.isNotEmpty()
          val beaconDelay = if (isAlreadyConnected) 60_000L else 10_000L
          delay(beaconDelay)
        }
      } catch (_: Exception) {
      } finally {
        try { udpSocket?.close() } catch (_: Exception) {}
      }
    }
  }

  private fun startUdpDiscoveryListener() {
    scope.launch {
      var socket: DatagramSocket? = null
      try {
        socket = DatagramSocket(null)
        socket.reuseAddress = true
        val localIp = wifiIpv4()
        val bindAddr = if (localIp != null) InetAddress.getByName(localIp) else null
        socket.bind(
          if (bindAddr != null) InetSocketAddress(bindAddr, UDP_DISCOVERY_PORT)
          else InetSocketAddress(UDP_DISCOVERY_PORT)
        )
        val buf = ByteArray(16384)
        while (isActive) {
          val packet = DatagramPacket(buf, buf.size)
          socket.receive(packet)
          val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
          if (text.startsWith("{")) {
            val json = JSONObject(text)
            val bType = json.optString("type")
            val senderIp = packet.address?.hostAddress ?: ""
            val myIp = getLocalIpv4Address()
            when (bType) {
              "P2P_BEACON" -> {
                val childIp = json.optString("lanIp", senderIp)
                val cid = json.optLong("childId", 0L)
                if (childIp.isNotBlank() && childIp != myIp && currentRole != "CHILD") {
                  if (cid > 0L) {
                    childLanIps[cid] = childIp
                  }
                  _status.value = _status.value.copy(childLanIp = childIp)
                  connectToChildLanIp(childIp, cid)
                }
              }
              "PC_BEACON" -> {
                val pcIp = json.optString("ip", senderIp)
                val pcPort = json.optInt("port", 8765)
                val pcName = json.optString("pc_name", "Windows ПК")
                val pCode = json.optString("pairing_code", "101-701")
                val pId = json.optString("pc_id", "")
                com.example.data.pc.PcBlockerClient.onUdpBeaconReceived(pcName, pcIp, pcPort, pCode, pId)
              }
              "CHILD_STATE", "CHILD_PATCH" -> {
                RealtimeCloudRelay.ingestImmediateStateJson(json)
                val isFromChild = json.optBoolean("isChildDevice", false)
                val childIp = json.optString("lanIp", senderIp)
                val cid = json.optLong("id", json.optLong("childId", 0L))
                if (isFromChild && childIp.isNotBlank() && childIp != myIp && currentRole != "CHILD") {
                  if (cid > 0L) childLanIps[cid] = childIp
                  connectToChildLanIp(childIp, cid)
                }
              }
              "PAIR_LOOKUP_REQ" -> {
                val reqIp = json.optString("requesterIp", senderIp).ifBlank { senderIp }
                if (reqIp != myIp) {
                  RealtimeCloudRelay.rebroadcastAllChildrenForPairing(reqIp)
                }
              }
            }
          }
        }
      } catch (_: Exception) {
      } finally {
        try { socket?.close() } catch (_: Exception) {}
      }
    }
  }

  /**
   * Persistent Raw Binary TCP Socket Loop (MQTT 3.1.1 wire protocol over raw Socket).
   * Subscribes to TOPIC_WILDCARD_ALL ("kidsguard/eb6ecfcf/v32/#") so every child has its own
   * retained state, screen, lock, and command subtopics without ever overwriting another child!
   */
  private fun startGlobalBinaryTcpSocketLoop() {
    scope.launch {
      var hostIdx = preferredRelayHost
      var retryDelayMs = 2_000L
      while (isActive) {
        val (host, port) = TCP_RELAY_HOSTS[hostIdx % TCP_RELAY_HOSTS.size]
        hostIdx++
        var sock: Socket? = null
        var succeeded = false
        try {
          sock = Socket()
          sock.connect(InetSocketAddress(host, port), 5000)
          sock.keepAlive = true
          sock.tcpNoDelay = true
          // CONNACK ждём ограниченное время: мёртвый брокер не должен вешать весь P2P.
          sock.soTimeout = 12_000
          val inp = DataInputStream(BufferedInputStream(sock.getInputStream()))
          val out = BufferedOutputStream(sock.getOutputStream())

          // 1. Send CONNECT packet
          val clientId = "kg_${UUID.randomUUID().toString().take(10)}"
          sendMqttConnect(out, clientId)
          val connAckType = inp.readUnsignedByte()
          val connAckLen = readRemainingLength(inp)
          val connAckBody = ByteArray(connAckLen)
          inp.readFully(connAckBody)

          if ((connAckType and 0xF0) == 0x20) {
            retryDelayMs = 2_000L // Reset backoff on successful connection
            sock.soTimeout = 0
            succeeded = true
            preferredRelayHost = (hostIdx - 1) % TCP_RELAY_HOSTS.size
            globalTcpOut = out
            _status.value = _status.value.copy(
              isGlobalTcpSocketConnected = true,
              activeTransportLabel = if (_status.value.isLanSocketConnected) {
                "🟢 Прямой P2P Сокет LAN (${_status.value.childLanIp}:$TCP_PORT)"
              } else {
                "⚡ Прямой бинарный TCP-Сокет ($host:$port)"
              }
            )
            Log.d(TAG, "Connected persistent binary TCP socket to $host:$port")

            // 2. Подписки строго по роли. Ребёнку НЕ нужны pc_state/pc_stream и чужие
            // state/chat: раньше wildcard '#' будил радио каждые 4 секунды от состояний ПК,
            // даже когда родитель ничего не смотрел — это давало десятки процентов расхода.
            val subscriptions = if (currentRole == "CHILD") {
              listOf(
                "$TOPIC_ROOT/cmd/#",
                "$TOPIC_ROOT/lock/#",
                "$TOPIC_ROOT/data/#",
                // совместимость со старым родительским приложением (v33)
                "$TOPIC_ROOT_LEGACY_PC/cmd/#",
                "$TOPIC_ROOT_LEGACY_PC/lock/#",
                "$TOPIC_ROOT_LEGACY_PC/data/#"
              )
            } else {
              listOf(
                TOPIC_WILDCARD_ALL,
                "$TOPIC_ROOT_LEGACY_PC/pc_state/#",
                "$TOPIC_ROOT_LEGACY_PC/pc_screen/#",
                "$TOPIC_ROOT_LEGACY_PC/pc_cmd/#"
              )
            }
            sendMqttSubscribe(out, subscriptions)

            // 2b. Immediately flush all retained packets (such as newly created Child profiles & pairing codes!)
            for ((retTopic, retJson) in retainedPacketsOutbox) {
              try {
                publishTcpSocketPacket(retTopic, retJson, retain = true)
              } catch (_: Exception) {}
            }

            // 3. Keepalive ping loop (battery-friendly 90s interval matching 120s MQTT keepalive)
            val pingJob = launch {
              while (isActive && sock.isConnected && !sock.isClosed) {
                delay(90_000L)
                synchronized(globalWriteLock) {
                  try {
                    out.write(byteArrayOf(0xC0.toByte(), 0x00))
                    out.flush()
                  } catch (_: Exception) {
                    // Соединение умерло — закрываем сокет, чтобы разбудить блокирующее чтение
                    // и переподключиться (иначе поток навсегда зависал на read()).
                    try { sock.close() } catch (_: Exception) {}
                  }
                }
              }
            }

            // 4. Read incoming binary TCP packets continuously
            try {
              while (isActive && sock.isConnected && !sock.isClosed) {
                val header = inp.readUnsignedByte()
                val remLen = readRemainingLength(inp)
                val payloadBytes = ByteArray(remLen)
                inp.readFully(payloadBytes)

                val packetType = (header and 0xF0) shr 4
                if (packetType == 3 && remLen >= 2) { // PUBLISH packet
                  val topicLen = ((payloadBytes[0].toInt() and 0xFF) shl 8) or (payloadBytes[1].toInt() and 0xFF)
                  if (remLen >= 2 + topicLen) {
                    val topic = String(payloadBytes, 2, topicLen, Charsets.UTF_8)
                    val qos = (header and 0x06) shr 1
                    val msgOffset = if (qos > 0) 2 + topicLen + 2 else 2 + topicLen
                    if (remLen > msgOffset) {
                      if (topic.contains("/pc_stream_bin")) {
                        // Живой кадр ПК в бинарном виде: без Base64 и без JSON-парсинга.
                        com.example.data.pc.PcBlockerClient.ingestP2pStreamFrameBinary(
                          topic,
                          payloadBytes,
                          msgOffset,
                          remLen - msgOffset
                        )
                      } else {
                        val jsonStr = String(payloadBytes, msgOffset, remLen - msgOffset, Charsets.UTF_8)
                        handleIncomingJsonPacket(topic, jsonStr, isFromLan = false)
                      }
                    }
                  }
                }
              }
            } finally {
              pingJob.cancel()
            }
          }
        } catch (e: Exception) {
          Log.d(TAG, "Binary TCP socket ($host:$port) reconnect: ${e.message}")
        } finally {
          globalTcpOut = null
          try { sock?.close() } catch (_: Exception) {}
          _status.value = _status.value.copy(
            isGlobalTcpSocketConnected = false,
            activeTransportLabel = if (_status.value.isLanSocketConnected) {
              "🟢 Прямой P2P Сокет LAN (${_status.value.childLanIp}:$TCP_PORT)"
            } else {
              "Переподключение TCP-сокета..."
            }
          )
        }
        delay(retryDelayMs)
        retryDelayMs = (retryDelayMs * 2L).coerceAtMost(30_000L)
        // После обрыва снова идём на «любимый» брокер; если он не ответил CONNACK,
        // hostIdx уже указывает на следующий — попробуем его.
        if (succeeded) {
          hostIdx = preferredRelayHost
        }
      }
    }
  }

  private fun encodeRemainingLength(out: ByteArrayOutputStream, length: Int) {
    var x = length
    do {
      var encodedByte = x % 128
      x /= 128
      if (x > 0) {
        encodedByte = encodedByte or 128
      }
      out.write(encodedByte)
    } while (x > 0)
  }

  private fun readRemainingLength(inp: DataInputStream): Int {
    var multiplier = 1
    var value = 0
    while (true) {
      val encodedByte = inp.readUnsignedByte()
      value += (encodedByte and 127) * multiplier
      if ((encodedByte and 128) == 0) break
      multiplier *= 128
      if (multiplier > 128 * 128 * 128) break
    }
    return value
  }

  private fun writeUtf8String(out: ByteArrayOutputStream, s: String) {
    val bytes = s.toByteArray(Charsets.UTF_8)
    out.write((bytes.size shr 8) and 0xFF)
    out.write(bytes.size and 0xFF)
    out.write(bytes)
  }

  private fun sendMqttConnect(out: BufferedOutputStream, clientId: String) {
    val body = ByteArrayOutputStream()
    writeUtf8String(body, "MQTT")
    body.write(4) // Protocol level 4 (MQTT 3.1.1)
    body.write(0x02) // Clean session
    body.write(0x00)
    body.write(0x78) // Keepalive 120s
    writeUtf8String(body, clientId)
    val bodyBytes = body.toByteArray()

    val pkt = ByteArrayOutputStream()
    pkt.write(0x10) // CONNECT
    encodeRemainingLength(pkt, bodyBytes.size)
    pkt.write(bodyBytes)
    synchronized(globalWriteLock) {
      out.write(pkt.toByteArray())
      out.flush()
    }
  }

  private fun sendMqttSubscribe(out: BufferedOutputStream, topics: List<String>) {
    val body = ByteArrayOutputStream()
    body.write(0x00)
    body.write(0x01) // Packet ID 1
    for (t in topics) {
      writeUtf8String(body, t)
      body.write(0x00) // QoS 0
    }
    val bodyBytes = body.toByteArray()

    val pkt = ByteArrayOutputStream()
    pkt.write(0x82) // SUBSCRIBE
    encodeRemainingLength(pkt, bodyBytes.size)
    pkt.write(bodyBytes)
    synchronized(globalWriteLock) {
      out.write(pkt.toByteArray())
      out.flush()
    }
  }

  /**
   * Publishes a JSON packet over BOTH the Direct LAN P2P TCP Socket AND the Global Binary TCP Socket.
   */
  fun publishTcpSocketPacket(topic: String, json: JSONObject, retain: Boolean = false): Boolean {
    if (retain) {
      if (json.optString("type") == "CHILD_DELETE") {
        retainedPacketsOutbox.remove(topic)
      } else {
        retainedPacketsOutbox[topic] = json
      }
    }
    val sent = pushDirectLanPacket(json)
    val out = globalTcpOut ?: return sent
    return try {
      val body = ByteArrayOutputStream()
      writeUtf8String(body, topic)
      val payloadBytes = json.toString().toByteArray(Charsets.UTF_8)
      body.write(payloadBytes)
      val bodyBytes = body.toByteArray()

      val pkt = ByteArrayOutputStream()
      pkt.write(if (retain) 0x31 else 0x30)
      encodeRemainingLength(pkt, bodyBytes.size)
      pkt.write(bodyBytes)

      synchronized(globalWriteLock) {
        out.write(pkt.toByteArray())
        out.flush()
      }
      true
    } catch (e: Exception) {
      Log.d(TAG, "publishTcpSocketPacket error: ${e.message}")
      sent
    }
  }

  private fun handleIncomingJsonPacket(topic: String, rawJson: String, isFromLan: Boolean) {
    if (!rawJson.startsWith("{")) return
    scope.launch {
      try {
        val msg = JSONObject(rawJson)
        val now = System.currentTimeMillis()
        _status.value = _status.value.copy(
          packetsReceived = _status.value.packetsReceived + 1,
          lastPacketTimeMs = now
        )

        val msgChildId = msg.optLong("childId", msg.optLong("id", 0L))
        val reportedLanIp = msg.optString("lanIp", "")
        if (reportedLanIp.isNotBlank() &&
            reportedLanIp != "0.0.0.0" &&
            reportedLanIp != "127.0.0.1" &&
            reportedLanIp != getLocalIpv4Address() &&
            currentRole != "CHILD") {
          if (msgChildId > 0L) {
            childLanIps[msgChildId] = reportedLanIp
          }
          if (!_status.value.isLanSocketConnected) {
            connectToChildLanIp(reportedLanIp, msgChildId)
          }
        }

        val type = msg.optString("type", "")
        val pktPairingCode = msg.optString("pairingCode", "")
        when {
          type == "DIRECT_SCREEN_FRAME" || type == "SCREEN_FRAME" || topic.startsWith(TOPIC_SCREEN) || topic.endsWith("/screen") -> {
            val rawCid = msg.optLong("childId", topic.substringAfterLast("/").toLongOrNull() ?: 0L)
            val cid = RealtimeCloudRelay.resolveCanonicalChildId(rawCid, pktPairingCode)
            val ts = msg.optLong("timestamp", msg.optLong("ts", now))
            if (cid <= 0L) return@launch
            val appName = msg.optString("activeAppName", "Экран ребёнка")
            val directB64 = msg.optString("base64Bitmap", "")
            val key = msg.optString("blobKey", "")
            val currentMap = _framesByChild.value.toMutableMap()
            val prevEntry = currentMap[cid] ?: currentMap[rawCid]
            // Only ignore if we already have a strictly newer frame with non-empty bitmap
            if (prevEntry != null && prevEntry.second.isNotBlank() && ts < prevEntry.third && directB64.isBlank()) {
              return@launch
            }
            if (directB64.isNotBlank()) {
              val entry = Triple(appName, directB64, ts)
              currentMap[cid] = entry
              if (rawCid > 0L) currentMap[rawCid] = entry
              _framesByChild.value = currentMap
            } else if (key.isNotBlank()) {
              val downloaded = RealtimeCloudRelay.downloadLargeBlob(key).orEmpty()
              if (downloaded.isNotBlank()) {
                val entry = Triple(appName, downloaded, ts)
                currentMap[cid] = entry
                if (rawCid > 0L) currentMap[rawCid] = entry
                _framesByChild.value = currentMap
              }
            } else if (appName.isNotBlank()) {
              val prevB64 = currentMap[cid]?.second ?: currentMap[rawCid]?.second.orEmpty()
              val entry = Triple(appName, prevB64, ts)
              currentMap[cid] = entry
              if (rawCid > 0L) currentMap[rawCid] = entry
              _framesByChild.value = currentMap
            }
          }
          type == "LOCK_STATE" || topic.startsWith(TOPIC_LOCK) -> {
            RealtimeCloudRelay.ingestAuthoritativeLockJson(msg)
            val rawCid = msg.optLong("childId", msg.optLong("id", 0L))
            val cid = RealtimeCloudRelay.resolveCanonicalChildId(rawCid, pktPairingCode)
            val locked = msg.optBoolean("isDeviceLocked", false)
            val ts = msg.optLong("ts", now)
            // Only emit LOCK if it is a fresh live command within the last 15 seconds (never from retained history)
            if (cid > 0L && (!locked || kotlin.math.abs(now - ts) <= 15_000L)) {
              _incomingCommandsFlow.tryEmit(Triple(cid, if (locked) "LOCK" else "UNLOCK", ""))
              applyImmediateLockOrUnlockIfChild(cid, locked)
            }
          }
          type == "CMD" || topic.startsWith(TOPIC_CMD) || topic.endsWith("/cmd") || msg.has("command") -> {
            val rawCid = msg.optLong("childId", topic.substringAfterLast("/").toLongOrNull() ?: 0L)
            val cid = RealtimeCloudRelay.resolveCanonicalChildId(rawCid, pktPairingCode)
            val cmd = msg.optString("command", "")
            val payload = msg.optString("payload", "")
            val ts = msg.optLong("timestamp", now)
            val maxAgeMs = if (cmd == "LOCK") 20_000L else 180_000L
            if (cmd.isNotBlank() && kotlin.math.abs(now - ts) < maxAgeMs) {
              if (currentRole == "CHILD") {
                markParentPresenceActive(cid)
                if (cmd == "MAP_VIEWING") {
                  lastParentMapViewMs = now
                }
              }
              _incomingCommandsFlow.tryEmit(Triple(cid, cmd, payload))
              if (cmd == "UNLOCK") {
                RealtimeCloudRelay.publishAuthoritativeLock(cid, false)
                applyImmediateLockOrUnlockIfChild(cid, false)
              } else if (cmd == "LOCK") {
                RealtimeCloudRelay.publishAuthoritativeLock(cid, true)
                applyImmediateLockOrUnlockIfChild(cid, true)
              }
            }
          }
          type == "CHILD_STATE" || type == "CHILD_PATCH" || type == "CHILD_DELETE" || topic.startsWith(TOPIC_STATE) -> {
            if (type == "PAIR_LOOKUP_REQ") {
              val reqIp = msg.optString("requesterIp", "")
              RealtimeCloudRelay.rebroadcastAllChildrenForPairing(reqIp)
            } else {
              RealtimeCloudRelay.ingestImmediateStateJson(msg)
              if (msg.has("isDeviceLocked") && !msg.optBoolean("isDeviceLocked", true)) {
                val rawCid = msg.optLong("childId", msg.optLong("id", 1L))
                applyImmediateLockOrUnlockIfChild(rawCid, false)
              }
            }
          }
          type == "PC_STATE" || topic.contains("/pc_state") -> {
            com.example.data.pc.PcBlockerClient.ingestP2pStateJson(msg)
          }
          type == "PC_STREAM_FRAME" || topic.contains("/pc_stream") -> {
            com.example.data.pc.PcBlockerClient.ingestP2pStreamFrame(msg)
          }
          type == "PC_SCREEN" || topic.contains("/pc_screen") -> {
            com.example.data.pc.PcBlockerClient.ingestP2pScreenJson(msg)
          }
          type == "POWER_STATS" -> {
            com.example.data.cloud.ChildPowerStats.ingest(msg)
          }
          type == "OFFLINE_HISTORY_BUNDLE" -> {
            _incomingOfflineBundlesFlow.tryEmit(msg)
          }
          topic.startsWith(TOPIC_DATA) || type in listOf("LOC_HISTORY", "APPS_LIST", "APP_PATCH", "CHAT", "ALERT", "RULES_LIST") -> {
            RealtimeCloudRelay.ingestImmediateDataJson(msg)
            handleImmediateIncomingSosOrChatOnParent(msg, now)
          }
        }

        if (isFromLan) {
          _status.value = _status.value.copy(
            isLanSocketConnected = true,
            activeTransportLabel = "🟢 Прямой P2P Сокет LAN (Wi-Fi)"
          )
        }
      } catch (_: Exception) {}
    }
  }

  fun storeReceivedScreenFrame(childId: Long, activeAppName: String, base64Bitmap: String, timestamp: Long = System.currentTimeMillis()) {
    val cid = if (childId > 0L) childId else 1L
    val currentMap = _framesByChild.value.toMutableMap()
    val prevB64 = currentMap[cid]?.second.orEmpty()
    val resolvedB64 = base64Bitmap.ifBlank { prevB64 }
    currentMap[cid] = Triple(activeAppName.ifBlank { "Экран ребёнка" }, resolvedB64, timestamp)
    _framesByChild.value = currentMap
  }

  /**
   * Streams a live screen frame directly over BOTH the LAN P2P Socket and the Global Binary TCP Socket
   * on the child's own dedicated topic `TOPIC_SCREEN/$childId`!
   */
  fun pushDirectScreenFrame(childId: Long, activeAppName: String, base64Bitmap: String): Boolean {
    val cid = if (childId > 0) childId else 1L
    val now = System.currentTimeMillis()
    val currentMap = _framesByChild.value.toMutableMap()
    currentMap[cid] = Triple(activeAppName, base64Bitmap, now)
    _framesByChild.value = currentMap

    val cachedChild = RealtimeCloudRelay.getCachedChild(cid)
    val cachedCode = cachedChild?.pairingCode.orEmpty()
    val cachedName = cachedChild?.name.orEmpty()
    val packet = JSONObject().apply {
      put("type", "DIRECT_SCREEN_FRAME")
      put("childId", cid)
      if (cachedCode.isNotBlank()) {
        put("pairingCode", cachedCode)
      }
      if (cachedName.isNotBlank()) {
        put("childName", cachedName)
      }
      put("activeAppName", activeAppName)
      put("base64Bitmap", base64Bitmap)
      put("lanIp", getLocalIpv4Address())
      put("timestamp", now)
    }
    // Если есть прямой LAN-сокет — кадр идёт и по Wi-Fi, без мобильного трафика.
    val sentLan = pushDirectLanPacket(packet)
    val sentCloud = publishTcpSocketPacket("$TOPIC_SCREEN/$cid", packet, retain = true)
    return sentLan || sentCloud
  }

  fun pushDirectLanPacket(json: JSONObject): Boolean {
    var sent = false
    val line = json.toString()
    parentSocketWriter?.let { w ->
      try {
        w.println(line)
        sent = true
      } catch (_: Exception) {}
    }
    for (w in connectedParentClients) {
      try {
        w.println(line)
        sent = true
      } catch (_: Exception) {
        connectedParentClients.remove(w)
      }
    }
    return sent
  }

  private suspend fun applyImmediateLockOrUnlockIfChild(targetId: Long, locked: Boolean) {
    val ctx = appContext ?: return
    try {
      val dao = com.example.data.local.KidsGuardDatabase.getDatabase(ctx).kidsGuardDao()
      val cfg = dao.getFamilyConfigOnce()
      if (cfg?.selectedRole != "CHILD") return
      val allLocal = dao.getAllChildrenOnce()
      for (c in allLocal) {
        dao.setChildDeviceLocked(c.id, locked)
      }
      if (targetId > 0L) {
        dao.setChildDeviceLocked(targetId, locked)
      }
      if (locked) {
        com.example.util.DeviceTotalLockManager.enforceTotalLock(
          ctx,
          "Экран заблокирован родителем.\nРазблокируйте через телефон родителя или введите PIN-код ниже."
        )
      } else {
        com.example.util.DeviceTotalLockManager.releaseTotalLock(ctx)
      }
    } catch (_: Exception) {}
  }

  @Volatile
  private var lastLoudSirenPlayedAtMs: Long = 0L
  private val recentProcessedChatKeys = java.util.Collections.synchronizedSet( LinkedHashSet<String>() )
  private val recentProcessedAlertKeys = java.util.Collections.synchronizedSet( LinkedHashSet<String>() )

  private suspend fun handleImmediateIncomingSosOrChatOnParent(msg: JSONObject, now: Long) {
    val ctx = appContext ?: return
    try {
      val type = msg.optString("type", "")
      if (type != "ALERT" && type != "CHAT") return
      val dao = com.example.data.local.KidsGuardDatabase.getDatabase(ctx).kidsGuardDao()
      val cfg = dao.getFamilyConfigOnce()
      val localRole = cfg?.selectedRole ?: "PARENT"
      val ts = msg.optLong("timestamp", msg.optLong("ts", now))
      val isFresh = kotlin.math.abs(now - ts) < 90_000L

      val allChildren = dao.getAllChildrenOnce()
      val rawCid = msg.optLong("childId", 1L).coerceAtLeast(1L)
      val pktCode = msg.optString("pairingCode", "").trim()
      val pktName = msg.optString("childName", msg.optString("senderName", "")).trim()

      val matchedChild = allChildren.firstOrNull { pktCode.isNotBlank() && it.pairingCode.equals(pktCode, ignoreCase = true) }
        ?: allChildren.firstOrNull {
          pktName.isNotBlank() &&
            !pktName.equals("Ребёнок", ignoreCase = true) &&
            !pktName.equals("Родитель", ignoreCase = true) &&
            it.name.equals(pktName, ignoreCase = true)
        }
        ?: allChildren.firstOrNull { it.id == rawCid }
        ?: allChildren.firstOrNull { it.id == cfg?.currentActiveChildId }
        ?: allChildren.firstOrNull()

      val matchedChildId = matchedChild?.id ?: rawCid

      if (type == "ALERT" && localRole != "CHILD") {
        val alertType = msg.optString("alertType", "GENERAL")
        val title = msg.optString("title", "Оповещение KidsGuard")
        val messageText = msg.optString("message", "")
        val payload = msg.optString("payload", "")

        // If SOS alert carries coordinates ("lat,lng"), immediately update the child's location in Room!
        if (alertType == "SOS" && payload.contains(",")) {
          val parts = payload.split(",")
          val sosLat = parts.getOrNull(0)?.trim()?.toDoubleOrNull() ?: 0.0
          val sosLng = parts.getOrNull(1)?.trim()?.toDoubleOrNull() ?: 0.0
          if (sosLat != 0.0 && sosLng != 0.0 && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(sosLat, sosLng)) {
            val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(ctx, sosLat, sosLng)
            dao.updateChildLocation(matchedChildId, sosLat, sosLng, addr, ts)
          }
        }

        val alertKey = "${matchedChildId}_${alertType}_${ts / 2000L}"
        if (recentProcessedAlertKeys.add(alertKey)) {
          val existingAlerts = dao.getAllAlerts().firstOrNull().orEmpty()
          val alreadyInDb = existingAlerts.any {
            it.type == alertType && it.childId == matchedChildId && kotlin.math.abs(it.timestamp - ts) < 2500L
          }
          if (!alreadyInDb) {
            dao.insertAlert(
              com.example.data.model.AlertNotificationEntity(
                id = 0,
                childId = matchedChildId,
                type = alertType,
                title = title,
                message = messageText,
                extraPayload = payload,
                timestamp = ts,
                isRead = false
              )
            )
          }
        }

        if (alertType == "SOS" && isFresh && (now - lastLoudSirenPlayedAtMs) > 4_000L) {
          lastLoudSirenPlayedAtMs = now
          com.example.util.SoundAlertManager.playLoudAttentionSignal(ctx)
        }
      } else if (type == "CHAT") {
        val senderRole = msg.optString("senderRole", msg.optString("sender", "CHILD")).ifBlank { "CHILD" }
        // Parent processes messages from CHILD; Child processes messages from PARENT
        val shouldIngest = (localRole != "CHILD" && senderRole == "CHILD") ||
          (localRole == "CHILD" && senderRole == "PARENT")
        if (shouldIngest) {
          val text = msg.optString("text", "")
          val senderName = msg.optString("senderName", if (senderRole == "CHILD") (matchedChild?.name ?: "Ребёнок") else "Родитель")
          val isAttention = msg.optBoolean("isAttentionSignal", msg.optBoolean("attention", false))
          if (text.isNotBlank()) {
            val chatKey = "${matchedChildId}_${senderRole}_${ts / 2000L}_${text.hashCode()}"
            if (recentProcessedChatKeys.add(chatKey)) {
              val existingMsgs = dao.getChatMessagesForChild(matchedChildId).firstOrNull().orEmpty()
              val alreadyInDb = existingMsgs.any {
                it.senderRole == senderRole && it.text == text && kotlin.math.abs(it.timestamp - ts) < 2500L
              }
              if (!alreadyInDb) {
                dao.insertChatMessage(
                  com.example.data.model.ChatMessageEntity(
                    id = 0,
                    childId = matchedChildId,
                    senderRole = senderRole,
                    senderName = senderName,
                    text = text,
                    timestamp = ts,
                    isRead = false,
                    isAttentionSignal = isAttention
                  )
                )
                if (localRole != "CHILD" && senderRole == "CHILD" && !isAttention) {
                  dao.insertAlert(
                    com.example.data.model.AlertNotificationEntity(
                      id = 0,
                      childId = matchedChildId,
                      type = "CHAT_MESSAGE",
                      title = "💬 Сообщение от «$senderName»",
                      message = text,
                      timestamp = ts,
                      isRead = false
                    )
                  )
                }
              }
            }
            if (isAttention && isFresh && (now - lastLoudSirenPlayedAtMs) > 4_000L) {
              lastLoudSirenPlayedAtMs = now
              com.example.util.SoundAlertManager.playLoudAttentionSignal(ctx)
            }
          }
        }
      }
    } catch (_: Exception) {}
  }
}
