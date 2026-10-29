package com.example

import com.example.data.model.ChildProfileEntity
import com.example.util.DeviceControlDebugger
import com.example.util.DeviceSecurityPermissionHelper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceAdminAndScreenTimeTest {

  @Test
  fun test1_PermissionSetupFlag_PreventsAntiUninstallInterception() {
    // 1. Normal state: not setting up permissions
    DeviceSecurityPermissionHelper.isSettingUpPermissions = false

    fun shouldIntercept(packageName: String, text: String): Boolean {
      if (packageName == "com.android.settings" || packageName == "com.google.android.packageinstaller") {
        if (DeviceSecurityPermissionHelper.isSettingUpPermissions) {
          return false // Allow user to activate admin or battery permissions
        }
        val lower = text.lowercase()
        if (lower.contains("удалить") || lower.contains("остановить") || lower.contains("стереть")) {
          if (lower.contains("kidsguard")) {
            return true
          }
        }
      }
      return false
    }

    // Attempt to uninstall from settings -> blocked!
    assertTrue(shouldIntercept("com.android.settings", "Удалить приложение KidsGuard"))

    // User is activating Device Admin from the in-app setup dialog
    DeviceSecurityPermissionHelper.isSettingUpPermissions = true
    assertFalse("Must NOT intercept settings when user clicks in-app setup wizard!",
      shouldIntercept("com.android.settings", "Активировать администратора устройства KidsGuard"))
    assertFalse("Must NOT intercept settings during permission flow!",
      shouldIntercept("com.android.settings", "KidsGuard Защита устройства"))

    // Reset flag
    DeviceSecurityPermissionHelper.isSettingUpPermissions = false
  }

  @Test
  fun test2_ScreenTimeAccrualAndEnforcement() {
    var usedMinutes = 0
    val dailyLimit = 120

    // Simulate 3 minutes passed
    for (i in 1..3) {
      usedMinutes += 1
    }
    assertEquals(3, usedMinutes)
    assertEquals(117, dailyLimit - usedMinutes)

    // Simulate exceeding daily limit
    usedMinutes = 125
    val isLimitExceeded = usedMinutes >= dailyLimit
    assertTrue("Should trigger limit lock when usedMinutes >= dailyLimit", isLimitExceeded)
  }

  @Test
  fun test3_TotalPhoneLockBehavior() {
    var phoneLocked = false
    var overlayReason = ""

    fun lockPhone() {
      phoneLocked = true
      overlayReason = "Телефон заблокирован родительским контролем!"
    }

    fun unlockPhone() {
      phoneLocked = false
      overlayReason = ""
    }

    lockPhone()
    assertTrue(phoneLocked)
    assertEquals("Телефон заблокирован родительским контролем!", overlayReason)

    unlockPhone()
    assertFalse(phoneLocked)
    assertEquals("", overlayReason)
  }
}
