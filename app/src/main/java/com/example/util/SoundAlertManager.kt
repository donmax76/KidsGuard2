package com.example.util

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

private const val TAG = "SoundAlertManager"

object SoundAlertManager {
  private var activeMediaPlayer: MediaPlayer? = null

  @Volatile
  private var isPlaying = false

  @Volatile
  private var lastStartTimeMs = 0L

  private val _isSirenActive = MutableStateFlow(false)
  val isSirenActive: StateFlow<Boolean> = _isSirenActive.asStateFlow()

  fun setRemoteSirenState(active: Boolean) {
    _isSirenActive.value = active
  }

  /**
   * Plays a built-in, 100% identical loud emergency siren melody across all Android phones.
   * Synthesizes a 12-second dual-tone harmonic siren WAV in cache so it never depends on
   * missing or silent OEM ringtones, and boosts STREAM_ALARM volume to maximum.
   */
  @Synchronized
  fun playLoudAttentionSignal(context: Context) {
    val now = System.currentTimeMillis()
    // Deduplicate simultaneous triggers from BackgroundService + AccessibilityService + Repository
    if (isPlaying && (now - lastStartTimeMs) < 4500L) {
      Log.d(TAG, "Siren is already playing (${now - lastStartTimeMs}ms elapsed), skipping duplicate trigger")
      return
    }

    try {
      stopAlarmInternal(context)
      lastStartTimeMs = now

      val appContext = context.applicationContext
      val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

      // 1. Force maximum volume on ALARM and MUSIC streams so siren is always heard even if muted
      try {
        audioManager?.let { am ->
          if (am.ringerMode != AudioManager.RINGER_MODE_NORMAL) {
            try { am.ringerMode = AudioManager.RINGER_MODE_NORMAL } catch (_: Exception) {}
          }
          val maxAlarm = am.getStreamMaxVolume(AudioManager.STREAM_ALARM)
          if (maxAlarm > 0) {
            am.setStreamVolume(AudioManager.STREAM_ALARM, maxAlarm, 0)
          }
          val maxMusic = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
          if (maxMusic > 0) {
            val targetMusic = (maxMusic * 0.9f).toInt().coerceAtLeast(1)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, targetMusic, 0)
          }
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusReq = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_EXCLUSIVE)
              .setAudioAttributes(
                AudioAttributes.Builder()
                  .setUsage(AudioAttributes.USAGE_ALARM)
                  .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                  .build()
              )
              .build()
            am.requestAudioFocus(focusReq)
          } else {
            @Suppress("DEPRECATION")
            am.requestAudioFocus(null, AudioManager.STREAM_ALARM, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
          }
        }
      } catch (e: Exception) {
        Log.w(TAG, "Volume boost warning: ${e.message}")
      }

      // 2. Ensure the built-in universal Siren WAV file exists on disk
      val sirenFile = getOrCreateUniversalSirenWavFile(appContext)

