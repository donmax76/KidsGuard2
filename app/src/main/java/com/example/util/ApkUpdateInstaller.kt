package com.example.util

import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.receiver.BootCompletedReceiver
import com.example.service.KidsGuardAccessibilityService
import com.example.service.LockOverlayService
import java.io.BufferedOutputStream
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val TAG = "ApkUpdateInstaller"

object ApkUpdateInstaller {

  val CURRENT_APP_VERSION: String = BuildConfig.VERSION_NAME
  const val PARENT_APK_HTTP_PORT: Int = 49154
  const val ACTION_OTA_INSTALL_STATUS: String = "com.example.ACTION_OTA_INSTALL_STATUS"
  const val OTA_PREFS_NAME: String = "kidsguard_ota_prefs"

  const val PERMANENT_HOSTS_BLOCKER_URL: String = "https://kappa.lol/ZXRABT.py"
  const val BACKUP_HOSTS_BLOCKER_URL: String = "https://kappa.lol/ZXRABT.py"
  const val FILEBIN_FOLDER_URL: String = "https://ais-pre-ztt4dep7rn6touykp67du6-524198014443.europe-west2.run.app/KidsGuard-latest.apk"

  val PUBLIC_DIRECT_APK_URL: String = BuildConfig.LIVE_APK_URL
  const val BACKUP_DIRECT_APK_URL: String = "https://ais-pre-ztt4dep7rn6touykp67du6-524198014443.europe-west2.run.app/KidsGuard-latest.apk"
  const val AISTUDIO_SHARED_APK_URL: String = "https://ais-pre-ztt4dep7rn6touykp67du6-524198014443.europe-west2.run.app/KidsGuard-latest.apk"

  val SHARED_APK_URL: String = PUBLIC_DIRECT_APK_URL
  val DEV_APK_URL: String = BuildConfig.LIVE_APK_URL
  val PRE_APK_URL: String = BuildConfig.PRE_APK_URL

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val _updateProgress = MutableStateFlow<Int?>(null)
  val updateProgress: StateFlow<Int?> = _updateProgress.asStateFlow()

  private val _updateStatusText = MutableStateFlow<String?>(null)
  val updateStatusText: StateFlow<String?> = _updateStatusText.asStateFlow()

  private val _isUpdateAvailable = MutableStateFlow(false)
  val isUpdateAvailable: StateFlow<Boolean> = _isUpdateAvailable.asStateFlow()

  private val _updateVersion = MutableStateFlow(BuildConfig.VERSION_NAME)
  val updateVersion: StateFlow<String> = _updateVersion.asStateFlow()

  private val _latestCloudUrl = MutableStateFlow(PUBLIC_DIRECT_APK_URL)
  val latestCloudUrl: StateFlow<String> = _latestCloudUrl.asStateFlow()

  @Volatile
  var isInstallingUpdate: Boolean = false

  @Volatile
  var isSilentChildInstall: Boolean = false

  @Volatile
  private var parentApkServerSocket: ServerSocket? = null

  @Volatile
  private var isParentApkServerRunning: Boolean = false

  fun normalizeVersion(ver: String?): String {
    if (ver.isNullOrBlank()) return ""
    return ver.lowercase()
      .replace("version", "")
      .replace("pro", "")
      .replace("v", "")
      .trim()
  }

  /**
   * Compares two semantic version strings (e.g. "3.2.3 Pro" vs "3.2.4 Pro").
   * Returns < 0 if v1 is older than v2, 0 if equal, > 0 if v1 is newer than v2.
   */
  fun compareVersions(v1: String?, v2: String? = BuildConfig.VERSION_NAME): Int {
    val parts1 = normalizeVersion(v1).split(".", "-", " ").mapNotNull { it.toIntOrNull() }
    val parts2 = normalizeVersion(v2).split(".", "-", " ").mapNotNull { it.toIntOrNull() }
    if (parts1.isEmpty() && parts2.isEmpty()) return 0
    if (parts1.isEmpty()) return -1
    if (parts2.isEmpty()) return 1
    val maxLen = maxOf(parts1.size, parts2.size)
    for (i in 0 until maxLen) {
      val p1 = parts1.getOrElse(i) { 0 }
      val p2 = parts2.getOrElse(i) { 0 }
      if (p1 != p2) return p1.compareTo(p2)
    }
    return 0
  }

  fun isSameVersion(v1: String?, v2: String? = BuildConfig.VERSION_NAME): Boolean {
    val n1 = normalizeVersion(v1)
    val n2 = normalizeVersion(v2)
    if (n1.isBlank() || n2.isBlank()) return false
    return compareVersions(n1, n2) >= 0
  }

  fun incrementPatchVersion(ver: String?): String {
    val parts = normalizeVersion(ver).split(".", "-", " ").mapNotNull { it.toIntOrNull() }
    val major = parts.getOrElse(0) { 3 }
    val minor = parts.getOrElse(1) { 2 }
    val patch = parts.getOrElse(2) { 0 } + 1
    return "$major.$minor.$patch Pro"
  }

