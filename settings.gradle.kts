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
        // Only reachable for this project's own group, and only used by :sample when run with
        // -PuseMavenLocal — see sample/build.gradle.kts. Content-filtered so an unrelated stale
        // artifact in ~/.m2 can never shadow a real one from Maven Central.
        mavenLocal {
            content { includeGroup("io.github.jamal-wia") }
        }
    }
}

rootProject.name = "KMPToolkit"

// Published library modules, added one at a time as they are ported. The full roadmap of planned
// modules lives in the root README's module table.
include(":kmptoolkit-coroutines")
include(":kmptoolkit-coroutines-testing")
include(":kmptoolkit-logging")
include(":kmptoolkit-logging-overlay")
include(":kmptoolkit-audio-player")
include(":kmptoolkit-audio-player-testing")
include(":kmptoolkit-audio-recorder")
include(":kmptoolkit-audio-recorder-testing")
include(":kmptoolkit-haptics")
include(":kmptoolkit-haptics-testing")
include(":kmptoolkit-scheduler")
include(":kmptoolkit-scheduler-testing")
include(":kmptoolkit-platform")
include(":kmptoolkit-platform-testing")
include(":kmptoolkit-storage")
include(":kmptoolkit-storage-testing")
include(":kmptoolkit-biometric")
include(":kmptoolkit-biometric-testing")
include(":kmptoolkit-permission")
include(":kmptoolkit-permission-testing")
include(":kmptoolkit-notification")
include(":kmptoolkit-notification-testing")
include(":kmptoolkit-session")
include(":kmptoolkit-session-testing")
include(":kmptoolkit-systembars")
include(":kmptoolkit-outbox")
include(":kmptoolkit-outbox-testing")
include(":kmptoolkit-outbox-sqldelight")

include(":kmptoolkit-bom")

// Not published — Android Compose demo, smoke-tests published artifacts from mavenLocal.
include(":sample")
