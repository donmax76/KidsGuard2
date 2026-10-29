package com.example.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.util.LruCache
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.LocationSearching
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.data.model.GeofenceZoneEntity
import com.example.data.model.LocationBreadcrumb
import com.example.location.LiveLocationTracker
import com.example.location.LocationState
import com.example.ui.theme.CyanLight
import com.example.ui.theme.EmeraldShieldLight
import com.example.ui.theme.Slate400
import com.example.ui.theme.Slate700
import com.example.ui.theme.Slate900
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Shared thread-safe memory + disk cache for Google Maps HD tiles (lyrs=m & lyrs=y)
 * with OpenStreetMap fallback. Requires ZERO paid API keys and never expires during a session.
 */
private object HdMapTileRepository {
  private val memoryCache = object : LruCache<String, Bitmap>(48 * 1024) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount / 1024
  }
  private val inFlight = Collections.synchronizedSet(HashSet<String>())
  private val executor = Executors.newFixedThreadPool(6)

  fun getFromMemory(layer: String, z: Int, x: Int, y: Int): Bitmap? {
    return memoryCache.get("$layer/$z/$x/$y")
  }

  fun requestTile(
    context: Context,
    layer: String,
    z: Int,
    x: Int,
    y: Int,
    onLoaded: () -> Unit
  ) {
    val key = "$layer/$z/$x/$y"
    if (memoryCache.get(key) != null) return
    if (!inFlight.add(key)) return

    val appContext = context.applicationContext
    executor.execute {
      try {
        val diskFile = File(appContext.cacheDir, "kg_hd_tiles/$layer/$z/$x/$y.png")
        if (diskFile.exists() && diskFile.length() > 100) {
          val bmp = BitmapFactory.decodeFile(diskFile.absolutePath)
          if (bmp != null) {
            memoryCache.put(key, bmp)
            onLoaded()
            return@execute
          }
        }

        val serverIdx = (x + y) and 3
        val urls = if (layer == "sat") {
          listOf(
            "https://mt$serverIdx.google.com/vt/lyrs=y&hl=ru&x=$x&y=$y&z=$z",
            "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/$z/$y/$x"
          )
        } else {
          listOf(
            "https://mt$serverIdx.google.com/vt/lyrs=m&hl=ru&x=$x&y=$y&z=$z",
            "https://tile.openstreetmap.org/$z/$x/$y.png"
          )
        }

        for (urlStr in urls) {
          var conn: HttpURLConnection? = null
          try {
            conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
              connectTimeout = 5000
              readTimeout = 5000
              setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Linux; Android 14; KidsGuardPro/3.3.15) AppleWebKit/537.36"
              )
            }
            if (conn.responseCode == 200) {
              val bytes = conn.inputStream.use { it.readBytes() }
              val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
              if (bmp != null) {
                memoryCache.put(key, bmp)
                try {
                  diskFile.parentFile?.mkdirs()
                  FileOutputStream(diskFile).use { it.write(bytes) }
                } catch (_: Exception) {}
                onLoaded()
                break
              }
            }
          } catch (_: Exception) {
          } finally {
            conn?.disconnect()
          }
        }
      } finally {
        inFlight.remove(key)
      }
    }
  }
}

/**
 * Hardware-accelerated native Android View that renders Google Maps HD Street & Satellite tiles
 * with continuous pinch-to-zoom (3.0x to 21.0x), parent/child tile pyramid fallback (so the map
 * NEVER disappears or flashes blank while zooming), geofence circles, 24h polylines, and markers.
 * Works 100% out-of-the-box without requiring any Google Cloud API Key or billing account.
 */
private class GoogleSlippyMapView(context: Context) : View(context) {
  var centerLat: Double = 55.751244
  var centerLng: Double = 37.618423
  var zoom: Float = 17.0f
  var isSatelliteHybrid: Boolean = false

  var targetLat: Double = 0.0
  var targetLng: Double = 0.0
  var childName: String = "Ребёнок"
  var currentAddress: String = ""
  var accuracyMeters: Float = 12f
  var geofences: List<GeofenceZoneEntity> = emptyList()
  var routePoints: List<LocationBreadcrumb> = emptyList()
  var selectedPoint: LocationBreadcrumb? = null
  var showRouteTrail: Boolean = true
  var compactMode: Boolean = false

