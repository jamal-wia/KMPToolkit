plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Flashlight")
    pomDescription.set(
        "A camera-torch blink cue: one Flashlight interface and two FlashPattern rhythms, " +
            "driven by CameraManager.setTorchMode on Android and AVCaptureDevice's torch on iOS. " +
            "Pick this module if your shared Kotlin code wants a cue that still works when the " +
            "screen is dark or the device is face-down on a table — it opens no capture session " +
            "on either platform, needs no permission at all, and every call is a best-effort " +
            "no-op on hardware with no flash unit instead of an exception."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.flashlight"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The only dependency: both platform implementations blink on a repeating coroutine
            // loop, and the public factory functions hand back plain Flashlight instances with no
            // coroutines type in their signature.
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