      // 3. Play universal siren over USAGE_ALARM with audibility enforced
      val player = MediaPlayer()
      player.setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(AudioAttributes.USAGE_ALARM)
          .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
          .setFlags(AudioAttributes.FLAG_AUDIBILITY_ENFORCED)
          .build()
      )
      player.setDataSource(sirenFile.absolutePath)
      player.isLooping = false
      player.setVolume(1.0f, 1.0f)
      player.setOnCompletionListener { mp ->
        try { mp.release() } catch (_: Exception) {}
        synchronized(this) {
          if (activeMediaPlayer == mp) {
            activeMediaPlayer = null
            isPlaying = false
            _isSirenActive.value = false
          }
        }
      }
      player.setOnErrorListener { mp, _, _ ->
        try { mp.release() } catch (_: Exception) {}
        synchronized(this) {
          if (activeMediaPlayer == mp) {
            activeMediaPlayer = null
            isPlaying = false
            _isSirenActive.value = false
          }
        }
        true
      }
      player.prepare()
      player.start()

      activeMediaPlayer = player
      isPlaying = true
      _isSirenActive.value = true

      // 4. Synchronized rhythmic vibration pattern (10 seconds)
      triggerStrongVibration(appContext)

      Log.d(TAG, "Universal built-in KidsGuard siren started (12s @ max alarm volume)")
    } catch (e: Exception) {
      isPlaying = false
      _isSirenActive.value = false
      Log.e(TAG, "playLoudAttentionSignal error: ${e.message}")
    }
  }

  private fun triggerStrongVibration(appContext: Context) {
    try {
      val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      val pattern = longArrayOf(0, 600, 200, 600, 200, 800, 250, 800, 250, 1000, 250, 1000)
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator?.vibrate(VibrationEffect.createWaveform(pattern, -1))
      } else {
        @Suppress("DEPRECATION")
        vibrator?.vibrate(pattern, -1)
      }
    } catch (_: Exception) {}
  }

  /**
   * Generates a 12-second, 44.1 kHz, 16-bit mono WAV file containing a crystal-clear,
   * high-visibility two-tone emergency siren + harmonic chime that is identical on all phones.
   */
  private fun getOrCreateUniversalSirenWavFile(context: Context): File {
    val file = File(context.cacheDir, "kidsguard_universal_siren_v32.wav")
    if (file.exists() && file.length() > 100_000L) {
      return file
    }

    val sampleRate = 22050
    val durationSeconds = 12
    val numSamples = sampleRate * durationSeconds
    val pcmBytes = ByteArray(numSamples * 2)

    var phase1 = 0.0
    var phase2 = 0.0

    for (i in 0 until numSamples) {
      val t = i.toDouble() / sampleRate
      // Cycle every 0.70s between urgent High-Low siren sweep and triple-pulse alert
      val cyclePos = (t % 0.70) / 0.70
      val baseFreq = if (cyclePos < 0.5) {
        // Rising & falling emergency siren sweep between 780 Hz and 1480 Hz
        780.0 + 700.0 * (cyclePos * 2.0)
      } else {
        1480.0 - 700.0 * ((cyclePos - 0.5) * 2.0)
      }

      // Brief 15ms envelope dip at cycle boundary for sharp attention-grabbing articulation
      val env = when {
        cyclePos < 0.03 -> cyclePos / 0.03
        cyclePos > 0.97 -> (1.0 - cyclePos) / 0.03
        else -> 1.0
      }

      phase1 += 2.0 * PI * baseFreq / sampleRate
      phase2 += 2.0 * PI * (baseFreq * 1.5) / sampleRate

      // Fundamental + 3rd harmonic + fifth for maximum speaker projection on mobile phone speakers
      val rawWave = 0.68 * sin(phase1) + 0.22 * sin(phase2) + 0.10 * sin(phase1 * 2.0)
      val sampleVal = (rawWave * env * 31000.0).toInt().coerceIn(-32767, 32767).toShort()

      pcmBytes[i * 2] = (sampleVal.toInt() and 0xFF).toByte()
      pcmBytes[i * 2 + 1] = ((sampleVal.toInt() shr 8) and 0xFF).toByte()
    }

    FileOutputStream(file).use { fos ->
      val totalDataLen = pcmBytes.size + 36
      val byteRate = sampleRate * 2
      val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray(Charsets.US_ASCII))
        putInt(totalDataLen)
        put("WAVE".toByteArray(Charsets.US_ASCII))
        put("fmt ".toByteArray(Charsets.US_ASCII))
        putInt(16) // PCM chunk size
        putShort(1) // AudioFormat = 1 (PCM)
        putShort(1) // NumChannels = 1 (Mono)
        putInt(sampleRate)
        putInt(byteRate)
        putShort(2) // BlockAlign
        putShort(16) // BitsPerSample
        put("data".toByteArray(Charsets.US_ASCII))
        putInt(pcmBytes.size)
      }.array()
      fos.write(header)
      fos.write(pcmBytes)
      fos.flush()
    }
    return file
  }

  @Synchronized
  fun stopAlarm(context: Context) {
    lastStartTimeMs = 0L
    _isSirenActive.value = false
    stopAlarmInternal(context)
  }

  private fun stopAlarmInternal(context: Context) {
    try {
      activeMediaPlayer?.let {
        try {
          if (it.isPlaying) {
            it.stop()
          }
        } catch (_: Exception) {}
        try {
          it.release()
        } catch (_: Exception) {}
      }
      activeMediaPlayer = null
      isPlaying = false

      val appContext = context.applicationContext
      val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vm = appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        vm?.defaultVibrator
      } else {
        @Suppress("DEPRECATION")
        appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
      }
      vibrator?.cancel()
    } catch (e: Exception) {
      Log.e(TAG, "stopAlarm error: ${e.message}")
    }
  }
}
