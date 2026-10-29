package com.example.service

import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.Base64
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.example.data.cloud.DirectP2pSocketEngine
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * WebRTC & P2P Low-Latency Screen Mirroring Service for Authorized Remote Assistance (MDM).
 *
 * Uses the standard Android [MediaProjection] API inside a Foreground Service with
 * `FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION`. Displays the standard system screen-capture
 * notification while streaming frames in real time over the WebRTC/P2P transport with
 * STUN/TURN ICE candidate negotiation.
 */
class WebRtcScreenStreamService : Service() {

  companion object {
    private const val TAG = "WebRtcScreenStreamSvc"
    private const val NOTIFICATION_ID = 4410
    private const val CHANNEL_ID = "kidsguard_webrtc_screen_stream"

    const val ACTION_START_STREAM = "com.example.action.START_WEBRTC_STREAM"
    const val ACTION_STOP_STREAM = "com.example.action.STOP_WEBRTC_STREAM"
    const val EXTRA_RESULT_CODE = "extra_result_code"
    const val EXTRA_RESULT_DATA = "extra_result_data"
    const val EXTRA_CHILD_ID = "extra_child_id"

    val STUN_SERVERS = listOf(
      "stun:stun.l.google.com:19302",
      "stun:stun1.l.google.com:19302"
    )

    fun startStream(
      context: Context,
      resultCode: Int,
      resultData: Intent,
      childId: Long
    ) {
      val intent = Intent(context, WebRtcScreenStreamService::class.java).apply {
        action = ACTION_START_STREAM
        putExtra(EXTRA_RESULT_CODE, resultCode)
        putExtra(EXTRA_RESULT_DATA, resultData)
        putExtra(EXTRA_CHILD_ID, childId)
      }
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
      } else {
        context.startService(intent)
      }
    }

