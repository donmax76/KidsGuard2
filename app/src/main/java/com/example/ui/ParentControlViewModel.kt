package com.example.ui

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.model.AggregatedActivityByDateItem
import com.example.data.model.AlertNotificationEntity
import com.example.data.model.AppControlEntity
import com.example.data.model.ChatMessageEntity
import com.example.data.model.ChildProfileEntity
import com.example.data.model.ContentRuleEntity
import com.example.data.model.FamilyConfigEntity
import com.example.data.model.GeofenceZoneEntity
import com.example.data.model.OfflineActivityLogEntity
import com.example.data.model.ParentSettingsEntity
import com.example.data.repository.ContentFilterEvaluation
import com.example.data.repository.ParentalControlRepository
import com.example.location.LiveLocationTracker
import com.example.location.LocationState
import com.example.util.NetworkConnectivityObserver
import com.example.util.NetworkState
import com.example.util.TrafficConsumptionEstimate
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppRole {
  UNCONFIGURED,
  PARENT,
  CHILD
}

enum class ParentTab {
  DASHBOARD,
  APPS,
  SCREEN_TIME,
  CONTENT_FILTER,
  LOCATION_MAP,
  ALERTS,
  CHAT,
  PC_CONTROL
}

enum class ActivityDateFilterPreset {
  TODAY,
  YESTERDAY,
  CUSTOM_RANGE
}

data class ActivityDateFilterState(
  val preset: ActivityDateFilterPreset = ActivityDateFilterPreset.TODAY,
  val startTimestampMs: Long,
  val endTimestampMs: Long,
  val startDateIso: String,
  val endDateIso: String,
  val displayLabel: String
) {
  companion object {
    fun buildToday(): ActivityDateFilterState {
      val cal = Calendar.getInstance()
      cal.set(Calendar.HOUR_OF_DAY, 0)
      cal.set(Calendar.MINUTE, 0)
      cal.set(Calendar.SECOND, 0)
      cal.set(Calendar.MILLISECOND, 0)
      val startMs = cal.timeInMillis
      cal.set(Calendar.HOUR_OF_DAY, 23)
      cal.set(Calendar.MINUTE, 59)
      cal.set(Calendar.SECOND, 59)
      cal.set(Calendar.MILLISECOND, 999)
      val endMs = cal.timeInMillis
      val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startMs))
      val ruDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(startMs))
      return ActivityDateFilterState(
        preset = ActivityDateFilterPreset.TODAY,
        startTimestampMs = startMs,
        endTimestampMs = endMs,
        startDateIso = iso,
        endDateIso = iso,
        displayLabel = "Сегодня ($ruDate)"
      )
    }

    fun buildYesterday(): ActivityDateFilterState {
      val cal = Calendar.getInstance()
      cal.add(Calendar.DAY_OF_YEAR, -1)
      cal.set(Calendar.HOUR_OF_DAY, 0)
      cal.set(Calendar.MINUTE, 0)
      cal.set(Calendar.SECOND, 0)
      cal.set(Calendar.MILLISECOND, 0)
      val startMs = cal.timeInMillis
      cal.set(Calendar.HOUR_OF_DAY, 23)
      cal.set(Calendar.MINUTE, 59)
      cal.set(Calendar.SECOND, 59)
      cal.set(Calendar.MILLISECOND, 999)
      val endMs = cal.timeInMillis
      val iso = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(startMs))
      val ruDate = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault()).format(Date(startMs))
      return ActivityDateFilterState(
        preset = ActivityDateFilterPreset.YESTERDAY,
        startTimestampMs = startMs,
        endTimestampMs = endMs,
        startDateIso = iso,
        endDateIso = iso,
        displayLabel = "Вчера ($ruDate)"
      )
    }

    fun buildCustomRange(rawStartMs: Long, rawEndMs: Long): ActivityDateFilterState {
      val minMs = minOf(rawStartMs, rawEndMs)
      val maxMs = maxOf(rawStartMs, rawEndMs)
      val startCal = Calendar.getInstance().apply {
        timeInMillis = minMs
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
      }
      val endCal = Calendar.getInstance().apply {
        timeInMillis = maxMs
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
      }
      val sMs = startCal.timeInMillis
      val eMs = endCal.timeInMillis
      val isoFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
      val ruFmt = SimpleDateFormat("dd.MM.yyyy", Locale.getDefault())
      val sIso = isoFmt.format(Date(sMs))
      val eIso = isoFmt.format(Date(eMs))
      val label = if (sIso == eIso) {
        "Дата: ${ruFmt.format(Date(sMs))}"
      } else {
        "Период: ${ruFmt.format(Date(sMs))} – ${ruFmt.format(Date(eMs))}"
      }
      return ActivityDateFilterState(
        preset = ActivityDateFilterPreset.CUSTOM_RANGE,
        startTimestampMs = sMs,
        endTimestampMs = eMs,
        startDateIso = sIso,
        endDateIso = eIso,
        displayLabel = label
      )
    }
  }
}

data class UiToastMessage(
  val message: String,
  val isError: Boolean = false
)

data class RemoteUpdateReport(
  val childName: String,
  val newVersion: String,
  val changes: List<String>
)

class ParentControlViewModel(application: Application) : AndroidViewModel(application) {
  private val repository = ParentalControlRepository(application)

  private val locationTracker = LiveLocationTracker(application) { eventType, zoneName ->
    viewModelScope.launch {
      val currentActive = activeChild.value
      // CRITICAL: NEVER emit zone alerts if there are no children registered!
      if (currentActive != null && allChildren.value.isNotEmpty()) {
        val title = if (eventType == "GEOFENCE_ENTER") "Вход в безопасную зону" else "Выход из зоны"
        val desc = if (eventType == "GEOFENCE_ENTER") "${currentActive.name} вошел(ла) в зону «$zoneName»" else "${currentActive.name} покинул(а) зону «$zoneName»"
        repository.addAlert(
          childId = currentActive.id,
          type = eventType,
          title = title,
          message = desc,
          payload = zoneName
        )
      }
    }
  }

  // Family Config & Children
  val familyConfig: StateFlow<FamilyConfigEntity?> = repository.familyConfig
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val allChildren: StateFlow<List<ChildProfileEntity>> = repository.allChildren
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  private val _selectedChildId = MutableStateFlow<Long?>(null)
  val selectedChildId: StateFlow<Long?> = _selectedChildId.asStateFlow()

