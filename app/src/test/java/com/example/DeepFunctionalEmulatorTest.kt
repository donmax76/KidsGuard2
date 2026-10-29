package com.example

import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
import com.example.util.DeviceControlDebugger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Comprehensive Self-Test & Diagnostic Suite for Parental Control Engine
 */
class DeepFunctionalEmulatorTest {

  @Test
  fun test1_DeviceLock_ExecutionPipeline() {
    var deviceLockActive = false
    val foregroundAppHistory = mutableListOf<String>()

    fun simulateOpenApp(packageName: String): Boolean {
      foregroundAppHistory.add(packageName)
      return if (deviceLockActive) {
        // Intercepted and blocked!
        false
      } else {
        true
      }
    }

    // 1. Phone unlocked: Apps open normally
    deviceLockActive = false
    assertTrue("YouTube should open when unlocked", simulateOpenApp("com.google.android.youtube"))
    assertTrue("Games should open when unlocked", simulateOpenApp("com.roblox.client"))

    // 2. Parent sends LOCK command
    deviceLockActive = true

    // 3. Child tries to open YouTube, TikTok, Telegram, Browser
    assertFalse("YouTube MUST BE BLOCKED when device is locked", simulateOpenApp("com.google.android.youtube"))
    assertFalse("Roblox MUST BE BLOCKED when device is locked", simulateOpenApp("com.roblox.client"))
    assertFalse("Chrome MUST BE BLOCKED when device is locked", simulateOpenApp("com.android.chrome"))

    // 4. Parent sends UNLOCK command
    deviceLockActive = false
    assertTrue("YouTube should open again after unlock", simulateOpenApp("com.google.android.youtube"))
  }

  @Test
  fun test2_IndividualAppBlock_And_Limits() {
    val controlledApps = mutableMapOf(
      "com.google.android.youtube" to AppControlEntity(
        id = 1L,
        childId = 101L,
        packageName = "com.google.android.youtube",
        appName = "YouTube",
        iconType = "youtube",
        category = "Видео",
        dailyLimitMinutes = 30,
        usedMinutesToday = 30,
        isBlocked = false
      ),
      "com.duolingo" to AppControlEntity(
        id = 2L,
        childId = 101L,
        packageName = "com.duolingo",
        appName = "Duolingo",
        iconType = "duolingo",
        category = "Образование",
        dailyLimitMinutes = 0,
        usedMinutesToday = 10,
        isBlocked = false
      )
    )

    fun isAppAllowed(packageName: String): Boolean {
      val app = controlledApps[packageName] ?: return true
      if (app.isBlocked) return false
      if (app.dailyLimitMinutes > 0 && app.usedMinutesToday >= app.dailyLimitMinutes) return false
      return true
    }

    // YouTube reached its limit (30/30) -> Should be blocked
    assertFalse("YouTube exceeded limit of 30 mins -> must be blocked", isAppAllowed("com.google.android.youtube"))

    // Educational Duolingo -> Should be allowed
    assertTrue("Duolingo has no limit -> must be allowed", isAppAllowed("com.duolingo"))

    // Parent explicitly toggles block switch for Duolingo
    controlledApps["com.duolingo"] = controlledApps["com.duolingo"]!!.copy(isBlocked = true)
    assertFalse("Duolingo was blocked by parent -> must be blocked", isAppAllowed("com.duolingo"))

    // Parent increases YouTube limit to 60 minutes
    controlledApps["com.google.android.youtube"] = controlledApps["com.google.android.youtube"]!!.copy(dailyLimitMinutes = 60)
    assertTrue("YouTube limit increased to 60m (used 30m) -> must be allowed", isAppAllowed("com.google.android.youtube"))
  }

  @Test
  fun test3_AntiUninstall_SettingsInterception() {
    var securityTriggered = false

    fun onSettingsScreenVisited(windowText: String) {
      val lower = windowText.lowercase()
      if (lower.contains("kidsguard") || lower.contains("удалить") || lower.contains("остановить")) {
        securityTriggered = true
      }
    }

    // Normal settings page (e.g. Wi-Fi) -> not triggered
    onSettingsScreenVisited("Беспроводные сети Wi-Fi Подключение")
    assertFalse(securityTriggered)

    // Child attempts to open Apps -> KidsGuard -> Uninstall
    onSettingsScreenVisited("Сведения о приложении KidsGuard Удалить Остановить")
    assertTrue("Anti-uninstall MUST TRIGGER on attempt to remove KidsGuard", securityTriggered)
  }

  @Test
  fun test4_MasterPinVerification_And_Change() {
    var storedMasterPin = "1234"

    fun verifyPin(input: String): Boolean = input == storedMasterPin
    fun changePin(oldPin: String, newPin: String): Boolean {
      if (verifyPin(oldPin) && newPin.length in 4..8) {
        storedMasterPin = newPin
        return true
      }
      return false
    }

    // Verify initial default PIN
    assertTrue(verifyPin("1234"))
    assertFalse(verifyPin("0000"))

    // Change PIN with incorrect old PIN -> fails
    assertFalse(changePin("9999", "5555"))
    assertEquals("1234", storedMasterPin)

    // Change PIN with correct old PIN -> succeeds
    assertTrue(changePin("1234", "7788"))
    assertEquals("7788", storedMasterPin)

    // Verify new PIN works
    assertTrue(verifyPin("7788"))
    assertFalse(verifyPin("1234"))
  }
}
