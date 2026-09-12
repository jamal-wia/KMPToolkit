// `compose.uiTest` (Compose Multiplatform's test artifact, used by the Robolectric-backed UI tests
// in androidUnitTest) is still behind this opt-in as of Compose Multiplatform 1.11.
@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.compose")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Language Compose")
    pomDescription.set(
        "Compose Multiplatform wiring for kmptoolkit-language: AppLocale provides " +
            "LocalLayoutDirection from an AppLanguage and forces a recomposition on language " +
            "change, and Modifier.mirrorOnRtl() / mirrorOnLtr() flip a directional drawable that " +
            "has no Compose auto-mirrored counterpart. Split from kmptoolkit-language so the base " +
            "module stays plain Kotlin for consumers who read AppLanguageHolder outside Compose."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.language.compose"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmptoolkit-language"))
            implementation(compose.runtime)
            implementation(compose.ui)
        }

        androidUnitTest.dependencies {
            implementation(compose.uiTest)
        }
    }
}

dependencies {
    // See kmptoolkit-systembars/build.gradle.kts for why this is debugImplementation rather than
    // an androidUnitTest dependency: ActivityScenario needs a ComponentActivity declared in the
    // merged *debug* manifest, which a test-only configuration is merged too late to provide.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
