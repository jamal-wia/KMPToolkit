plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the Android handler needs a real Context to answer
    // checkSelfPermission, and the "this module contributes no permission to a consumer's merged
    // manifest" guarantee is only assertable against a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Permission")
    pomDescription.set(
        "A runtime-permission seam plus the flow around it: a PermissionHandler that checks and " +
            "requests notifications, microphone and camera on Android and iOS, a PermissionStatus " +
            "that tells a first refusal apart from a permanent one, and a headless " +
            "PermissionRequestFlow state machine covering rationale, request and the trip to " +
            "system settings. Pick this module if shared Kotlin code has to decide what to show a " +
            "user about a permission — it ships no UI and no copy, declares no permission in its " +
            "own manifest, and derives its stored flags from your own application id."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.permission"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: PermissionRequestFlow.state is a StateFlow, so
            // kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: both platform factories take a `logger: Logger = NoopLogger`.
            api(project(":kmptoolkit-logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // api on both: ActivityAccess and KeyValueStorage are parameters of the public Android
            // factory, so a consumer needs them on their compile classpath to call it.
            api(project(":kmptoolkit-platform"))
            api(project(":kmptoolkit-storage"))
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":kmptoolkit-storage-testing"))
        }
    }
}
