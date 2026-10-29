// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
  alias(libs.plugins.android.application) apply false
  alias(libs.plugins.kotlin.compose) apply false
  alias(libs.plugins.google.devtools.ksp) apply false
  alias(libs.plugins.secrets) apply false
  alias(libs.plugins.google.services) apply false
}

/**
 * Phase 4C: Aggressive Repository Cleanup Task for GitHub & CI/CD Push (`./gradlew cleanRepositoryForCiCd`).
 * Removes all build outputs, KSP/Room caches, .idea folders, .kotlin caches, and old .apk/.aab/.idsig files.
 */
tasks.register<Delete>("cleanRepositoryForCiCd") {
  group = "build"
  description = "Aggressively cleans cached builds, .idea folders, old .apk/.aab artifacts, and temp files before GitHub push."

  delete(
    rootProject.layout.buildDirectory,
    file("${rootDir}/app/build"),
    file("${rootDir}/.idea"),
    file("${rootDir}/.kotlin"),
    file("${rootDir}/app/.cxx"),
    fileTree(rootDir) {
      include(
        "**/*.apk",
        "**/*.aab",
        "**/*.idsig",
        "**/*.iml",
        "**/*.log",
        "**/*.hprof",
        "**/.DS_Store"
      )
      exclude(".gradle/**")
    }
  )

  doLast {
    println("🧹 [KidsGuard CI/CD] Repository cleaned: build caches, .idea, and stale .apk/.aab files removed.")
  }
}

