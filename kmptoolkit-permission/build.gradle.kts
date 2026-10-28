plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: the Android controller needs a real Context to answer
    // checkSelfPermission, and the "this module contributes no permission to a consumer's merged
    // manifest" guarantee is only assertable against a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Permission")
    pomDescription.set(
        "One PermissionController for every grant an app asks for on Android and iOS: runtime " +
            "permissions shared by both platforms (notifications, microphone, camera, location, " +
            "photos, contacts, calendar, motion, Bluetooth), Android special access, roles and device " +
            "admins, and iOS tracking, speech, reminders and Focus — with a PermissionStatus that " +
            "tells a first refusal from a permanent one and a limited, restricted or unavailable " +
            "permission from a refusal, batch requests, observation, and a headless " +
            "PermissionRequestFlow covering rationale, request and the trip to system settings. It " +
            "ships no UI and no copy, declares no permission in its own manifest, and derives its " +
            "stored flags from your own application id."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.permission"
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: PermissionRequestFlow.state is a StateFlow, so
            // kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: both platform factories take a `logger: Logger = NoopLogger`.
            api(project(":kmptoolkit-logging"))

            // api, not implementation: Permission is @SubclassOptInRequired(ToolkitInternalApi::class),
            // so the marker is part of this module's public signature.
            api(project(":kmptoolkit-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // api: KeyValueStorage is a parameter of the public Android factory, so a consumer
            // needs it on their compile classpath to call it.
            api(project(":kmptoolkit-storage"))

            // NotificationManagerCompat: getEnabledListenerPackages(), the only way to check
            // AndroidPermission.NotificationListenerAccess, and areNotificationsEnabled(), the
            // pre-API-33 notification status.
            implementation(libs.androidx.core.ktx)

            // api, not implementation: the factory takes an ActivityAccess and a SystemScreenLauncher,
            // and PermissionScreen is a SystemScreenKind, so a consumer needs it on their compile
            // classpath to call it.
            api(project(":kmptoolkit-activity"))

            // ActivityResultPermissionRequestHost: a ComponentActivity's ActivityResultRegistry is the
            // one way to show a permission dialog or a role chooser that survives the activity being
            // recreated meanwhile. implementation, not api: no androidx.activity type is in this
            // module's public signatures.
            implementation(libs.androidx.activity)
        }
        androidHostTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":kmptoolkit-storage-testing"))
        }
    }
}
