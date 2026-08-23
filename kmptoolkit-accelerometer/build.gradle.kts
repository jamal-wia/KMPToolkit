plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Accelerometer")
    pomDescription.set(
        "The raw accelerometer, and nothing else: an Accelerometer interface with an isAvailable " +
            "gate and a cold observe(): Flow<AccelerometerSample> that registers the platform " +
            "sensor on collection and releases it when collection ends. Pick this module if your " +
            "shared Kotlin code wants raw acceleration in m/s² on both platforms without " +
            "writing the SensorManager dance on Android and the CMMotionManager g-to-m/s² " +
            "conversion on iOS — it interprets nothing, declares no permission or feature of its " +
            "own, and reports missing hardware as a typed isAvailable flag instead of throwing."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.accelerometer"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: Accelerometer.observe() returns Flow<AccelerometerSample>,
            // so kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)
        }
    }
}