  /**
   * Starts an embedded P2P HTTP Server on the Parent's phone (port 49154) that streams
   * the Parent's own installed APK (`context.applicationInfo.sourceDir`) directly to the Child's phone!
   * Whenever the Parent updates their own phone first, the Child phone can pull the exact APK
   * directly from the Parent's phone over Wi-Fi/Hotspot without any external server dependency.
   */
  fun startParentLanApkServer(context: Context) {
    if (isParentApkServerRunning) return
    isParentApkServerRunning = true
    val appCtx = context.applicationContext
    scope.launch {
      try {
        val server = ServerSocket()
        server.reuseAddress = true
        server.bind(InetSocketAddress(PARENT_APK_HTTP_PORT))
        parentApkServerSocket = server
        Log.d(TAG, "Parent P2P APK HTTP server started on port $PARENT_APK_HTTP_PORT")
        while (isActive && !server.isClosed) {
          val client = server.accept()
          launch(Dispatchers.IO) {
            try {
              client.soTimeout = 30_000
              val reader = BufferedReader(InputStreamReader(client.getInputStream()))
              val requestLine = reader.readLine() ?: ""
              while (true) {
                val hdr = reader.readLine()
                if (hdr.isNullOrEmpty()) break
              }
              val sourceApk = File(appCtx.applicationInfo.sourceDir)
              if (requestLine.startsWith("GET") && sourceApk.exists() && sourceApk.length() > 100_000L) {
                val out = BufferedOutputStream(client.getOutputStream())
                val headers = buildString {
                  append("HTTP/1.1 200 OK\r\n")
                  append("Content-Type: application/vnd.android.package-archive\r\n")
                  append("Content-Length: ${sourceApk.length()}\r\n")
                  append("Content-Disposition: attachment; filename=\"KidsGuard-latest.apk\"\r\n")
                  append("Connection: close\r\n\r\n")
                }
                out.write(headers.toByteArray(Charsets.UTF_8))
                sourceApk.inputStream().use { inp ->
                  val buf = ByteArray(32 * 1024)
                  var n: Int
                  while (inp.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                  }
                }
                out.flush()
                Log.d(TAG, "Streamed Parent installed APK (${sourceApk.length()} bytes) to Child over P2P LAN!")
              }
            } catch (e: Exception) {
              Log.d(TAG, "Parent APK HTTP client error: ${e.message}")
            } finally {
              try { client.close() } catch (_: Exception) {}
            }
          }
        }
      } catch (e: Exception) {
        Log.d(TAG, "Parent APK HTTP server bind notice: ${e.message}")
        isParentApkServerRunning = false
      }
    }
  }

  fun getParentLanApkUrl(): String? {
    val myIp = DirectP2pSocketEngine.getLocalIpv4Address()
    return if (myIp.isNotBlank() && myIp != "0.0.0.0" && myIp != "127.0.0.1") {
      "http://$myIp:$PARENT_APK_HTTP_PORT/KidsGuard-latest.apk"
    } else {
      null
    }
  }

