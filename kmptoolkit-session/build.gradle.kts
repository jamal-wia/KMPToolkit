plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Session")
    pomDescription.set(
        "Session lifecycle state plus a fan-out teardown SPI: one SessionManager that says " +
            "whether a session is open and, when it ends, runs every registered SessionCleaner " +
            "concurrently — timeout-bounded, failure-isolated, exactly once per session. Pick " +
            "this module if several features each hold per-account state and all of it must be " +
            "wiped on sign-out — and not if you want token storage, refresh, or login, none of " +
            "which it does."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.session"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: SessionManager.state is a StateFlow and createSessionManager
            // takes a CoroutineDispatcher, so both types are part of this module's own public API.
            api(libs.kotlinx.coroutines.core)
            // api as well: createSessionManager takes a Logger.
            api(project(":kmptoolkit-logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // No dependency on :kmptoolkit-session-testing — that module depends on this one, so
            // the reverse edge would be a project cycle. This module's tests declare their own
            // local fakes; the published fixtures are covered by that module's own tests.
        }
    }
}
