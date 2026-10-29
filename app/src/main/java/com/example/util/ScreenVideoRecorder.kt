package com.example.util

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream

private const val TAG = "ScreenVideoRecorder"

data class SavedMediaResult(
  val success: Boolean,
  val uri: Uri? = null,
  val displayPath: String = "",
  val frameCount: Int = 0
)

object ScreenVideoRecorder {
  @Volatile
  private var isRecording = false
  private val recordedFrames = mutableListOf<Bitmap>()
  private val frameLock = Any()

  fun startRecording() {
    synchronized(frameLock) {
      recordedFrames.clear()
      isRecording = true
    }
    Log.d(TAG, "Video recording started")
  }

  fun addFrame(bitmap: Bitmap) {
    if (!isRecording) return
    synchronized(frameLock) {
      if (recordedFrames.size < 180) { // Up to 3 minutes at 1fps or 90s at 2fps
        val scaled = scaleEven(bitmap, 480, 800)
        recordedFrames.add(scaled)
      }
    }
  }

  fun isCurrentlyRecording(): Boolean = isRecording

  suspend fun stopAndSaveToGallery(context: Context, childName: String): SavedMediaResult = withContext(Dispatchers.IO) {
    val framesToEncode: List<Bitmap>
    synchronized(frameLock) {
      isRecording = false
      framesToEncode = recordedFrames.toList()
      recordedFrames.clear()
    }

    if (framesToEncode.isEmpty()) {
      return@withContext SavedMediaResult(
        success = false,
        displayPath = "Нет кадров для сохранения видео. Дождитесь появления экрана."
      )
    }

    val tempMp4 = File(context.cacheDir, "KidsGuard_Temp_${System.currentTimeMillis()}.mp4")
    val encodedOk = encodeBitmapsToMp4(framesToEncode, tempMp4)

    if (!encodedOk || !tempMp4.exists() || tempMp4.length() == 0L) {
      // Fallback: if hardware AVC encoder failed on a specific chipset, save the key frames to Gallery
      val firstFrame = framesToEncode.last()
      val savedUri = ScreenShotManager.saveBitmapToGalleryWithUri(
        context,
        firstFrame,
        "KidsGuard_ScreenRec_${System.currentTimeMillis()}"
      )
      return@withContext SavedMediaResult(
        success = savedUri != null,
        uri = savedUri,
        displayPath = "Галерея -> Альбом «KidsGuard» (DCIM/KidsGuard)",
        frameCount = framesToEncode.size
      )
    }

    val filename = "KidsGuard_Video_${System.currentTimeMillis()}.mp4"
    val savedUri = saveMp4FileToGallery(context, tempMp4, filename)
    try { tempMp4.delete() } catch (_: Exception) {}

    SavedMediaResult(
      success = savedUri != null,
      uri = savedUri,
      displayPath = "Галерея -> Альбом «KidsGuard» (DCIM/KidsGuard/$filename)",
      frameCount = framesToEncode.size
    )
  }

  private fun scaleEven(src: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
    val out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(out)
    canvas.drawColor(Color.parseColor("#020617"))
    val scale = minOf(
      targetWidth.toFloat() / src.width.coerceAtLeast(1),
      targetHeight.toFloat() / src.height.coerceAtLeast(1)
    )
    val sw = (src.width * scale).toInt().coerceAtLeast(1)
    val sh = (src.height * scale).toInt().coerceAtLeast(1)
    val scaled = Bitmap.createScaledBitmap(src, sw, sh, true)
    val left = (targetWidth - sw) / 2f
    val top = (targetHeight - sh) / 2f
    canvas.drawBitmap(scaled, left, top, null)
    return out
  }

