plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // For LibraryManifestTest: the no-permissions invariant is asserted against a real package
    // manager, which needs the Robolectric rig this convention plugin sets up.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit System Bars Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-systembars. RecordingSystemBarsController is a faithful " +
            "SystemBarsController double: overrides stack, the newest wins a shared axis, an " +
            "override claims only the axes it names, and releasing one restores whatever is " +
            "underneath it at that moment — so a test can assert what a screen claims and that it " +
            "leaves no layer behind. RecordingScreenWakeLockController does the same for " +
            "ScreenWakeLockController, recording every setKeepScreenOn call. Pick this module as a " +
            "testImplementation dependency alongside kmptoolkit-systembars when you want those " +
            "assertions without a real Activity, UIApplication or window."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.systembars.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // Matches kmptoolkit-systembars, which publishes `jvm` for the shared-UI-tree case described in
    // `docs/01-architecture.md` § "One module publishes a desktop target". These fixtures are pure
    // Kotlin with no platform code at all, and the whole point of the exception is that a consumer
    // writes one tree for phone and desktop — a `commonTest` that cannot resolve the double on
    // `jvm` would push that consumer straight back into per-platform test code.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingScreenWakeLockController implements
            // ScreenWakeLockController, so it is part of this module's own public API.
            api(project(":kmptoolkit-systembars"))
        }
    }
}
