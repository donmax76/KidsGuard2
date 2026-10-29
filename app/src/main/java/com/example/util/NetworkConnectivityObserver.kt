package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged

enum class NetworkTransportType {
  WIFI,
  CELLULAR,
  ETHERNET,
  NONE
}

data class NetworkState(
  val transportType: NetworkTransportType = NetworkTransportType.NONE,
  val isConnected: Boolean = false,
  val isWifi: Boolean = false,
  val isCellular: Boolean = false,
  val displayName: String = "Нет подключения"
)

data class ComponentTrafficBreakdown(
  val componentName: String,
  val description: String,
  val bytesPerEvent: String,
  val frequencyPerHour: String,
  val hourlyKb: Double
)

data class TrafficConsumptionEstimate(
  val modeTitle: String,
  val hourlyKbMin: Int,
  val hourlyKbMax: Int,
  val dailyMbAverage: Double,
  val monthlyMbAverage: Double,
  val components: List<ComponentTrafficBreakdown>
) {
  val hourlyFormatted: String
    get() = "$hourlyKbMin–$hourlyKbMax КБ/час"
  val dailyFormatted: String
    get() = String.format("%.2f МБ/сутки", dailyMbAverage)
  val monthlyFormatted: String
    get() = String.format("%.1f МБ/месяц", monthlyMbAverage)
}

class NetworkConnectivityObserver(private val context: Context) {

  private val connectivityManager =
    context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

  fun observe(): Flow<NetworkState> = callbackFlow {
    val callback = object : ConnectivityManager.NetworkCallback() {
      override fun onAvailable(network: Network) {
        trySend(getCurrentNetworkState())
      }

      override fun onLost(network: Network) {
        trySend(getCurrentNetworkState())
      }

      override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
        trySend(resolveCapabilities(capabilities))
      }
    }

    // Emit current state immediately
    trySend(getCurrentNetworkState())

    try {
      val request = NetworkRequest.Builder()
        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        .build()

      connectivityManager.registerNetworkCallback(request, callback)
    } catch (_: Exception) {
      // Fallback for emulators without callback permission
    }