  var onZoomChanged: ((Float) -> Unit)? = null

  private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
  private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(35, 56, 189, 248)
    strokeWidth = 1f
    style = Paint.Style.STROKE
  }
  private val routeGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(80, 6, 182, 212)
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(235, 34, 211, 238)
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
  }
  private val geoFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val geoStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    pathEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)
  }
  private val markerFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.FILL
  }
  private val markerStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
  }
  private val labelBgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(230, 15, 23, 42)
    style = Paint.Style.FILL
  }
  private val labelBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(180, 56, 189, 248)
    style = Paint.Style.STROKE
    strokeWidth = 2f
  }
  private val titleTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.WHITE
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
  }
  private val subTextPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = android.graphics.Color.argb(220, 167, 243, 208)
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
  }

  private val srcRect = Rect()
  private val dstRect = RectF()
  private val routePath = Path()
  private val calloutRect = RectF()

  private var isScaling = false
  private var lastFocusX = 0f
  private var lastFocusY = 0f

  private val scaleDetector = ScaleGestureDetector(
    context,
    object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
      override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        isScaling = true
        lastFocusX = detector.focusX
        lastFocusY = detector.focusY
        return true
      }

      override fun onScale(detector: ScaleGestureDetector): Boolean {
        val factor = detector.scaleFactor.coerceIn(0.8f, 1.25f)
        if (abs(factor - 1.0f) > 0.001f && width > 0 && height > 0) {
          val focusX = detector.focusX
          val focusY = detector.focusY

          // Keep the geographic point under the pinch focus stationary while zooming
          val (focusLat, focusLng) = screenToLatLng(focusX, focusY)
          val newZoom = (zoom + (ln(factor.toDouble()) / ln(2.0)).toFloat()).coerceIn(3.0f, 21.0f)
          zoom = newZoom

          val worldSize = 256.0 * 2.0.pow(zoom.toDouble())
          val focusWorldX = lngToWorldX(focusLng, worldSize)
          val focusWorldY = latToWorldY(focusLat, worldSize)

          val newCenterWorldX = focusWorldX - (focusX - width / 2.0)
          val newCenterWorldY = focusWorldY - (focusY - height / 2.0)

          centerLng = wrapLng(worldXToLng(newCenterWorldX, worldSize))
          centerLat = worldYToLat(newCenterWorldY, worldSize).coerceIn(-85.0511, 85.0511)

          onZoomChanged?.invoke(zoom)
          invalidate()
        }
        lastFocusX = detector.focusX
        lastFocusY = detector.focusY
        return true
      }

      override fun onScaleEnd(detector: ScaleGestureDetector) {
        isScaling = false
      }
    }
  )

  private val gestureDetector = GestureDetector(
    context,
    object : GestureDetector.SimpleOnGestureListener() {
      override fun onDown(e: MotionEvent): Boolean = true

      override fun onScroll(
        e1: MotionEvent?,
        e2: MotionEvent,
        distanceX: Float,
        distanceY: Float
      ): Boolean {
        if (isScaling || width <= 0 || height <= 0) return false
        val worldSize = 256.0 * 2.0.pow(zoom.toDouble())
        val newWorldX = lngToWorldX(centerLng, worldSize) + distanceX
        val newWorldY = latToWorldY(centerLat, worldSize) + distanceY
        centerLng = wrapLng(worldXToLng(newWorldX, worldSize))
        centerLat = worldYToLat(newWorldY, worldSize).coerceIn(-85.0511, 85.0511)
        invalidate()
        return true
      }

      override fun onDoubleTap(e: MotionEvent): Boolean {
        val (tapLat, tapLng) = screenToLatLng(e.x, e.y)
        zoom = (zoom + 1.2f).coerceIn(3.0f, 21.0f)
        val worldSize = 256.0 * 2.0.pow(zoom.toDouble())
        val tapWorldX = lngToWorldX(tapLng, worldSize)
        val tapWorldY = latToWorldY(tapLat, worldSize)
        centerLng = wrapLng(worldXToLng(tapWorldX - (e.x - width / 2.0), worldSize))
        centerLat = worldYToLat(tapWorldY - (e.y - height / 2.0), worldSize).coerceIn(-85.0511, 85.0511)
        onZoomChanged?.invoke(zoom)
        invalidate()
        return true
      }
    }
  )

  override fun onTouchEvent(event: MotionEvent): Boolean {
    when (event.actionMasked) {
      MotionEvent.ACTION_DOWN,
      MotionEvent.ACTION_MOVE,
      MotionEvent.ACTION_POINTER_DOWN -> {
        parent?.requestDisallowInterceptTouchEvent(true)
      }
      MotionEvent.ACTION_UP,
      MotionEvent.ACTION_CANCEL -> {
        parent?.requestDisallowInterceptTouchEvent(false)
      }
    }
    scaleDetector.onTouchEvent(event)
    gestureDetector.onTouchEvent(event)
    return true
  }

  fun setZoomLevel(newZoom: Float) {
    zoom = newZoom.coerceIn(3.0f, 21.0f)
    onZoomChanged?.invoke(zoom)
    invalidate()
  }

  fun centerOn(lat: Double, lng: Double, targetZoom: Float? = null) {
    if (lat == 0.0 && lng == 0.0) return
    centerLat = lat.coerceIn(-85.0511, 85.0511)
    centerLng = wrapLng(lng)
    if (targetZoom != null) {
      zoom = targetZoom.coerceIn(3.0f, 21.0f)
      onZoomChanged?.invoke(zoom)
    }
    invalidate()
  }

  override fun onDraw(canvas: Canvas) {
    super.onDraw(canvas)
    val w = width.toFloat()
    val h = height.toFloat()
    if (w <= 0f || h <= 0f) return

    canvas.drawColor(android.graphics.Color.rgb(12, 22, 38))

    // 1. Draw Slippy Map Tiles with Multi-Level Pyramid Fallback (never blanks out on zoom!)
    val tileZ = zoom.toInt().coerceIn(3, 20)
    val scale = 2.0.pow((zoom - tileZ).toDouble()).toFloat()
    val scaledTileSize = 256.0 * scale
    val numTiles = 1 shl tileZ
    val layer = if (isSatelliteHybrid) "sat" else "map"

    val centerTileX = (centerLng + 180.0) / 360.0 * numTiles
    val sinLat = sin(Math.toRadians(centerLat.coerceIn(-85.0511, 85.0511)))
    val centerTileY = (0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)) * numTiles

    val halfCols = ceil((w / 2.0) / scaledTileSize).toInt() + 1
    val halfRows = ceil((h / 2.0) / scaledTileSize).toInt() + 1
    val minTx = floor(centerTileX).toInt() - halfCols
    val maxTx = floor(centerTileX).toInt() + halfCols
    val minTy = (floor(centerTileY).toInt() - halfRows).coerceAtLeast(0)
    val maxTy = (floor(centerTileY).toInt() + halfRows).coerceAtMost(numTiles - 1)

    for (ty in minTy..maxTy) {
      for (tx in minTx..maxTx) {
        val left = (w / 2.0 + (tx - centerTileX) * scaledTileSize).toFloat()
        val top = (h / 2.0 + (ty - centerTileY) * scaledTileSize).toFloat()
        val right = (left + scaledTileSize).toFloat() + 0.6f
        val bottom = (top + scaledTileSize).toFloat() + 0.6f
        dstRect.set(left, top, right, bottom)

        val wrappedTx = ((tx % numTiles) + numTiles) % numTiles
        val exactBmp = HdMapTileRepository.getFromMemory(layer, tileZ, wrappedTx, ty)
        if (exactBmp != null) {
          canvas.drawBitmap(exactBmp, null, dstRect, bitmapPaint)
        } else {
          // Request high-res tile asynchronously
          HdMapTileRepository.requestTile(context, layer, tileZ, wrappedTx, ty) {
            postInvalidateOnAnimation()
          }
          // Also warm up parent tile z-1 if not cached yet
          if (tileZ > 3) {
            HdMapTileRepository.requestTile(context, layer, tileZ - 1, wrappedTx shr 1, ty shr 1) {
              postInvalidateOnAnimation()
            }
          }

          // Parent Tile Pyramid Fallback: draw cropped quadrant from nearest cached ancestor (z-1..z-6)
          var drawnFallback = false
          for (deltaZ in 1..6) {
            val parentZ = tileZ - deltaZ
            if (parentZ < 3) break
            val pTx = wrappedTx shr deltaZ
            val pTy = ty shr deltaZ
            val parentBmp = HdMapTileRepository.getFromMemory(layer, parentZ, pTx, pTy)
            if (parentBmp != null) {
              val subDiv = 1 shl deltaZ
              val subX = wrappedTx and (subDiv - 1)
              val subY = ty and (subDiv - 1)
              val chunkW = parentBmp.width.toFloat() / subDiv
              val chunkH = parentBmp.height.toFloat() / subDiv
              srcRect.set(
                (subX * chunkW).toInt(),
                (subY * chunkH).toInt(),
                ((subX + 1) * chunkW).toInt(),
                ((subY + 1) * chunkH).toInt()
              )
              canvas.drawBitmap(parentBmp, srcRect, dstRect, bitmapPaint)
              drawnFallback = true
              break
            }
          }

          // Child Tile Fallback when zooming out from a higher zoom level
          if (!drawnFallback && tileZ < 20) {
            val childZ = tileZ + 1
            val halfW = (right - left) / 2f
            val halfH = (bottom - top) / 2f
            var anyChild = false
            for (dy in 0..1) {
              for (dx in 0..1) {
                val cBmp = HdMapTileRepository.getFromMemory(
                  layer,
                  childZ,
                  wrappedTx * 2 + dx,
                  ty * 2 + dy
                )
                if (cBmp != null) {
                  val cRect = RectF(
                    left + dx * halfW,
                    top + dy * halfH,
                    left + (dx + 1) * halfW,
                    top + (dy + 1) * halfH
                  )
                  canvas.drawBitmap(cBmp, null, cRect, bitmapPaint)
                  anyChild = true
                }
              }
            }
            if (!anyChild) {
              canvas.drawRect(dstRect, gridPaint)
            }
          }
        }
      }
    }

    val density = resources.displayMetrics.density
    val worldSize = 256.0 * 2.0.pow(zoom.toDouble())
    val centerWorldX = lngToWorldX(centerLng, worldSize)
    val centerWorldY = latToWorldY(centerLat, worldSize)

    fun latLngToScreen(lat: Double, lng: Double): Pair<Float, Float> {
      val wx = lngToWorldX(lng, worldSize)
      val wy = latToWorldY(lat, worldSize)
      val sx = (w / 2.0 + (wx - centerWorldX)).toFloat()
      val sy = (h / 2.0 + (wy - centerWorldY)).toFloat()
      return sx to sy
    }

    fun metersToPixels(meters: Double, atLat: Double): Float {
      val metersPerPixel = (156543.03392 * cos(Math.toRadians(atLat.coerceIn(-85.0, 85.0)))) /
        2.0.pow(zoom.toDouble())
      return (meters / metersPerPixel.coerceAtLeast(0.01)).toFloat()
    }

    // 2. Draw Geofence Safe Zones
    geofences.forEach { zone ->
      if (zone.latitude != 0.0 && zone.longitude != 0.0 &&
        !LiveLocationTracker.isEmulatorSyntheticUsLocation(zone.latitude, zone.longitude)
      ) {
        val (gx, gy) = latLngToScreen(zone.latitude, zone.longitude)
        val radiusPx = metersToPixels(zone.radiusMeters.toDouble().coerceAtLeast(30.0), zone.latitude)
          .coerceIn(14f * density, 500f * density)

        val baseR = if (zone.iconType == "home") 16 else if (zone.iconType == "school") 56 else 245
        val baseG = if (zone.iconType == "home") 185 else if (zone.iconType == "school") 189 else 158
        val baseB = if (zone.iconType == "home") 129 else if (zone.iconType == "school") 248 else 11

        geoFillPaint.color = android.graphics.Color.argb(45, baseR, baseG, baseB)
        geoStrokePaint.color = android.graphics.Color.argb(220, baseR, baseG, baseB)
        geoStrokePaint.strokeWidth = 2.2f * density

        canvas.drawCircle(gx, gy, radiusPx, geoFillPaint)
        canvas.drawCircle(gx, gy, radiusPx, geoStrokePaint)
      }
    }

    // 3. Draw 24-Hour Route Trail & Waypoints
    if (showRouteTrail && routePoints.size >= 2) {
      routePath.reset()
      routePoints.forEachIndexed { idx, pt ->
        val (px, py) = latLngToScreen(pt.latitude, pt.longitude)
        if (idx == 0) routePath.moveTo(px, py) else routePath.lineTo(px, py)
      }
      routeGlowPaint.strokeWidth = 8f * density
      routePaint.strokeWidth = 3.8f * density
      canvas.drawPath(routePath, routeGlowPaint)
      canvas.drawPath(routePath, routePaint)

      routePoints.forEachIndexed { idx, pt ->
        val (px, py) = latLngToScreen(pt.latitude, pt.longitude)
        val isSel = selectedPoint != null && selectedPoint?.timestamp == pt.timestamp
        val r = if (isSel) 7f * density else 4.2f * density
        markerFillPaint.color = if (isSel) {
          android.graphics.Color.rgb(250, 204, 21)
        } else if (idx == 0) {
          android.graphics.Color.rgb(16, 185, 129)
        } else {
          android.graphics.Color.rgb(56, 189, 248)
        }
        markerStrokePaint.color = android.graphics.Color.WHITE
        markerStrokePaint.strokeWidth = 1.8f * density
        canvas.drawCircle(px, py, r, markerFillPaint)
        canvas.drawCircle(px, py, r, markerStrokePaint)
      }
    }

    // 4. Draw Child's Live Location Marker, Accuracy Halo & Callout Bubble
    if (targetLat != 0.0 && targetLng != 0.0) {
      val (cx, cy) = latLngToScreen(targetLat, targetLng)
      val accRadiusPx = metersToPixels(accuracyMeters.coerceIn(8f, 120f).toDouble(), targetLat)
        .coerceIn(18f * density, 140f * density)

      // Outer GPS accuracy halo
      markerFillPaint.color = android.graphics.Color.argb(42, 16, 185, 129)
      canvas.drawCircle(cx, cy, accRadiusPx, markerFillPaint)
      markerStrokePaint.color = android.graphics.Color.argb(175, 16, 185, 129)
      markerStrokePaint.strokeWidth = 1.6f * density
      canvas.drawCircle(cx, cy, accRadiusPx, markerStrokePaint)

      // Pulsing outer ring + crisp pin core
      markerFillPaint.color = android.graphics.Color.argb(85, 6, 182, 212)
      canvas.drawCircle(cx, cy, 15f * density, markerFillPaint)

      markerFillPaint.color = android.graphics.Color.rgb(6, 182, 212)
      canvas.drawCircle(cx, cy, 8.5f * density, markerFillPaint)

      markerStrokePaint.color = android.graphics.Color.WHITE
      markerStrokePaint.strokeWidth = 2.6f * density
      canvas.drawCircle(cx, cy, 8.5f * density, markerStrokePaint)

      // Floating Callout Label above pin
      val titleStr = if (selectedPoint != null) {
        val tStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(selectedPoint!!.timestamp))
        "$childName • $tStr"
      } else {
        "$childName • ±${accuracyMeters.toInt().coerceAtLeast(3)}м"
      }
      val rawAddr = selectedPoint?.title?.takeIf { it.isNotBlank() }
        ?: currentAddress.takeIf { it.isNotBlank() && !LiveLocationTracker.isStaleOrDemoAddress(it) }
        ?: "%.5f, %.5f".format(Locale.US, targetLat, targetLng)
      val subStr = if (rawAddr.length > 36) rawAddr.take(34) + "…" else rawAddr

      titleTextPaint.textSize = (if (compactMode) 11f else 12.5f) * density
      subTextPaint.textSize = (if (compactMode) 9.5f else 11f) * density

      val textW = maxOf(titleTextPaint.measureText(titleStr), subTextPaint.measureText(subStr))
      val padH = 10f * density
      val padV = 6f * density
      val boxW = textW + padH * 2
      val boxH = (titleTextPaint.textSize + subTextPaint.textSize + padV * 2.5f)

      val boxLeft = (cx - boxW / 2f).coerceIn(8f * density, (w - boxW - 8f * density).coerceAtLeast(8f * density))
      val boxTop = (cy - 22f * density - boxH).coerceAtLeast(8f * density)
      calloutRect.set(boxLeft, boxTop, boxLeft + boxW, boxTop + boxH)

      canvas.drawRoundRect(calloutRect, 10f * density, 10f * density, labelBgPaint)
      canvas.drawRoundRect(calloutRect, 10f * density, 10f * density, labelBorderPaint)

      canvas.drawText(
        titleStr,
        boxLeft + padH,
        boxTop + padV + titleTextPaint.textSize * 0.85f,
        titleTextPaint
      )
      canvas.drawText(
        subStr,
        boxLeft + padH,
        boxTop + boxH - padV * 0.85f,
        subTextPaint
      )
    }
  }

  private fun screenToLatLng(sx: Float, sy: Float): Pair<Double, Double> {
    val worldSize = 256.0 * 2.0.pow(zoom.toDouble())
    val centerWorldX = lngToWorldX(centerLng, worldSize)
    val centerWorldY = latToWorldY(centerLat, worldSize)
    val wx = centerWorldX + (sx - width / 2.0)
    val wy = centerWorldY + (sy - height / 2.0)
    val lat = worldYToLat(wy, worldSize).coerceIn(-85.0511, 85.0511)
    val lng = wrapLng(worldXToLng(wx, worldSize))
    return lat to lng
  }

  private fun lngToWorldX(lng: Double, worldSize: Double): Double {
    return ((lng + 180.0) / 360.0) * worldSize
  }

  private fun latToWorldY(lat: Double, worldSize: Double): Double {
    val sinLat = sin(Math.toRadians(lat.coerceIn(-85.05112878, 85.05112878)))
    val y = 0.5 - ln((1.0 + sinLat) / (1.0 - sinLat)) / (4.0 * Math.PI)
    return y * worldSize
  }

  private fun worldXToLng(wx: Double, worldSize: Double): Double {
    return (wx / worldSize) * 360.0 - 180.0
  }

  private fun worldYToLat(wy: Double, worldSize: Double): Double {
    val n = Math.PI - 2.0 * Math.PI * (wy / worldSize)
    return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
  }

  private fun wrapLng(lng: Double): Double {
    var l = lng
    while (l < -180.0) l += 360.0
    while (l > 180.0) l -= 360.0
    return l
  }
}

