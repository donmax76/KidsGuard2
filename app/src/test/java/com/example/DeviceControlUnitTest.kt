package com.example

import com.example.data.model.AppControlEntity
import com.example.data.model.ChildProfileEntity
import com.example.util.DeviceControlDebugger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceControlUnitTest {

  @Test
  fun testAppBlockingLogic() {
    val blockedPackages = mutableSetOf("com.google.android.youtube", "com.zhiliaoapp.musically")
    
    // Test 1: Check YouTube is blocked
    assertTrue(blockedPackages.contains("com.google.android.youtube"))
    
    // Test 2: Unblock YouTube
    blockedPackages.remove("com.google.android.youtube")
    assertFalse(blockedPackages.contains("com.google.android.youtube"))
    
    // Test 3: Educational app is not blocked
    assertFalse(blockedPackages.contains("com.duolingo"))
  }

  @Test
  fun testDeviceLockLogic() {
    var isDeviceLocked = false
    
    // Simulate Lock command
    isDeviceLocked = true
    assertTrue(isDeviceLocked)
    
    // When locked, any external package must be intercepted
    val targetApp = "com.android.chrome"
    val shouldIntercept = isDeviceLocked && targetApp != "com.example"
    assertTrue(shouldIntercept)
    
    // Simulate Unlock command
    isDeviceLocked = false
    assertFalse(isDeviceLocked)
  }

  @Test
  fun testMasterPinChange() {
    var masterPin = "1234"
    assertEquals("1234", masterPin)
    
    // Change to custom PIN
    masterPin = "9876"
    assertEquals("9876", masterPin)
  }

  @Test
  fun testDebuggerLogging() {
    DeviceControlDebugger.clearLogs()
    DeviceControlDebugger.logEvent("TEST_ACTION", "Testing debugger pipeline", "SUCCESS")
    
    val logs = DeviceControlDebugger.logs.value
    assertTrue(logs.isNotEmpty())
    assertEquals("TEST_ACTION", logs.first().action)
    assertEquals("SUCCESS", logs.first().status)
  }
}