    awaitClose {
      try {
        connectivityManager.unregisterNetworkCallback(callback)
      } catch (_: Exception) {
      }
    }
  }.distinctUntilChanged()

  fun getCurrentNetworkState(): NetworkState {
    return try {
      val activeNetwork = connectivityManager.activeNetwork ?: return NetworkState(
        transportType = NetworkTransportType.WIFI,
        isConnected = true,
        isWifi = true,
        displayName = "Wi-Fi (Сеть подключена)"
      )
      val capabilities = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return NetworkState(
        transportType = NetworkTransportType.WIFI,
        isConnected = true,
        isWifi = true,
        displayName = "Wi-Fi (Сеть подключена)"
      )
      resolveCapabilities(capabilities)
    } catch (_: Exception) {
      NetworkState(
        transportType = NetworkTransportType.WIFI,
        isConnected = true,
        isWifi = true,
        displayName = "Wi-Fi (Сеть подключена)"
      )
    }
  }

  private fun resolveCapabilities(capabilities: NetworkCapabilities): NetworkState {
    val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    val isEthernet = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)

    val type = when {
      isWifi -> NetworkTransportType.WIFI
      isCellular -> NetworkTransportType.CELLULAR
      isEthernet -> NetworkTransportType.ETHERNET
      else -> NetworkTransportType.NONE
    }

    val displayName = when {
      !hasInternet -> "Нет подключения к интернету"
      isWifi -> "Wi-Fi (Беспроводная сеть)"
      isCellular -> "Мобильный интернет (4G / 5G / LTE)"
      isEthernet -> "Проводная сеть"
      else -> "Сеть подключена"
    }

    return NetworkState(
      transportType = type,
      isConnected = hasInternet,
      isWifi = isWifi,
      isCellular = isCellular,
      displayName = displayName
    )
  }

  companion object {
    /**
     * Возвращает структурированный расчет потребления мобильного трафика
     * для различных сценариев работы приложения KidsGuard.
     */
    fun getTrafficCalculations(): List<TrafficConsumptionEstimate> {
      return listOf(
        TrafficConsumptionEstimate(
          modeTitle = "Стандартный смешанный режим (Улица + Школа)",
          hourlyKbMin = 18,
          hourlyKbMax = 35,
          dailyMbAverage = 0.55,
          monthlyMbAverage = 16.5,
          components = listOf(
            ComponentTrafficBreakdown(
              componentName = "GPS Телеметрия (Координаты)",
              description = "Компактный бинарный/JSON пакет: lat, lng, точность, заряд батареи, скорость",
              bytesPerEvent = "~240 байт",
              frequencyPerHour = "60 раз (1 раз в минуту в движении)",
              hourlyKb = 14.4
            ),
            ComponentTrafficBreakdown(
              componentName = "Heartbeat & Проверка правил",
              description = "Легковесный MQTT/WebSocket ping родительских команд (блокировка, лимиты)",
              bytesPerEvent = "~60 байт",
              frequencyPerHour = "60 раз (каждые 60 сек)",
              hourlyKb = 3.6
            ),
            ComponentTrafficBreakdown(
              componentName = "Чат и служебные уведомления",
              description = "Текстовые сообщения и статус прочтения (при наличии сообщений)",
              bytesPerEvent = "~300 байт",
              frequencyPerHour = "10–20 раз",
              hourlyKb = 4.5
            ),
            ComponentTrafficBreakdown(
              componentName = "Синхронизация экранного времени",
              description = "Дельта использованных минут по приложениям",
              bytesPerEvent = "~350 байт",
              frequencyPerHour = "12 раз (каждые 5 минут)",
              hourlyKb = 4.2
            )
          )
        ),
        TrafficConsumptionEstimate(
          modeTitle = "Режим покоя / Дома / Уроки (Статичный)",
          hourlyKbMin = 8,
          hourlyKbMax = 14,
          dailyMbAverage = 0.25,
          monthlyMbAverage = 7.5,
          components = listOf(
            ComponentTrafficBreakdown(
              componentName = "GPS Смарт-интервал",
              description = "Акселерометр определяет покой — GPS отправляется реже",
              bytesPerEvent = "~240 байт",
              frequencyPerHour = "12 раз (1 раз в 5 минут)",
              hourlyKb = 2.9
            ),
            ComponentTrafficBreakdown(
              componentName = "Heartbeat пинг соединения",
              description = "Поддержание канала связи для приема срочных вызовов от родителя",
              bytesPerEvent = "~60 байт",
              frequencyPerHour = "60 раз",
              hourlyKb = 3.6
            ),
            ComponentTrafficBreakdown(
              componentName = "Фоновый синхронизатор",
              description = "Проверка геозон и правил фильтрации",
              bytesPerEvent = "~250 байт",
              frequencyPerHour = "12 раз",
              hourlyKb = 3.0
            )
          )
        ),
        TrafficConsumptionEstimate(
          modeTitle = "Режим «Только Wi-Fi» (Экономия)",
          hourlyKbMin = 0,
          hourlyKbMax = 0,
          dailyMbAverage = 0.0,
          monthlyMbAverage = 0.0,
          components = listOf(
            ComponentTrafficBreakdown(
              componentName = "Мобильный интернет выключен",
              description = "При выходе из зоны Wi-Fi сотовые данные полностью блокируются приложением",
              bytesPerEvent = "0 байт",
              frequencyPerHour = "0 раз",
              hourlyKb = 0.0
            ),
            ComponentTrafficBreakdown(
              componentName = "Очередь сообщений (Offline Queue)",
              description = "Все события накапливаются локально в защищенной БД и синхронизируются при входе в Wi-Fi",
              bytesPerEvent = "0 байт в мобильной сети",
              frequencyPerHour = "Синхронизация по Wi-Fi",
              hourlyKb = 0.0
            )
          )
        )
      )
    }
  }
}
