plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Permission Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-permission: FakePermissionController, a PermissionController " +
            "double for any permission — common or platform-specific — that records every check, " +
            "request, batch and settings trip, lets a test script what each one answers — including " +
            "a request that flips the status the way a real system dialog would — and can hold a " +
            "request in flight to test cancellation. Pick this module as a testImplementation dependency alongside " +
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
            // api, not implementation: FakePermissionController implements PermissionController and
            // deals in Permission/PermissionStatus, so all three are its own public API.
            api(project(":kmptoolkit-permission"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
