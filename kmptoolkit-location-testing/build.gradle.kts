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

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.location.testing"
    }

    iosArm64()
    iosSimulatorArm64()

    // A desktop target, like kmptoolkit-location itself: the fakes belong in the tests of a module
    // that shares location-dependent code with desktop. See docs/01-architecture.md
    // § "Desktop targets".
    jvm()

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
