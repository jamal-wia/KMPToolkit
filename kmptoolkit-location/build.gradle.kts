plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: LibraryManifestTest asserts against a real PackageManager
    // that neither location permission is in the merged manifest.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Location")
    pomDescription.set(
        "Platform-agnostic access to the device's geographic position: a LocationProvider " +
            "interface with a one-shot suspend fun for a single fix, a Flow for continuous " +
            "updates, and a suspend check for whether the device-wide location service is on. " +
            "Raw coordinates only — no caching, no permission UI, no business logic. The Android " +
            "side is plain android.location.LocationManager, not Play Services: see " +
            "docs/kmptoolkit-location/05-platform-notes.md for why."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.location"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: observeLocation() returns a Flow and getCurrentLocation()
            // suspends, so kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: createLocationProvider takes a `logger: Logger = NoopLogger`
            // parameter, so Logger is part of this module's public signatures.
            api(project(":kmptoolkit-logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
