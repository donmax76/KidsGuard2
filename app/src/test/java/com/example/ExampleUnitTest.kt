package com.example

import com.example.data.repository.ParentalControlRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

class ExampleUnitTest {
  @Test
  fun addition_isCorrect() {
    assertEquals(4, 2 + 2)
  }

  @Test
  fun geofence_distanceCalculation_isAccurate() {
    // Red Square (55.7539, 37.6208) to GUM nearby (55.7547, 37.6215) ~ 100 meters
    val lat1 = 55.7539
    val lon1 = 37.6208
    val lat2 = 55.7547
    val lon2 = 37.6215

    val earthRadius = 6371000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
        sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    val distance = earthRadius * c

    assertTrue(distance in 80.0..120.0)
  }

  @Test
  fun contentFilter_heuristics_detectBadKeywords() {
    val adultKeywords = listOf("porn", "xxx", "sex", "порно", "секс", "18+")
    val gamblingKeywords = listOf("casino", "казино", "ставки", "1xbet")

    val testSafeQuery = "реферат по истории москвы".lowercase()
    val testAdultQuery = "смотреть онлайн порно 18+".lowercase()
    val testGamblingQuery = "зеркало казино вулкан играть".lowercase()

    assertFalse(adultKeywords.any { testSafeQuery.contains(it) })
    assertFalse(gamblingKeywords.any { testSafeQuery.contains(it) })

    assertTrue(adultKeywords.any { testAdultQuery.contains(it) })
    assertTrue(gamblingKeywords.any { testGamblingQuery.contains(it) })
  }

  @Test
  fun screenTime_remainingCalculation_isCorrect() {
    val dailyLimit = 150
    val usedMinutes = 90
    val remaining = (dailyLimit - usedMinutes).coerceAtLeast(0)
    assertEquals(60, remaining)

    val overUsedMinutes = 180
    val remainingOver = (dailyLimit - overUsedMinutes).coerceAtLeast(0)
    assertEquals(0, remainingOver)
  }
}
