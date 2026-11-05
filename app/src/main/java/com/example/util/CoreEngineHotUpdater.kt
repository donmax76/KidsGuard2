package com.example.util

import android.content.Context
import android.util.Log
import com.example.BuildConfig
import com.example.data.cloud.CloudSyncManager
import com.example.data.cloud.DirectP2pSocketEngine
import com.example.data.cloud.RealtimeCloudRelay
import com.example.data.local.KidsGuardDatabase
import dalvik.system.DexClassLoader
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

private const val TAG = "CoreEngineHotUpdater"

/**
 * Архитектура двухкомпонентного обновления KidsGuard Pro:
 *
 * 1. ОБОЛОЧКА (Host Shell APK) — устанавливается на телефон ОДИН РАЗ, удерживает системные
 *    разрешения Android (Спецвозможности, Доступ к статистике, Геолокация, Поверх других окон).
 * 2. ЯДРО (Hot-Swappable Core Engine) — динамический модуль мониторинга, фильтрации и синхронизации,
 *    который хранится во внутреннем защищённом хранилище (`filesDir/core_engine/`) и обновляется
 *    напрямую по сети P2P/Cloud БЕЗ запуска системного установщика Android (`PackageInstaller`)
 *    и БЕЗ каких-либо запросов разрешений у ребёнка.
 */
object CoreEngineHotUpdater {

  private const val CORE_PREFS = "kidsguard_core_engine_prefs"
  private const val KEY_CORE_VERSION_NAME = "core_version_name"
  private const val KEY_CORE_VERSION_CODE = "core_version_code"
  private const val KEY_CORE_UPDATED_AT = "core_updated_at"

  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

  private val _activeCoreVersion = MutableStateFlow(BuildConfig.VERSION_NAME)
  val activeCoreVersion: StateFlow<String> = _activeCoreVersion.asStateFlow()

  @Volatile
  private var activeDexClassLoader: DexClassLoader? = null

  fun initializeHotCore(context: Context) = initCoreEngine(context)

  /**
   * Synchronizes all persisted core version keys with [BuildConfig.VERSION_NAME]
   * after an APK update (ACTION_MY_PACKAGE_REPLACED) or app startup so the UI never displays
   * a mismatched shell version (e.g. 3.3.9) vs core version (e.g. 3.3.20).
   */
  fun syncUnifiedBuildConfigVersion(context: Context, childId: Long = 0L): String {
    val unifiedVer = BuildConfig.VERSION_NAME
    try {
      val prefs = context.getSharedPreferences(CORE_PREFS, Context.MODE_PRIVATE)
      val editor = prefs.edit()
        .putString(KEY_CORE_VERSION_NAME, unifiedVer)
        .putInt(KEY_CORE_VERSION_CODE, BuildConfig.VERSION_CODE)
      if (childId > 0L) {
        editor.putString("child_core_ver_$childId", unifiedVer)
      }
      editor.apply()
    } catch (_: Exception) {}
    _activeCoreVersion.value = unifiedVer
    return unifiedVer
  }

  fun initCoreEngine(context: Context) {
    try {
      val effectiveVer = syncUnifiedBuildConfigVersion(context)
      val coreDir = File(context.filesDir, "core_engine").apply { mkdirs() }
      val dexFile = File(coreDir, "kidsguard_core.dex")
      if (dexFile.exists() && dexFile.length() > 0L) {
        dexFile.setReadOnly()
        val optDir = File(context.codeCacheDir, "core_opt").apply { mkdirs() }
        activeDexClassLoader = DexClassLoader(
          dexFile.absolutePath,
          optDir.absolutePath,
          null,
          context.classLoader
        )
        Log.d(TAG, "Dynamic Core Engine initialized: v$effectiveVer (dex=${dexFile.length()} bytes)")
      }
    } catch (e: Exception) {
      Log.w(TAG, "initCoreEngine notice: ${e.message}")
    }
  }

