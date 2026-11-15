plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: LibraryManifestTest asserts against a real PackageManager
    // that neither location permission is in the merged manifest, and the settings-screen tests
    // need a real Application, activities and started intents.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Location")
    pomDescription.set(
        "Platform-agnostic access to the device's geographic position: a LocationProvider " +
            "interface with a one-shot suspend fun, a Flow of readings and an observable " +
            "location-service state, where every reading is a fix with its accuracy or a typed " +
            "reason there is none; a compass HeadingProvider; and pure geo math on validated " +
            "coordinates (distance, bearing, destination, antimeridian-safe bounding box). No " +
            "caching, no permission UI, no business logic. The Android side is plain " +
            "android.location.LocationManager, not Play Services: see " +
            "docs/kmptoolkit-location/05-platform-notes.md for why."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.location"
    }

    iosArm64()
    iosSimulatorArm64()

    // A desktop target, under the exception in docs/01-architecture.md § "Desktop targets": the
    // geo math and every common type (coordinates, fixes, readings, options) are threaded through
    // code a consumer shares with desktop, and omitting the target would stop that shared code
    // compiling at all. The jvm half answers Unavailable(UNSUPPORTED): no desktop location source
    // exists in this library. See `docs/kmptoolkit-location/05-platform-notes.md`.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: observeLocation() returns a Flow and getCurrentLocation()
            // suspends, so kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: the platform slot interfaces (PlatformFixDetails,
            // PlatformCurrentLocationOptions, PlatformLocationUpdatesOptions) are
            // @SubclassOptInRequired(ToolkitInternalApi::class), so the marker is part of this
            // module's public signature.
            api(project(":kmptoolkit-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // api, not implementation: the factory overloads that take an app's own tracker or a
            // launcher have ActivityAccess and SystemScreenLauncher in their signatures, so a
            // consumer needs them on their compile classpath to call them.
            api(project(":kmptoolkit-activity"))

            // api, not implementation: the Android factories take a `logger: Logger = NoopLogger`
            // parameter. Android and iOS only, because the jvm factories have no logger parameter,
            // which is also why this module's jvm target needs no logging artifact of its own.
            api(project(":kmptoolkit-logging"))
        }
        iosMain.dependencies {
            // api, not implementation: the iOS factories take a `logger: Logger = NoopLogger`
            // parameter, so Logger is part of this module's public signatures there.
            api(project(":kmptoolkit-logging"))
        }
    }
}

// LocationDocsSamplesInSyncTest reads the docs pages and the test sources of both location modules as text.
// Gradle cannot see those reads, so without these inputs a docs-only edit would leave the cached result green.
tasks.named<Test>("jvmTest") {
    inputs.dir(rootProject.layout.projectDirectory.dir("docs/kmptoolkit-location"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("locationDocs")
    inputs.dir(rootProject.layout.projectDirectory.dir("kmptoolkit-location-testing/src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("locationTestingSources")
    inputs.dir(layout.projectDirectory.dir("src"))
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .withPropertyName("locationSources")
}
