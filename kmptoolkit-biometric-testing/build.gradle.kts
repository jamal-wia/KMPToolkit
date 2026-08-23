plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Biometric Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-biometric: ScriptedBiometricGate, a BiometricGate double " +
            "that records the prompt copy it was handed and returns whichever BiometricResult " +
            "the test dictates — per call, so one test can walk a retry loop from a rejected " +
            "finger to a lockout to an authenticated user. Pick this module as a " +
            "testImplementation dependency alongside kmptoolkit-biometric when you want to " +
            "assert what your app does when biometrics are unenrolled, locked out or cancelled, " +
            "none of which is reachable on an emulator without fighting it."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.biometric.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: ScriptedBiometricGate implements BiometricGate and returns
            // its result types, so the whole of that module is on this one's public surface.
            api(project(":kmptoolkit-biometric"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
