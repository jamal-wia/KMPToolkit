plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the Activity tracker, the wake lock and the connectivity
    // observer all need a real Application/Activity lifecycle and a real ConnectivityManager, and
    // the "no permission is in the merged manifest" guarantee is only assertable against a real
    // PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Platform")
    pomDescription.set(
        "The platform seams shared Kotlin code keeps reaching for and cannot express: a " +
            "connectivity observer, device info and form factor, a reduced-motion probe, a URL " +
            "opener, a file picker, a screen wake lock, a crash handler with an on-disk crash " +
            "log, and a leak-resistant Android activity tracker. Pick this module when your " +
            "common code needs one of those facts or actions and you would otherwise write an " +
            "expect/actual pair per app — every seam is an interface with a platform factory, " +
            "declares no permission of its own, and reports failure as a typed result instead of " +
            "an exception."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.platform"

    // isPlatformDebugBuild / platformBuildVariant read this module's own BuildConfig — AGP no
    // longer generates it for a library by default. See docs/kmptoolkit-platform/05-platform-notes.md
    // for what that value actually means once this module is consumed as a published AAR.
    buildFeatures {
        buildConfig = true
    }
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: ConnectivityObserver exposes StateFlow and FilePicker is a
            // suspending API, so kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: every factory that can report a failure takes a
            // `logger: Logger = NoopLogger`, so Logger is part of this module's public signatures
            // and a consumer needs it on their compile classpath.
            api(project(":kmptoolkit-logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
