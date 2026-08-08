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

// Published library modules are added here one at a time as they are ported —
// see /Users/admin/.claude/plans/valiant-wobbling-dahl.md, phases 1-3.
include(":kmptoolkit-coroutines")
include(":kmptoolkit-coroutines-testing")
include(":kmptoolkit-audio-recorder")
include(":kmptoolkit-audio-recorder-testing")

// Not published — Android Compose demo, smoke-tests published artifacts from mavenLocal.
include(":sample")
