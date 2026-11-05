package com.example.location

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.Geocoder
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import com.example.data.model.GeofenceZoneEntity
import com.example.data.model.LocationBreadcrumb
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import android.os.Looper
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlin.coroutines.resume
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LocationState(
  val latitude: Double = 0.0,
  val longitude: Double = 0.0,
  val accuracyMeters: Float = 0f,
  val speedKmh: Float = 0f,
  val currentAddress: String = "Определение точного адреса GPS...",
  val isGpsActive: Boolean = false,
  val hasGpsFix: Boolean = false,
  val activeGeofenceName: String? = null,
  val breadcrumbs: List<LocationBreadcrumb> = emptyList(),
  val batteryLevel: Int = 100,
  val isMoving: Boolean = false
)

class LiveLocationTracker(
  private val context: Context,
  private val onGeofenceEvent: (type: String, zoneName: String) -> Unit = { _, _ -> }
) {
  companion object {
    const val DEFAULT_BAKU_LAT = 0.0
    const val DEFAULT_BAKU_LNG = 0.0
    const val DEFAULT_BAKU_ADDRESS = "Ожидание точного GPS-сигнала с телефона ребёнка..."

    @Volatile
    var isGpsTrackingAllowed: Boolean = false

    private val activeInstances = java.util.Collections.synchronizedSet(java.util.LinkedHashSet<LiveLocationTracker>())

    /**
     * Hardware GPS Toggle Enforcer:
     * Immediately stops or starts FusedLocationProviderClient and LocationManager across all active tracker instances
     * to save battery when the Parent toggles GPS off.
     */
    @JvmName("applyHardwareGpsTrackingAllowed")
    fun setGpsTrackingAllowed(allowed: Boolean) {
      isGpsTrackingAllowed = allowed
      val snapshot = synchronized(activeInstances) { activeInstances.toList() }
      for (tracker in snapshot) {
        if (allowed) {
          tracker.startRealLocationUpdates()
        } else {
          tracker.stopLocationUpdates()
        }
      }
    }

    @Volatile
    private var cachedGeoLat: Double = 0.0
    @Volatile
    private var cachedGeoLng: Double = 0.0
    @Volatile
    private var cachedGeoAddress: String = ""

    private val geocodeHttpClient = OkHttpClient.Builder()
      .connectTimeout(4, TimeUnit.SECONDS)
      .readTimeout(4, TimeUnit.SECONDS)
      .build()

    fun isEmulator(): Boolean {
      val fp = Build.FINGERPRINT.lowercase()
      val model = Build.MODEL.lowercase()
      val hw = Build.HARDWARE.lowercase()
      val prod = Build.PRODUCT.lowercase()
      val brand = Build.BRAND.lowercase()
      return fp.startsWith("generic") ||
        fp.contains("emulator") ||
        fp.contains("sdk_gphone") ||
        model.contains("google_sdk") ||
        model.contains("emulator") ||
        model.contains("android sdk") ||
        model.contains("sdk_gphone") ||
        hw.contains("goldfish") ||
        hw.contains("ranchu") ||
        prod.contains("sdk_gphone") ||
        prod.contains("emulator") ||
        prod.contains("simulator") ||
        (brand.startsWith("generic") && Build.DEVICE.lowercase().startsWith("generic"))
    }

    fun isEmulatorSyntheticUsLocation(lat: Double, lng: Double): Boolean {
      if (lat == 0.0 && lng == 0.0) return true
      // Western Hemisphere longitude (USA / Americas: lng < -10.0) comes from the cloud emulator
      if (lng < -10.0) return true
      if (lat in 36.0..39.0 && lng in -124.0..-120.0) return true
      // Also reject the legacy hardcoded 4-decimal demo seed (40.4093, 49.8671)
      if (kotlin.math.abs(lat - 40.4093) < 0.00015 && kotlin.math.abs(lng - 49.8671) < 0.00015) return true
      return false
    }

    fun isStaleOrDemoAddress(address: String): Boolean {
      if (address.isBlank()) return true
      return address.contains("Mountain View", ignoreCase = true) ||
        address.contains("California", ignoreCase = true) ||
        address.contains("United States", ignoreCase = true) ||
        address.contains("USA", ignoreCase = true) ||
        address.contains("США", ignoreCase = true)
    }

    @Volatile
    private var latestHardwareFix: Location? = null

    @Volatile
    var lastKnownHighAccuracyLocation: Location? = null

    /**
     * Actively awaits a fresh PRIORITY_HIGH_ACCURACY location fix from FusedLocationProviderClient
     * with a bounded timeout, falling back to getBestImmediateLocation if satellites/Wi-Fi time out.
     */
    @SuppressLint("MissingPermission")
    suspend fun awaitHighAccuracyLocation(context: Context, timeoutMs: Long = 4_000L): Location? {
      if (isEmulator()) return null
      val hasPerm = com.example.util.DeviceSecurityPermissionHelper.hasLocationPermission(context)
      if (!hasPerm) return getBestImmediateLocation(context)

      val fused = try {
        LocationServices.getFusedLocationProviderClient(context.applicationContext)
      } catch (_: Exception) {
        null
      }

      if (fused != null) {
        val freshFix = withTimeoutOrNull(timeoutMs) {
          suspendCancellableCoroutine<Location?> { cont ->
            val cts = CancellationTokenSource()
            cont.invokeOnCancellation { cts.cancel() }
            try {
              val req = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(5_000L)
                .setDurationMillis(timeoutMs)
                .build()
              fused.getCurrentLocation(req, cts.token)
                .addOnSuccessListener { loc ->
                  if (cont.isActive) {
                    if (loc != null && !isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude) &&
                        loc.latitude != 0.0 && loc.longitude != 0.0) {
                      latestHardwareFix = loc
                      if (loc.accuracy in 0.1f..80f) {
                        lastKnownHighAccuracyLocation = loc
                      }
                      cont.resume(loc)
                    } else {
                      cont.resume(null)
                    }
                  }
                }
                .addOnFailureListener {
                  if (cont.isActive) cont.resume(null)
                }
            } catch (_: Exception) {
              if (cont.isActive) cont.resume(null)
            }
          }
        }
        if (freshFix != null) {
          val fallback = getBestImmediateLocation(context)
          // Do not let a coarse cell-tower fix (>200m) override a recent accurate GPS/Wi-Fi fix (<=55m)
          if (fallback != null && freshFix.accuracy > 200f && fallback.accuracy in 0.1f..55f &&
              (System.currentTimeMillis() - fallback.time) < 15 * 60_000L) {
            return fallback
          }
          return freshFix
        }
      }

      return getBestImmediateLocation(context)
    }

    @SuppressLint("MissingPermission")
    fun getBestImmediateLocation(context: Context): Location? {
      if (isEmulator()) return null
      val hasPerm = com.example.util.DeviceSecurityPermissionHelper.hasLocationPermission(context)
      if (!hasPerm) return lastKnownHighAccuracyLocation ?: latestHardwareFix
      return try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
        val candidates = mutableListOf<Location>()
        lastKnownHighAccuracyLocation?.let { candidates.add(it) }
        latestHardwareFix?.let { candidates.add(it) }
        if (lm != null) {
          val providerNames = linkedSetOf(
            "fused",
            LocationManager.GPS_PROVIDER,
            LocationManager.NETWORK_PROVIDER,
            LocationManager.PASSIVE_PROVIDER
          ).apply {
            try { addAll(lm.allProviders) } catch (_: Exception) {}
          }
          for (provider in providerNames) {
            val loc = try { lm.getLastKnownLocation(provider) } catch (_: Exception) { null }
            if (loc != null) candidates.add(loc)
          }
        }
        val valid = candidates.filter {
          !isEmulatorSyntheticUsLocation(it.latitude, it.longitude) &&
            it.latitude != 0.0 && it.longitude != 0.0
        }
        if (valid.isEmpty()) return null
        val now = System.currentTimeMillis()

        // If any recent high-accuracy fix (<=65m within 15 minutes) exists, discard coarse cell-tower fixes (>150m)
        val recentAccurate = valid.filter {
          val ageMs = (now - it.time).coerceAtLeast(0L)
          it.accuracy in 0.1f..65f && ageMs <= 15 * 60_000L
        }
        val pool = if (recentAccurate.isNotEmpty()) {
          recentAccurate
        } else {
          val mediumFixes = valid.filter {
            val ageMs = (now - it.time).coerceAtLeast(0L)
            it.accuracy in 0.1f..180f && ageMs <= 30 * 60_000L
          }
          mediumFixes.ifEmpty { valid }
        }

        pool.minByOrNull { loc ->
          val ageMinutes = ((now - loc.time).coerceAtLeast(0L)) / 60_000.0
          val accMeters = if (loc.accuracy > 0f) loc.accuracy.toDouble() else 120.0
          accMeters + (ageMinutes * 8.0)
        }?.also { best ->
          latestHardwareFix = best
          if (best.accuracy in 0.1f..80f) {
            lastKnownHighAccuracyLocation = best
          }
        }
      } catch (_: Exception) {
        lastKnownHighAccuracyLocation ?: latestHardwareFix
      }
    }

    fun reverseGeocodeExact(context: Context, lat: Double, lng: Double): String {
      if (isEmulatorSyntheticUsLocation(lat, lng)) {
        return DEFAULT_BAKU_ADDRESS
      }
      // Return cached address if child moved less than 12 meters
      if (cachedGeoAddress.isNotBlank() && cachedGeoLat != 0.0 && cachedGeoLng != 0.0) {
        val dLat = Math.toRadians(lat - cachedGeoLat)
        val dLon = Math.toRadians(lng - cachedGeoLng)
        val a = sin(dLat / 2) * sin(dLat / 2) +
          cos(Math.toRadians(cachedGeoLat)) * cos(Math.toRadians(lat)) *
          sin(dLon / 2) * sin(dLon / 2)
        val distMeters = 6371000.0 * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        if (distMeters < 12.0) {
          return cachedGeoAddress
        }
      }
      // 1. Try OpenStreetMap Nominatim for exact street + house number + neighborhood in Russian
      try {
        val url = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1&accept-language=ru"
        val req = Request.Builder()
          .url(url)
          .header("User-Agent", "KidsGuardParentalControl/2.8 (Android)")
          .build()
        geocodeHttpClient.newCall(req).execute().use { resp ->
          if (resp.isSuccessful) {
            val bodyStr = resp.body?.string().orEmpty()
            if (bodyStr.isNotBlank()) {
              val json = JSONObject(bodyStr)
              val addrObj = json.optJSONObject("address")
              if (addrObj != null) {
                val road = addrObj.optString("road")
                  .ifBlank { addrObj.optString("pedestrian") }
                  .ifBlank { addrObj.optString("residential") }
                  .ifBlank { addrObj.optString("neighbourhood") }
                  .ifBlank { addrObj.optString("suburb") }
                val house = addrObj.optString("house_number")
                val suburb = addrObj.optString("suburb")
                  .ifBlank { addrObj.optString("city_district") }
                  .ifBlank { addrObj.optString("quarter") }
                  .ifBlank { addrObj.optString("neighbourhood") }
                val city = addrObj.optString("city")
                  .ifBlank { addrObj.optString("town") }
                  .ifBlank { addrObj.optString("village") }
                  .ifBlank { addrObj.optString("state") }
                val parts = mutableListOf<String>()
                if (road.isNotBlank()) {
                  parts.add(if (house.isNotBlank()) "$road, $house" else road)
                } else if (house.isNotBlank()) {
                  parts.add("д. $house")
                }
                if (suburb.isNotBlank() && suburb != road) {
                  parts.add(suburb)
                }
                if (city.isNotBlank()) {
                  parts.add(city)
                }
                if (parts.isNotEmpty()) {
                  val result = parts.joinToString(", ")
                  cachedGeoLat = lat
                  cachedGeoLng = lng
                  cachedGeoAddress = result
                  return result
                }
              }
              val displayName = json.optString("display_name")
              if (displayName.isNotBlank()) {
                val result = displayName.split(",").take(4).joinToString(", ") { it.trim() }
                cachedGeoLat = lat
                cachedGeoLng = lng
                cachedGeoAddress = result
                return result
              }
            }
          }
        }
      } catch (_: Exception) {}

      // 2. Fallback to Android Geocoder
      try {
        val geocoder = Geocoder(context, Locale("ru", "RU"))
        @Suppress("DEPRECATION")
        val addresses = geocoder.getFromLocation(lat, lng, 1)
        if (!addresses.isNullOrEmpty()) {
          val addr = addresses[0]
          val fullLine = addr.getAddressLine(0)
          if (!fullLine.isNullOrBlank()) {
            return fullLine
          }
          val street = addr.thoroughfare ?: addr.featureName ?: ""
          val house = addr.subThoroughfare ?: ""
          val district = addr.subLocality ?: ""
          val city = addr.locality ?: addr.adminArea ?: ""
          val parts = listOfNotNull(
            if (street.isNotBlank()) (if (house.isNotBlank()) "$street, $house" else street) else null,
            district.takeIf { it.isNotBlank() },
            city.takeIf { it.isNotBlank() }
          )
          if (parts.isNotEmpty()) return parts.joinToString(", ")
        }
      } catch (_: Exception) {}

      return "Точные координаты: %.5f, %.5f".format(Locale.US, lat, lng)
    }

    /**
     * Disabled IP-based geolocation fallback because ISP gateway IP coordinates point to the
     * mobile operator's central node (wrong city/district) instead of the child's real location.
     * Instead we return the best hardware GPS/Fused/Network location.
     */
    fun fetchNetworkIpLocationFallback(context: Context): Location? {
      return getBestImmediateLocation(context)
    }
  }

  private val _locationState = MutableStateFlow(LocationState())
  val locationState: StateFlow<LocationState> = _locationState.asStateFlow()

  private val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
  private val fusedLocationClient = try {
    com.google.android.gms.location.LocationServices.getFusedLocationProviderClient(context)
  } catch (_: Exception) {
    null
  }
  private var fusedLocationCallback: com.google.android.gms.location.LocationCallback? = null
  private val scope = CoroutineScope(Dispatchers.Default)

  private var activeZones: List<GeofenceZoneEntity> = emptyList()
  private var lastDetectedZone: String? = null
  private var simulationJob: kotlinx.coroutines.Job? = null
  private var activeListener: LocationListener? = null
  @Volatile
  private var lastAcceptedLocation: Location? = null

  // Экономный GPS: режим «высокая точность» только пока родитель смотрит карту,
  // а обратный геокодинг и запись в БД — не на каждую секунду, а по факту перемещения.
  @Volatile
  private var lastTrackingHighAccuracy: Boolean = false
  private var lastTrackingModeSwitchMs: Long = 0L
  @Volatile
  private var lastGeocodeAddress: String = ""
  @Volatile
  private var lastGeocodeMs: Long = 0L
  private var lastPersistMs: Long = 0L

  init {
    activeInstances.add(this)
    readRealDeviceBattery()
  }

  fun stopLocationUpdates() {
    simulationJob?.cancel()
    simulationJob = null
    activeListener?.let { listener ->
      try {
        locationManager?.removeUpdates(listener)
      } catch (_: Exception) {}
    }
    fusedLocationCallback?.let { cb ->
      try {
        fusedLocationClient?.removeLocationUpdates(cb)
      } catch (_: Exception) {}
    }
    _locationState.value = _locationState.value.copy(isGpsActive = false)
  }

  fun startLiveMovementSimulation() {
    stopSimulation()
  }

  fun stopSimulation() {
    simulationJob?.cancel()
    simulationJob = null
  }

  fun updateKnownGeofences(zones: List<GeofenceZoneEntity>) {
    activeZones = zones
    if (_locationState.value.hasGpsFix) {
      checkGeofenceTransition(_locationState.value.latitude, _locationState.value.longitude)
    }
  }

  fun readRealDeviceBattery(): Int {
    val pct = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
    _locationState.value = _locationState.value.copy(batteryLevel = pct)
    return pct
  }

  private fun isBetterLocation(candidate: Location, currentBest: Location?): Boolean {
    if (currentBest == null) return true
    val timeDelta = candidate.time - currentBest.time
    val candidateAcc = if (candidate.accuracy > 0f) candidate.accuracy else 200f
    val currentAcc = if (currentBest.accuracy > 0f) currentBest.accuracy else 200f

    // Reject coarse cell-tower jumps (>120m error) if we have an accurate fix (<=60m) within the last 15 minutes
    if (candidateAcc > 120f && currentAcc <= 60f && timeDelta < 15 * 60_000L) {
      return false
    }
    if (timeDelta < -60_000L) return false

    val accuracyDelta = candidateAcc - currentAcc
    return when {
      accuracyDelta < -5f -> true
      timeDelta > 60_000L && candidateAcc <= 85f -> true
      timeDelta > 0L && accuracyDelta <= 20f -> true
      timeDelta > 15 * 60_000L -> true
      else -> false
    }
  }

  @SuppressLint("MissingPermission")
  fun startRealLocationUpdates() {
    if (isEmulator() || !isGpsTrackingAllowed) {
      stopLocationUpdates()
      return
    }
    android.os.Handler(Looper.getMainLooper()).post {
      try {
        readRealDeviceBattery()
        val lm = locationManager ?: return@post

        val providers = listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)
        val bestLastLoc = getBestImmediateLocation(context)
        bestLastLoc?.let { loc ->
          processNewLocation(loc)
        }

        // Remove previous registrations before re-registering to prevent duplicate listeners
        activeListener?.let { prev ->
          try { lm.removeUpdates(prev) } catch (_: Exception) {}
        }

        val listener = activeListener ?: object : LocationListener {
          override fun onLocationChanged(loc: Location) {
            if (!isGpsTrackingAllowed) {
              stopLocationUpdates()
              return
            }
            processNewLocation(loc)
          }
          override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
          override fun onProviderEnabled(provider: String) {}
          override fun onProviderDisabled(provider: String) {}
        }.also { activeListener = it }

        var anyActive = false
        // Режим трекинга зависит от того, смотрит ли родитель карту прямо сейчас.
        // Раньше ВСЕГДА запрашивался PRIORITY_HIGH_ACCURACY каждые 3–8 секунд — GPS-радио
        // не спало никогда, и телефон ребёнка разряжался за несколько часов.
        val parentViewing = com.example.data.cloud.DirectP2pSocketEngine.isParentMapViewing()
        lastTrackingHighAccuracy = parentViewing
        // 1. Primary Google Play Services FusedLocationProviderClient
        try {
          val fused = fusedLocationClient
          if (fused != null) {
            fused.lastLocation.addOnSuccessListener { loc ->
              if (loc != null && !isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
                processNewLocation(loc)
              }
            }
            // Активный GPS-фикс — тоже только когда родитель смотрит карту; иначе
            // читаем лишь пассивный кэш (lastLocation), не будя GNSS-радио.
            if (parentViewing) {
              val currentReq = CurrentLocationRequest.Builder()
                .setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                .setMaxUpdateAgeMillis(10_000L)
                .setDurationMillis(8_000L)
                .build()
              fused.getCurrentLocation(currentReq, null)
                .addOnSuccessListener { loc ->
                  if (loc != null && !isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
                    processNewLocation(loc)
                  }
                }
            }
            // Непрерывные обновления координат включаем ТОЛЬКО пока родитель смотрит карту.
            // Иначе (приложение свёрнуто / родитель на другом экране) GPS-радио не должно
            // просыпаться каждые 90 секунд — это и был главный фоновый расход батареи.
            if (parentViewing) {
              fusedLocationCallback?.let { prevCb ->
                try { fused.removeLocationUpdates(prevCb) } catch (_: Exception) {}
              }
              val cb = object : com.google.android.gms.location.LocationCallback() {
                override fun onLocationResult(result: com.google.android.gms.location.LocationResult) {
                  if (!isGpsTrackingAllowed) {
                    stopLocationUpdates()
                    return
                  }
                  val bestInBatch = result.locations
                    .filter { !isEmulatorSyntheticUsLocation(it.latitude, it.longitude) }
                    .minByOrNull { if (it.accuracy > 0f) it.accuracy else 999f }
                    ?: result.lastLocation
                  if (bestInBatch != null) {
                    processNewLocation(bestInBatch)
                  }
                }
              }
              fusedLocationCallback = cb
              val fusedReq = com.google.android.gms.location.LocationRequest.Builder(
                Priority.PRIORITY_HIGH_ACCURACY,
                12_000L
              )
                .setMinUpdateIntervalMillis(8_000L)
                .setMinUpdateDistanceMeters(10f)
                .setWaitForAccurateLocation(true)
                .build()
              fused.requestLocationUpdates(fusedReq, cb, Looper.getMainLooper())
              anyActive = true
            }
          }
        } catch (_: Exception) {}

        // 2. Hardware LocationManager fallback — тоже только пока родитель смотрит карту.
        if (parentViewing) {
          for (provider in providers) {
            try {
              if (lm.allProviders.contains(provider)) {
                val isSatelliteGps = provider == LocationManager.GPS_PROVIDER
                val intervalMs = if (isSatelliteGps) 15_000L else 20_000L
                val minDistanceM = 10f
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                  val quality = if (isSatelliteGps) {
                    android.location.LocationRequest.QUALITY_HIGH_ACCURACY
                  } else {
                    android.location.LocationRequest.QUALITY_BALANCED_POWER_ACCURACY
                  }
                  val req = android.location.LocationRequest.Builder(intervalMs)
                    .setQuality(quality)
                    .setMinUpdateDistanceMeters(minDistanceM)
                    .setMinUpdateIntervalMillis(10_000L)
                    .build()
                  lm.requestLocationUpdates(provider, req, context.mainExecutor, listener)
                } else {
                  lm.requestLocationUpdates(provider, intervalMs, minDistanceM, listener, Looper.getMainLooper())
                }
                anyActive = true
              }
            } catch (_: Exception) {}
          }
        }

        _locationState.value = _locationState.value.copy(
          isGpsActive = anyActive
        )
      } catch (_: Exception) {}
    }
  }

  private fun processNewLocation(loc: Location) {
    if (isEmulator() || isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
      return
    }
    if (loc.latitude == 0.0 && loc.longitude == 0.0) return
    val prev = lastAcceptedLocation
    if (prev != null && !isBetterLocation(loc, prev)) {
      return
    }
    val movedMeters = if (prev != null) {
      distanceMeters(prev.latitude, prev.longitude, loc.latitude, loc.longitude)
    } else {
      Double.MAX_VALUE
    }
    lastAcceptedLocation = loc
    latestHardwareFix = loc
    if (loc.accuracy in 0.1f..80f) {
      lastKnownHighAccuracyLocation = loc
    }

    val nowMs = System.currentTimeMillis()
    // Переключаем режим GPS при появлении/пропадании родителя на карте (не чаще раза в 30 с).
    val parentViewingNow = com.example.data.cloud.DirectP2pSocketEngine.isParentMapViewing()
    if (parentViewingNow != lastTrackingHighAccuracy && nowMs - lastTrackingModeSwitchMs > 30_000L) {
      lastTrackingModeSwitchMs = nowMs
      startRealLocationUpdates()
    }

    // Геокодинг — только при заметном перемещении или раз в 2 минуты (сеть/Geocoder дорогие).
    val needGeocode = lastGeocodeAddress.isBlank() || movedMeters > 60.0 || nowMs - lastGeocodeMs > 120_000L
    // Запись маршрута в SQLite — при перемещении >40 м или раз в минуту.
    val needPersist = movedMeters > 40.0 || nowMs - lastPersistMs > 60_000L

    scope.launch(Dispatchers.IO) {
      val effectiveLat = loc.latitude
      val effectiveLng = loc.longitude
      val realAddress = if (needGeocode) {
        val fresh = reverseGeocodeExact(context, effectiveLat, effectiveLng)
        if (fresh.isNotBlank() && !isStaleOrDemoAddress(fresh)) {
          lastGeocodeAddress = fresh
          lastGeocodeMs = System.currentTimeMillis()
          fresh
        } else {
          lastGeocodeAddress.ifBlank { fresh }
        }
      } else {
        lastGeocodeAddress.ifBlank { _locationState.value.currentAddress }
      }
      val speed = (loc.speed * 3.6f).coerceAtLeast(0f)
      val accuracy = if (loc.accuracy > 0f) loc.accuracy else 8f
      val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(context)
      val zoneName = checkGeofenceTransition(effectiveLat, effectiveLng)

      val cutoff24h = nowMs - 24L * 3600L * 1000L
      val newCrumb = LocationBreadcrumb(
        latitude = effectiveLat,
        longitude = effectiveLng,
        timestamp = nowMs,
        title = realAddress.substringBefore(","),
        speedKmh = speed
      )

      val updatedBreadcrumbs = (_locationState.value.breadcrumbs + newCrumb)
        .filter { it.timestamp >= cutoff24h }
        .takeLast(200)

      _locationState.value = _locationState.value.copy(
        latitude = effectiveLat,
        longitude = effectiveLng,
        accuracyMeters = accuracy,
        speedKmh = speed,
        currentAddress = realAddress,
        isGpsActive = true,
        hasGpsFix = true,
        activeGeofenceName = zoneName,
        breadcrumbs = updatedBreadcrumbs,
        batteryLevel = batt,
        isMoving = speed > 2.5f
      )

      if (!needPersist) return@launch
      lastPersistMs = nowMs

      // Directly sync real physical device GPS coordinates & save to Offline Store-and-Forward Room DB when this phone is CHILD
      try {
        val db = com.example.data.local.KidsGuardDatabase.getDatabase(context)
        val dao = db.kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        if (cfg?.selectedRole == "CHILD") {
          val targetChildId = when {
            cfg.thisDeviceChildId > 0 -> cfg.thisDeviceChildId
            cfg.currentActiveChildId > 0 -> cfg.currentActiveChildId
            else -> dao.getAllChildrenOnce().firstOrNull()?.id ?: 1L
          }
          dao.updateChildLocation(targetChildId, effectiveLat, effectiveLng, realAddress, nowMs)
          // Save into local Room OfflineStoreAndForward queue so even if parent is offline, full route is preserved!
          com.example.data.cloud.OfflineStoreAndForwardManager.recordChildLocationOffline(
            context = context,
            childId = targetChildId,
            lat = effectiveLat,
            lng = effectiveLng,
            address = realAddress,
            speedKmh = speed,
            accuracyMeters = accuracy
          )
          // Battery Saver: Only transmit real-time network packets when Parent is actively connected
          if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
            com.example.data.cloud.CloudSyncManager(context).updateLocation(
              childId = targetChildId,
              lat = effectiveLat,
              lng = effectiveLng,
              address = realAddress
            )
            com.example.data.cloud.OfflineStoreAndForwardManager.flushUnsyncedHistoryToParent(context, targetChildId)
          }
        }
      } catch (_: Exception) {}
    }
  }

  private fun checkGeofenceTransition(lat: Double, lng: Double): String? {
    var detectedZone: GeofenceZoneEntity? = null
    for (zone in activeZones) {
      val dist = distanceMeters(lat, lng, zone.latitude, zone.longitude)
      if (dist <= zone.radiusMeters) {
        detectedZone = zone
        break
      }
    }

    val currentZoneName = detectedZone?.name

    if (currentZoneName != lastDetectedZone) {
      if (detectedZone != null && detectedZone.notifyOnEntry) {
        onGeofenceEvent("GEOFENCE_ENTER", detectedZone.name)
      } else if (lastDetectedZone != null) {
        onGeofenceEvent("GEOFENCE_EXIT", lastDetectedZone!!)
      }
      lastDetectedZone = currentZoneName
    }

    return currentZoneName
  }

  private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return r * c
  }
}
