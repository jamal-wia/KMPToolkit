plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Coroutines")
    pomDescription.set(
        "A testable coroutine dispatcher seam: AppDispatchers plus a TestAppDispatchers double " +
            "that collapses io/main/default onto one deterministic UnconfinedTestDispatcher. Pick " +
            "this module if your code references Dispatchers.IO/Main/Default directly and you " +
            "want that replaceable in tests without exercising real background threads."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.coroutines"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: AppDispatchers exposes CoroutineDispatcher in its own
            // signatures, so consumers need kotlinx-coroutines-core on their compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        // No commonTest dependency on :kmptoolkit-coroutines-testing — that module depends on
        // this one, so the reverse edge would be a project cycle. TestAppDispatchers is covered
        // by that module's own tests.
    }
}
