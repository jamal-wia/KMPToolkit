pluginManagement {
    includeBuild("build-logic")
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
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "KMPToolkit"

// Published library modules, added one at a time as they are ported. The full roadmap of planned
// modules lives in the root README's module table.
include(":kmptoolkit-logging")
include(":kmptoolkit-logging-overlay")
include(":kmptoolkit-audio-player")
include(":kmptoolkit-audio-player-testing")
include(":kmptoolkit-audio-recorder")
include(":kmptoolkit-audio-recorder-testing")
include(":kmptoolkit-haptics")
include(":kmptoolkit-haptics-testing")
include(":kmptoolkit-flashlight")
include(":kmptoolkit-flashlight-testing")
include(":kmptoolkit-scheduler")
include(":kmptoolkit-scheduler-testing")
include(":kmptoolkit-storage")
include(":kmptoolkit-storage-testing")
include(":kmptoolkit-biometric")
include(":kmptoolkit-biometric-testing")
include(":kmptoolkit-permission")
include(":kmptoolkit-permission-testing")
include(":kmptoolkit-notification")
include(":kmptoolkit-notification-testing")
include(":kmptoolkit-activity")
include(":kmptoolkit-systembars")
include(":kmptoolkit-systembars-testing")
include(":kmptoolkit-hardware-keys")
include(":kmptoolkit-uploader")
include(":kmptoolkit-uploader-testing")
include(":kmptoolkit-uploader-sqldelight")
include(":kmptoolkit-accelerometer")
include(":kmptoolkit-accelerometer-testing")
include(":kmptoolkit-location")
include(":kmptoolkit-location-testing")
include(":kmptoolkit-proximity")
include(":kmptoolkit-proximity-testing")
include(":kmptoolkit-downloader")
include(":kmptoolkit-downloader-testing")
include(":kmptoolkit-language")
include(":kmptoolkit-language-compose")
include(":kmptoolkit-hijri")
include(":kmptoolkit-video-player")
include(":kmptoolkit-video-player-testing")
include(":kmptoolkit-video-player-compose")
include(":kmptoolkit-video-player-vlcj")
include(":kmptoolkit-video-player-javafx")

include(":kmptoolkit-bom")

// Not published — Android Compose demo, smoke-tests the released artifacts by coordinate.
include(":sample")