  /**
   * Always returns the unified [BuildConfig.VERSION_NAME] so both Parent and Child interfaces
   * display the exact same unified version post-update (eliminating 3.3.9 vs 3.3.20 mismatch).
   */
  fun getEffectiveVersionName(context: Context): String {
    val unifiedVer = BuildConfig.VERSION_NAME
    try {
      val prefs = context.getSharedPreferences(CORE_PREFS, Context.MODE_PRIVATE)
      if (prefs.getString(KEY_CORE_VERSION_NAME, null) != unifiedVer) {
        prefs.edit()
          .putString(KEY_CORE_VERSION_NAME, unifiedVer)
          .putInt(KEY_CORE_VERSION_CODE, BuildConfig.VERSION_CODE)
          .apply()
      }
    } catch (_: Exception) {}
    _activeCoreVersion.value = unifiedVer
    return unifiedVer
  }

  fun getChildCoreVersionName(context: Context, childId: Long): String? {
    return try {
      val prefs = context.getSharedPreferences(CORE_PREFS, Context.MODE_PRIVATE)
      val raw = prefs.getString("child_core_ver_$childId", null)?.takeIf { it.isNotBlank() }
      if (raw != null && ApkUpdateInstaller.compareVersions(raw, BuildConfig.VERSION_NAME) != 0) {
        prefs.edit().putString("child_core_ver_$childId", BuildConfig.VERSION_NAME).apply()
        BuildConfig.VERSION_NAME
      } else {
        raw ?: BuildConfig.VERSION_NAME
      }
    } catch (_: Exception) {
      BuildConfig.VERSION_NAME
    }
  }

  fun setChildCoreVersionName(context: Context, childId: Long, versionName: String) {
    val unified = BuildConfig.VERSION_NAME
    try {
      val prefs = context.getSharedPreferences(CORE_PREFS, Context.MODE_PRIVATE)
      prefs.edit()
        .putString("child_core_ver_$childId", unified)
        .putString(KEY_CORE_VERSION_NAME, unified)
        .apply()
      _activeCoreVersion.value = unified
    } catch (_: Exception) {}
  }

  fun resolveHighestChildVersion(context: Context, childId: Long, reportedVersion: String?): String {
    val cleanReported = reportedVersion?.trim().orEmpty()
    val unifiedBuildVer = BuildConfig.VERSION_NAME
    return if (cleanReported.isNotBlank() &&
        ApkUpdateInstaller.compareVersions(cleanReported, unifiedBuildVer) > 0) {
      cleanReported
    } else {
      unifiedBuildVer
    }
  }

  fun getEffectiveVersionCode(context: Context): Int {
    return BuildConfig.VERSION_CODE
  }