  private fun encodeBitmapsToMp4(frames: List<Bitmap>, outputFile: File): Boolean {
    val width = 480
    val height = 800
    val mime = MediaFormat.MIMETYPE_VIDEO_AVC
    val frameRate = 2 // 2 frames per second
    var codec: MediaCodec? = null
    var muxer: MediaMuxer? = null
    var muxerStarted = false

    return try {
      val format = MediaFormat.createVideoFormat(mime, width, height).apply {
        setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        setInteger(MediaFormat.KEY_BIT_RATE, 1_200_000)
        setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
      }

      codec = MediaCodec.createEncoderByType(mime)
      codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
      codec.start()

      muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
      val bufferInfo = MediaCodec.BufferInfo()
      var trackIndex = -1
      var ptsUsec = 0L
      val frameDurationUsec = 1_000_000L / frameRate

      // Duplicate frames if needed so even a short recording is at least 4 frames (2 seconds)
      val fullFrames = if (frames.size < 4) {
        List(4) { idx -> frames[idx % frames.size] }
      } else {
        frames
      }

      for (frame in fullFrames) {
        val yuv = bitmapToNv12(frame, width, height)
        val inIndex = codec.dequeueInputBuffer(10_000)
        if (inIndex >= 0) {
          val inputBuffer = codec.getInputBuffer(inIndex)
          inputBuffer?.clear()
          inputBuffer?.put(yuv)
          codec.queueInputBuffer(inIndex, 0, yuv.size, ptsUsec, 0)
          ptsUsec += frameDurationUsec
        }

        trackIndex = drainEncoder(codec, muxer, bufferInfo, trackIndex, false).also {
          if (it >= 0) muxerStarted = true
        }
      }

      val eosIndex = codec.dequeueInputBuffer(10_000)
      if (eosIndex >= 0) {
        codec.queueInputBuffer(eosIndex, 0, 0, ptsUsec, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
      }
      trackIndex = drainEncoder(codec, muxer, bufferInfo, trackIndex, true).also {
        if (it >= 0) muxerStarted = true
      }

      true
    } catch (e: Exception) {
      Log.e(TAG, "encodeBitmapsToMp4 error: ${e.message}")
      false
    } finally {
      try { codec?.stop() } catch (_: Exception) {}
      try { codec?.release() } catch (_: Exception) {}
      if (muxerStarted) {
        try { muxer?.stop() } catch (_: Exception) {}
      }
      try { muxer?.release() } catch (_: Exception) {}
    }
  }

  private fun drainEncoder(
    codec: MediaCodec,
    muxer: MediaMuxer,
    bufferInfo: MediaCodec.BufferInfo,
    currentTrackIndex: Int,
    endOfStream: Boolean
  ): Int {
    var trackIndex = currentTrackIndex
    while (true) {
      val outIndex = codec.dequeueOutputBuffer(bufferInfo, 10_000)
      if (outIndex == MediaCodec.INFO_TRY_AGAIN_LATER) {
        if (!endOfStream) break
      } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
        if (trackIndex < 0) {
          trackIndex = muxer.addTrack(codec.outputFormat)
          muxer.start()
        }
      } else if (outIndex >= 0) {
        val encodedData = codec.getOutputBuffer(outIndex)
        if (encodedData != null && bufferInfo.size > 0 && trackIndex >= 0) {
          if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
            encodedData.position(bufferInfo.offset)
            encodedData.limit(bufferInfo.offset + bufferInfo.size)
            muxer.writeSampleData(trackIndex, encodedData, bufferInfo)
          }
        }
        codec.releaseOutputBuffer(outIndex, false)
        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
          break
        }
      } else {
        break
      }
    }
    return trackIndex
  }

  private fun bitmapToNv12(bitmap: Bitmap, width: Int, height: Int): ByteArray {
    val argb = IntArray(width * height)
    bitmap.getPixels(argb, 0, width, 0, 0, width, height)
    val yuv = ByteArray(width * height * 3 / 2)
    var yIndex = 0
    var uvIndex = width * height
    var index = 0

    for (j in 0 until height) {
      for (i in 0 until width) {
        val c = argb[index++]
        val r = (c shr 16) and 0xFF
        val g = (c shr 8) and 0xFF
        val b = c and 0xFF

        val y = ((66 * r + 129 * g + 25 * b + 128) shr 8) + 16
        val u = ((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128
        val v = ((112 * r - 94 * g - 18 * b + 128) shr 8) + 128

        yuv[yIndex++] = y.coerceIn(0, 255).toByte()
        if (j % 2 == 0 && i % 2 == 0 && uvIndex + 1 < yuv.size) {
          yuv[uvIndex++] = u.coerceIn(0, 255).toByte()
          yuv[uvIndex++] = v.coerceIn(0, 255).toByte()
        }
      }
    }
    return yuv
  }

  private fun saveMp4FileToGallery(context: Context, mp4File: File, filename: String): Uri? {
    return try {
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val values = ContentValues().apply {
          put(MediaStore.Video.Media.DISPLAY_NAME, filename)
          put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
          put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_DCIM + "/KidsGuard")
          put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { out ->
            FileInputStream(mp4File).use { input ->
              input.copyTo(out)
            }
          }
          values.clear()
          values.put(MediaStore.Video.Media.IS_PENDING, 0)
          resolver.update(uri, values, null, null)
          uri
        } else null
      } else {
        val dcim = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        val dir = File(dcim, "KidsGuard").apply { mkdirs() }
        val outFile = File(dir, filename)
        FileInputStream(mp4File).use { input ->
          FileOutputStream(outFile).use { out ->
            input.copyTo(out)
          }
        }
        MediaScannerConnection.scanFile(context, arrayOf(outFile.absolutePath), arrayOf("video/mp4"), null)
        Uri.fromFile(outFile)
      }
    } catch (e: Exception) {
      Log.e(TAG, "saveMp4FileToGallery error: ${e.message}")
      null
    }
  }
}
