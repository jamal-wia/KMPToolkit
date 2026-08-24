plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Platform Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-platform: fakes for the connectivity observer, device " +
            "info, the reduced-motion probe, the URL opener, the file picker, the screen wake " +
            "lock and the crash log store — each one drivable from a test and recording what it " +
            "was asked to do. Pick this module as a testImplementation dependency alongside " +
            "kmptoolkit-platform when you want to assert how your code behaves offline, on a " +
            "tablet, with reduced motion on, or when a picker is cancelled — without an emulator."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.platform.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: every fixture implements a kmptoolkit-platform interface
            // and returns its result types, so the whole of that module is on this one's surface.
            api(project(":kmptoolkit-platform"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
