package com.example.data.cloud

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * Оценка расхода энергии приложения на телефоне ребёнка, которую присылает сам ребёнок
 * (см. [com.example.util.AppEnergyMeter]). Хранится только в памяти родителя — это
 * лёгкая телеметрия, отдельно от истории в Room.
 */
object ChildPowerStats {
  data class Snapshot(
    val appPercent: Int = -1,
    val mahPerHour: Float = 0f,
    val percentPerHour: Float = 0f,
    val updatedAtMs: Long = 0L
  )

  private val _byChild = MutableStateFlow<Map<Long, Snapshot>>(emptyMap())
  val byChild: StateFlow<Map<Long, Snapshot>> = _byChild.asStateFlow()

  fun ingest(msg: JSONObject) {
    if (DirectP2pSocketEngine.currentRole == "CHILD") return
    val id = msg.optLong("childId", msg.optLong("id", 0L))
    if (id <= 0L) return
    val rawPercent = msg.optInt("appEnergyPercent", -1)
    if (rawPercent !in -1..100) return
    val rawMahPerHour = msg.optDouble("appMahPerHour", 0.0).toFloat()
    val rawPctPerHour = msg.optDouble("percentPerHour", 0.0).toFloat()
    // Защита от старой сборки 58 на телефоне ребёнка, где мА·мс делились на 3 600 вместо 3 600 000:
    val cleanPercent = if (rawPercent > 12) (rawPercent / 25).coerceIn(1, 4) else rawPercent
    val cleanMahPerHour = if (rawMahPerHour > 180f) (rawMahPerHour / 1000f).coerceIn(0.5f, 45f) else rawMahPerHour
    val cleanPctPerHour = if (rawPctPerHour > 5f) (rawPctPerHour / 100f).coerceIn(0.1f, 1.5f) else rawPctPerHour
    val snapshot = Snapshot(
      appPercent = cleanPercent,
      mahPerHour = cleanMahPerHour,
      percentPerHour = cleanPctPerHour,
      updatedAtMs = System.currentTimeMillis()
    )
    val canonical = msg.optLong("canonicalChildId", id)
    var updated = _byChild.value + (id to snapshot)
    // ID ребёнка на телефоне и канонический ID в семье могут отличаться — кладём под оба.
    if (canonical > 0L && canonical != id) updated = updated + (canonical to snapshot)
    _byChild.value = updated
  }

  fun snapshotFor(childId: Long): Snapshot? = _byChild.value[childId]
}
