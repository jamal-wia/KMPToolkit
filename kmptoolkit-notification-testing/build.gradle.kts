plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Notification Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-notification: RecordingNotifier, a Notifier double that " +
            "records every LocalNotification your code would have posted, tracks which ids are " +
            "still showing, and lets a test dictate the NotificationResult handed back. Pick " +
            "this module as a testImplementation dependency alongside kmptoolkit-notification " +
            "when you want to assert what a download or a reminder shows the user, and how your " +
            "code behaves when the permission is denied or the channel is blocked."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.notification.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingNotifier implements Notifier and exposes
            // LocalNotification and NotificationResult, so all three are part of its own API.
            api(project(":kmptoolkit-notification"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
