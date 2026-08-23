plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Session Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-session: RecordingSessionCleaner and RecordingSessionRevoker, " +
            "doubles that count their invocations and let a test make them throw or hang on " +
            "demand. Pick this module as a testImplementation dependency alongside " +
            "kmptoolkit-session when you need to assert that ending a session wipes what it " +
            "should — including the failure paths that are awkward to reproduce for real."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.session.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: the fixtures implement SessionCleaner and SessionRevoker,
            // so those types are part of this module's own public API.
            api(project(":kmptoolkit-session"))
        }
        commonTest.dependencies {
            // Only this module's own tests need these: the fixtures pull in no test framework, but
            // driving them through a real SessionManager — which is how a consumer uses them, and
            // therefore what these tests must exercise — needs a test dispatcher to run on.
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":kmptoolkit-coroutines-testing"))
        }
    }
}
