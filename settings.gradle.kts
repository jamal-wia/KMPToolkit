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

        // Present ONLY when asked for by name. The release workflow's smoke-test step passes
        // `-PuseMavenLocal` so `:sample` can resolve a `publishToMavenLocal` dry run by coordinate
        // and catch a malformed POM, a missing variant, or a publication that drops a transitive
        // dependency — before the version reaches Central, where a bad release cannot be withdrawn.
        //
        // Every other build, including every verification run, resolves without it. That is the
        // point: a permanently reachable `~/.m2` is exactly what makes a broken publication — or a
        // stale local version that was never published at all — look correct on the one machine
        // that produced it, and on no other.
        if (providers.gradleProperty("useMavenLocal").isPresent) {
            mavenLocal { content { includeGroup("io.github.jamal-wia") } }
        }
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

include(":kmptoolkit-bom")

// Not published — Android Compose demo, smoke-tests the released artifacts by coordinate.
include(":sample")
