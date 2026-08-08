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
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            // TestAppDispatchers ships in commonMain (not commonTest) so consumers can reach it
            // from their own test source sets via a plain `implementation("...kmptoolkit-coroutines")`
            // — Kotlin Multiplatform has no mechanism to expose one module's commonTest to another
            // module's commonTest or to a consumer's test source set.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
