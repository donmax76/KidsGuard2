import com.google.gms.googleservices.GoogleServicesPlugin.MissingGoogleServicesStrategy
import java.util.Properties

plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.kotlin.compose)
  alias(libs.plugins.google.devtools.ksp)
  alias(libs.plugins.google.services)
}

// Suppress benign KSP in-process IntelliJ CoreApplicationEnvironment teardown NPE on AWT-EventQueue-0
Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
  val msg = throwable.message ?: ""
  val isKspAwtTeardown = thread.name.startsWith("AWT-EventQueue") ||
    msg.contains("ksp.com.intellij.openapi.application.ApplicationManager") ||
    throwable.stackTrace.any { it.className.startsWith("ksp.com.intellij") }
  if (!isKspAwtTeardown) {
    System.err.println("Uncaught exception in thread \"${thread.name}\": $throwable")
  }
}

val versionPropsFile = rootProject.file("version.properties")
val versionProps = Properties().apply {
  if (versionPropsFile.exists()) {
    versionPropsFile.inputStream().use { load(it) }
  }
}

val verMajor = versionProps.getProperty("VERSION_MAJOR", "3").toIntOrNull() ?: 3
val verMinor = versionProps.getProperty("VERSION_MINOR", "3").toIntOrNull() ?: 3
val currentPatch = maxOf(34, versionProps.getProperty("VERSION_PATCH", "34").toIntOrNull() ?: 34)
val currentVerCode = maxOf(64, versionProps.getProperty("VERSION_CODE", "64").toIntOrNull() ?: 64)
val currentVerName = "$verMajor.$verMinor.$currentPatch Pro"

android {
  namespace = "com.example"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    applicationId = "com.aistudio.kidsguard.pvrtqx"
    minSdk = 24
    targetSdk = 36
    versionCode = currentVerCode
    versionName = currentVerName

    buildConfigField("String", "LIVE_APK_URL", "\"https://ais-pre-ztt4dep7rn6touykp67du6-524198014443.europe-west2.run.app/KidsGuard-latest.apk\"")
    buildConfigField("String", "PRE_APK_URL", "\"https://ais-pre-ztt4dep7rn6touykp67du6-524198014443.europe-west2.run.app/KidsGuard-latest.apk\"")

    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }

  signingConfigs {
    // Проектный debug.keystore может отсутствовать (например, на CI или на новом ПК).
    // Раньше build падал на подписи; теперь при отсутствии файла используется
    // стандартный debug-ключ Android (AGP создаст его сам).
    val projectDebugKeystore = file("${rootDir}/debug.keystore")
    if (projectDebugKeystore.exists()) {
      create("debugConfig") {
        storeFile = projectDebugKeystore
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
      }
    }
    val keystorePath = System.getenv("KEYSTORE_PATH") ?: "${rootDir}/my-upload-key.jks"
    val releaseKeyFile = file(keystorePath)
    if (releaseKeyFile.exists() && !System.getenv("STORE_PASSWORD").isNullOrBlank()) {
      create("release") {
        storeFile = releaseKeyFile
        storePassword = System.getenv("STORE_PASSWORD")
        keyAlias = "upload"
        keyPassword = System.getenv("KEY_PASSWORD")
      }
    }
  }

  buildTypes {
    release {
      isCrunchPngs = false
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
      val releaseSigning = signingConfigs.findByName("release") ?: signingConfigs.findByName("debugConfig")
      if (releaseSigning != null) signingConfig = releaseSigning
    }
    debug {
      signingConfigs.findByName("debugConfig")?.let { signingConfig = it }
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
  buildFeatures {
    compose = true
    buildConfig = true
  }
  testOptions { unitTests { isIncludeAndroidResources = true } }
  dependenciesInfo {
    includeInApk = false
    includeInBundle = true
  }
}

googleServices { missingGoogleServicesStrategy = MissingGoogleServicesStrategy.WARN }

// Some unused dependencies are commented out below instead of being removed.
// This makes it easy to add them back in the future if needed.
dependencies {
  implementation(platform(libs.androidx.compose.bom))
  // implementation(platform(libs.firebase.bom))
  // implementation(libs.accompanist.permissions)
  implementation(libs.androidx.activity.compose)
  // implementation(libs.androidx.camera.camera2)
  // implementation(libs.androidx.camera.core)
  // implementation(libs.androidx.camera.lifecycle)
  // implementation(libs.androidx.camera.view)
  implementation(libs.androidx.compose.material.icons.core)
  implementation(libs.androidx.compose.material.icons.extended)
  implementation(libs.androidx.compose.material3)
  implementation(libs.androidx.compose.ui)
  implementation(libs.androidx.compose.ui.graphics)
  implementation(libs.androidx.compose.ui.tooling.preview)
  implementation(libs.androidx.core.ktx)
  // implementation(libs.androidx.datastore.preferences)
  implementation(libs.androidx.lifecycle.runtime.compose)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.lifecycle.viewmodel.compose)
  // implementation(libs.androidx.navigation.compose)
  implementation(libs.androidx.room.ktx)
  implementation(libs.androidx.room.runtime)
  // implementation(libs.coil.compose)
  // implementation(libs.converter.moshi)
  // implementation(libs.firebase.ai)
  // Uncomment to use Firestore:
  // implementation(libs.firebase.firestore)

  // Uncomment ALL FOUR of the following dependencies together to use Firebase Auth and Google
  // Sign-In via Credential Manager:
  // implementation(libs.firebase.auth)
  // implementation(libs.androidx.credentials)
  // implementation(libs.androidx.credentials.play.services)
  // implementation(libs.googleid)
  // implementation(libs.firebase.appcheck.recaptcha)
  // implementation(libs.firebase.appcheck.debug)
  implementation(libs.kotlinx.coroutines.android)
  implementation(libs.kotlinx.coroutines.core)
  // implementation(libs.logging.interceptor)
  // implementation(libs.moshi.kotlin)
  implementation(libs.okhttp)
  implementation(libs.play.services.location)
  // implementation(libs.play.services.maps)
  // implementation(libs.retrofit)
  testImplementation(libs.androidx.compose.ui.test.junit4)
  testImplementation(libs.androidx.core)
  testImplementation(libs.androidx.junit)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
  testImplementation(libs.robolectric)
  androidTestImplementation(platform(libs.androidx.compose.bom))
  androidTestImplementation(libs.androidx.compose.ui.test.junit4)
  androidTestImplementation(libs.androidx.espresso.core)
  androidTestImplementation(libs.androidx.junit)
  androidTestImplementation(libs.androidx.runner)
  debugImplementation(libs.androidx.compose.ui.test.manifest)
  debugImplementation(libs.androidx.compose.ui.tooling)
  "ksp"(libs.androidx.room.compiler)
  // "ksp"(libs.moshi.kotlin.codegen)
}
