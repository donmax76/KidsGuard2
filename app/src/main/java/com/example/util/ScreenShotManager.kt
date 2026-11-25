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
  private val screenshotExecutor = Executors.newFixedThreadPool(2)
  private val takeScreenshotCallbackExecutor = Executors.newSingleThreadExecutor()

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
      val bootVal = BootPrefs.isLiveStreamGranted(context)
      val enabled = prefsVal || fileVal || bootVal
      if (enabled && !bootVal) {
        BootPrefs.setLiveStreamGranted(context, true)
      }
      enabled
    } catch (_: Exception) {
      false
    }
  }

  fun setLiveStreamEnabled(context: Context, enabled: Boolean) {
    // ВАЖНО: _isMediaProjectionActive отражает ТОЛЬКО реальный живой MediaProjection,
    // иначе AccessibilityService думает, что MediaProjection уже шлёт видео, и перестаёт делать скриншоты!
    _isMediaProjectionActive.value = enabled && (mediaProjection != null && virtualDisplay != null)
    try {
      BootPrefs.setLiveStreamGranted(context, enabled)
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
      val liveWantedInitially = com.example.service.KidsGuardAccessibilityService.isLiveStreamingActiveNow()
      isProjectionSurfaceAttached = liveWantedInitially
      reader.setOnImageAvailableListener({ ir ->
        val img = try { ir.acquireLatestImage() } catch (_: Exception) { null } ?: return@setOnImageAvailableListener
        try {
          val now = System.currentTimeMillis()
          val liveWanted = com.example.service.KidsGuardAccessibilityService.isLiveStreamingActiveNow() ||
            pendingCallbacks.isNotEmpty()
          if (!liveWanted) {
            setProjectionSurfaceAttached(false)
            return@setOnImageAvailableListener
          }
          if (now - lastProjectionCopyMs < 450L) {
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

            if (pendingCallbacks.isNotEmpty()) {
              screenshotExecutor.execute {
                try {
                  val b64 = bitmapToBase64(cropped)
                  val item = CapturedScreenItem(
                    activeAppName = "Экран устройства",
                    timestamp = now,
                    statusNote = "Живой кадр экрана",
                    base64Bitmap = b64
                  )
                  addCapturedItem(item)
                  deliverToPendingCallbacks(item)
                } catch (_: Exception) {}
              }
            }

            if (com.example.service.KidsGuardAccessibilityService.isLiveStreamingActiveNow() && now - lastAutoStreamMs >= 650L) {
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
        if (liveWantedInitially) reader.surface else null,
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
      Log.d(TAG, "MediaProjection VirtualDisplay started ($targetW x $targetH, surfaceAttached=$liveWantedInitially)")
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
   * Когда родитель не смотрит прямой эфир (active = false), отсоединяем Surface (`vd.setSurface(null)`),
   * чтобы системный композитор SurfaceFlinger вообще не рендерил 60 кадров/сек в ImageReader (0% расхода батареи!).
   */
  fun setLiveCaptureActive(active: Boolean) {
    mainHandler.post {
      if (mediaProjection == null) return@post
      setProjectionSurfaceAttached(active)
    }
  }

  private fun setProjectionSurfaceAttached(attached: Boolean) {
    val vd = virtualDisplay ?: return
    val reader = imageReader ?: return
    if (attached) {
      if (isProjectionSurfaceAttached) return
      try {
        vd.setSurface(reader.surface)
        isProjectionSurfaceAttached = true
      } catch (_: Exception) {}
    } else {
      if (!isProjectionSurfaceAttached) return
      try {
        vd.setSurface(null)
        isProjectionSurfaceAttached = false
      } catch (_: Exception) {}
    }
  }

  private fun ensureProjectionAttachedTemporarily() {
    if (mediaProjection == null) return
    mainHandler.post {
      setProjectionSurfaceAttached(true)
      mainHandler.postDelayed({
        if (!com.example.service.KidsGuardAccessibilityService.isLiveStreamingActiveNow() && pendingCallbacks.isEmpty()) {
          setProjectionSurfaceAttached(false)
        }
      }, 2_200L)
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
    try { virtualDisplay?.setSurface(null) } catch (_: Exception) {}
    try { virtualDisplay?.release() } catch (_: Exception) {}
    virtualDisplay = null
    isProjectionSurfaceAttached = false
    try { imageReader?.close() } catch (_: Exception) {}
    imageReader = null
  }

  @Volatile
  private var lastHardwareBase64: String? = null

  @Volatile
  private var lastDiskFrameSaveMs: Long = 0L

  private const val CACHED_FRAME_FILE = "kidsguard_last_screen_frame.b64"

  private fun saveLastBase64ToDiskIfNeeded(context: Context?, b64: String) {
    if (b64.isBlank()) return
    lastHardwareBase64 = b64
    val now = System.currentTimeMillis()
    if (now - lastDiskFrameSaveMs < 20_000L) return
    val ctx = context?.applicationContext ?: com.example.data.cloud.DirectP2pSocketEngine.appContext ?: return
    lastDiskFrameSaveMs = now
    try {
      File(ctx.filesDir, CACHED_FRAME_FILE).writeText(b64)
    } catch (_: Exception) {}
  }

  private fun loadLastBase64FromDisk(context: Context?): String? {
    lastHardwareBase64?.takeIf { it.isNotBlank() }?.let { return it }
    val ctx = context?.applicationContext
      ?: com.example.service.KidsGuardAccessibilityService.instance?.applicationContext
      ?: com.example.data.cloud.DirectP2pSocketEngine.appContext
      ?: return null
    return try {
      val f = File(ctx.filesDir, CACHED_FRAME_FILE)
      if (f.exists()) {
        f.readText().trim().takeIf { it.isNotBlank() }?.also {
          lastHardwareBase64 = it
        }
      } else null
    } catch (_: Exception) {
      null
    }
  }

  fun triggerHardwareScreenCapture(
    appName: String,
    saveToDeviceGallery: Boolean = false,
    onCaptured: ((CapturedScreenItem) -> Unit)? = null
  ) {
    val nowMs = System.currentTimeMillis()
    if (onCaptured != null) {
      pendingCallbacks.add(onCaptured)
    }

    // 1. Свежий кадр MediaProjection (не старше 2.5 с) берём сразу, без повторного захвата.
    val projBmp = latestProjectionBitmap
    val freshProjection = nowMs - lastProjectionFrameMs < 2_500L
    if (_isMediaProjectionActive.value && mediaProjection != null && projBmp != null && !projBmp.isRecycled && freshProjection) {
      screenshotExecutor.execute {
        val b64 = bitmapToBase64(projBmp)
        val svc = com.example.service.KidsGuardAccessibilityService.instance
        saveLastBase64ToDiskIfNeeded(svc?.applicationContext, b64)
        val item = CapturedScreenItem(
          activeAppName = appName,
          statusNote = "Живой видеопоток MediaProjection ($appName)",
          base64Bitmap = b64
        )
        addCapturedItem(item)
        if (saveToDeviceGallery && svc != null) {
          saveBitmapToGallery(svc.applicationContext, projBmp, "KidsGuard_${System.currentTimeMillis()}")
        }
        deliverToPendingCallbacks(item)
      }
      return
    }

    // 2. Если только что (< 850 мс) уже сняли реальный аппаратный кадр — сразу отдаём его всем ожидающим
    val recentHw = lastHardwareBitmap
    if (recentHw != null && !recentHw.isRecycled && (nowMs - lastHardwareBitmapMs < 850L)) {
      screenshotExecutor.execute {
        val b64 = lastHardwareBase64?.takeIf { it.isNotBlank() } ?: bitmapToBase64(recentHw)
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
          Log.w(TAG, "Watchdog reset stuck isTakingScreenshot flag after 2.2s")
          isTakingScreenshot = false
        } else {
          return
        }
      }
      val elapsedSinceLastAttempt = nowMs - lastAttemptTimeMs
      if (elapsedSinceLastAttempt < 1180L) {
        // Android 11+ лимитирует takeScreenshot до ~1 раза в 1.15–1.25 секунды.
        // Обязательно выполняем захват сразу после окончания кулдауна (даже если прямой эфир выключен)!
        if (!isDelayedCaptureScheduled) {
          isDelayedCaptureScheduled = true
          val waitMs = (1220L - elapsedSinceLastAttempt).coerceIn(220L, 1220L)
          mainHandler.postDelayed({
            isDelayedCaptureScheduled = false
            triggerHardwareScreenCapture(appName, saveToDeviceGallery, null)
          }, waitMs)
        }
        return
      }

      lastAttemptTimeMs = nowMs
      lastTakeScreenshotStartMs = nowMs
      val attemptStamp = nowMs
      isTakingScreenshot = true

      // Сторожевой таймер (2.5 с): если Android не вызвал ни onSuccess, ни onFailure —
      // освобождаем флаг и отдаём кадр.
      mainHandler.postDelayed({
        if (isTakingScreenshot && lastTakeScreenshotStartMs == attemptStamp) {
          isTakingScreenshot = false
          fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
        }
      }, 2_500L)

      try {
        svc.takeScreenshot(
          Display.DEFAULT_DISPLAY,
          takeScreenshotCallbackExecutor,
          object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
              val finishedAt = System.currentTimeMillis()
              lastAttemptTimeMs = finishedAt
              isTakingScreenshot = false
              var softBmp: Bitmap? = null
              val hwBuffer = try { screenshot.hardwareBuffer } catch (_: Exception) { null }
              try {
                if (hwBuffer != null) {
                  val colorSpace = try { screenshot.colorSpace } catch (_: Exception) { null }
                  val wrapped = Bitmap.wrapHardwareBuffer(hwBuffer, colorSpace)
                  if (wrapped != null) {
                    // КРИТИЧНО: копируем пиксели ДО закрытия hwBuffer!
                    softBmp = wrapped.copy(Bitmap.Config.ARGB_8888, false)
                    wrapped.recycle()
                  }
                }
              } catch (e: Exception) {
                Log.e(TAG, "HardwareBuffer copy error: ${e.message}")
              } finally {
                try { hwBuffer?.close() } catch (_: Exception) {}
              }

              val capturedBmp = softBmp
              if (capturedBmp != null) {
                lastHardwareBitmap = capturedBmp
                lastHardwareBitmapMs = finishedAt
                lastCaptureDeliveredMs = finishedAt
                screenshotExecutor.execute {
                  val b64 = bitmapToBase64(capturedBmp)
                  saveLastBase64ToDiskIfNeeded(svc.applicationContext, b64)
                  val item = CapturedScreenItem(
                    activeAppName = appName,
                    timestamp = finishedAt,
                    statusNote = "Реальный экран устройства ($appName)",
                    base64Bitmap = b64
                  )
                  addCapturedItem(item)
                  if (saveToDeviceGallery) {
                    saveBitmapToGallery(svc.applicationContext, capturedBmp, "KidsGuard_${finishedAt}")
                  }
                  if (pendingCallbacks.isNotEmpty()) {
                    deliverToPendingCallbacks(item)
                  }
                  if (b64.isNotBlank()) {
                    val cid = com.example.data.cloud.DirectP2pSocketEngine.thisDeviceBoundChildId.takeIf { it > 0L } ?: 1L
                    com.example.data.cloud.DirectP2pSocketEngine.pushDirectScreenFrame(cid, appName, b64)
                  }
                }
                return
              }
              fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
            }

            override fun onFailure(errorCode: Int) {
              lastAttemptTimeMs = System.currentTimeMillis()
              isTakingScreenshot = false
              // errorCode == 3 (ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) -> ждём 1250 мс, чтобы системный кулдаун истёк,
              // и ГАРАНТИРОВАННО делаем реальный снимок (НЕ очищая pendingCallbacks текстовой заглушкой!)
              if (errorCode == 3) {
                if (!isDelayedCaptureScheduled) {
                  isDelayedCaptureScheduled = true
                  mainHandler.postDelayed({
                    isDelayedCaptureScheduled = false
                    triggerHardwareScreenCapture(appName, saveToDeviceGallery, null)
                  }, 1_250L)
                }
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

    // 4. Fallback: отдаём последний реальный аппаратный кадр экрана (из ОЗУ или диска) без замены на текстовую заглушку
    fallbackToCachedBitmapOrText(appName, saveToDeviceGallery)
  }

  private fun fallbackToCachedBitmapOrText(
    appName: String,
    saveToDeviceGallery: Boolean
  ) {
    val now = System.currentTimeMillis()
    val proj = latestProjectionBitmap?.takeIf { !it.isRecycled }
    val hw = lastHardwareBitmap?.takeIf { !it.isRecycled }
    val realPixelBitmap = when {
      proj != null && hw != null -> if (lastProjectionFrameMs >= lastHardwareBitmapMs) proj else hw
      proj != null -> proj
      else -> hw
    }
    screenshotExecutor.execute {
      val b64 = realPixelBitmap?.let { bitmapToBase64(it) }
        ?: loadLastBase64FromDisk(null)
      val item = CapturedScreenItem(
        activeAppName = appName,
        timestamp = now,
        statusNote = "Экран устройства ($appName)",
        base64Bitmap = b64
      )
      if (!b64.isNullOrBlank()) {
        addCapturedItem(item)
        val cid = com.example.data.cloud.DirectP2pSocketEngine.thisDeviceBoundChildId.takeIf { it > 0L } ?: 1L
        com.example.data.cloud.DirectP2pSocketEngine.pushDirectScreenFrame(cid, appName, b64)
      }
      if (saveToDeviceGallery && realPixelBitmap != null) {
        val svc = com.example.service.KidsGuardAccessibilityService.instance
        if (svc != null) {
          saveBitmapToGallery(svc.applicationContext, realPixelBitmap, "KidsGuard_${now}")
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
    val resolvedB64 = base64 ?: freshBmp?.let { bitmapToBase64(it) } ?: loadLastBase64FromDisk(null)
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
    if (list.size > 12) {
      _capturedScreens.value = list.take(12)
    } else {
      _capturedScreens.value = list
    }
  }

  fun clearForChild(childId: Long) {
    _capturedScreens.value = _capturedScreens.value.filterNot { it.childId == childId }
  }

  fun clearAllCapturedScreens() {
    _capturedScreens.value = emptyList()
  }

  fun bitmapToBase64(bitmap: Bitmap): String {
    val stream = ByteArrayOutputStream()
    val targetWidth = 480
    val scaled = if (bitmap.width > targetWidth) {
      val ratio = targetWidth.toFloat() / bitmap.width
      Bitmap.createScaledBitmap(bitmap, targetWidth, (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    } else {
      bitmap
    }
    scaled.compress(Bitmap.CompressFormat.JPEG, 65, stream)
    if (scaled !== bitmap) {
      try { scaled.recycle() } catch (_: Exception) {}
    }
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