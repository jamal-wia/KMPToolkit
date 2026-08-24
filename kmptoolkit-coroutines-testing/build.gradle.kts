plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Coroutines Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-coroutines: TestAppDispatchers, an AppDispatchers double " +
            "that collapses io/main/default onto one deterministic UnconfinedTestDispatcher. Pick " +
            "this module as a testImplementation dependency alongside kmptoolkit-coroutines — it " +
            "is kept separate so kotlinx-coroutines-test never reaches a consumer's runtime " +
            "classpath."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.coroutines.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: TestAppDispatchers implements AppDispatchers, so the
            // interface is part of this module's own public API.
            api(project(":kmptoolkit-coroutines"))
            api(libs.kotlinx.coroutines.test)
        }
    }
}
