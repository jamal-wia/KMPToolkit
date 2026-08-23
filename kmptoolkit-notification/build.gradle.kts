plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the Android notifier needs a real NotificationManager to
    // answer "is this channel blocked", and the "this module merges no permission into a
    // consumer's manifest" guarantee is only assertable against a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Notification")
    pomDescription.set(
        "Local notifications from shared Kotlin: NotificationManagerCompat on Android, " +
            "UNUserNotificationCenter on iOS, behind one Notifier interface whose post() returns " +
            "a typed NotificationResult instead of failing silently when the permission is " +
            "missing, notifications are switched off, or the user blocked the channel. Pick this " +
            "module if shared code decides what to show and when — and not if you need push/FCM " +
            "delivery or wall-clock scheduling, neither of which it does. It declares no " +
            "permission of its own and derives its broadcast action from your application id."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.notification"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: PermissionHandler is a parameter of both public factories,
            // so a consumer needs it on their compile classpath to call one.
            api(project(":kmptoolkit-permission"))

            // The iOS notifier awaits UNUserNotificationCenter's completion handler; nothing of
            // kotlinx-coroutines reaches this module's own public surface.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // NotificationCompat, NotificationManagerCompat, and String.toUri.
            implementation(libs.androidx.core.ktx)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // RecordingPermissionHandler: the Android notifier's gates are only reachable with a
            // permission handler a test can drive.
            implementation(project(":kmptoolkit-permission-testing"))
        }
    }
}