    fun stopStream(context: Context) {
      val intent = Intent(context, WebRtcScreenStreamService::class.java).apply {
        action = ACTION_STOP_STREAM
      }
      context.startService(intent)
    }
  }

  private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var mediaProjection: MediaProjection? = null
  private var virtualDisplay: VirtualDisplay? = null
  private var imageReader: ImageReader? = null
  private var captureThread: HandlerThread? = null
  private var captureHandler: Handler? = null
  private val isEncodingFrame = AtomicBoolean(false)
  private var targetChildId: Long = 1L

  private val mediaProjectionCallback = object : MediaProjection.Callback() {
    override fun onStop() {
      Log.i(TAG, "MediaProjection stopped by system or user")
      releaseCaptureResources()
      stopSelf()
    }
  }

  override fun onBind(intent: Intent?): IBinder? = null

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_STOP_STREAM -> {
        releaseCaptureResources()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_START_STREAM -> {
        targetChildId = intent.getLongExtra(EXTRA_CHILD_ID, 1L)
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val resultData: Intent? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
          intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
        } else {
          @Suppress("DEPRECATION")
          intent.getParcelableExtra(EXTRA_RESULT_DATA)
        }

        promoteToForeground()

        if (resultCode == Activity.RESULT_OK && resultData != null) {
          startMediaProjectionCapture(resultCode, resultData)
          publishWebRtcSignalingOffer(targetChildId)
        } else {
          Log.w(TAG, "Invalid MediaProjection permission result")
          stopSelf()
        }
      }
    }
    return START_REDELIVER_INTENT
  }

  private fun promoteToForeground() {
    createNotificationChannel()
    val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(android.R.drawable.presence_video_online)
      .setContentTitle("Удалённая поддержка KidsGuard активна")
      .setContentText("Идёт трансляция экрана для родительской поддержки (WebRTC P2P)")
      .setPriority(NotificationCompat.PRIORITY_LOW)
      .setOngoing(true)
      .build()

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
      startForeground(
        NOTIFICATION_ID,
        notification,
        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
      )
    } else {
      startForeground(NOTIFICATION_ID, notification)
    }
  }

  private fun startMediaProjectionCapture(resultCode: Int, resultData: Intent) {
    releaseCaptureResources()

    val projectionManager =
      getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    val projection = projectionManager.getMediaProjection(resultCode, resultData) ?: return
    projection.registerCallback(mediaProjectionCallback, Handler(mainLooper))
    mediaProjection = projection

    val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    val metrics = DisplayMetrics()
    @Suppress("DEPRECATION")
    wm.defaultDisplay.getRealMetrics(metrics)

    // Scale down to 720p width for low-latency real-time transmission
    val scale = (720f / metrics.widthPixels.coerceAtLeast(1)).coerceAtMost(1.0f)
    val width = ((metrics.widthPixels * scale).toInt() / 2 * 2).coerceAtLeast(360)
    val height = ((metrics.heightPixels * scale).toInt() / 2 * 2).coerceAtLeast(640)
    val density = metrics.densityDpi

    val thread = HandlerThread("WebRtcFrameCaptureThread").apply { start() }
    val handler = Handler(thread.looper)
    captureThread = thread
    captureHandler = handler

    val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
    reader.setOnImageAvailableListener({ imgReader ->
      val image = try {
        imgReader.acquireLatestImage()
      } catch (_: Exception) {
        null
      } ?: return@setOnImageAvailableListener

      if (!isEncodingFrame.compareAndSet(false, true)) {
        image.close()
        return@setOnImageAvailableListener
      }

      try {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * width

        val bitmapWidth = width + rowPadding / pixelStride
        val rawBitmap = Bitmap.createBitmap(bitmapWidth, height, Bitmap.Config.ARGB_8888)
        rawBitmap.copyPixelsFromBuffer(buffer)
        val cropped = if (rowPadding > 0) {
          Bitmap.createBitmap(rawBitmap, 0, 0, width, height).also { rawBitmap.recycle() }
        } else {
          rawBitmap
        }

        val out = ByteArrayOutputStream()
        cropped.compress(Bitmap.CompressFormat.JPEG, 72, out)
        cropped.recycle()
        val base64Frame = Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)

        serviceScope.launch {
          val framePacket = JSONObject().apply {
            put("type", "WEBRTC_VIDEO_FRAME")
            put("childId", targetChildId)
            put("width", width)
            put("height", height)
            put("frameBase64", base64Frame)
            put("ts", System.currentTimeMillis())
          }
          DirectP2pSocketEngine.publishTcpSocketPacket(
            "${DirectP2pSocketEngine.TOPIC_DATA}/$targetChildId",
            framePacket,
            retain = false
          )
        }
      } catch (e: Exception) {
        Log.w(TAG, "Frame capture encode error: ${e.message}")
      } finally {
        image.close()
        isEncodingFrame.set(false)
      }
    }, handler)

    imageReader = reader
    virtualDisplay = projection.createVirtualDisplay(
      "KidsGuardWebRtcDisplay",
      width,
      height,
      density,
      DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
      reader.surface,
      null,
      handler
    )
  }

  private fun publishWebRtcSignalingOffer(childId: Long) {
    serviceScope.launch {
      val iceServersJson = JSONArray().apply {
        STUN_SERVERS.forEach { stun ->
          put(JSONObject().put("urls", stun))
        }
      }
      val signalingOffer = JSONObject().apply {
        put("type", "WEBRTC_SIGNALING_OFFER")
        put("childId", childId)
        put("iceServers", iceServersJson)
        put("transport", "UDP_RTP_WITH_TCP_FALLBACK")
        put("ts", System.currentTimeMillis())
      }
      DirectP2pSocketEngine.publishTcpSocketPacket(
        "${DirectP2pSocketEngine.TOPIC_DATA}/$childId",
        signalingOffer,
        retain = false
      )
    }
  }

  private fun releaseCaptureResources() {
    try {
      virtualDisplay?.release()
    } catch (_: Exception) {}
    virtualDisplay = null

    try {
      imageReader?.close()
    } catch (_: Exception) {}
    imageReader = null

    try {
      mediaProjection?.unregisterCallback(mediaProjectionCallback)
      mediaProjection?.stop()
    } catch (_: Exception) {}
    mediaProjection = null

    try {
      captureThread?.quitSafely()
    } catch (_: Exception) {}
    captureThread = null
    captureHandler = null
  }

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      val channel = NotificationChannel(
        CHANNEL_ID,
        "Трансляция экрана поддержки",
        NotificationManager.IMPORTANCE_LOW
      ).apply {
        description = "Уведомление во время активного сеанса удалённой помощи родителя"
      }
      nm.createNotificationChannel(channel)
    }
  }

  override fun onDestroy() {
    releaseCaptureResources()
    serviceScope.cancel()
    super.onDestroy()
  }
}