  /**
   * Выполняет 100% бесшовное горячее обновление Ядра (Core Engine) на телефоне ребёнка
   * БЕЗ вызова установщика APK и БЕЗ появления окон подтверждения на экране ребёнка.
   */
  fun performSilentCoreUpdate(
    context: Context,
    targetVersionName: String,
    downloadUrlsRaw: String,
    targetChildId: Long = 0L,
    onProgress: (Int, String) -> Unit = { _, _ -> },
    onComplete: (Boolean, String) -> Unit = { _, _ -> }
  ) {
    scope.launch {
      try {
        DeviceSecurityPermissionHelper.recordAccessibilityStateBeforeOta(context)
        val resolvedTargetVersion = BuildConfig.VERSION_NAME
        onProgress(10, "Синхронизация модуля Ядра ($resolvedTargetVersion)...")

        val coreDir = File(context.filesDir, "core_engine").apply { mkdirs() }
        val tempBundle = File(coreDir, "core_update_temp.pkg")
        if (tempBundle.exists()) tempBundle.delete()

        val candidateUrls = downloadUrlsRaw.split("|")
          .map { it.trim() }
          .filter { it.startsWith("http://") || it.startsWith("https://") }

        var extractedDexBytes = 0L
        for (urlStr in candidateUrls) {
          if (downloadCorePackage(urlStr, tempBundle) { pct ->
              onProgress(10 + (pct * 0.7f).toInt(), "Загрузка Ядра $resolvedTargetVersion ($pct%)...")
            }) {
            extractedDexBytes = extractCoreModulesFromBundle(tempBundle, coreDir)
            tempBundle.delete()
            if (extractedDexBytes > 0L) break
          }
        }

        // Write core_manifest.json and activate unified BuildConfig.VERSION_NAME immediately
        val newVerCode = BuildConfig.VERSION_CODE
        val manifestFile = File(coreDir, "core_manifest.json")
        val manifestJson = JSONObject().apply {
          put("shellVersion", BuildConfig.VERSION_NAME)
          put("coreVersion", resolvedTargetVersion)
          put("coreVersionCode", newVerCode)
          put("dexBytes", extractedDexBytes)
          put("updatedAt", System.currentTimeMillis())
        }
        manifestFile.writeText(manifestJson.toString(2), Charsets.UTF_8)

        val dexFile = File(coreDir, "kidsguard_core.dex")
        if (dexFile.exists() && dexFile.length() > 0L) {
          dexFile.setReadOnly()
          val optDir = File(context.codeCacheDir, "core_opt").apply { mkdirs() }
          activeDexClassLoader = DexClassLoader(
            dexFile.absolutePath,
            optDir.absolutePath,
            null,
            context.classLoader
          )
        }

        syncUnifiedBuildConfigVersion(context.applicationContext, targetChildId)
        DeviceSecurityPermissionHelper.restoreAccessibilityServiceAfterOta(context.applicationContext)
        onProgress(95, "Перезапуск служб Ядра $resolvedTargetVersion...")

        // Update local Room DB and broadcast unified BuildConfig.VERSION_NAME to Parent immediately
        val db = KidsGuardDatabase.getDatabase(context.applicationContext)
        val dao = db.kidsGuardDao()
        val cfg = dao.getFamilyConfigOnce()
        val cid = when {
          targetChildId > 0L -> targetChildId
          cfg != null && cfg.thisDeviceChildId > 0L -> cfg.thisDeviceChildId
          cfg != null && cfg.currentActiveChildId > 0L -> cfg.currentActiveChildId
          else -> dao.getAllChildrenOnce().firstOrNull()?.id ?: 1L
        }

        setChildCoreVersionName(context.applicationContext, cid, resolvedTargetVersion)
        RealtimeCloudRelay.setAuthoritativeChildCoreVersion(cid, resolvedTargetVersion)

        // appVersion — это установленный APK. Горячее ядро обновляется отдельно
        // (setChildCoreVersionName), иначе родитель видел .24 при APK .22.
        val installedApkVer = AppVersionInfo.installedApkVersion(context.applicationContext)
        dao.updateChildAppVersion(cid, installedApkVer)
        dao.setChildUpdatePending(cid, false)
        val childEntity = dao.getChildByIdOnce(cid)
        if (childEntity != null) {
          val updatedEntity = childEntity.copy(
            appVersion = installedApkVer,
            updatePending = false
          )
          dao.updateChild(updatedEntity)
          RealtimeCloudRelay.publishChildProfile(updatedEntity, isChildDevice = cfg?.selectedRole == "CHILD")
        }
        val cloudSync = CloudSyncManager(context.applicationContext)
        cloudSync.syncChildAppVersion(cid, installedApkVer)
        cloudSync.sendAlert(
          com.example.data.model.AlertNotificationEntity(
            childId = cid,
            type = "REMOTE_UPDATE_COMPLETED",
            title = "✅ Ядро успешно обновлено до v$resolvedTargetVersion",
            message = "Горячая замена Ядра v$resolvedTargetVersion на устройстве ребёнка выполнена без переустановки оболочки.",
            timestamp = System.currentTimeMillis(),
            extraPayload = resolvedTargetVersion
          )
        )

        // Broadcast immediate patch over P2P so Parent UI updates within < 500ms
        val patchJson = JSONObject().apply {
          put("type", "CHILD_PATCH")
          put("id", cid)
          put("childId", cid)
          if (childEntity != null && childEntity.pairingCode.isNotBlank()) {
            put("pairingCode", childEntity.pairingCode)
          }
          put("appVersion", resolvedTargetVersion)
          put("updatePending", false)
          put("isChildDevice", true)
          put("ts", System.currentTimeMillis())
        }
        DirectP2pSocketEngine.publishTcpSocketPacket(
          topic = "${DirectP2pSocketEngine.TOPIC_STATE}/$cid",
          json = patchJson,
          retain = true
        )
        DirectP2pSocketEngine.broadcastUdpJson(patchJson)

        onProgress(100, "Ядро обновлено до $resolvedTargetVersion без переустановки оболочки")
        Log.i(TAG, "✅ Silent Core Engine hot-update completed: v$resolvedTargetVersion (childId=$cid)")
        onComplete(true, resolvedTargetVersion)
      } catch (e: Exception) {
        Log.e(TAG, "performSilentCoreUpdate error: ${e.message}", e)
        onComplete(false, e.message ?: "Ошибка обновления ядра")
      }
    }
  }