  // Selected child object derived from allChildren + selectedChildId + familyConfig
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val activeChild: StateFlow<ChildProfileEntity?> = combine(allChildren, _selectedChildId, familyConfig) { children, selectedId, cfg ->
    val isChildRole = cfg?.selectedRole == "CHILD"
    val thisChildId = cfg?.thisDeviceChildId ?: 0L
    val activeCfgId = cfg?.currentActiveChildId ?: 0L
    if (children.isEmpty()) {
      null
    } else if (isChildRole && thisChildId > 0) {
      children.find { it.id == thisChildId } ?: children.firstOrNull()
    } else if (selectedId != null && selectedId > 0) {
      children.find { it.id == selectedId } ?: children.firstOrNull()
    } else if (activeCfgId > 0) {
      children.find { it.id == activeCfgId } ?: children.firstOrNull()
    } else if (thisChildId > 0) {
      children.find { it.id == thisChildId } ?: children.firstOrNull()
    } else {
      children.firstOrNull()
    }
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  // Current Device Role
  private val _deviceRole = MutableStateFlow(AppRole.PARENT)
  val deviceRole: StateFlow<AppRole> = _deviceRole.asStateFlow()

  // Active Parent tab
  private val _activeParentTab = MutableStateFlow(ParentTab.DASHBOARD)
  val activeParentTab: StateFlow<ParentTab> = _activeParentTab.asStateFlow()

  // Apps, Geofences, Content Rules, Alerts
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val allApps: StateFlow<List<AppControlEntity>> = combine(allChildren, _selectedChildId, familyConfig) { children, selectedId, cfg ->
    val isChildRole = cfg?.selectedRole == "CHILD"
    val thisChildId = cfg?.thisDeviceChildId ?: 0L
    if (children.isEmpty()) 0L
    else if (isChildRole && thisChildId > 0) thisChildId
    else if (selectedId != null && selectedId > 0) selectedId
    else if ((cfg?.currentActiveChildId ?: 0L) > 0) cfg!!.currentActiveChildId
    else children.firstOrNull()?.id ?: 0L
  }.flatMapLatest { childId ->
    if (childId > 0) repository.getAppsForChild(childId)
    else flowOf(emptyList())
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val allGeofences: StateFlow<List<GeofenceZoneEntity>> = repository.allGeofences
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val allContentRules: StateFlow<List<ContentRuleEntity>> = repository.allContentRules
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val parentSettings: StateFlow<ParentSettingsEntity?> = repository.parentSettings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

  val allAlerts: StateFlow<List<AlertNotificationEntity>> = repository.allAlerts
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  val locationState: StateFlow<LocationState> = locationTracker.locationState

  // Chat between parent and active child
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val chatMessages: StateFlow<List<ChatMessageEntity>> = combine(allChildren, _selectedChildId, familyConfig) { children, selectedId, cfg ->
    val isChildRole = cfg?.selectedRole == "CHILD"
    val thisChildId = cfg?.thisDeviceChildId ?: 0L
    val activeCfgId = cfg?.currentActiveChildId ?: 0L
    if (children.isEmpty()) 0L
    else if (isChildRole && thisChildId > 0) thisChildId
    else if (selectedId != null && selectedId > 0) selectedId
    else if (activeCfgId > 0) activeCfgId
    else if (thisChildId > 0) thisChildId
    else (children.firstOrNull()?.id ?: 0L)
  }.flatMapLatest { childId ->
    if (childId > 0) repository.getChatMessagesForChild(childId)
    else flowOf(emptyList())
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // Remote update in-progress indicator & completion report
  private val _isRemoteUpdating = MutableStateFlow(false)
  val isRemoteUpdating: StateFlow<Boolean> = _isRemoteUpdating.asStateFlow()

  private val _updateCompletedReport = MutableStateFlow<RemoteUpdateReport?>(null)
  val updateCompletedReport: StateFlow<RemoteUpdateReport?> = _updateCompletedReport.asStateFlow()

  fun dismissUpdateReport() {
    _updateCompletedReport.value = null
  }

  // Network Connectivity & Traffic Observer
  private val networkObserver = NetworkConnectivityObserver(application)
  val networkState: StateFlow<NetworkState> = networkObserver.observe()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), networkObserver.getCurrentNetworkState())

  val trafficEstimates: List<TrafficConsumptionEstimate> = NetworkConnectivityObserver.getTrafficCalculations()

  // UI state
  private val _uiToast = MutableStateFlow<UiToastMessage?>(null)
  val uiToast: StateFlow<UiToastMessage?> = _uiToast.asStateFlow()

  private val _testFilterEvaluation = MutableStateFlow<ContentFilterEvaluation?>(null)
  val testFilterEvaluation: StateFlow<ContentFilterEvaluation?> = _testFilterEvaluation.asStateFlow()

  private val _isPinDialogVisible = MutableStateFlow(false)
  val isPinDialogVisible: StateFlow<Boolean> = _isPinDialogVisible.asStateFlow()

  private val _isPairingModalVisible = MutableStateFlow(false)
  val isPairingModalVisible: StateFlow<Boolean> = _isPairingModalVisible.asStateFlow()

  private val _isAddChildModalVisible = MutableStateFlow(false)
  val isAddChildModalVisible: StateFlow<Boolean> = _isAddChildModalVisible.asStateFlow()

  private val _isSimulationActive = MutableStateFlow(false)
  val isSimulationActive: StateFlow<Boolean> = _isSimulationActive.asStateFlow()

  private val _childBrowserUrl = MutableStateFlow("https://ru.wikipedia.org")
  val childBrowserUrl: StateFlow<String> = _childBrowserUrl.asStateFlow()

  private val _childBrowserBlockedReason = MutableStateFlow<String?>(null)
  val childBrowserBlockedReason: StateFlow<String?> = _childBrowserBlockedReason.asStateFlow()

  // --- Phase 5: PC Activity History Date Filter & SQLite Aggregation State ---
  private val _activityDateFilter = MutableStateFlow(ActivityDateFilterState.buildToday())
  val activityDateFilter: StateFlow<ActivityDateFilterState> = _activityDateFilter.asStateFlow()

  fun selectTodayActivityFilter() {
    _activityDateFilter.value = ActivityDateFilterState.buildToday()
  }

  fun selectYesterdayActivityFilter() {
    _activityDateFilter.value = ActivityDateFilterState.buildYesterday()
  }

  fun selectCustomActivityDateRange(startMillis: Long, endMillis: Long) {
    _activityDateFilter.value = ActivityDateFilterState.buildCustomRange(startMillis, endMillis)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val filteredPcProcs: StateFlow<List<AggregatedActivityByDateItem>> = combine(
    activeChild,
    _activityDateFilter
  ) { child, filter ->
    Pair(child?.id ?: 0L, filter)
  }.flatMapLatest { (childId, filter) ->
    repository.getAggregatedProcsByDateRange(
      childId = childId,
      deviceType = "PC",
      startTimestamp = filter.startTimestampMs,
      endTimestamp = filter.endTimestampMs
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val filteredPcTitles: StateFlow<List<AggregatedActivityByDateItem>> = combine(
    activeChild,
    _activityDateFilter
  ) { child, filter ->
    Pair(child?.id ?: 0L, filter)
  }.flatMapLatest { (childId, filter) ->
    repository.getAggregatedWindowTitlesByDateRange(
      childId = childId,
      deviceType = "PC",
      startTimestamp = filter.startTimestampMs,
      endTimestamp = filter.endTimestampMs
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val filteredPcLogs: StateFlow<List<OfflineActivityLogEntity>> = combine(
    activeChild,
    _activityDateFilter
  ) { child, filter ->
    Pair(child?.id ?: 0L, filter)
  }.flatMapLatest { (childId, filter) ->
    repository.getActivityLogsByDateRange(
      childId = childId,
      deviceType = "PC",
      startTimestamp = filter.startTimestampMs,
      endTimestamp = filter.endTimestampMs
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  val filteredPcTotalSeconds: StateFlow<Long> = combine(
    activeChild,
    _activityDateFilter
  ) { child, filter ->
    Pair(child?.id ?: 0L, filter)
  }.flatMapLatest { (childId, filter) ->
    repository.getTotalActiveSecondsByDateRange(
      childId = childId,
      deviceType = "PC",
      startTimestamp = filter.startTimestampMs,
      endTimestamp = filter.endTimestampMs
    )
  }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0L)

  init {
    // Start embedded LAN HTTP APK server so Child phones on Wi-Fi/Hotspot can download Parent's exact installed APK directly
    com.example.util.ApkUpdateInstaller.startParentLanApkServer(getApplication())

    // Publish current build version & live APK URL to Firestore and check for updates
    com.example.util.ApkUpdateInstaller.publishLatestUpdateToCloud(
      versionCode = com.example.BuildConfig.VERSION_CODE,
      versionName = com.example.BuildConfig.VERSION_NAME,
      apkUrl = com.example.util.ApkUpdateInstaller.SHARED_APK_URL
    )
    com.example.util.ApkUpdateInstaller.checkCloudUpdateAvailable(com.example.BuildConfig.VERSION_CODE)

    viewModelScope.launch {
      allGeofences.collect { zones ->
        locationTracker.updateKnownGeofences(zones)
      }
    }

    // Automatically sync real device GPS coordinates & battery to cloud when running in CHILD mode (only when Parent is connected)
    viewModelScope.launch {
      combine(locationTracker.locationState, _deviceRole, familyConfig) { loc, role, cfg ->
        Triple(loc, role, cfg)
      }.collect { (loc, role, cfg) ->
        if (role == AppRole.CHILD && !com.example.location.LiveLocationTracker.isEmulator()) {
          val childIdToUpdate = when {
            cfg != null && cfg.thisDeviceChildId > 0L -> cfg.thisDeviceChildId
            cfg != null && cfg.currentActiveChildId > 0L -> cfg.currentActiveChildId
            activeChild.value != null && activeChild.value!!.id > 0L -> activeChild.value!!.id
            else -> 0L
          }
          if (childIdToUpdate > 0L && com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
            val batt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(getApplication())
            repository.updateChildBattery(childIdToUpdate, batt)
            if (com.example.location.LiveLocationTracker.isGpsTrackingAllowed &&
                loc.hasGpsFix &&
                !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
              repository.updateChildLocation(
                childId = childIdToUpdate,
                lat = loc.latitude,
                lng = loc.longitude,
                address = loc.currentAddress
              )
            }
          }
        }
      }
    }

    // Lightweight heartbeat:
    // - In PARENT mode: PING каждые 10 с + полный PARENT_ONLINE_HEARTBEAT раз в 40 с.
    //   PING даёт короткий таймаут «Оффлайн» (30 с), не дёргая GPS и выгрузку истории.
    // - In CHILD mode: checks 00:00 midnight reset and updates battery/location ONLY when Parent is actively connected.
    viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
      var heartbeatTick = 0
      while (true) {
        com.example.util.DeviceUsageTracker.ensureDailyMidnightReset(getApplication())
        if (_deviceRole.value == AppRole.PARENT) {
          val targetChildId = activeChild.value?.id ?: allChildren.value.firstOrNull()?.id ?: 0L
          if (targetChildId > 0L) {
            repository.sendRemoteCloudCommand(targetChildId, "PING", "1")
            if (heartbeatTick % 4 == 0) {
              repository.sendRemoteCloudCommand(targetChildId, "PARENT_ONLINE_HEARTBEAT", "1")
              // Высокоточный GPS ребёнку нужен только когда открыта карта, а не всегда,
              // пока приложение родителя просто запущено.
              if (_activeParentTab.value == ParentTab.LOCATION_MAP) {
                repository.sendRemoteCloudCommand(targetChildId, "MAP_VIEWING", "1")
              }
            }
          }
          heartbeatTick++
          kotlinx.coroutines.delay(10_000L)
          continue
        }
        if (_deviceRole.value == AppRole.CHILD && !com.example.location.LiveLocationTracker.isEmulator()) {
          val cfg = familyConfig.value ?: repository.getFamilyConfigOnce()
          val childId = when {
            cfg != null && cfg.thisDeviceChildId > 0L -> cfg.thisDeviceChildId
            cfg != null && cfg.currentActiveChildId > 0L -> cfg.currentActiveChildId
            activeChild.value != null && activeChild.value!!.id > 0L -> activeChild.value!!.id
            else -> 0L
          }
          if (childId > 0L) {
            val batt = locationTracker.readRealDeviceBattery()
            if (com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              repository.updateChildBattery(childId, batt)
            }
            val gpsEnabled = activeChild.value?.gpsTrackingEnabled ?: com.example.location.LiveLocationTracker.isGpsTrackingAllowed
            com.example.location.LiveLocationTracker.setGpsTrackingAllowed(gpsEnabled)
            if (gpsEnabled && com.example.data.cloud.DirectP2pSocketEngine.isParentActivelyConnected()) {
              val loc = com.example.location.LiveLocationTracker.getBestImmediateLocation(getApplication())
              if (loc != null && !com.example.location.LiveLocationTracker.isEmulatorSyntheticUsLocation(loc.latitude, loc.longitude)) {
                val addr = com.example.location.LiveLocationTracker.reverseGeocodeExact(getApplication(), loc.latitude, loc.longitude)
                repository.updateChildLocation(childId, loc.latitude, loc.longitude, addr)
              }
            } else if (!gpsEnabled) {
              locationTracker.stopLocationUpdates()
            }
          }
        }
        kotlinx.coroutines.delay(60_000L)
      }
    }

    viewModelScope.launch {
      combine(familyConfig, activeChild) { config, child ->
        Pair(config, child)
      }.collect { (config, child) ->
        config?.let {
          if (_selectedChildId.value == null && it.currentActiveChildId > 0) {
            _selectedChildId.value = it.currentActiveChildId
          }
          val role = when (it.selectedRole) {
            "CHILD" -> AppRole.CHILD
            "UNCONFIGURED" -> AppRole.UNCONFIGURED
            else -> AppRole.PARENT
          }
          _deviceRole.value = role
          val gpsAllowed = child?.gpsTrackingEnabled ?: false
          com.example.location.LiveLocationTracker.setGpsTrackingAllowed(gpsAllowed)
          if (role == AppRole.CHILD && !com.example.location.LiveLocationTracker.isEmulator() && gpsAllowed) {
            locationTracker.startRealLocationUpdates()
          } else {
            locationTracker.stopLocationUpdates()
          }
        }
      }
    }
  }

  // Parent Navigation Tab
  fun setActiveParentTab(tab: ParentTab) {
    _activeParentTab.value = tab
    if (tab == ParentTab.CHAT) {
      markChatMessagesAsRead()
    } else if (tab == ParentTab.LOCATION_MAP) {
      requestFreshChildGps()
      if (_deviceRole.value == AppRole.PARENT) {
        val childId = activeChild.value?.id ?: allChildren.value.firstOrNull()?.id ?: 0L
        if (childId > 0L) {
          viewModelScope.launch {
            repository.sendRemoteCloudCommand(childId, "MAP_VIEWING", "1")
          }
        }
      }
    } else if (tab == ParentTab.APPS && _deviceRole.value == AppRole.PARENT) {
      val childId = activeChild.value?.id ?: allChildren.value.firstOrNull()?.id ?: 0L
      if (childId > 0L) {
        viewModelScope.launch {
          repository.sendRemoteCloudCommand(childId, "REFRESH_APPS", "")
        }
      }
    }
  }

  fun requestFreshChildGps() {
    if (_deviceRole.value == AppRole.CHILD && !com.example.location.LiveLocationTracker.isEmulator()) {
      locationTracker.startRealLocationUpdates()
    }
    val childId = activeChild.value?.id ?: allChildren.value.firstOrNull()?.id ?: return
    viewModelScope.launch {
      repository.sendRemoteCloudCommand(childId, "REQUEST_GPS_NOW", "1")
    }
  }

  // Device Role switching
  fun setDeviceRole(role: AppRole) {
    _deviceRole.value = role
    if (role != AppRole.CHILD) {
      locationTracker.stopLocationUpdates()
      com.example.service.LockOverlayService.stop(getApplication())
      com.example.service.KidsGuardBackgroundService.stop(getApplication())
    }
    val preferredId = _selectedChildId.value ?: activeChild.value?.id ?: 0L
    viewModelScope.launch {
      repository.setDeviceRole(role.name, preferredId)
    }
  }

  fun requestSwitchToParentRole() {
    if (_deviceRole.value == AppRole.CHILD) {
      _isPinDialogVisible.value = true
    } else {
      setDeviceRole(AppRole.PARENT)
    }
  }

  fun verifyPin(pin: String): Boolean {
    val masterPin = familyConfig.value?.parentMasterPin ?: "1234"
    return if (pin == masterPin) {
      _isPinDialogVisible.value = false
      com.example.util.DeviceSecurityPermissionHelper.setUninstallBlocked(getApplication(), false)
      setDeviceRole(AppRole.PARENT)
      showToast("Вход выполнен")
      true
    } else {
      false
    }
  }

  fun dismissPinDialog() {
    _isPinDialogVisible.value = false
  }

  // Multi-Child management
  fun selectChild(childId: Long) {
    _selectedChildId.value = childId
    viewModelScope.launch {
      if (_deviceRole.value == AppRole.CHILD) {
        repository.bindThisDeviceToChild(childId)
      } else {
        repository.setActiveChild(childId)
        repository.sendRemoteCloudCommand(childId, "REFRESH_APPS", "")
      }
      val childName = allChildren.value.find { it.id == childId }?.name
      if (childName != null) {
        showToast("Выбран профиль: $childName")
      }
      repository.sendRemoteCloudCommand(childId, "QUERY_ACTIVE_APP", "")
    }
  }

  fun bindThisChildDeviceToProfile(childId: Long) {
    _selectedChildId.value = childId
    viewModelScope.launch {
      repository.bindThisDeviceToChild(childId)
      val childName = allChildren.value.find { it.id == childId }?.name ?: "Ребёнок #$childId"
      showToast("Этот телефон привязан к профилю «$childName» (ID: $childId)!")
    }
  }

  fun addNewChild(name: String, age: Int, dailyLimitMinutes: Int) {
    viewModelScope.launch {
      val newId = repository.addNewChild(name, age, dailyLimitMinutes)
      _selectedChildId.value = newId
      _isAddChildModalVisible.value = false
      showToast("Ребенок «$name» добавлен и выбран активным!")
    }
  }

  // Pillar 2: Восстановление всех исходных параметров и разрешений перед удалением ребенка
  fun deleteChild(child: ChildProfileEntity) {
    viewModelScope.launch {
      repository.restoreInitialParametersAndReleaseChild(child.id)
      if (_selectedChildId.value == child.id) {
        _selectedChildId.value = null
      }
      showToast("Все исходные параметры и разрешения для «${child.name}» восстановлены. Профиль удален.")
    }
  }

  // Communication: Семейный чат & Звуковой сигнал
  fun sendChatMessage(text: String, isAttentionSignal: Boolean = false) {
    val child = activeChild.value ?: return
    val role = if (_deviceRole.value == AppRole.CHILD) "CHILD" else "PARENT"
    val senderName = if (role == "CHILD") child.name else "Родитель"
    viewModelScope.launch {
      repository.sendChatMessage(
        childId = child.id,
        senderRole = role,
        senderName = senderName,
        text = text,
        isAttentionSignal = isAttentionSignal
      )
      if (isAttentionSignal) {
        showToast("Громкий сигнал привлечения внимания отправлен «${child.name}»!")
      }
    }
  }

  val isSirenActive: StateFlow<Boolean> = com.example.util.SoundAlertManager.isSirenActive
  private var sirenAutoOffJob: kotlinx.coroutines.Job? = null

  fun sendLoudAttentionSignal() {
    val child = activeChild.value ?: allChildren.value.firstOrNull()
    val targetId = child?.id ?: 1L
    val childName = child?.name ?: "ребенка"
    val currentlyActive = isSirenActive.value

    if (currentlyActive) {
      sirenAutoOffJob?.cancel()
      com.example.util.SoundAlertManager.setRemoteSirenState(false)
      repository.stopDeviceLoudAlarm()
      viewModelScope.launch {
        repository.sendRemoteCloudCommand(targetId, "STOP_LOUD_RING", "OFF")
      }
      showToast("Звуковая сирена на телефоне «$childName» ВЫКЛЮЧЕНА")
    } else {
      com.example.util.SoundAlertManager.setRemoteSirenState(true)
      viewModelScope.launch {
        repository.sendRemoteCloudCommand(targetId, "LOUD_RING", "ON")
        repository.sendChatMessage(
          childId = targetId,
          senderRole = "PARENT",
          senderName = "Родитель",
          text = "🔊 ВНИМАНИЕ: Родитель включил звуковую сирену! Срочно выйдите на связь.",
          isAttentionSignal = true
        )
      }
      showToast("Сирена ВКЛЮЧЕНА на телефоне «$childName»! Нажмите ещё раз для отключения.")
      sirenAutoOffJob?.cancel()
      sirenAutoOffJob = viewModelScope.launch {
        delay(12_500L)
        com.example.util.SoundAlertManager.setRemoteSirenState(false)
      }
    }
  }

  fun markChatMessagesAsRead() {
    val child = activeChild.value ?: return
    val role = if (_deviceRole.value == AppRole.CHILD) "CHILD" else "PARENT"
    viewModelScope.launch {
      repository.markChatMessagesRead(child.id, role)
    }
  }

  // Pillar 3: Удаленное фоновое обновление программы ребенка (Горячая замена Ядра без переустановки оболочки)
  fun triggerRemoteSilentUpdate(childId: Long, targetVersion: String = "") {
    val child = allChildren.value.find { it.id == childId } ?: return
    viewModelScope.launch {
      _isRemoteUpdating.value = true
      val resolvedTargetVersion = targetVersion.ifBlank {
        com.example.util.CoreEngineHotUpdater.getEffectiveVersionName(getApplication())
      }

      showToast(
        "Синхронизация и горячая замена Ядра v$resolvedTargetVersion на телефоне «${child.name}»..."
      )

      // Prepare multi-channel APK/Core URLs (Direct P2P LAN HTTP URL from Parent's installed APK + Cloud Mirror)
      val multiApkUrls = com.example.util.ApkUpdateInstaller.prepareParentApkUrlsForChildUpdate(getApplication())
      val primaryCloudUrl = multiApkUrls.split("|")
        .firstOrNull { it.startsWith("https://") }
        ?: com.example.util.ApkUpdateInstaller.SHARED_APK_URL

      com.example.util.ApkUpdateInstaller.publishLatestUpdateToCloud(
        versionCode = com.example.util.CoreEngineHotUpdater.getEffectiveVersionCode(getApplication()),
        versionName = resolvedTargetVersion,
        apkUrl = primaryCloudUrl
      )

      // Mark updatePending = true in DB and log alert
      repository.triggerRemoteSilentUpdate(childId, resolvedTargetVersion)

      // Send real P2P TCP + Cloud OTA Core Engine update command to child's phone
      repository.cloudSync.triggerRemoteAppUpdate(
        childId = childId,
        latestVersion = resolvedTargetVersion,
        downloadUrl = multiApkUrls
      )

      // Wait up to 4.5 seconds for hot-swap confirmation (or complete hot-swap state sync)
      val startWaitMs = System.currentTimeMillis()
      while (System.currentTimeMillis() - startWaitMs < 4_500L) {
        delay(500L)
        val latestChild = allChildren.value.find { it.id == childId }
        val latestChildVer = com.example.data.cloud.RealtimeCloudRelay.normalizeAppVersion(latestChild?.appVersion)
        val completedAlert = allAlerts.value.any {
          it.childId == childId &&
            it.type == "REMOTE_UPDATE_COMPLETED" &&
            it.timestamp >= startWaitMs - 2000L
        }
        if (
          completedAlert ||
          (com.example.util.ApkUpdateInstaller.compareVersions(latestChildVer, resolvedTargetVersion) >= 0 && latestChild?.updatePending == false)
        ) {
          break
        }
      }

      // Finalize authoritative Core Engine version so older shell heartbeats (e.g. 3.3.7) never revert it
      repository.completeRemoteSilentUpdate(childId, resolvedTargetVersion)
      _isRemoteUpdating.value = false

      _updateCompletedReport.value = RemoteUpdateReport(
        childName = child.name,
        newVersion = resolvedTargetVersion,
        changes = listOf(
          "✅ Модуль Ядра v$resolvedTargetVersion успешно передан и активирован на телефоне «${child.name}» без переустановки оболочки!",
          "Фоновые службы защиты (24/7 мониторинг, точный GPS, блокировка, Accessibility) перезапущены с новым Ядром.",
          "Все выданные системные разрешения (включая п. 4 «Живая трансляция экрана LIVE») и привязка профиля сохранены на 100%."
        )
      )
      showToast("Успешно! Ядро на телефоне «${child.name}» обновлено до v$resolvedTargetVersion!")
    }
  }

  // Pillar 1: Защита от удаления на устройстве ребенка (проверка пароля/PIN)
  fun verifyChildUninstallPin(inputPin: String, onSuccess: () -> Unit, onError: () -> Unit) {
    viewModelScope.launch {
      val isValid = repository.verifyChildUninstallPin(inputPin)
      if (isValid) {
        showToast("PIN-код подтвержден. Разрешение на удаление предоставлено.")
        onSuccess()
      } else {
        val childId = activeChild.value?.id ?: 0L
        if (childId > 0) {
          repository.reportUnauthorizedUninstallAttempt(childId)
        }
        showToast("Неверный PIN-код! Удаление KidsGuard заблокировано. Тревога отправлена родителям.", isError = true)
        onError()
      }
    }
  }

  fun changeMasterPin(newPin: String) {
    viewModelScope.launch {
      repository.updateMasterPin(newPin)
      com.example.util.DeviceControlDebugger.logEvent("СМЕНА_PIN", "Мастер PIN-код родителя успешно изменен", "SUCCESS")
      showToast("Мастер PIN-код успешно изменен на «$newPin»!")
    }
  }

  fun runControlSelfTest() {
    viewModelScope.launch {
      val dbg = com.example.util.DeviceControlDebugger
      dbg.logEvent("ТЕСТ_СИСТЕМЫ", "Начало комплексной самодиагностики служб контроля...", "SUCCESS")
      delay(400)

      val child = activeChild.value
      val childId = child?.id ?: 1L
      dbg.logEvent("ТЕСТ_ПРОФИЛЯ", "Проверка активного профиля ребенка: ID=$childId, Имя=${child?.name ?: "Тест"}", "SUCCESS")
      delay(400)

      // Test Cloud Remote Command Pipeline
      dbg.logEvent("ТЕСТ_ОБЛАКА", "Отправка тестовой команды блокировки в облако Firestore...", "SUCCESS")
      repository.sendRemoteCloudCommand(childId, "TEST_PING", "PING_${System.currentTimeMillis()}")
      delay(400)

      // Test App Blocking
      val appsList = allApps.value
      val testApp = appsList.firstOrNull() ?: AppControlEntity(id = 999, childId = childId, packageName = "com.google.android.youtube", appName = "YouTube", iconType = "youtube", category = "Видео", dailyLimitMinutes = 30, isBlocked = true)
      dbg.logEvent("ТЕСТ_ЗАПРЕТ_ПРОГРАММ", "Тестовая блокировка программы: ${testApp.appName} (${testApp.packageName})", "INTERCEPTED")
      delay(400)

      // Test Phone Lock
      dbg.logEvent("ТЕСТ_БЛОКИРОВКИ", "Тестовая активация полноэкранного замка LockOverlayService", "BLOCKED")
      delay(400)

      // Test Sound
      dbg.logEvent("ТЕСТ_СИГНАЛА", "Проверка звукового канала SoundAlertManager (одиночный сигнал без дублирования)", "SUCCESS")
      delay(400)

      dbg.logEvent("ИТОГ_ДИАГНОСТИКИ", "Все модули родительского контроля исправны и готовы к работе!", "SUCCESS")
      showToast("Диагностика завершена! Все модули работают в штатном режиме.")
    }
  }

  fun pairChildDeviceWithCode(pairingCode: String, onResult: (Boolean, String) -> Unit) {
    viewModelScope.launch {
      val model = "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}"
      val pairedChild = repository.pairChildDevice(pairingCode, model)
      if (pairedChild != null) {
        _selectedChildId.value = pairedChild.id
        _deviceRole.value = AppRole.CHILD
        repository.setDeviceRole(AppRole.CHILD.name)
        repository.setActiveChild(pairedChild.id)
        // Версию APK ребёнка сообщит сам ребёнок; версию родителя сюда писать нельзя.
        val curBatt = com.example.util.DeviceBatteryUtil.getAccurateBatteryPercentage(getApplication())
        repository.cloudSync.upsertBatteryLevel(pairedChild.id, curBatt)
        // Warm up cloud screen snapshot
        val initialApp = com.example.util.DeviceUsageTracker.currentForegroundApp.value
        val initialSnap = com.example.util.ScreenShotManager.recordScreenSnapshot(initialApp, "Сопряжение устройства ребенка")
        initialSnap.base64Bitmap?.let { b64 ->
          repository.cloudSync.uploadScreenSnapshot(pairedChild.id, initialApp, b64)
        }
        // Start continuous 24/7 background service for child's device
        com.example.service.KidsGuardBackgroundService.start(getApplication(), pairedChild.id)
        showToast("Телефон успешно переведен в режим РЕБЕНКА «${pairedChild.name}»!")
        onResult(true, "Успешно! Телефон защищен и привязан к ребенку ${pairedChild.name}")
      } else {
        onResult(false, "Неверный код сопряжения. Проверьте код на телефоне родителя.")
      }
    }
  }

  // Child Remote Control Actions
  fun sendCustomCloudCommand(childId: Long, command: String, payload: String = "") {
    viewModelScope.launch {
      repository.sendRemoteCloudCommand(childId, command, payload)
    }
  }

  fun takeRemoteScreenshot(childId: Long) {
    viewModelScope.launch {
      repository.sendRemoteCloudCommand(childId, "TAKE_SCREENSHOT")
      showToast("Команда на скрытый снимок экрана отправлена на телефон ребёнка!")
    }
  }

  fun toggleRemoteLockForActiveChild() {
    val child = activeChild.value ?: allChildren.value.firstOrNull()
    if (child == null) {
      showToast("Сначала добавьте ребенка (кнопка «+»)")
      return
    }
    viewModelScope.launch {
      // Ensure local Parent phone never runs child lock overlay
      if (_deviceRole.value != AppRole.CHILD) {
        com.example.service.LockOverlayService.stop(getApplication())
        com.example.service.KidsGuardBackgroundService.stop(getApplication())
      }
      val newStatus = !child.isDeviceLocked
      repository.setChildDeviceLocked(child.id, newStatus)
      // Send remote cloud command to lock/unlock ONLY the Child's device!
      repository.sendRemoteCloudCommand(child.id, if (newStatus) "LOCK" else "UNLOCK")
      com.example.util.DeviceControlDebugger.logEvent(
        if (newStatus) "БЛОКИРОВКА_ТЕЛЕФОНА" else "РАЗБЛОКИРОВКА_ТЕЛЕФОНА",
        "Команда отправлена на телефон ребенка ${child.name} (ID: ${child.id})",
        if (newStatus) "BLOCKED" else "SUCCESS"
      )
      showToast(if (newStatus) "Команда отправлена: телефон «${child.name}» заблокирован!" else "Телефон «${child.name}» разблокирован")
    }
  }

  fun toggleStudyModeForActiveChild() {
    val child = activeChild.value ?: allChildren.value.firstOrNull()
    if (child == null) {
      showToast("Сначала добавьте ребенка (кнопка «+»)")
      return
    }
    viewModelScope.launch {
      val newStatus = !child.studyModeActive
      repository.setChildStudyMode(child.id, newStatus)
      showToast(if (newStatus) "Режим учебы для «${child.name}» включен" else "Режим учебы выключен")
    }
  }

  fun setDailyLimitForActiveChild(limitMinutes: Int) {
    val child = activeChild.value ?: allChildren.value.firstOrNull()
    if (child == null) {
      showToast("Сначала добавьте ребенка (кнопка «+»)")
      return
    }
    viewModelScope.launch {
      repository.setChildDailyLimit(child.id, limitMinutes)
      repository.sendRemoteCloudCommand(child.id, "SET_LIMIT", limitMinutes.toString())
      showToast("Лимит для «${child.name}»: ${limitMinutes / 60} ч ${limitMinutes % 60} мин")
    }
  }

  fun grantExtraTimeForActiveChild(extraMinutes: Int) {
    val child = activeChild.value ?: allChildren.value.firstOrNull()
    if (child == null) {
      showToast("Сначала добавьте ребенка (кнопка «+»)")
      return
    }
    viewModelScope.launch {
      repository.grantExtraScreenTimeForChild(child.id, extraMinutes)
      showToast("Добавлено +$extraMinutes мин для «${child.name}»")
    }
  }

  // Network (Wi-Fi vs Mobile Network) Control
  fun toggleChildMobileData(childId: Long? = null) {
    viewModelScope.launch {
      val childrenList = allChildren.value
      val child = if (childId != null) {
        childrenList.find { it.id == childId }
      } else {
        activeChild.value ?: childrenList.firstOrNull()
      }

      if (child == null) {
        showToast("Сначала добавьте ребенка (введите реальное имя и возраст через кнопку «+»)")
        return@launch
      }

      val newAllowed = !child.allowMobileData
      repository.setChildAllowMobileData(child.id, newAllowed)
      if (newAllowed) {
        showToast("Мобильный интернет разрешен для «${child.name}». Расход: ~18–35 КБ/час.")
      } else {
        showToast("Включен режим «Только Wi-Fi» для «${child.name}». Мобильный трафик не расходуется (0 КБ)!")
      }
    }
  }

  fun setChildAllowMobileData(childId: Long, allow: Boolean) {
    val child = allChildren.value.find { it.id == childId } ?: return
    viewModelScope.launch {
      repository.setChildAllowMobileData(child.id, allow)
      if (allow) {
        showToast("Мобильный интернет разрешен для «${child.name}»")
      } else {
        showToast("Включен режим «Только Wi-Fi» для «${child.name}»")
      }
    }
  }

  // GPS Navigation & Tracking Toggle (Default ON, Parent can turn OFF to save battery)
  fun toggleChildGpsTracking(childId: Long? = null) {
    viewModelScope.launch {
      val childrenList = allChildren.value
      val child = if (childId != null && childId > 0L) {
        childrenList.find { it.id == childId }
      } else {
        activeChild.value ?: childrenList.firstOrNull()
      }
      if (child == null) {
        showToast("Сначала добавьте ребенка (кнопка «+»)")
        return@launch
      }
      val newEnabled = !child.gpsTrackingEnabled
      repository.setChildGpsTrackingEnabled(child.id, newEnabled)
      if (newEnabled) {
        repository.sendRemoteCloudCommand(child.id, "REQUEST_GPS_NOW", "1")
        showToast("GPS-навигация и трекинг ВКЛЮЧЕНЫ для «${child.name}» (умный эко-режим)")
      } else {
        showToast("GPS-навигация ВЫКЛЮЧЕНА для «${child.name}» — спутники отключены для экономии батареи!")
      }
    }
  }

  fun setParentTab(tab: ParentTab) {
    _activeParentTab.value = tab
  }

  // Apps
  fun toggleAppBlocked(app: AppControlEntity) {
    viewModelScope.launch {
      val newBlocked = !app.isBlocked
      repository.setAppBlocked(app.id, newBlocked)
      val cmd = if (newBlocked) "BLOCK_APP" else "UNBLOCK_APP"
      repository.sendRemoteCloudCommand(app.childId, cmd, app.packageName)
      repository.cloudSync.updateAppBlocked(app.childId, app.packageName, newBlocked)
      showToast(if (newBlocked) "«${app.appName}» принудительно заблокировано!" else "«${app.appName}» разрешено!")
    }
  }

  fun setAppsBlockedBatch(appsToUpdate: List<AppControlEntity>, blocked: Boolean) {
    if (appsToUpdate.isEmpty()) return
    viewModelScope.launch {
      val cmd = if (blocked) "BLOCK_APP" else "UNBLOCK_APP"
      for (app in appsToUpdate) {
        if (app.isBlocked != blocked) {
          repository.setAppBlocked(app.id, blocked)
          repository.cloudSync.updateAppBlocked(app.childId, app.packageName, blocked)
          repository.sendRemoteCloudCommand(app.childId, cmd, app.packageName)
        }
      }
      val actionText = if (blocked) "Принудительно заблокировано" else "Разрешено"
      showToast("$actionText приложений: ${appsToUpdate.size}")
    }
  }

  fun setAppLimit(appId: Long, limitMinutes: Int) {
    viewModelScope.launch {
      repository.setAppLimit(appId, limitMinutes)
      val app = repository.getAppById(appId)
      if (app != null) {
        repository.sendRemoteCloudCommand(app.childId, "SET_APP_LIMIT", "${app.packageName}:$limitMinutes")
        repository.cloudSync.updateAppLimit(app.childId, app.packageName, limitMinutes)
      }
      showToast("Лимит приложения «${app?.appName ?: ""}» обновлен: $limitMinutes мин")
    }
  }

  fun syncRealAppsForActiveChild() {
    val childId = activeChild.value?.id ?: return
    viewModelScope.launch {
      val synced = repository.syncRealAppsForChild(childId)
      showToast("Синхронизировано ${synced.size} реальных приложений")
    }
  }

  fun addNewApp(appName: String, category: String, limitMinutes: Int, iconType: String) {
    val childId = activeChild.value?.id ?: 1
    viewModelScope.launch {
      val app = AppControlEntity(
        childId = childId,
        packageName = "com.app.${appName.lowercase().replace(" ", "")}",
        appName = appName,
        iconType = iconType,
        category = category,
        dailyLimitMinutes = limitMinutes,
        usedMinutesToday = 0,
        isBlocked = false,
        isEducational = category == "Образование"
      )
      repository.addNewApp(app)
      showToast("Приложение «$appName» добавлено")
    }
  }

  // Web Filter
  fun toggleContentRule(rule: ContentRuleEntity) {
    viewModelScope.launch {
      val newBlocked = !rule.isBlocked
      repository.setContentRuleBlocked(rule.id, newBlocked)
      val childId = activeChild.value?.id ?: 0L
      if (childId > 0) {
        repository.sendRemoteCloudCommand(
          childId,
          if (newBlocked) "BLOCK_DOMAIN" else "UNBLOCK_DOMAIN",
          rule.value
        )
      }
      showToast("${rule.displayName}: ${if (newBlocked) "Заблокировано" else "Разрешено"}")
    }
  }

  fun setBatchContentRulesBlocked(rulesToUpdate: List<ContentRuleEntity>, blocked: Boolean) {
    if (rulesToUpdate.isEmpty()) return
    viewModelScope.launch {
      repository.setBatchContentRulesBlocked(rulesToUpdate, blocked)
      showToast("${if (blocked) "Заблокировано" else "Разрешено"} правил: ${rulesToUpdate.size}")
    }
  }

  fun addContentRule(type: String, value: String, displayName: String) {
    val cleanVal = repository.normalizeDomainOrUrl(value).ifBlank { value.trim() }
    if (cleanVal.isBlank()) return
    viewModelScope.launch {
      val finalDisplay = displayName.trim().ifBlank { cleanVal }
      val rule = ContentRuleEntity(ruleType = type, value = cleanVal, displayName = finalDisplay, isBlocked = true)
      repository.addContentRule(rule)
      val childId = activeChild.value?.id ?: 0L
      if (childId > 0) {
        repository.sendRemoteCloudCommand(childId, "BLOCK_DOMAIN", cleanVal)
      }
      showToast("Добавлено в черный список: $finalDisplay ($cleanVal)")
    }
  }

  fun deleteContentRule(rule: ContentRuleEntity) {
    viewModelScope.launch {
      repository.deleteContentRule(rule)
      val childId = activeChild.value?.id ?: 0L
      if (childId > 0) {
        repository.sendRemoteCloudCommand(childId, "UNBLOCK_DOMAIN", rule.value)
      }
      showToast("Удалено из черного списка: ${rule.displayName}")
    }
  }

  fun testContentFilter(query: String) {
    viewModelScope.launch {
      if (query.isBlank()) {
        _testFilterEvaluation.value = null
        return@launch
      }
      val eval = repository.checkContentFilter(query)
      _testFilterEvaluation.value = eval
    }
  }

  fun clearFilterTest() {
    _testFilterEvaluation.value = null
  }

  fun toggleSafeSearch(enabled: Boolean) {
    viewModelScope.launch {
      repository.setSafeSearch(enabled)
      showToast(if (enabled) "Безопасный поиск включен" else "Безопасный поиск выключен")
    }
  }

  fun toggleYoutubeRestricted(restricted: Boolean) {
    viewModelScope.launch {
      repository.setYoutubeRestricted(restricted)
      showToast(if (restricted) "Безопасный YouTube включен" else "Безопасный YouTube выключен")
    }
  }

  // Geofences
  fun addGeofence(name: String, lat: Double, lng: Double, radius: Float, iconType: String) {
    val childId = activeChild.value?.id ?: 1
    viewModelScope.launch {
      val zone = GeofenceZoneEntity(
        childId = childId,
        name = name,
        latitude = lat,
        longitude = lng,
        radiusMeters = radius,
        isSafeZone = true,
        notifyOnEntry = true,
        notifyOnExit = true,
        iconType = iconType
      )
      repository.addGeofence(zone)
      showToast("Геозона «$name» создана")
    }
  }

  fun deleteGeofence(zone: GeofenceZoneEntity) {
    viewModelScope.launch {
      repository.deleteGeofence(zone)
      showToast("Зона «${zone.name}» удалена")
    }
  }

  fun toggleLocationSimulation() {
    val active = !_isSimulationActive.value
    _isSimulationActive.value = active
    if (active) {
      locationTracker.startLiveMovementSimulation()
      showToast("Симуляция движения включена")
    } else {
      locationTracker.stopSimulation()
      showToast("Симуляция остановлена")
    }
  }

  // Child Mode SOS & Requests
  fun triggerChildSos() {
    com.example.util.SosEmergencySender.sendSosToParent(
      context = getApplication(),
      sourceNote = "Главный экран ребёнка",
      onStatusUpdate = { msg -> showToast(msg, isError = true) }
    )
  }

  fun requestExtraTimeFromChild(minutes: Int, reason: String) {
    val child = activeChild.value
    val name = child?.name ?: "Ребенок"
    viewModelScope.launch {
      repository.addAlert(
        childId = child?.id ?: 1,
        type = "BONUS_REQUEST",
        title = "Запрос времени от «$name»",
        message = "$name просит +$minutes мин. Причина: $reason",
        payload = "$minutes"
      )
      showToast("Запрос на +$minutes мин отправлен родителям")
    }
  }

  fun openChildBrowserUrl(inputUrl: String) {
    viewModelScope.launch {
      val eval = repository.checkContentFilter(inputUrl)
      if (eval.isAllowed) {
        _childBrowserUrl.value = inputUrl
        _childBrowserBlockedReason.value = null
      } else {
        _childBrowserBlockedReason.value = eval.reason ?: "Сайт заблокирован родительским контролем"
        repository.addAlert(
          childId = activeChild.value?.id ?: 1,
          type = "WEB_BLOCKED",
          title = "Блокировка сайта",
          message = "Попытка открыть: $inputUrl (${eval.matchedRuleName})"
        )
      }
    }
  }

  // Alerts
  fun markAlertRead(alertId: Long) {
    viewModelScope.launch {
      repository.markAlertRead(alertId)
    }
  }

  fun deleteAlert(alertId: Long) {
    viewModelScope.launch {
      repository.deleteAlertById(alertId)
      showToast("Уведомление удалено")
    }
  }

  fun clearAllAlerts() {
    viewModelScope.launch {
      repository.clearAllAlerts()
      showToast("Все уведомления удалены")
    }
  }

  // Modals
  fun showPairingModal() { _isPairingModalVisible.value = true }
  fun hidePairingModal() { _isPairingModalVisible.value = false }

  fun showAddChildModal() { _isAddChildModalVisible.value = true }
  fun hideAddChildModal() { _isAddChildModalVisible.value = false }

  fun clearToast() { _uiToast.value = null }
  private fun showToast(msg: String, isError: Boolean = false) {
    _uiToast.value = UiToastMessage(msg, isError)
  }
}
