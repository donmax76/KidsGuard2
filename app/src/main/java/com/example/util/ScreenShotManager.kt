package com.example.util

import android.accessibilityservice.AccessibilityService
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.MediaScannerConnection
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.view.Display
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "ScreenShotManager"

data class CapturedScreenItem(
  val id: Long = System.currentTimeMillis(),
  val childId: Long = 1L,
  val activeAppName: String,
  val timestamp: Long = System.currentTimeMillis(),
  val timeFormatted: String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date()),
  val statusNote: String,
  val base64Bitmap: String? = null
)

object ScreenShotManager {
  private val _capturedScreens = MutableStateFlow<List<CapturedScreenItem>>(emptyList())
  val capturedScreens: StateFlow<List<CapturedScreenItem>> = _capturedScreens.asStateFlow()

  private val _isMediaProjectionActive = MutableStateFlow(false)
  val isMediaProjectionActive: StateFlow<Boolean> = _isMediaProjectionActive.asStateFlow()

  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private val screenshotExecutor = Executors.newSingleThreadExecutor()

  @Volatile
  private var latestProjectionBitmap: Bitmap? = null

  @Volatile
  private var lastHardwareBitmap: Bitmap? = null

  @Volatile
  private var lastDecorViewBitmap: Bitmap? = null

  @Volatile
  private var lastAttemptTimeMs: Long = 0L

  @Volatile
  private var lastAutoStreamMs: Long = 0L

  @Volatile
  private var pendingResultCode: Int = 0

  @Volatile
  private var pendingResultData: Intent? = null

  @Volatile
  private var isTakingScreenshot: Boolean = false

  @Volatile
  private var isDelayedCaptureScheduled: Boolean = false

  private val pendingCallbacks = CopyOnWriteArrayList<(CapturedScreenItem) -> Unit>()

  private const val PREFS_LIVE_STREAM = "kidsguard_live_stream_prefs"
  private const val KEY_LIVE_STREAM_ENABLED = "live_stream_enabled"

  fun isLiveStreamEnabled(context: Context): Boolean {
    val persisted = try {
      val prefsVal = context.applicationContext
        .getSharedPreferences(PREFS_LIVE_STREAM, Context.MODE_PRIVATE)
        .getBoolean(KEY_LIVE_STREAM_ENABLED, false)
      val fileVal = java.io.File(context.applicationContext.filesDir, "live_stream_enabled.flag").exists()
      prefsVal || fileVal
    } catch (_: Exception) {
      false
    }
    if (persisted && !_isMediaProjectionActive.value) {
      _isMediaProjectionActive.value = true
    }
    return persisted
  }

  fun setLiveStreamEnabled(context: Context, enabled: Boolean) {
    _isMediaProjectionActive.value = enabled
    try {
      context.applicationContext
        .getSharedPreferences(PREFS_LIVE_STREAM, Context.MODE_PRIVATE)
        .edit()
        .putBoolean(KEY_LIVE_STREAM_ENABLED, enabled)
        .commit()
      val flagFile = java.io.File(context.applicationContext.filesDir, "live_stream_enabled.flag")
      if (enabled) {
        flagFile.writeText("1")
      } else if (flagFile.exists()) {
        flagFile.delete()
      }
    } catch (_: Exception) {}
  }

  fun restorePersistentLiveStreamState(context: Context): Boolean {
    val enabled = isLiveStreamEnabled(context)
    if (enabled) {
      _isMediaProjectionActive.value = true
      com.example.service.KidsGuardAccessibilityService.activateLiveStreamingWindow(24L * 3600_000L)
    }
    return enabled
  }

