plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Location Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-location: FakeLocationProvider, a LocationProvider driven " +
            "by hand instead of a GPS chip. Pick this module as a testImplementation dependency " +
            "alongside kmptoolkit-location when you want to assert how your code behaves with no " +
            "fix yet, with the location service off, or on a location change — without a device."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.location.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: FakeLocationProvider implements the kmptoolkit-location
            // interface and returns its result types, so that module is on this one's surface.
            api(project(":kmptoolkit-location"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
