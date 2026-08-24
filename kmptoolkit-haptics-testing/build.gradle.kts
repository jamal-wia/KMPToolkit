plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Haptics Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-haptics: RecordingHapticFeedback, a HapticFeedback double " +
            "that records every requested HapticType and lets a test dictate the HapticResult " +
            "handed back. Pick this module as a testImplementation dependency alongside " +
            "kmptoolkit-haptics when you want to assert which haptics a screen fires, and how it " +
            "behaves when the device reports no vibrator or a missing permission."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.haptics.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingHapticFeedback implements HapticFeedback and
            // returns HapticResult, so both are part of this module's own public API.
            api(project(":kmptoolkit-haptics"))
        }
    }
}
