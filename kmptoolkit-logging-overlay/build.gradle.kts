// `compose.uiTest` (Compose Multiplatform's test artifact, used by the Robolectric-backed UI tests
// in androidUnitTest) is still behind this opt-in as of Compose Multiplatform 1.11.
@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.compose")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Logging Overlay")
    pomDescription.set(
        "A debug-build-only, on-screen log viewer for Compose Multiplatform: a LogOverlayState " +
            "you create and own, a bounded record buffer it fills through kmptoolkit-logging's " +
            "LogSink SPI, and a LogOverlayHost composable that draws the records over your UI. " +
            "Pick this module if you want to read a device's logs without a cable — on a tester's " +
            "phone, in a kiosk, on an iPad across the room. It is a development tool: it retains " +
            "records in memory and paints them on screen, so it must never ship in a release build."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.logging.overlay"
}

kotlin {
    // No iosX64(): Compose Multiplatform 1.11+ publishes no artifact for that target, so declaring
    // it fails the build. Every non-Compose module in this repo declares all three — the difference
    // is deliberate, see `kmptoolkit.compose`'s doc comment in build-logic.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: LogSink, LogLevel and Logger all appear in this module's
            // public signatures, so a consumer cannot use the overlay without them on the compile
            // classpath.
            api(project(":kmptoolkit-logging"))

            // StateFlow is part of LogOverlayState's public surface. Compose's runtime already
            // brings coroutines-core transitively; declaring it here states the direct use.
            api(libs.kotlinx.coroutines.core)

            // Exactly what the overlay uses and nothing more: runtime for snapshot state and
            // collectAsState, foundation for LazyColumn/clickable/layout, material3 for the
            // Surface/Text/TextButton chrome, ui for Modifier and units.
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }

        commonTest.dependencies {
            // Only for the concurrent-append test, which needs real parallelism from
            // Dispatchers.Default on both the JVM and Kotlin/Native.
            implementation(libs.kotlinx.coroutines.test)
        }

        androidUnitTest.dependencies {
            implementation(compose.uiTest)
        }
    }
}

dependencies {
    // Robolectric launches the Compose test host through ActivityScenario, which needs an
    // `androidx.activity.ComponentActivity` declared in the *merged debug manifest* — a test-only
    // configuration is merged too late for that, which is why this is `debugImplementation` and not
    // an androidUnitTest dependency. Without it every UI test dies with "Unable to resolve activity
    // for Intent ... ComponentActivity". ui-test-manifest is a manifest-only AAR, the debug variant
    // is never published (`publishLibraryVariants("release")`), so nothing reaches a consumer.
    //
    // Versioned through the Compose BOM because the catalog's ui-test-manifest entry carries no
    // version of its own.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
