plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit System Bars Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-systembars: RecordingScreenWakeLockController, a " +
            "ScreenWakeLockController double that records every setKeepScreenOn call. Pick this " +
            "module as a testImplementation dependency alongside kmptoolkit-systembars when you " +
            "want to assert that a screen toggles the wake lock at the right moments without a " +
            "real Activity or UIApplication."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.systembars.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingScreenWakeLockController implements
            // ScreenWakeLockController, so it is part of this module's own public API.
            api(project(":kmptoolkit-systembars"))
        }
    }
}