  fun isLiveCaptureReady(): Boolean {
    return _isMediaProjectionActive.value ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && com.example.service.KidsGuardAccessibilityService.instance != null)
  }

  fun onPermissionGrantedFromUi(context: Context, resultCode: Int, data: Intent) {
    pendingResultCode = resultCode
    pendingResultData = data
    setLiveStreamEnabled(context, true)
    com.example.service.KidsGuardAccessibilityService.activateLiveStreamingWindow(24L * 3600_000L)
  }

  fun getPendingProjectionConsent(): Pair<Int, Intent>? {
    val d = pendingResultData ?: return null
    val rc = pendingResultCode
    if (rc == 0) return null
    return Pair(rc, d)
  }

  /**
   * Captures the current Activity window inside KidsGuard for diagnostics.
   */
  fun captureActivityDecorView(context: Context) {
    val activity = context as? android.app.Activity ?: return
    try {
      val decorView = activity.window?.decorView?.rootView ?: return
      if (decorView.width > 0 && decorView.height > 0) {
        val scale = if (decorView.width > 640) 640f / decorView.width else 1f
        val w = (decorView.width * scale).toInt().coerceAtLeast(360)
        val h = (decorView.height * scale).toInt().coerceAtLeast(640)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.scale(scale, scale)
        decorView.draw(canvas)
        lastDecorViewBitmap = bmp
      }
    } catch (_: Exception) {}
  }

  /**
   * Starts standard Android MediaProjection screen sharing (must be called after ForegroundService
   * with FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION is running on Android 10–14+).
   */
  fun startMediaProjection(context: Context, resultCode: Int, data: Intent) {
    try {
      releaseVirtualDisplay()
      try { mediaProjection?.stop() } catch (_: Exception) {}
      mediaProjection = null

      val appCtx = context.applicationContext
      setLiveStreamEnabled(appCtx, true)
      val mpm = appCtx.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager ?: return
      val projection = mpm.getMediaProjection(resultCode, data) ?: return
      mediaProjection = projection
      _isMediaProjectionActive.value = true

      // MANDATORY on Android 14+ (API 34+): must register a callback BEFORE calling createVirtualDisplay!
      projection.registerCallback(object : MediaProjection.Callback() {
        override fun onStop() {
          releaseVirtualDisplay()
          mediaProjection = null
          // Keep live stream active via Accessibility fallback if user enabled item #4
          _isMediaProjectionActive.value = isLiveStreamEnabled(appCtx)
        }
      }, mainHandler)

      val metrics = appCtx.resources.displayMetrics
      val rawW = metrics.widthPixels.coerceAtLeast(720)
      val rawH = metrics.heightPixels.coerceAtLeast(1280)
      val scale = if (rawW > 640) 640f / rawW else 1f
      val targetW = (rawW * scale).toInt().coerceAtLeast(360)
      val targetH = (rawH * scale).toInt().coerceAtLeast(640)

      val reader = ImageReader.newInstance(targetW, targetH, PixelFormat.RGBA_8888, 2)
      imageReader = reader
      reader.setOnImageAvailableListener({ ir ->
        val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
        try {
          val planes = img.planes
          if (planes.isNotEmpty()) {
            val buffer = planes[0].buffer
            val pixelStride = planes[0].pixelStride
            val rowStride = planes[0].rowStride
            val rowPadding = rowStride - pixelStride * targetW
            val fullW = targetW + rowPadding / pixelStride
            val rawBmp = Bitmap.createBitmap(fullW, targetH, Bitmap.Config.ARGB_8888)
            rawBmp.copyPixelsFromBuffer(buffer)
            val cropped = if (fullW != targetW) {
              Bitmap.createBitmap(rawBmp, 0, 0, targetW, targetH).also { rawBmp.recycle() }
            } else {
              rawBmp
            }
            latestProjectionBitmap = cropped
            lastHardwareBitmap = cropped

            val now = System.currentTimeMillis()
            if (now - lastAutoStreamMs >= 1800L) {
              lastAutoStreamMs = now
              screenshotExecutor.execute {
                try {
                  val b64 = bitmapToBase64(cropped)
                  val cid = com.example.data.cloud.DirectP2pSocketEngine.thisDeviceBoundChildId.takeIf { it > 0L } ?: 1L
                  val appLabel = DeviceUsageTracker.resolveCurrentForegroundApp(appCtx)
                  com.example.data.cloud.DirectP2pSocketEngine.pushDirectScreenFrame(cid, appLabel, b64)
                } catch (_: Exception) {}
              }
            }
          }
        } catch (_: Exception) {
        } finally {
          try { img.close() } catch (_: Exception) {}
        }
      }, mainHandler)

      virtualDisplay = projection.createVirtualDisplay(
        "KidsGuardLiveScreen",
        targetW,
        targetH,
        metrics.densityDpi,
        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
        reader.surface,
        null,
        mainHandler
      )
      _isMediaProjectionActive.value = true
      Log.d(TAG, "MediaProjection VirtualDisplay started ($targetW x $targetH)")
    } catch (e: Exception) {
      Log.e(TAG, "startMediaProjection error: ${e.message}")
    }
  }

  fun stopMediaProjection(context: Context? = null, disablePreference: Boolean = false) {
    pendingResultCode = 0
    pendingResultData = null
    releaseVirtualDisplay()
    try { mediaProjection?.stop() } catch (_: Exception) {}
    mediaProjection = null
    if (disablePreference && context != null) {
      setLiveStreamEnabled(context, false)
      _isMediaProjectionActive.value = false
    } else if (context != null) {
      _isMediaProjectionActive.value = isLiveStreamEnabled(context)
    }
  }

  private fun releaseVirtualDisplay() {
    try { virtualDisplay?.release() } catch (_: Exception) {}
    virtualDisplay = null
    try { imageReader?.close() } catch (_: Exception) {}
    imageReader = null
  }

  fun triggerHardwareScreenCapture(
    appName: String,
    saveToDeviceGallery: Boolean = false,
    onCaptured: (CapturedScreenItem) -> Unit = {}
  ) {
    // 1. If MediaProjection VirtualDisplay is actively streaming frames, grab the latest frame immediately
    val projBmp = latestProjectionBitmap
    if (_isMediaProjectionActive.value && projBmp != null && !projBmp.isRecycled) {
      screenshotExecutor.execute {
        val b64 = bitmapToBase64(projBmp)
        val item = CapturedScreenItem(
          activeAppName = appName,
          statusNote = "Живой видеопоток MediaProjection ($appName)",
          base64Bitmap = b64
        )
        addCapturedItem(item)
        if (saveToDeviceGallery) {
          val svc = com.example.service.KidsGuardAccessibilityService.instance
          if (svc != null) {
            saveBitmapToGallery(svc.applicationContext, projBmp, "KidsGuard_${System.currentTimeMillis()}")
          }
        }
        onCaptured(item)
      }
      return
    }

    // 2. Android 11+ (API 30+) Official AccessibilityService.takeScreenshot hardware pixel capture
    val svc = com.example.service.KidsGuardAccessibilityService.instance
    val nowMs = System.currentTimeMillis()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && svc != null && (nowMs - lastAttemptTimeMs >= 1100L) && !isTakingScreenshot) {
      lastAttemptTimeMs = nowMs
      isTakingScreenshot = true
      try {
        svc.takeScreenshot(
          Display.DEFAULT_DISPLAY,
          screenshotExecutor,
          object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
              isTakingScreenshot = false
              try {
                val hwBuffer = screenshot.hardwareBuffer
                val colorSpace = screenshot.colorSpace
                val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                hwBuffer.close()
                if (wrapped != null) {
                  val softBmp = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                  wrapped.recycle()
                  if (softBmp != null) {
                    lastHardwareBitmap = softBmp
                    val b64 = bitmapToBase64(softBmp)
                    val item = CapturedScreenItem(
                      activeAppName = appName,
                      statusNote = "Реальный экран устройства ($appName)",
                      base64Bitmap = b64
                    )
                    addCapturedItem(item)
                    if (saveToDeviceGallery) {
                      saveBitmapToGallery(svc.applicationContext, softBmp, "KidsGuard_${System.currentTimeMillis()}")
                    }
                    onCaptured(item)
                    return
                  }
                }
              } catch (_: Exception) {}
              fallbackToCachedBitmapOrText(appName, saveToDeviceGallery, onCaptured)
            }

            override fun onFailure(errorCode: Int) {
              isTakingScreenshot = false
              fallbackToCachedBitmapOrText(appName, saveToDeviceGallery, onCaptured)
            }
          }
        )
        return
      } catch (_: Exception) {
        isTakingScreenshot = false
      }
    }

    // 3. Fallback to most recent hardware/projection/decor bitmap if rate-limited (< 1.1s)
    fallbackToCachedBitmapOrText(appName, saveToDeviceGallery, onCaptured)
  }

  private fun fallbackToCachedBitmapOrText(
    appName: String,
    saveToDeviceGallery: Boolean,
    onCaptured: (CapturedScreenItem) -> Unit
  ) {
    val realPixelBitmap = latestProjectionBitmap ?: lastHardwareBitmap ?: lastDecorViewBitmap
    if (realPixelBitmap != null && !realPixelBitmap.isRecycled) {
      screenshotExecutor.execute {
        val b64 = bitmapToBase64(realPixelBitmap)
        val item = CapturedScreenItem(
          activeAppName = appName,
          statusNote = "Экран устройства ($appName)",
          base64Bitmap = b64
        )
        addCapturedItem(item)
        if (saveToDeviceGallery) {
          val svc = com.example.service.KidsGuardAccessibilityService.instance
          if (svc != null) {
            saveBitmapToGallery(svc.applicationContext, realPixelBitmap, "KidsGuard_${System.currentTimeMillis()}")
          }
        }
        onCaptured(item)
      }
      return
    }
    val textOnlyItem = CapturedScreenItem(
      activeAppName = appName,
      statusNote = "Активный контент ($appName)",
      base64Bitmap = null
    )
    addCapturedItem(textOnlyItem)
    onCaptured(textOnlyItem)
  }

  fun captureRealHardwareScreenshot(
    service: AccessibilityService,
    appName: String,
    saveToDeviceGallery: Boolean = false,
    onCaptured: (CapturedScreenItem) -> Unit = {}
  ) {
    triggerHardwareScreenCapture(appName, saveToDeviceGallery, onCaptured)
  }

  private fun emitLastRealBitmapIfAvailable(appName: String) {
    val realBmp = lastHardwareBitmap ?: latestProjectionBitmap ?: lastDecorViewBitmap
    if (realBmp != null && !realBmp.isRecycled) {
      val item = CapturedScreenItem(
        activeAppName = appName,
        statusNote = "Реальный экран ($appName)",
        base64Bitmap = bitmapToBase64(realBmp)
      )
      deliverToPendingCallbacks(item)
    } else {
      pendingCallbacks.clear()
    }
  }

  private fun deliverToPendingCallbacks(item: CapturedScreenItem) {
    val callbacks = ArrayList(pendingCallbacks)
    pendingCallbacks.clear()
    for (cb in callbacks) {
      try {
        cb(item)
      } catch (_: Exception) {}
    }
  }

  fun recordScreenSnapshot(appName: String, note: String = "Снимок экрана активного приложения", base64: String? = null): CapturedScreenItem {
    val resolvedB64 = base64 ?: (lastHardwareBitmap ?: latestProjectionBitmap ?: lastDecorViewBitmap)
      ?.takeIf { !it.isRecycled }
      ?.let { bitmapToBase64(it) }
    val item = CapturedScreenItem(
      activeAppName = appName,
      statusNote = note,
      base64Bitmap = resolvedB64
    )
    if (!resolvedB64.isNullOrBlank()) {
      addCapturedItem(item)
    }
    return item
  }

  fun addCapturedItem(item: CapturedScreenItem) {
    val list = _capturedScreens.value.toMutableList()
    list.add(0, item)
    if (list.size > 30) {
      _capturedScreens.value = list.take(30)
    } else {
      _capturedScreens.value = list
    }
    DeviceControlDebugger.logEvent("СКРИНШОТ_ЭКРАНА", "Зафиксирован снимок: «${item.activeAppName}»", "SUCCESS")
  }

  fun clearForChild(childId: Long) {
    _capturedScreens.value = _capturedScreens.value.filterNot { it.childId == childId }
  }

  fun clearAllCapturedScreens() {
    _capturedScreens.value = emptyList()
  }

  fun bitmapToBase64(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    val scaled = if (bitmap.width > 480) {
      val ratio = 480f / bitmap.width
      Bitmap.createScaledBitmap(bitmap, 480, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    } else {
      bitmap
    }
    scaled.compress(Bitmap.CompressFormat.JPEG, 60, stream)
    return Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
  }

  /**
   * Saves a screenshot bitmap directly into the phone's main Gallery (DCIM/KidsGuard)
   * and returns the MediaStore Uri so it can be opened immediately.
   */
  fun saveBitmapToGalleryWithUri(context: Context, bitmap: Bitmap, title: String): Uri? {
    return try {
      val cleanTitle = title.replace(Regex("[^a-zA-Z0-9а-яА-Я_\\-]"), "_")
      val filename = "${cleanTitle}.jpg"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.MediaColumns.DISPLAY_NAME, filename)
          put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
          put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/KidsGuard")
          put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { os ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
          }
          values.clear()
          values.put(MediaStore.Images.Media.IS_PENDING, 0)
          resolver.update(uri, values, null, null)
          uri
        } else null
      } else {
        val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val subDir = File(dir, "KidsGuard").apply { mkdirs() }
        val file = File(subDir, filename)
        FileOutputStream(file).use { os ->
          bitmap.compress(Bitmap.CompressFormat.JPEG, 92, os)
        }
        MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/jpeg"), null)
        Uri.fromFile(file)
      }
    } catch (e: Exception) {
      Log.e(TAG, "saveBitmapToGalleryWithUri error: ${e.message}")
      null
    }
  }

  fun saveBitmapToGallery(context: Context, bitmap: Bitmap, title: String): Boolean {
    return saveBitmapToGalleryWithUri(context, bitmap, title) != null
  }
}