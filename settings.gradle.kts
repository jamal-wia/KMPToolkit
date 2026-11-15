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
include(":kmptoolkit-core")
include(":kmptoolkit-files")
include(":kmptoolkit-files-testing")
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
include(":kmptoolkit-storage-jna")
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

// Not published — shared test helpers (path syntax converter, host assumptions), used only as a test
// dependency. Deliberately not named kmptoolkit-*, so the BOM does not list it.
include(":test-support")

// Not published — Kotlin half of the iOS secure-storage host app (storage-ios-harness/ios-host); real Keychain
// round trips run by a person on the simulator. Deliberately not named kmptoolkit-*, so the BOM does not list it.
include(":storage-ios-harness")

// Not published — Android Compose demo, smoke-tests the released artifacts by coordinate.
include(":sample")

// Not published — packaging smoke of the desktop storage (storage plan 6.7 P): a Compose Desktop release distribution
// with ProGuard that runs one secure and one plain round trip, in two variants (JDK 25 + FFM, JDK 21 + kmptoolkit-storage-jna).
// Deliberately not named kmptoolkit-*, so the BOM does not list it. The two variant projects share ONE build script.
include(":smoke-storage-desktop")
include(":smoke-storage-desktop:ffm")
include(":smoke-storage-desktop:jna")
project(":smoke-storage-desktop:ffm").buildFileName = "../variant.gradle.kts"
project(":smoke-storage-desktop:jna").buildFileName = "../variant.gradle.kts"

