plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Accelerometer Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-accelerometer: ScriptedAccelerometer, an Accelerometer " +
            "double that replays a scripted list of AccelerometerSample values to every collector " +
            "and counts registrations, so a test can assert that code which stops collecting " +
            "really releases the sensor. Pick this module as a testImplementation dependency " +
            "alongside kmptoolkit-accelerometer when you want to assert what your app does with a " +
            "scripted motion sequence, or with a device that reports no accelerometer at all."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.accelerometer.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: ScriptedAccelerometer implements Accelerometer and returns
            // AccelerometerSample, so the whole of that module is on this one's public surface.
            api(project(":kmptoolkit-accelerometer"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