@Composable
fun TileMapCanvas(
  locationState: LocationState,
  geofences: List<GeofenceZoneEntity>,
  childName: String? = null,
  childLat: Double? = null,
  childLng: Double? = null,
  historyPoints: List<LocationBreadcrumb> = emptyList(),
  selectedHistoryPoint: LocationBreadcrumb? = null,
  showRouteTrail: Boolean = true,
  compactControls: Boolean = false,
  onCenterClick: () -> Unit = {},
  modifier: Modifier = Modifier
) {
  val rawLat = selectedHistoryPoint?.latitude
    ?: if (childLat != null && childLat != 0.0) childLat else locationState.latitude
  val rawLng = selectedHistoryPoint?.longitude
    ?: if (childLng != null && childLng != 0.0) childLng else locationState.longitude

  val isValidRealCoord = !LiveLocationTracker.isEmulatorSyntheticUsLocation(rawLat, rawLng) &&
    rawLat != 0.0 && rawLng != 0.0
  val targetLat = if (isValidRealCoord) rawLat else 0.0
  val targetLng = if (isValidRealCoord) rawLng else 0.0

  val effectiveRoutePoints = remember(historyPoints, locationState.breadcrumbs) {
    val raw = if (historyPoints.isNotEmpty()) historyPoints else locationState.breadcrumbs
    raw.filter {
      it.latitude != 0.0 &&
        it.longitude != 0.0 &&
        !LiveLocationTracker.isEmulatorSyntheticUsLocation(it.latitude, it.longitude)
    }.sortedBy { it.timestamp }
  }

  var mapViewRef by remember { mutableStateOf<GoogleSlippyMapView?>(null) }
  var isSatelliteHybrid by remember { mutableStateOf(false) }
  var currentZoom by remember { mutableFloatStateOf(if (compactControls) 16.5f else 17.5f) }
  var hasCenteredOnInitialCoord by remember { mutableStateOf(false) }
  var lastCenteredLat by remember { mutableStateOf(0.0) }
  var lastCenteredLng by remember { mutableStateOf(0.0) }

  LaunchedEffect(
    mapViewRef,
    targetLat,
    targetLng,
    childName,
    geofences,
    effectiveRoutePoints,
    selectedHistoryPoint,
    showRouteTrail,
    isSatelliteHybrid,
    locationState.accuracyMeters,
    locationState.currentAddress
  ) {
    val view = mapViewRef ?: return@LaunchedEffect
    view.targetLat = targetLat
    view.targetLng = targetLng
    view.childName = childName ?: "Ребёнок"
    view.currentAddress = locationState.currentAddress
    view.accuracyMeters = locationState.accuracyMeters
    view.geofences = geofences
    view.routePoints = effectiveRoutePoints
    view.selectedPoint = selectedHistoryPoint
    view.showRouteTrail = showRouteTrail
    view.isSatelliteHybrid = isSatelliteHybrid
    view.compactMode = compactControls

    if (targetLat != 0.0 && targetLng != 0.0) {
      val movedSignificantly = abs(targetLat - lastCenteredLat) > 0.0004 ||
        abs(targetLng - lastCenteredLng) > 0.0004
      if (!hasCenteredOnInitialCoord || selectedHistoryPoint != null || movedSignificantly) {
        val desiredZoom = if (!hasCenteredOnInitialCoord) {
          if (compactControls) 16.5f else 17.5f
        } else {
          view.zoom.coerceIn(14f, 20.5f)
        }
        view.centerOn(targetLat, targetLng, desiredZoom)
        hasCenteredOnInitialCoord = true
        lastCenteredLat = targetLat
        lastCenteredLng = targetLng
      }
    }
    view.invalidate()
  }

  Box(
    modifier = modifier
      .fillMaxSize()
      .background(Color(0xFF0A1424))
      .testTag("google_map_canvas_container")
  ) {
    AndroidView(
      factory = { ctx ->
        GoogleSlippyMapView(ctx).apply {
          zoom = currentZoom
          if (targetLat != 0.0 && targetLng != 0.0) {
            centerLat = targetLat
            centerLng = targetLng
            hasCenteredOnInitialCoord = true
            lastCenteredLat = targetLat
            lastCenteredLng = targetLng
          }
          onZoomChanged = { updatedZoom ->
            currentZoom = updatedZoom
          }
          mapViewRef = this
        }
      },
      update = { view ->
        view.targetLat = targetLat
        view.targetLng = targetLng
        view.childName = childName ?: "Ребёнок"
        view.currentAddress = locationState.currentAddress
        view.accuracyMeters = locationState.accuracyMeters
        view.geofences = geofences
        view.routePoints = effectiveRoutePoints
        view.selectedPoint = selectedHistoryPoint
        view.showRouteTrail = showRouteTrail
        view.isSatelliteHybrid = isSatelliteHybrid
        view.compactMode = compactControls
        view.invalidate()
      },
      modifier = Modifier.fillMaxSize()
    )

    // Non-blocking waiting banner if coordinates haven't arrived from child yet
    if (targetLat == 0.0 && targetLng == 0.0) {
      Box(
        modifier = Modifier
          .align(Alignment.Center)
          .padding(horizontal = 20.dp)
          .clip(RoundedCornerShape(16.dp))
          .background(Slate900.copy(alpha = 0.90f))
          .border(1.dp, CyanLight.copy(alpha = 0.45f), RoundedCornerShape(16.dp))
          .padding(horizontal = 16.dp, vertical = 12.dp)
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
          Icon(
            imageVector = Icons.Default.LocationSearching,
            contentDescription = null,
            tint = CyanLight,
            modifier = Modifier.size(22.dp)
          )
          Column {
            Text(
              text = "Запрос высокоточного GPS-сигнала...",
              color = Color.White,
              fontSize = 13.sp,
              fontWeight = FontWeight.Bold
            )
            Text(
              text = "Карта Google HD готова • Нажмите прицел для обновления",
              color = Slate400,
              fontSize = 11.sp
            )
          }
        }
      }
    }

    // Bottom-Left Zoom Level & Map Layer Badge
    Box(
      modifier = Modifier
        .align(Alignment.BottomStart)
        .padding(start = 10.dp, bottom = if (compactControls) 8.dp else 14.dp)
        .clip(RoundedCornerShape(8.dp))
        .background(Slate900.copy(alpha = 0.86f))
        .border(0.5.dp, Slate700, RoundedCornerShape(8.dp))
        .clickable {
          isSatelliteHybrid = !isSatelliteHybrid
          mapViewRef?.isSatelliteHybrid = isSatelliteHybrid
          mapViewRef?.invalidate()
        }
        .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
      val modeLabel = if (isSatelliteHybrid) "Спутник HD" else "Схема HD"
      Text(
        text = "Google Maps • $modeLabel • Зум ${"%.1f".format(Locale.US, currentZoom)}x",
        color = EmeraldShieldLight,
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold
      )
    }

    // Right-Side Floating Map Controls (+ / - / Layer / Center on Child)
    val btnSize = if (compactControls) 36.dp else 44.dp
    Column(
      modifier = Modifier
        .align(Alignment.CenterEnd)
        .padding(end = if (compactControls) 8.dp else 14.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
      // Toggle Satellite / Street Map
      IconButton(
        onClick = {
          isSatelliteHybrid = !isSatelliteHybrid
          mapViewRef?.isSatelliteHybrid = isSatelliteHybrid
          mapViewRef?.invalidate()
        },
        modifier = Modifier
          .size(btnSize)
          .shadow(6.dp, CircleShape)
          .clip(CircleShape)
          .background(Slate900.copy(alpha = 0.92f))
          .border(1.dp, Slate700, CircleShape)
          .testTag("map_toggle_layer_button")
      ) {
        Icon(
          imageVector = Icons.Default.Layers,
          contentDescription = "Переключить слой карты (Схема / Спутник)",
          tint = if (isSatelliteHybrid) EmeraldShieldLight else CyanLight,
          modifier = Modifier.size(if (compactControls) 18.dp else 20.dp)
        )
      }

      // Zoom In (+) up to 21x building/street detail
      IconButton(
        onClick = {
          val nextZoom = (currentZoom + 1.0f).coerceIn(3.0f, 21.0f)
          currentZoom = nextZoom
          mapViewRef?.setZoomLevel(nextZoom)
        },
        modifier = Modifier
          .size(btnSize)
          .shadow(6.dp, CircleShape)
          .clip(CircleShape)
          .background(Slate900.copy(alpha = 0.92f))
          .border(1.dp, Slate700, CircleShape)
          .testTag("map_zoom_in_button")
      ) {
        Icon(
          imageVector = Icons.Default.Add,
          contentDescription = "Приблизить карту",
          tint = Color.White,
          modifier = Modifier.size(if (compactControls) 18.dp else 20.dp)
        )
      }

      // Zoom Out (-)
      IconButton(
        onClick = {
          val nextZoom = (currentZoom - 1.0f).coerceIn(3.0f, 21.0f)
          currentZoom = nextZoom
          mapViewRef?.setZoomLevel(nextZoom)
        },
        modifier = Modifier
          .size(btnSize)
          .shadow(6.dp, CircleShape)
          .clip(CircleShape)
          .background(Slate900.copy(alpha = 0.92f))
          .border(1.dp, Slate700, CircleShape)
          .testTag("map_zoom_out_button")
      ) {
        Icon(
          imageVector = Icons.Default.Remove,
          contentDescription = "Отдалить карту",
          tint = Color.White,
          modifier = Modifier.size(if (compactControls) 18.dp else 20.dp)
        )
      }

      if (!compactControls) {
        Spacer(modifier = Modifier.height(2.dp))
      }

      // Center on Child & Request Fresh High-Accuracy GPS Fix
      IconButton(
        onClick = {
          if (targetLat != 0.0 && targetLng != 0.0) {
            val focusZoom = currentZoom.coerceAtLeast(18f)
            currentZoom = focusZoom
            mapViewRef?.centerOn(targetLat, targetLng, focusZoom)
          }
          onCenterClick()
        },
        modifier = Modifier
          .size(btnSize)
          .shadow(8.dp, CircleShape)
          .clip(CircleShape)
          .background(CyanLight)
          .testTag("map_center_button")
      ) {
        Icon(
          imageVector = Icons.Default.MyLocation,
          contentDescription = "Центрировать на ребёнке",
          tint = Slate900,
          modifier = Modifier.size(if (compactControls) 18.dp else 20.dp)
        )
      }
    }
  }
}
