plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Permission Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-permission: RecordingPermissionHandler, a PermissionHandler " +
            "double that records every check, request and settings trip, and lets a test script " +
            "what each one answers — including a request that flips the status the way a real " +
            "system dialog would. Pick this module as a testImplementation dependency alongside " +
            "kmptoolkit-permission to drive your own screens through the denied, permanently " +
            "denied and revoked-while-backgrounded paths without an emulator."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.permission.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingPermissionHandler implements PermissionHandler and
            // deals in Permission/PermissionStatus, so all three are its own public API.
            api(project(":kmptoolkit-permission"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
