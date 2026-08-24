plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the prompt configuration is an androidx.biometric
    // PromptInfo whose validity is API-level dependent (a strong-biometric-plus-device-credential
    // prompt is rejected below API 30), and the "no permission is in the merged manifest"
    // guarantee is only assertable against a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Biometric")
    pomDescription.set(
        "A biometric authentication gate: one BiometricGate interface, a typed " +
            "BiometricAvailability telling you why the device cannot authenticate, and a typed " +
            "BiometricResult distinguishing an authenticated user from a cancelled prompt, a " +
            "rejected finger, a temporary lockout, a permanent lockout, an unenrolled device and " +
            "absent hardware. Pick this module if your shared Kotlin code needs to put a " +
            "biometric check in front of a screen without writing the androidx.biometric " +
            "FragmentActivity dance on Android and the LAContext dance on iOS — it declares no " +
            "permission of its own, ships no prompt copy (the title, subtitle and cancel label " +
            "are required parameters of every call), and reports every failure as a value " +
            "instead of an exception."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.biometric"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // implementation, not api: `authenticate` is a suspending function, but no coroutines
            // type appears in a public signature, so consumers do not need this on their compile
            // classpath to call the API.
            implementation(libs.kotlinx.coroutines.core)
        }
        androidMain.dependencies {
            implementation(libs.androidx.biometric)

            // api, not implementation: createBiometricGate takes an ActivityAccess, so that type
            // is part of this module's public Android signature.
            api(project(":kmptoolkit-platform"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
