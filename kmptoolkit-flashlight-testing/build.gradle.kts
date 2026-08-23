plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Flashlight Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-flashlight: RecordingFlashlight, a Flashlight double that " +
            "records every requested FlashPattern, in order, with a null entry per stop. Pick " +
            "this module as a testImplementation dependency alongside kmptoolkit-flashlight when " +
            "you want to assert which torch cue a screen fires, and how it behaves on a device " +
            "with no flash unit."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.flashlight.testing"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingFlashlight implements Flashlight and takes
            // FlashPattern, so both are part of this module's own public API.
            api(project(":kmptoolkit-flashlight"))
        }
    }
}