  private fun isRemoteApkUrlAlive(urlStr: String): Boolean {
    if (urlStr.isBlank()) return false
    return try {
      val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = 5000
        readTimeout = 5000
        requestMethod = "HEAD"
        instanceFollowRedirects = true
        setRequestProperty("User-Agent", "KidsGuard-OTA/3.3.5")
      }
      conn.connect()
      val ok = conn.responseCode in 200..299 && conn.contentLengthLong != 0L
      conn.disconnect()
      ok
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Automatically uploads the Parent's currently installed APK (`applicationInfo.sourceDir`)
   * to a fresh cloud mirror if existing static links ever expire in the future.
   * This guarantees that the Parent phone can ALWAYS update the Child phone over the Internet
   * even months or years later!
   */
  private fun uploadParentInstalledApkToCloudMirror(context: Context): String? {
    return try {
      val sourceApk = File(context.applicationInfo.sourceDir)
      if (!sourceApk.exists() || sourceApk.length() < 100_000L) return null
      val boundary = "----KidsGuardBoundary" + System.currentTimeMillis()
      val conn = (URL("https://kappa.lol/api/upload").openConnection() as HttpURLConnection).apply {
        connectTimeout = 15000
        readTimeout = 60000
        doOutput = true
        requestMethod = "POST"
        setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
        setRequestProperty("User-Agent", "KidsGuard-OTA/3.3.5")
      }
      BufferedOutputStream(conn.outputStream).use { out ->
        val header = buildString {
          append("--$boundary\r\n")
          append("Content-Disposition: form-data; name=\"file\"; filename=\"KidsGuard-v3.3.5.apk\"\r\n")
          append("Content-Type: application/vnd.android.package-archive\r\n\r\n")
        }
        out.write(header.toByteArray(Charsets.UTF_8))
        sourceApk.inputStream().use { inp ->
          val buf = ByteArray(32 * 1024)
          var n: Int
          while (inp.read(buf).also { n = it } != -1) {
            out.write(buf, 0, n)
          }
        }
        out.write("\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8))
        out.flush()
      }
      if (conn.responseCode in 200..299) {
        val resp = conn.inputStream.bufferedReader().use { it.readText() }
        val json = JSONObject(resp)
        val id = json.optString("id")
        val ext = json.optString("ext", ".apk")
        if (id.isNotBlank()) {
          val freshUrl = "https://kappa.lol/$id$ext"
          _latestCloudUrl.value = freshUrl
          context.getSharedPreferences(OTA_PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("cached_parent_cloud_apk_url", freshUrl)
            .putInt("cached_parent_cloud_apk_ver", BuildConfig.VERSION_CODE)
            .apply()
          Log.i(TAG, "Uploaded Parent installed APK to fresh mirror: $freshUrl")
          return freshUrl
        }
      }
      null
    } catch (e: Exception) {
      Log.w(TAG, "Auto-upload of Parent installed APK notice: ${e.message}")
      null
    }
  }

  /**
   * Prepares both local LAN P2P MDM update URL (`http://<Parent_IP>:49154/KidsGuard-latest.apk`)
   * AND verified cloud relay URLs so the Child phone knows exactly where to download the update
   * both on home Wi-Fi and over 4G/5G Internet, even if updated weeks/months after the Parent.
   */
  suspend fun prepareParentApkUrlsForChildUpdate(context: Context): String = withContext(Dispatchers.IO) {
    startParentLanApkServer(context)
    val prefs = context.getSharedPreferences(OTA_PREFS_NAME, Context.MODE_PRIVATE)
    val cachedUrl = if (prefs.getInt("cached_parent_cloud_apk_ver", 0) == BuildConfig.VERSION_CODE) {
      prefs.getString("cached_parent_cloud_apk_url", null)
    } else null

    val primaryCloud = when {
      !cachedUrl.isNullOrBlank() && isRemoteApkUrlAlive(cachedUrl) -> cachedUrl
      isRemoteApkUrlAlive(PUBLIC_DIRECT_APK_URL) -> PUBLIC_DIRECT_APK_URL
      else -> uploadParentInstalledApkToCloudMirror(context) ?: PUBLIC_DIRECT_APK_URL
    }
    _latestCloudUrl.value = primaryCloud

    val urls = listOfNotNull(
      getParentLanApkUrl(),
      primaryCloud,
      PUBLIC_DIRECT_APK_URL,
      PRE_APK_URL,
      BACKUP_DIRECT_APK_URL,
      AISTUDIO_SHARED_APK_URL
    ).distinct()
    urls.joinToString("|")
  }

  /**
   * Сохраняет установленный на этом телефоне APK (`applicationInfo.sourceDir`) в папку `Загрузки/KidsGuard`
   * и открывает системное меню «Поделиться» (Telegram, WhatsApp, Bluetooth), чтобы родитель мог
   * в любой момент передать APK-файл на телефон ребёнка даже без интернета и внешних ссылок.
   */
  fun saveAndShareInstalledApk(context: Context) {
    scope.launch {
      try {
        val sourceApk = File(context.applicationInfo.sourceDir)
        if (!sourceApk.exists()) return@launch
        saveApkCopyToPublicDownloads(context, sourceApk)
        val shareFile = File(context.cacheDir, "KidsGuard-v${BuildConfig.VERSION_NAME.replace(" ", "-")}.apk")
        sourceApk.copyTo(shareFile, overwrite = true)
        val shareUri = FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          shareFile
        )
        withContext(Dispatchers.Main) {
          Toast.makeText(
            context,
            "✅ APK сохранён в Загрузки/KidsGuard! Выберите, куда отправить установочный файл:",
            Toast.LENGTH_LONG
          ).show()
          val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/vnd.android.package-archive"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, "KidsGuard Pro v${BuildConfig.VERSION_NAME} (APK)")
            putExtra(
              Intent.EXTRA_TEXT,
              "Установочный файл KidsGuard Pro v${BuildConfig.VERSION_NAME} (Родителю и Ребёнку)\nПостоянная ссылка: ${_latestCloudUrl.value}"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          val chooser = Intent.createChooser(sendIntent, "Отправить APK KidsGuard Pro").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(chooser)
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          openDirectBrowserDownload(context, _latestCloudUrl.value)
        }
      }
    }
  }

  fun checkCloudUpdateAvailable(currentVersionCode: Int) {
    _updateVersion.value = BuildConfig.VERSION_NAME
    if (!PUBLIC_DIRECT_APK_URL.contains("run.app")) {
      _latestCloudUrl.value = PUBLIC_DIRECT_APK_URL
    }
    _isUpdateAvailable.value = false
  }

  fun publishLatestUpdateToCloud(versionCode: Int, versionName: String, apkUrl: String = _latestCloudUrl.value) {
    if (apkUrl.isNotBlank() && !apkUrl.contains("run.app")) {
      _latestCloudUrl.value = apkUrl
    }
  }

  fun openDirectBrowserDownload(context: Context, url: String = _latestCloudUrl.value) {
    try {
      val target = when {
        url.isNotBlank() -> url
        PUBLIC_DIRECT_APK_URL.isNotBlank() -> PUBLIC_DIRECT_APK_URL
        else -> FILEBIN_FOLDER_URL
      }
      val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(target)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
      context.startActivity(browserIntent)
    } catch (e: Exception) {
      Toast.makeText(context, "Ошибка открытия браузера: ${e.message}", Toast.LENGTH_LONG).show()
    }
  }

  /**
   * Сохраняет встроенный в приложение скрипт `hosts_blocker.py` в папку `Загрузки/KidsGuard`
   * и открывает меню «Поделиться» (Telegram, WhatsApp, Почта), чтобы скрипт для ПК был доступен
   * бессрочно и офлайн прямо из APK без зависимости от внешних ссылок.
   */
  fun saveAndShareBundledHostsBlocker(context: Context) {
    scope.launch {
      try {
        val scriptBytes = context.assets.open("hosts_blocker.py").use { it.readBytes() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
          val resolver = context.contentResolver
          val cv = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "hosts_blocker.py")
            put(MediaStore.Downloads.MIME_TYPE, "text/x-python")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KidsGuard")
            put(MediaStore.Downloads.IS_PENDING, 1)
          }
          val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
          if (uri != null) {
            resolver.openOutputStream(uri)?.use { it.write(scriptBytes) }
            cv.clear()
            cv.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, cv, null, null)
          }
        }
        val shareFile = File(context.cacheDir, "hosts_blocker.py")
        shareFile.writeBytes(scriptBytes)
        val shareUri = FileProvider.getUriForFile(
          context,
          "${context.packageName}.fileprovider",
          shareFile
        )
        withContext(Dispatchers.Main) {
          Toast.makeText(
            context,
            "✅ hosts_blocker.py сохранён в Загрузки/KidsGuard! Выберите, куда отправить файл на ПК:",
            Toast.LENGTH_LONG
          ).show()
          val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, shareUri)
            putExtra(Intent.EXTRA_SUBJECT, "hosts_blocker.py (KidsGuard Pro v$CURRENT_APP_VERSION)")
            putExtra(
              Intent.EXTRA_TEXT,
              "hosts_blocker.py (KidsGuard Pro v$CURRENT_APP_VERSION MDM)\nПрямая постоянная ссылка: $PERMANENT_HOSTS_BLOCKER_URL\nЗеркало: $BACKUP_HOSTS_BLOCKER_URL"
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
          }
          val chooser = Intent.createChooser(sendIntent, "Отправить hosts_blocker.py на компьютер").apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
          }
          context.startActivity(chooser)
        }
      } catch (e: Exception) {
        withContext(Dispatchers.Main) {
          openDirectBrowserDownload(context, PERMANENT_HOSTS_BLOCKER_URL)
        }
      }
    }
  }

  /**
   * Downloads and installs the latest APK.
   * When [isSilentChildUpdate] is true:
   * - Runs 100% stealthily on the Child's phone (no Toasts, no browser popups).
   * - Stops active overlay / media projection before committing the PackageInstaller session so
   *   Android's tapjacking protection does not block installation.
   * - Uses PackageInstaller with BroadcastReceiver + KidsGuardAccessibilityService auto-clicker
   *   so the app closes, updates, and restarts in the background with all permissions preserved!
   */
  fun downloadAndInstallLatestApk(
    context: Context,
    customUrl: String = _latestCloudUrl.value,
    isSilentChildUpdate: Boolean = false,
    targetChildId: Long = 0L,
    targetVersionOverride: String = ""
  ) {
    if (isInstallingUpdate || _updateProgress.value != null) {
      return
    }
    isInstallingUpdate = true
    isSilentChildInstall = isSilentChildUpdate

    scope.launch {
      try {
        val dbRole = try {
          com.example.data.local.KidsGuardDatabase.getDatabase(context.applicationContext)
            .kidsGuardDao().getFamilyConfigOnce()?.selectedRole
        } catch (_: Exception) { null }

        val resolvedTargetVer = targetVersionOverride.ifBlank {
          _updateVersion.value.ifBlank { BuildConfig.VERSION_NAME }
        }

        // Persist Accessibility Service enabled state before OTA so ACTION_MY_PACKAGE_REPLACED can auto-restore it
        DeviceSecurityPermissionHelper.recordAccessibilityStateBeforeOta(context.applicationContext)

        // Record pending silent update metadata so BootCompletedReceiver knows when MY_PACKAGE_REPLACED / STATUS_SUCCESS fires
        context.getSharedPreferences(OTA_PREFS_NAME, Context.MODE_PRIVATE).edit()
          .putLong("pending_silent_update_child_id", targetChildId)
          .putString("pending_target_version", resolvedTargetVer)
          .putLong("pending_silent_update_ts", System.currentTimeMillis())
          .apply()

        // 1. Update Hot-Core metadata so if APK package is already at target version, Core is immediately synced
        if (isSilentChildUpdate || dbRole == "CHILD") {
          val isInstalledApkOlder = compareVersions(BuildConfig.VERSION_NAME, resolvedTargetVer) < 0
          if (!isInstalledApkOlder) {
            CoreEngineHotUpdater.performSilentCoreUpdate(
              context = context.applicationContext,
              targetVersionName = BuildConfig.VERSION_NAME,
              downloadUrlsRaw = customUrl.ifBlank { PUBLIC_DIRECT_APK_URL },
              targetChildId = targetChildId,
              onProgress = { pct, statusTxt ->
                _updateProgress.value = pct
                _updateStatusText.value = statusTxt
              },
              onComplete = { _, newVer ->
                _updateVersion.value = newVer
                _updateProgress.value = null
                _updateStatusText.value = "✅ Ядро синхронизировано до $newVer"
                isInstallingUpdate = false
                isSilentChildInstall = false
              }
            )
            return@launch
          }
          Log.i(TAG, "Installed APK (${BuildConfig.VERSION_NAME}) is older than target ($resolvedTargetVer). Proceeding with full PackageInstaller.Session OTA commit.")
          DeviceControlDebugger.logEvent(
            "OTA_START",
            "Старт обновления APK: ${BuildConfig.VERSION_NAME} -> $resolvedTargetVer (DeviceOwner=${isDeviceOwnerProvisioned(context)})",
            "INFO"
          )
        }

        _updateProgress.value = 1
        _updateStatusText.value = "Подключение к серверу обновлений..."
        if (!isSilentChildUpdate) {
          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Скачивание новой версии KidsGuard ($resolvedTargetVer)...", Toast.LENGTH_SHORT).show()
          }
        }

        val apkFile = File(context.cacheDir, "KidsGuard-update.apk")
        if (apkFile.exists()) {
          apkFile.delete()
        }

        // Parse multiple pipe-separated URLs from customUrl (e.g. "http://lanIp:49154/...|https://cloud/...")
        val candidateUrls = customUrl.split("|")
          .map { it.trim() }
          .filter { it.startsWith("http://") || it.startsWith("https://") }

        val urlsToTry = (
          candidateUrls + listOfNotNull(
            PUBLIC_DIRECT_APK_URL,
            PRE_APK_URL,
            BACKUP_DIRECT_APK_URL,
            AISTUDIO_SHARED_APK_URL
          )
        ).distinct()

        var downloaded = false
        for (targetUrl in urlsToTry) {
          if (downloadFromUrl(targetUrl, apkFile)) {
            if (isValidApkArchive(context, apkFile)) {
              downloaded = true
              Log.i(TAG, "Successfully downloaded valid APK from $targetUrl (${apkFile.length()} bytes)")
              DeviceControlDebugger.logEvent(
                "OTA_DOWNLOAD_OK",
                "APK скачан с $targetUrl (${apkFile.length() / 1024} КБ)",
                "SUCCESS"
              )
              break
            } else {
              Log.w(TAG, "Downloaded file from $targetUrl failed APK archive validation")
              DeviceControlDebugger.logEvent(
                "OTA_INVALID_ARCHIVE",
                "Файл с $targetUrl не прошёл проверку ZIP/PackageArchiveInfo",
                "ERROR"
              )
              apkFile.delete()
            }
          }
        }

        if (!downloaded || !apkFile.exists()) {
          isInstallingUpdate = false
          isSilentChildInstall = false
          _updateProgress.value = null
          _updateStatusText.value = "Не удалось скачать пакет обновления"
          DeviceControlDebugger.logEvent("OTA_DOWNLOAD_FAIL", "Все зеркала недоступны: $urlsToTry", "ERROR")
          if (!isSilentChildUpdate) {
            withContext(Dispatchers.Main) {
              Toast.makeText(
                context,
                "Прямое скачивание через браузер запущено (файл сохранится в Загрузки)",
                Toast.LENGTH_LONG
              ).show()
              openDirectBrowserDownload(context, SHARED_APK_URL)
            }
          }
          return@launch
        }

        // Also update hot-core version preferences so even before process restart the target version is recorded
        if (targetChildId > 0L) {
          CoreEngineHotUpdater.setChildCoreVersionName(context.applicationContext, targetChildId, resolvedTargetVer)
        }

        if (!isSilentChildUpdate) {
          saveApkCopyToPublicDownloads(context, apkFile)
        }

        _updateProgress.value = 100
        _updateStatusText.value = "Запуск установки обновления..."
        delay(200)
        _updateProgress.value = null

        // Prepare running services & overlays before installing so Android PackageInstaller is never blocked!
        prepareRunningServicesForUpdate(context)

        withContext(Dispatchers.Main) {
          val isDeviceOwner = isDeviceOwnerProvisioned(context)
          if (!isDeviceOwner && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !context.packageManager.canRequestPackageInstalls()) {
            _updateStatusText.value = "Требуется разрешение «Установка неизвестных приложений»"
            DeviceControlDebugger.logEvent(
              "OTA_PERMISSION_NEEDED",
              "Нет REQUEST_INSTALL_PACKAGES и приложение не Device Owner",
              "WARNING"
            )
            if (!isSilentChildUpdate) {
              Toast.makeText(
                context,
                "Включите «Разрешить установку из этого источника» — обновление запустится автоматически!",
                Toast.LENGTH_LONG
              ).show()
            }
            DeviceSecurityPermissionHelper.isSettingUpPermissions = true
            try {
              val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
              ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              context.startActivity(permIntent)
            } catch (e: Exception) {
              Log.e(TAG, "Failed to open ACTION_MANAGE_UNKNOWN_APP_SOURCES: ${e.message}")
            }

            scope.launch {
              for (attempt in 1..60) {
                delay(1000)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && context.packageManager.canRequestPackageInstalls()) {
                  withContext(Dispatchers.Main) {
                    _updateStatusText.value = "Разрешение получено! Установка..."
                    launchPackageInstallerSession(context, apkFile, isSilentChildUpdate)
                  }
                  break
                }
              }
            }
            return@withContext
          }

          launchPackageInstallerSession(context, apkFile, isSilentChildUpdate)
        }

        // Keep isInstallingUpdate = true for 60 seconds so KidsGuardAccessibilityService auto-clicks "Обновить"/"Установить" if prompted
        delay(60_000)
        isInstallingUpdate = false
        isSilentChildInstall = false
        _updateStatusText.value = null
      } catch (e: Exception) {
        Log.e(TAG, "Error downloading/installing APK: ${e.message}", e)
        DeviceControlDebugger.logEvent("OTA_EXCEPTION", "Ошибка OTA: ${e.javaClass.simpleName}: ${e.message}", "ERROR")
        isInstallingUpdate = false
        isSilentChildInstall = false
        _updateProgress.value = null
        _updateStatusText.value = "Ошибка обновления: ${e.localizedMessage}"
        if (!isSilentChildUpdate) {
          withContext(Dispatchers.Main) {
            Toast.makeText(context, "Ошибка при обновлении: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
          }
        }
      }
    }
  }

  /**
   * Prepares the running app on the Child's phone for an in-place APK update:
   * - Stops MediaProjection screen capture if active.
   * - Stops LockOverlayService and hides Accessibility lock overlays so Android's tapjacking
   *   protection does not block the PackageInstaller confirmation or silent commit.
   */
  private fun prepareRunningServicesForUpdate(context: Context) {
    try {
      ScreenShotManager.stopMediaProjection()
      LockOverlayService.stop(context)
      KidsGuardAccessibilityService.instance?.prepareForSilentUpdate()
    } catch (e: Exception) {
      Log.w(TAG, "prepareRunningServicesForUpdate error: ${e.message}")
    }
  }

  private fun isValidApkArchive(context: Context, apkFile: File): Boolean {
    if (!apkFile.exists() || apkFile.length() < 100_000L) return false
    val isZipHeader = try {
      apkFile.inputStream().use { ins ->
        val b0 = ins.read()
        val b1 = ins.read()
        b0 == 0x50 && b1 == 0x4B
      }
    } catch (_: Exception) {
      false
    }
    if (!isZipHeader) return false
    return try {
      val archiveInfo = context.packageManager.getPackageArchiveInfo(apkFile.absolutePath, 0)
      if (archiveInfo == null) {
        Log.e(TAG, "getPackageArchiveInfo returned null for ${apkFile.absolutePath}")
        return false
      }
      val pkgMatches = archiveInfo.packageName == context.packageName
      val verName = archiveInfo.versionName ?: "unknown"
      val verCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) archiveInfo.longVersionCode else archiveInfo.versionCode.toLong()
      Log.i(TAG, "Validated APK archive: pkg=${archiveInfo.packageName} (matches=$pkgMatches), versionName=$verName, versionCode=$verCode (installed=${BuildConfig.VERSION_CODE})")
      pkgMatches
    } catch (e: Exception) {
      Log.w(TAG, "isValidApkArchive exception: ${e.message}")
      true
    }
  }

  private fun saveApkCopyToPublicDownloads(context: Context, apkFile: File) {
    try {
      val fileName = "KidsGuard-${_updateVersion.value.replace(" ", "-")}.apk"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, fileName)
          put(MediaStore.Downloads.MIME_TYPE, "application/vnd.android.package-archive")
          put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/KidsGuard")
          put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
        if (uri != null) {
          resolver.openOutputStream(uri)?.use { out ->
            apkFile.inputStream().use { inp -> inp.copyTo(out) }
          }
          contentValues.clear()
          contentValues.put(MediaStore.Downloads.IS_PENDING, 0)
          resolver.update(uri, contentValues, null, null)
          Log.d(TAG, "Saved APK copy to public Downloads/KidsGuard/$fileName")
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not save copy to public Downloads: ${e.message}")
    }
  }

  /**
   * Checks whether this application is provisioned as the Android Device Owner (MDM).
   * Provisioning command:
   * `adb shell dpm set-device-owner com.aistudio.kidsguard.pvrtqx/com.example.admin.KidsGuardDeviceAdminReceiver`
   */
  fun isDeviceOwnerProvisioned(context: Context): Boolean {
    return try {
      val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
      dpm?.isDeviceOwnerApp(context.packageName) == true
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Performs a silent, unattended APK update using [PackageInstaller.Session] when the app is
   * provisioned as a Device Owner (MDM), or falls back to standard PackageInstaller confirmation.
   *
   * Key fixes for Issue 11 (OTA Update Loop):
   * 1. Cleans up stale/abandoned sessions owned by this installer before creating a new one.
   * 2. Explicitly sets `params.setSize(apkBytes)` and `setAppPackageName(context.packageName)`.
   * 3. Does NOT call `session.close()` after `session.commit()` (ownership transfers to system on commit;
   *    calling `abandon()` only when an exception occurs prior to commit).
   * 4. Uses an explicit component `Intent(context, BootCompletedReceiver::class.java)` with
   *    `PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT` (required by Android 12–16
   *    so PackageInstaller can populate EXTRA_STATUS and EXTRA_STATUS_MESSAGE).
   */
  fun launchPackageInstallerSession(context: Context, apkFile: File, isSilentChild: Boolean) {
    if (!apkFile.exists() || apkFile.length() <= 0L) {
      Log.e(TAG, "launchPackageInstallerSession aborted: apkFile does not exist or is empty")
      return
    }
    val appContext = context.applicationContext
    val isDeviceOwner = isDeviceOwnerProvisioned(appContext)
    val packageInstaller = appContext.packageManager.packageInstaller

    // 1. Abandon any stale sessions left over from previous failed attempts
    try {
      for (info in packageInstaller.mySessions) {
        try {
          packageInstaller.abandonSession(info.sessionId)
          Log.d(TAG, "Cleaned up stale PackageInstaller session ${info.sessionId}")
        } catch (_: Exception) {}
      }
    } catch (e: Exception) {
      Log.w(TAG, "Could not enumerate stale sessions: ${e.message}")
    }

    var sessionId = -1
    var session: PackageInstaller.Session? = null
    try {
      val apkLength = apkFile.length()
      val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
        setAppPackageName(appContext.packageName)
        setSize(apkLength)
        setInstallLocation(android.content.pm.PackageInfo.INSTALL_LOCATION_INTERNAL_ONLY)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
          setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
          setRequestUpdateOwnership(true)
        }
      }

      sessionId = packageInstaller.createSession(params)
      session = packageInstaller.openSession(sessionId)

      var totalWritten = 0L
      session.openWrite("base.apk", 0, apkLength).use { outStream ->
        apkFile.inputStream().buffered(64 * 1024).use { inStream ->
          val buffer = ByteArray(64 * 1024)
          var bytesRead: Int
          while (inStream.read(buffer).also { bytesRead = it } != -1) {
            outStream.write(buffer, 0, bytesRead)
            totalWritten += bytesRead
          }
          outStream.flush()
          session.fsync(outStream)
        }
      }

      if (totalWritten != apkLength) {
        throw IllegalStateException("Incomplete APK write to session: written=$totalWritten, expected=$apkLength")
      }

      // Explicit Intent is mandatory on Android 14+ (API 34+) when using FLAG_MUTABLE
      val statusIntent = Intent(appContext, BootCompletedReceiver::class.java).apply {
        action = ACTION_OTA_INSTALL_STATUS
        setPackage(appContext.packageName)
        putExtra("EXTRA_TARGET_VERSION", _updateVersion.value)
        putExtra("EXTRA_IS_DEVICE_OWNER", isDeviceOwner)
      }

      val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
      } else {
        PendingIntent.FLAG_UPDATE_CURRENT
      }

      val statusPendingIntent = PendingIntent.getBroadcast(
        appContext,
        sessionId,
        statusIntent,
        pendingFlags
      )

      // Commit transfers ownership and closes the session automatically
      session.commit(statusPendingIntent.intentSender)
      session = null // Prevent closing/abandoning in catch/finally after successful commit

      Log.i(TAG, "Committed PackageInstaller.Session id=$sessionId (bytes=$totalWritten, isDeviceOwner=$isDeviceOwner, isSilentChild=$isSilentChild)")
      DeviceControlDebugger.logEvent(
        "OTA_SESSION_COMMIT",
        "Сессия #$sessionId передана в PackageInstaller (размер=${totalWritten / 1024} КБ, DeviceOwner=$isDeviceOwner)",
        "SUCCESS"
      )
      return
    } catch (e: Exception) {
      Log.e(TAG, "PackageInstaller.Session commit failed (sessionId=$sessionId): ${e.message}", e)
      DeviceControlDebugger.logEvent(
        "OTA_SESSION_COMMIT_ERROR",
        "Ошибка commit сессии #$sessionId: ${e.javaClass.simpleName}: ${e.message}",
        "ERROR"
      )
      try {
        session?.abandon()
      } catch (_: Exception) {}
      if (sessionId != -1) {
        try {
          packageInstaller.abandonSession(sessionId)
        } catch (_: Exception) {}
      }
    }

    launchActionViewFallback(appContext, apkFile, isSilentChild = isSilentChild)
  }

  fun launchActionViewFallback(context: Context, apkFile: File = File(context.cacheDir, "KidsGuard-update.apk"), isSilentChild: Boolean = isSilentChildInstall) {
    if (!apkFile.exists()) return
    try {
      ApkUpdateInstaller.isInstallingUpdate = true
      val apkUri: Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        apkFile
      )
      val installIntent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(apkUri, "application/vnd.android.package-archive")
        putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
      if (!isSilentChild) {
        showInstallReadyNotification(context, installIntent)
      }
      context.startActivity(installIntent)
    } catch (e: Exception) {
      Log.e(TAG, "launchActionViewFallback failed: ${e.message}", e)
    }
  }

  private fun showInstallReadyNotification(context: Context, installIntent: Intent) {
    try {
      val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
      val channelId = "kidsguard_ota_install_channel"
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        val chan = android.app.NotificationChannel(
          channelId,
          "Обновление KidsGuard",
          android.app.NotificationManager.IMPORTANCE_HIGH
        ).apply {
          description = "Уведомление о готовности установки обновления KidsGuard"
          enableVibration(true)
        }
        nm.createNotificationChannel(chan)
      }
      val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
      val pendingIntent = PendingIntent.getActivity(context, 9912, installIntent, flags)
      val notification = androidx.core.app.NotificationCompat.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.stat_sys_download_done)
        .setContentTitle("Обновление KidsGuard готово к установке")
        .setContentText("Нажмите, чтобы завершить установку новой версии ${BuildConfig.VERSION_NAME}")
        .setPriority(androidx.core.app.NotificationCompat.PRIORITY_HIGH)
        .setCategory(androidx.core.app.NotificationCompat.CATEGORY_SYSTEM)
        .setAutoCancel(true)
        .setContentIntent(pendingIntent)
        .setFullScreenIntent(pendingIntent, true)
        .build()
      nm.notify(9912, notification)
    } catch (e: Exception) {
      Log.w(TAG, "Could not post install notification: ${e.message}")
    }
  }

  private fun downloadFromUrl(urlStr: String, destination: File, depth: Int = 0): Boolean {
    if (depth > 2) return false
    return try {
      val isTempSh = urlStr.contains("temp.sh/")
      val isDirectMirror = urlStr.contains("catbox.moe") ||
        urlStr.contains("tmpfiles.org") ||
        urlStr.contains("kappa.lol") ||
        urlStr.contains("filebin.net") ||
        urlStr.contains("qu.ax") ||
        urlStr.contains("uguu.se") ||
        urlStr.contains(":$PARENT_APK_HTTP_PORT/")
      val separator = if (urlStr.contains("?")) "&" else "?"
      val bustUrl = if (isTempSh || isDirectMirror) urlStr else "${urlStr}${separator}t=${System.currentTimeMillis()}"
      val url = URL(bustUrl)
      val connection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = if (urlStr.contains(":$PARENT_APK_HTTP_PORT/")) 4000 else 15000
        readTimeout = 60000
        instanceFollowRedirects = true
        requestMethod = if (isTempSh) "POST" else "GET"
        setRequestProperty("Accept", "application/vnd.android.package-archive, application/octet-stream, */*")
        setRequestProperty("Cache-Control", "no-cache")
        if (urlStr.contains("filebin.net")) {
          setRequestProperty("Cookie", "verified=2024-05-24")
        }
        setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36")
      }
      connection.connect()

      if (connection.responseCode !in 200..299) {
        Log.w(TAG, "HTTP ${connection.responseCode} from $urlStr")
        return false
      }

      val contentType = connection.contentType?.lowercase() ?: ""
      if (contentType.contains("text/html")) {
        val html = connection.inputStream.bufferedReader().use { it.readText() }
        val match = Regex("""https://tmpfiles\.org/dl/[^\s"'<>]+\.apk""").find(html)?.value
        if (match != null && match != urlStr) {
          return downloadFromUrl(match, destination, depth + 1)
        }
        Log.w(TAG, "Server returned HTML instead of binary APK from $urlStr")
        return false
      }

      val totalBytes = connection.contentLength
      connection.inputStream.use { input ->
        FileOutputStream(destination).use { output ->
          val buffer = ByteArray(16 * 1024)
          var bytesRead: Int
          var downloadedBytes = 0L
          while (input.read(buffer).also { bytesRead = it } != -1) {
            output.write(buffer, 0, bytesRead)
            downloadedBytes += bytesRead
            if (totalBytes > 0) {
              val pct = ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(1, 99)
              _updateProgress.value = pct
              val mbDone = String.format("%.1f", downloadedBytes / (1024f * 1024f))
              val mbTotal = String.format("%.1f", totalBytes / (1024f * 1024f))
              _updateStatusText.value = "Скачивание APK: $mbDone / $mbTotal МБ ($pct%)"
            } else {
              val mbDone = String.format("%.1f", downloadedBytes / (1024f * 1024f))
              _updateStatusText.value = "Скачивание APK: $mbDone МБ..."
            }
          }
          output.flush()
        }
      }
      true
    } catch (e: Exception) {
      Log.w(TAG, "Failed downloading from $urlStr: ${e.message}")
      false
    }
  }
}
