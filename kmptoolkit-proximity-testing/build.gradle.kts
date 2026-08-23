plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Proximity Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-proximity: FakeProximitySensor, a ProximitySensor double " +
            "driven with emit(near) and a mutable isAvailable, so a test can walk a phone going " +
            "to the ear mid-call or a device with no sensor at all without an emulator. Pick " +
            "this module as a testImplementation dependency alongside kmptoolkit-proximity."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.proximity.testing"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: FakeProximitySensor implements ProximitySensor, so the
            // whole of that module is on this one's public surface.
            api(project(":kmptoolkit-proximity"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
