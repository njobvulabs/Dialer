pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven("https://maven.mozilla.org/maven2/")
    maven("https://jitpack.io")
  }
}

rootProject.name = "nj-dialer"

include(":app")
include(":terminal-emulator")
include(":terminal-view")
include(":j2me")
include(":dexlib")
include(":Bcore")
include(":black-reflection")
include(":compiler")
