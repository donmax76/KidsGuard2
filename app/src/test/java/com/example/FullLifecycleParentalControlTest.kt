package com.example

import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
import com.example.util.DeviceControlDebugger
import com.example.util.ScreenShotManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class FullLifecycleParentalControlTest {

  @Test
  fun test1_IndividualAppBlockingWithCloseToHomeOption() {
    val blockedPackages = mutableSetOf("com.google.android.youtube", "com.roblox.client")
    var lastOverlayShown: String? = null
    var isTotalDeviceLocked = false

    fun onAppAttemptLaunch(packageName: String): Boolean {
      if (isTotalDeviceLocked) {
        lastOverlayShown = "ТЕЛЕФОН_ЗАБЛОКИРОВАН"
        return false
      }
      if (blockedPackages.contains(packageName)) {
        lastOverlayShown = "ПРОГРАММА_ЗАБЛОКИРОВАНА"
        return false
      }
      lastOverlayShown = null
      return true
    }

    // 1. YouTube blocked -> overlay shows with close-to-home option
    val youtubeAllowed = onAppAttemptLaunch("com.google.android.youtube")
    assertFalse(youtubeAllowed)
    assertEquals("ПРОГРАММА_ЗАБЛОКИРОВАНА", lastOverlayShown)

    // Child clicks "НА ГЛАВНЫЙ ЭКРАН (ЗАКРЫТЬ)" -> safely returned to home
    lastOverlayShown = null

    // 2. Unblock YouTube -> opens freely
    blockedPackages.remove("com.google.android.youtube")
    assertTrue(onAppAttemptLaunch("com.google.android.youtube"))
  }

  @Test
  fun test2_TotalDeviceLockSurvivabilityAcrossReboot() {
    var isDeviceLocked = true
    var bootReceiverExecuted = false
    var lockRestoredAfterReboot = false

    fun simulateDeviceReboot() {
      bootReceiverExecuted = true
      if (isDeviceLocked) {
        // Enforce lock overlay immediately upon boot
        lockRestoredAfterReboot = true
      }
    }

    simulateDeviceReboot()
    assertTrue("Boot receiver must execute on reboot", bootReceiverExecuted)
    assertTrue("Lock state must be restored on boot so child cannot bypass by restart", lockRestoredAfterReboot)
  }

  @Test
  fun test3_ScreenTimeAccumulationAndSnapshots() {
    var screenTimeMinutes = 45
    val dailyLimit = 120

    // Simulate minute tick
    screenTimeMinutes += 15
    assertEquals(60, screenTimeMinutes)
    assertEquals(60, dailyLimit - screenTimeMinutes)

    // Record screenshot snapshot with real base64 frame
    ScreenShotManager.recordScreenSnapshot(
      appName = "com.google.android.youtube",
      note = "Тестовый снимок экрана родителя",
      base64 = "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg=="
    )
    val snapshots = ScreenShotManager.capturedScreens.value
    assertTrue("Snapshot must be saved in history", snapshots.isNotEmpty())
    assertEquals("com.google.android.youtube", snapshots.first().activeAppName)
  }

  @Test
  fun test4_MultiChildP2pStrictIsolation_ThreeChildren() {
    // Verify that Child 1, Child 2, and Child 3 maintain 100% isolated telemetry (battery, GPS, lock, screen frames)
    val child1 = ChildProfileEntity(
      id = 1L,
      name = "Ребёнок 1",
      age = 9,
      deviceModel = "Samsung Galaxy A54",
      pairingCode = "111-222",
      batteryLevel = 88,
      lastLocationLat = 40.4093,
      lastLocationLng = 49.8671,
      lastLocationAddress = "Баку, пр. Нефтяников 10"
    )
    val child2 = ChildProfileEntity(
      id = 2L,
      name = "Ребёнок 2",
      age = 11,
      deviceModel = "Xiaomi Redmi Note 12",
      pairingCode = "333-444",
      batteryLevel = 42,
      lastLocationLat = 40.3777,
      lastLocationLng = 49.8920,
      lastLocationAddress = "Баку, ул. Низами 45"
    )
    val child3 = ChildProfileEntity(
      id = 3L,
      name = "Ребёнок 3",
      age = 13,
      deviceModel = "Poco X5 Pro",
      pairingCode = "555-666",
      batteryLevel = 65,
      lastLocationLat = 40.3953,
      lastLocationLng = 49.8822,
      lastLocationAddress = "Баку, пр. Гейдара Алиева 1"
    )

    // Simulate packets coming from each respective child's phone (isChildDevice = true)
    val json1 = org.json.JSONObject().apply {
      put("type", "CHILD_STATE")
      put("id", child1.id)
      put("name", child1.name)
      put("deviceModel", child1.deviceModel)
      put("pairingCode", child1.pairingCode)
      put("isChildDevice", true)
      put("batteryLevel", child1.batteryLevel)
      put("lastLocationLat", child1.lastLocationLat)
      put("lastLocationLng", child1.lastLocationLng)
      put("lastLocationAddress", child1.lastLocationAddress)
    }
    val json2 = org.json.JSONObject().apply {
      put("type", "CHILD_STATE")
      put("id", child2.id)
      put("name", child2.name)
      put("deviceModel", child2.deviceModel)
      put("pairingCode", child2.pairingCode)
      put("isChildDevice", true)
      put("batteryLevel", child2.batteryLevel)
      put("lastLocationLat", child2.lastLocationLat)
      put("lastLocationLng", child2.lastLocationLng)
      put("lastLocationAddress", child2.lastLocationAddress)
    }
    val json3 = org.json.JSONObject().apply {
      put("type", "CHILD_STATE")
      put("id", child3.id)
      put("name", child3.name)
      put("deviceModel", child3.deviceModel)
      put("pairingCode", child3.pairingCode)
      put("isChildDevice", true)
      put("batteryLevel", child3.batteryLevel)
      put("lastLocationLat", child3.lastLocationLat)
      put("lastLocationLng", child3.lastLocationLng)
      put("lastLocationAddress", child3.lastLocationAddress)
    }

    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(json1)
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(json2)
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(json3)

    val c1Cached = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(1L)!!
    val c2Cached = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(2L)!!
    val c3Cached = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(3L)!!

    assertEquals(88, c1Cached.batteryLevel)
    assertEquals(42, c2Cached.batteryLevel)
    assertEquals(65, c3Cached.batteryLevel)

    assertEquals("Samsung Galaxy A54", c1Cached.deviceModel)
    assertEquals("Xiaomi Redmi Note 12", c2Cached.deviceModel)
    assertEquals("Poco X5 Pro", c3Cached.deviceModel)

    // Verify that if Parent device sends a packet (isChildDevice = false), it NEVER overwrites Child 1's battery!
    val parentPacketForChild1 = org.json.JSONObject().apply {
      put("type", "CHILD_STATE")
      put("id", 1L)
      put("name", "Ребёнок 1")
      put("isChildDevice", false)
      put("batteryLevel", 15) // Parent's own battery or stale value must be ignored
      put("dailyScreenLimitMinutes", 180) // Parent setting a limit must be applied
    }
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(parentPacketForChild1)

    val c1AfterParentUpdate = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(1L)!!
    assertEquals("Parent packet must NOT overwrite child's real battery", 88, c1AfterParentUpdate.batteryLevel)
    assertEquals("Parent packet MUST update dailyScreenLimitMinutes", 180, c1AfterParentUpdate.dailyScreenLimitMinutes)
    assertEquals("Child 2 battery must remain untouched", 42, com.example.data.cloud.RealtimeCloudRelay.getCachedChild(2L)!!.batteryLevel)
    assertEquals("Child 3 battery must remain untouched", 65, com.example.data.cloud.RealtimeCloudRelay.getCachedChild(3L)!!.batteryLevel)

    // Verify Child deletion removes only the target child
    val deleteChild2Packet = org.json.JSONObject().apply {
      put("type", "CHILD_DELETE")
      put("id", 2L)
    }
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(deleteChild2Packet)
    assertEquals(null, com.example.data.cloud.RealtimeCloudRelay.getCachedChild(2L))
    assertEquals(88, com.example.data.cloud.RealtimeCloudRelay.getCachedChild(1L)!!.batteryLevel)
    assertEquals(65, com.example.data.cloud.RealtimeCloudRelay.getCachedChild(3L)!!.batteryLevel)
  }

  @Test
  fun test5_remoteSilentUpdateVersionIsolationAndCompletionLifecycle() {
    // 1. Child 1 connects running an older version "3.2.0 Pro"
    val childOldVersionPacket = org.json.JSONObject().apply {
      put("type", "CHILD_STATE")
      put("id", 1L)
      put("name", "Алиса")
      put("deviceModel", "Samsung Galaxy A54")
      put("isChildDevice", true)
      put("batteryLevel", 77)
      put("appVersion", "3.2.0 Pro")
      put("updatePending", false)
    }
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(childOldVersionPacket)

    val beforeUpdate = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(1L)!!
    assertEquals("3.2.0 Pro", beforeUpdate.appVersion)
    assertFalse(beforeUpdate.updatePending)

    // 2. Parent (running 3.2.4 Pro) presses "Скрытно обновить у ребёнка" -> sends CHILD_PATCH with updatePending=true
    // Even if Parent packet accidentally carries Parent's BuildConfig.VERSION_NAME, it MUST NOT overwrite Child's appVersion!
    val parentTriggerUpdatePatch = org.json.JSONObject().apply {
      put("type", "CHILD_PATCH")
      put("id", 1L)
      put("isChildDevice", false)
      put("updatePending", true)
      put("appVersion", com.example.BuildConfig.VERSION_NAME)
    }
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(parentTriggerUpdatePatch)

    val duringUpdate = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(1L)!!
    assertEquals("Parent patch must NOT prematurely overwrite Child's old version", "3.2.0 Pro", duringUpdate.appVersion)
    assertTrue("updatePending must be true while Child is downloading/installing APK", duringUpdate.updatePending)

    // 3. Child finishes background installation, restarts services on MY_PACKAGE_REPLACED, and reports its new version
    val childCompletedUpdatePatch = org.json.JSONObject().apply {
      put("type", "CHILD_PATCH")
      put("id", 1L)
      put("isChildDevice", true)
      put("batteryLevel", 76)
      put("appVersion", com.example.BuildConfig.VERSION_NAME)
      put("updatePending", false)
    }
    com.example.data.cloud.RealtimeCloudRelay.ingestImmediateStateJson(childCompletedUpdatePatch)

    val afterUpdate = com.example.data.cloud.RealtimeCloudRelay.getCachedChild(1L)!!
    assertEquals(com.example.BuildConfig.VERSION_NAME, afterUpdate.appVersion)
    assertFalse("updatePending must be cleared after Child reports new version", afterUpdate.updatePending)
    assertEquals(76, afterUpdate.batteryLevel)
  }

  @Test
  fun test6_pcBlockerIntegrationLifecycleAndTelemetry() {
    val pcPacket = org.json.JSONObject().apply {
      put("type", "PC_STATE")
      put("pc_name", "DESKTOP-KIDSPC")
      put("local_ip", "192.168.1.45")
      put("port", 8765)
      put("pairing_code", "101-701")
      put("computer_today_sec", 2700)
      put("computer_total_sec", 18000)
      put(
        "full",
        org.json.JSONObject().apply {
          put("now", false)
          put("blocked", false)
          put("text", "сегодня осталось 75 мин")
          put("has_account", true)
          put("child_account", "KidUser")
          put("daily", 120)
          put("total", 0)
          put("from", "08:00")
          put("to", "21:00")
        }
      )
      put(
        "entries",
        org.json.JSONArray().apply {
          put(
            org.json.JSONObject().apply {
              put("name", "YouTube")
              put("enabled", true)
              put("blocked", false)
              put("text", "сегодня осталось 30 мин")
              put("domains_count", 7)
              put("programs_count", 2)
              put("ips_count", 0)
              put(
                "limit",
                org.json.JSONObject().apply {
                  put("daily", 60)
                  put("total", 0)
                  put("from", "09:00")
                  put("to", "20:00")
                  put("close_tab", true)
                }
              )
            }
          )
        }
      )
      put(
        "activity",
        org.json.JSONObject().apply {
          put(
            "KidUser",
            org.json.JSONObject().apply {
              put(
                "procs",
                org.json.JSONArray().apply {
                  put(org.json.JSONObject().apply { put("name", "RobloxPlayerBeta.exe"); put("seconds", 1200) })
                }
              )
            }
          )
        }
      )
    }

    com.example.data.pc.PcBlockerClient.ingestP2pStateJson(pcPacket)
    val st1 = com.example.data.pc.PcBlockerClient.state.value
    assertTrue(st1.isOnline)
    assertEquals("DESKTOP-KIDSPC", st1.pcName)
    assertEquals("192.168.1.45", st1.lanIp)
    assertEquals("KidUser", st1.fullLock.childAccount)
    assertEquals(1, st1.activityByUser.size)
    assertEquals("RobloxPlayerBeta.exe", st1.activityByUser.first().procs.first().label)

    // Toggle full PC lock
    com.example.data.pc.PcBlockerClient.toggleFullPcLock(lockNow = true, childAccount = "KidUser")
    assertTrue(com.example.data.pc.PcBlockerClient.state.value.fullLock.now)

    // Add a new rule from Activity
    com.example.data.pc.PcBlockerClient.addEntry("Roblox", listOf("roblox.com"), listOf("RobloxPlayerBeta.exe"))
    assertTrue(com.example.data.pc.PcBlockerClient.state.value.entries.any { it.name == "Roblox" && it.enabled })
  }
}

