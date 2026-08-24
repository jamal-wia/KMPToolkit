plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Proximity")
    pomDescription.set(
        "A proximity sensor seam: ProximitySensor reports whether something is right up " +
            "against the screen as a cold, event-driven Flow<Boolean>, and ProximityRule folds " +
            "a raw distance reading into that boolean as a pure, directly-testable function. " +
            "Pick this module if shared Kotlin code wants to know when a phone goes to the ear " +
            "without writing the TYPE_PROXIMITY SensorEventListener dance on Android — it " +
            "declares no permission of its own, and is honest that iOS has no equivalent API to " +
            "draw on."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.proximity"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: observe() returns a Flow, so kotlinx-coroutines-core is on
            // this module's own public surface.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