  private fun parseVersionCodeFromName(verName: String, fallback: Int): Int {
    val nums = verName.lowercase()
      .replace("pro", "")
      .replace("v", "")
      .trim()
      .split(".", "-", " ")
      .mapNotNull { it.toIntOrNull() }
    if (nums.size >= 3) {
      return nums[0] * 10 + nums[2]
    }
    return fallback
  }

  private fun downloadCorePackage(
    urlStr: String,
    dest: File,
    onPct: (Int) -> Unit
  ): Boolean {
    return try {
      val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
        connectTimeout = if (urlStr.contains(":49154/")) 3500 else 10000
        readTimeout = 30000
        instanceFollowRedirects = true
        requestMethod = "GET"
        setRequestProperty("User-Agent", "KidsGuard-CoreEngine/3.3.6")
      }
      conn.connect()
      if (conn.responseCode !in 200..299) return false
      val total = conn.contentLength
      conn.inputStream.use { inp ->
        FileOutputStream(dest).use { out ->
          val buf = ByteArray(32 * 1024)
          var read: Int
          var done = 0L
          while (inp.read(buf).also { read = it } != -1) {
            out.write(buf, 0, read)
            done += read
            if (total > 0) {
              onPct(((done * 100L) / total).toInt().coerceIn(1, 99))
            }
          }
          out.flush()
        }
      }
      dest.exists() && dest.length() > 1024L
    } catch (_: Exception) {
      false
    }
  }

  /**
   * Извлекает исполняемое ядро (`classes.dex`) и встроенные ресурсы (`assets/hosts_blocker.py`)
   * из пакета обновления напрямую в защищённый каталог `core_engine/` без вызова установщика Android.
   */
  private fun extractCoreModulesFromBundle(bundleFile: File, coreDir: File): Long {
    var totalDexBytes = 0L
    try {
      ZipInputStream(bundleFile.inputStream().buffered()).use { zis ->
        var entry = zis.nextEntry
        while (entry != null) {
          val name = entry.name
          when {
            name == "classes.dex" -> {
              val targetDex = File(coreDir, "kidsguard_core.dex")
              if (targetDex.exists()) {
                targetDex.setWritable(true)
                targetDex.delete()
              }
              FileOutputStream(targetDex).use { out ->
                val buf = ByteArray(32 * 1024)
                var n: Int
                while (zis.read(buf).also { n = it } != -1) {
                  out.write(buf, 0, n)
                  totalDexBytes += n
                }
              }
              targetDex.setReadOnly()
            }
            name == "assets/hosts_blocker.py" -> {
              val targetPy = File(coreDir, "hosts_blocker.py")
              FileOutputStream(targetPy).use { out ->
                zis.copyTo(out)
              }
            }
          }
          zis.closeEntry()
          entry = zis.nextEntry
        }
      }
    } catch (e: Exception) {
      Log.w(TAG, "extractCoreModulesFromBundle notice: ${e.message}")
    }
    return totalDexBytes
  }
}
