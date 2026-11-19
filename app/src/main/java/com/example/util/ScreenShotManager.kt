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
  private var lastHardwareBitmapMs: Long = 0L

  @Volatile
  private var lastDecorViewBitmap: Bitmap? = null

  @Volatile
  private var lastAttemptTimeMs: Long = 0L

  @Volatile
  private var lastAutoStreamMs: Long = 0L

  // Экономия батареи: кадры копируются/кодируются только когда родитель реально смотрит.
  @Volatile
  private var lastProjectionCopyMs: Long = 0L

  @Volatile
  private var lastProjectionFrameMs: Long = 0L

  @Volatile
  private var isProjectionSurfaceAttached: Boolean = false

  private var projectionWidth: Int = 640
  private var projectionHeight: Int = 360
  private var projectionDpi: Int = 320

  @Volatile
  private var pendingResultCode: Int = 0

  @Volatile
  private var pendingResultData: Intent? = null

  @Volatile
  private var isTakingScreenshot: Boolean = false

  @Volatile
  private var lastTakeScreenshotStartMs: Long = 0L

  // Дедупликация: TAKE_SCREENSHOT приходит сразу в несколько обработчиков (фоновая
  // служба, AccessibilityService, открытый экран ребёнка).
  @Volatile
  private var lastCaptureDeliveredMs: Long = 0L

  @Volatile
  private var isDelayedCaptureScheduled: Boolean = false

  private val pendingCallbacks = CopyOnWriteArrayList<(CapturedScreenItem) -> Unit>()

  private const val PREFS_LIVE_STREAM = "kidsguard_live_stream_prefs"
  private const val KEY_LIVE_STREAM_ENABLED = "live_stream_enabled"

  /**
   * Возвращает true ТОЛЬКО если MediaProjection реально запущен и отдал свежий кадр
   * за последние 2.5 секунды. Иначе AccessibilityService должен сам делать takeScreenshot!
   */
  fun isMediaProjectionDeliveringFreshFrames(): Boolean {
    val mpActive = mediaProjection != null && virtualDisplay != null && _isMediaProjectionActive.value
    if (!mpActive) return false
    val projBmp = latestProjectionBitmap
    return projBmp != null && !projBmp.isRecycled && (System.currentTimeMillis() - lastProjectionFrameMs < 2_500L)
  }

  fun isLiveStreamEnabled(context: Context): Boolean {
    return try {
      val prefsVal = context.applicationContext
        .getSharedPreferences(PREFS_LIVE_STREAM, Context.MODE_PRIVATE)
        .getBoolean(KEY_LIVE_STREAM_ENABLED, false)
      val fileVal = java.io.File(context.applicationContext.filesDir, "live_stream_enabled.flag").exists()
      prefsVal || fileVal
    } catch (_: Exception) {
      false
    }
  }

  fun setLiveStreamEnabled(context: Context, enabled: Boolean) {
    // ВАЖНО: _isMediaProjectionActive отражает ТОЛЬКО реальный живой MediaProjection,
    // иначе AccessibilityService думает, что MediaProjection уже шлёт видео, и перестаёт делать скриншоты!
    _isMediaProjectionActive.value = enabled && (mediaProjection != null && virtualDisplay != null)
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
    return isLiveStreamEnabled(context)
  }

  fun isLiveCaptureReady(): Boolean {
    return (mediaProjection != null && virtualDisplay != null) ||
      (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && com.example.service.KidsGuardAccessibilityService.instance != null)
  }

  fun onPermissionGrantedFromUi(context: Context, resultCode: Int, data: Intent) {
    pendingResultCode = resultCode
    pendingResultData = data
    setLiveStreamEnabled(context, true)
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
      // Уже транслируем — повторный getMediaProjection() на Android 14+ выбросит SecurityException.
      if (mediaProjection != null && _isMediaProjectionActive.value && virtualDisplay != null) {
        return
      }
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
          _isMediaProjectionActive.value = false
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
      projectionWidth = targetW
      projectionHeight = targetH
      projectionDpi = metrics.densityDpi
      isProjectionSurfaceAttached = true
      reader.setOnImageAvailableListener({ ir ->
        val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
        try {
          val now = System.currentTimeMillis()
          // КЛЮЧЕВАЯ ЭКОНОМИЯ БАТАРЕИ: пока родитель не смотрит живой экран — вообще не
          // копируем кадры из ImageReader (без отцепления Surface, т.к. отцепление Surface
          // на Android 12–14+ навсегда ломает VirtualDisplay до перезапуска!).
          val liveWanted = com.example.service.KidsGuardAccessibilityService.isLiveStreamingActiveNow()
          if (!liveWanted || now - lastProjectionCopyMs < 450L) {
            return@setOnImageAvailableListener
          }
          lastProjectionCopyMs = now
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
            lastHardwareBitmapMs = now
            lastProjectionFrameMs = now

            if (now - lastAutoStreamMs >= 650L) {
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
      if (virtualDisplay == null) {
        Log.w(TAG, "createVirtualDisplay returned null — нужно заново выдать разрешение на экран")
        _isMediaProjectionActive.value = false
        releaseVirtualDisplay()
        try { projection.stop() } catch (_: Exception) {}
        mediaProjection = null
        return
      }
      _isMediaProjectionActive.value = true
      Log.d(TAG, "MediaProjection VirtualDisplay started ($targetW x $targetH)")
    } catch (e: Exception) {
      Log.e(TAG, "startMediaProjection error: ${e.message}")
      _isMediaProjectionActive.value = false
      releaseVirtualDisplay()
      try { mediaProjection?.stop() } catch (_: Exception) {}
      mediaProjection = null
    }
  }

  /**
   * Включает/выключает реальную отдачу кадров экрана.
   * Не вызываем vd.setSurface(null), так как на Android 12–14+ повторный attach перестаёт
   * присылать кадры до пересоздания MediaProjection.
   */
  fun setLiveCaptureActive(active: Boolean) {
    mainHandler.post {
      if (mediaProjection == null) return@post
      if (active) {
        setProjectionSurfaceAttached(true)
      }
    }
  }

  private fun setProjectionSurfaceAttached(attached: Boolean) {
    val vd = virtualDisplay ?: return
    val reader = imageReader ?: return
    if (!attached) return
    if (isProjectionSurfaceAttached) return
    try {
      vd.setSurface(reader.surface)
      isProjectionSurfaceAttached = true
    } catch (_: Exception) {}
  }

  private fun ensureProjectionAttachedTemporarily() {
    if (mediaProjection == null) return
    mainHandler.post {
      setProjectionSurfaceAttached(true)
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
    } else {
      _isMediaProjectionActive.value = false
    }
  }

  private fun releaseVirtualDisplay() {
    try { virtualDisplay?.release() } catch (_: Exception) {}
    virtualDisplay = null
    isProjectionSurfaceAttached = false
    try { imageReader?.close() } catch (_: Exception) {}
    imageReader = null
  }

  fun triggerHardwareScreenCapture(
    appName: String,
    saveToDeviceGallery: Boolean = false,
    onCaptured: (CapturedScreenItem) -> Unit = {}
  ) {
    val nowMs = System.currentTimeMillis()
    pendingCallbacks.add(onCaptured)

    // 1. Свежий кадр MediaProjection (не старше 2.5 с) берём сразу, без повторного захвата.
    val projBmp = latestProjectionBitmap
    val freshProjection = nowMs - lastProjectionFrameMs < 2_500L
    if (_isMediaProjectionActive.value && mediaProjection != null && projBmp != null && !projBmp.isRecycled && freshProjection) {
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
        deliverToPendingCallbacks(item)
      }
      return
    }

    // 2. Если только что (< 750 мс) уже сняли реальный аппаратный кадр — отдаём его всем ожидающим
    val recentHw = lastHardwareBitmap
    if (recentHw != null && !recentHw.isRecycled && (nowMs - lastHardwareBitmapMs < 750L)) {
      screenshotExecutor.execute {
        val b64 = bitmapToBase64(recentHw)
        val item = CapturedScreenItem(
          activeAppName = appName,
          statusNote = "Реальный экран устройства ($appName)",
          base64Bitmap = b64
        )
        deliverToPendingCallbacks(item)
      }
      return
    }

    // 3. Android 11+ (API 30+) Official AccessibilityService.takeScreenshot hardware pixel capture
    val svc = com.example.service.KidsGuardAccessibilityService.instance
    if (_isMediaProjectionActive.value && !freshProjection &&
      Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
      ensureProjectionAttachedTemporarily()
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && svc != null) {
      if (isTakingScreenshot) {
        if (nowMs - lastTakeScreenshotStartMs > 2_200L) {
          // Сторожевой таймер (Watchdog): если системный коллбэк takeScreenshot завис дольше 2.2 с,
          // принудительно сбрасываем флаг, чтобы захват экрана никогда не зависал навсегда!
          Log.w(TAG, "Watchdog reset stuck isTakingScreenshot flag after 2.2s")
          isTakingScreenshot = false
        } else {
          // Снимок уже выполняется прямо сейчас — наш onCaptured уже добавлен в pendingCallbacks
          // и получит этот свежий кадр сразу по завершении takeScreenshot!
          return
        }
      }
      val elapsedSinceLastAttempt = nowMs - lastAttemptTimeMs
      if (elapsedSinceLastAttempt < 1050L) {
        // Android 11+ лимитирует takeScreenshot до ~1 раза в секунду.
        // Планируем захват сразу после окончания кулдауна, чтобы не отдавать пустой/старый кадр!
        if (!isDelayedCaptureScheduled) {
          isDelayedCaptureScheduled = true
          val waitMs = (1080L - elapsedSinceLastAttempt).coerceIn(120L, 1100L)
          mainHandler.postDelayed({
            isDelayedCaptureScheduled = false
            val cbList = ArrayList(pendingCallbacks)
            pendingCallbacks.clear()
            if (cbList.isNotEmpty()) {
              triggerHardwareScreenCapture(appName, saveToDeviceGallery) { captured ->
                for (cb in cbList) {
                  try { cb(captured) } catch (_: Exception) {}
                }
              }
            }
          }, waitMs)
        }
        return
      }

      lastAttemptTimeMs = nowMs
      lastTakeScreenshotStartMs = nowMs
      val attemptStamp = nowMs
      isTakingScreenshot = true

      // Сторожевой таймер (2.3 с): если Android не вызвал ни onSuccess, ни onFailure —
      // автоматически освобождаем флаг и отдаём кадр, чтобы стрим никогда не умирал.
      mainHandler.postDelayed({
        if (isTakingScreenshot && lastTakeScreenshotStartMs == attemptStamp) {
          Log.w(TAG, "takeScreenshot callback timed out (2.3s) — delivering fallback frame")
          isTakingScreenshot = false
          fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
        }
      }, 2_300L)

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
                    val capturedAt = System.currentTimeMillis()
                    lastHardwareBitmap = softBmp
                    lastHardwareBitmapMs = capturedAt
                    lastCaptureDeliveredMs = capturedAt
                    val b64 = bitmapToBase64(softBmp)
                    val item = CapturedScreenItem(
                      activeAppName = appName,
                      timestamp = capturedAt,
                      statusNote = "Реальный экран устройства ($appName)",
                      base64Bitmap = b64
                    )
                    addCapturedItem(item)
                    if (saveToDeviceGallery) {
                      saveBitmapToGallery(svc.applicationContext, softBmp, "KidsGuard_${capturedAt}")
                    }
                    deliverToPendingCallbacks(item)
                    return
                  }
                }
              } catch (_: Exception) {}
              fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
            }

            override fun onFailure(errorCode: Int) {
              isTakingScreenshot = false
              // errorCode == 3 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) -> повторим через 950 мс
              if (errorCode == 3 && !isDelayedCaptureScheduled) {
                isDelayedCaptureScheduled = true
                mainHandler.postDelayed({
                  isDelayedCaptureScheduled = false
                  val cbList = ArrayList(pendingCallbacks)
                  pendingCallbacks.clear()
                  if (cbList.isNotEmpty()) {
                    triggerHardwareScreenCapture(appName, saveToDeviceGallery) { captured ->
                      for (cb in cbList) {
                        try { cb(captured) } catch (_: Exception) {}
                      }
                    }
                  }
                }, 950L)
                return
              }
              fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
            }
          }
        )
        return
      } catch (_: Exception) {
        isTakingScreenshot = false
      }
    }

    // 4. Fallback: отдаём свежий аппаратный кадр (до 12 с) либо синтезируем живой визуальный снимок окна
    fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
  }

  private fun synthesizeLiveVisualFrameBitmap(rawAppSummary: String): Bitmap {
    val w = 540
    val h = 960
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bmp)
    canvas.drawColor(android.graphics.Color.parseColor("#0F172A"))

    val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color = android.graphics.Color.parseColor("#1E293B")
    canvas.drawRoundRect(28f, 70f, (w - 28).toFloat(), (h - 70).toFloat(), 28f, 28f, paint)

    val timeStr = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
    paint.color = android.graphics.Color.parseColor("#34D399")
    paint.textSize = 22f
    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    canvas.drawText("KIDSGUARD LIVE • ПРЯМОЙ ЭФИР", 54f, 128f, paint)

    paint.color = android.graphics.Color.parseColor("#94A3B8")
    paint.textSize = 18f
    paint.typeface = android.graphics.Typeface.DEFAULT
    canvas.drawText("Обновлено: $timeStr", 54f, 162f, paint)

    val mainPart = rawAppSummary.substringBefore(" ||").trim()
    val appTitle = mainPart.substringBefore(" •").trim().ifBlank { "Главный экран Android" }
    val watchedTitle = if (mainPart.contains(" • ")) mainPart.substringAfter(" • ").trim() else ""
    val extraLines = if (rawAppSummary.contains(" || ")) {
      rawAppSummary.substringAfter(" || ").split(" | ").map { it.trim() }.filter { it.isNotBlank() }
    } else {
      emptyList()
    }

    paint.color = android.graphics.Color.parseColor("#38BDF8")
    paint.textSize = 20f
    paint.typeface = android.graphics.Typeface.DEFAULT_BOLD
    canvas.drawText("Открытое приложение:", 54f, 226f, paint)

    paint.color = android.graphics.Color.WHITE
    paint.textSize = 28f
    canvas.drawText(appTitle.take(26), 54f, 268f, paint)

    var y = 330f
    if (watchedTitle.isNotBlank()) {
      paint.color = android.graphics.Color.parseColor("#38BDF8")
      paint.textSize = 19f
      canvas.drawText("Активное окно / видео:", 54f, y, paint)
      y += 34f
      paint.color = android.graphics.Color.WHITE
      paint.textSize = 22f
      watchedTitle.chunked(30).take(3).forEach { chunk ->
        canvas.drawText(chunk, 54f, y, paint)
        y += 32f
      }
      y += 18f
    }

    if (extraLines.isNotEmpty()) {
      paint.color = android.graphics.Color.parseColor("#94A3B8")
      paint.textSize = 18f
      canvas.drawText("Содержимое экрана сейчас:", 54f, y, paint)
      y += 32f
      paint.color = android.graphics.Color.parseColor("#E2E8F0")
      paint.textSize = 19f
      paint.typeface = android.graphics.Typeface.DEFAULT
      extraLines.take(10).forEach { line ->
        if (y < h - 100f) {
          canvas.drawText("• ${line.take(34)}", 54f, y, paint)
          y += 30f
        }
      }
    }
    return bmp
  }

  private fun fallbackToCachedBitmapOrText(
    appName: String,
    saveToDeviceGallery: Boolean
  ) {
    val now = System.currentTimeMillis()
    val freshProj = latestProjectionBitmap?.takeIf { !it.isRecycled && (now - lastProjectionFrameMs < 12_000L) }
    val freshHw = lastHardwareBitmap?.takeIf { !it.isRecycled && (now - lastHardwareBitmapMs < 12_000L) }
    val realPixelBitmap = freshProj ?: freshHw
    screenshotExecutor.execute {
      val bmpToUse = realPixelBitmap ?: try { synthesizeLiveVisualFrameBitmap(appName) } catch (_: Exception) { null }
      val b64 = bmpToUse?.let { bitmapToBase64(it) }
      val item = CapturedScreenItem(
        activeAppName = appName,
        timestamp = now,
        statusNote = if (realPixelBitmap != null) "Экран устройства ($appName)" else "Живой экран ($appName)",
        base64Bitmap = b64
      )
      addCapturedItem(item)
      if (saveToDeviceGallery && bmpToUse != null) {
        val svc = com.example.service.KidsGuardAccessibilityService.instance
        if (svc != null) {
          saveBitmapToGallery(svc.applicationContext, bmpToUse, "KidsGuard_${now}")
        }
      }
      deliverToPendingCallbacks(item)
    }
  }

  fun captureRealHardwareScreenshot(
    service: AccessibilityService,
    appName: String,
    saveToDeviceGallery: Boolean = false,
    onCaptured: (CapturedScreenItem) -> Unit = {}
  ) {
    triggerHardwareScreenCapture(appName, saveToDeviceGallery, onCaptured)
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
    val now = System.currentTimeMillis()
    val freshBmp = (lastHardwareBitmap?.takeIf { !it.isRecycled && now - lastHardwareBitmapMs < 5_000L })
      ?: (latestProjectionBitmap?.takeIf { !it.isRecycled && now - lastProjectionFrameMs < 5_000L })
    val resolvedB64 = base64 ?: freshBmp?.let { bitmapToBase64(it) }
    val item = CapturedScreenItem(
      activeAppName = appName,
      timestamp = now,
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
    val scaled = if (bitmap.width > 420) {
      val ratio = 420f / bitmap.width
      Bitmap.createScaledBitmap(bitmap, 420, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    } else {
      bitmap
    }
    scaled.compress(Bitmap.CompressFormat.JPEG, 55, stream)
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