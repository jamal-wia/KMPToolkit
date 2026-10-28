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

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.language.compose"
    }

    iosArm64()
    iosSimulatorArm64()

    // Follows kmptoolkit-language: a UI tree shared with a desktop build wraps its root in AppLocale
    // exactly as the phone build does, and could not compile while this resolved on only some of its
    // targets.
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmptoolkit-language"))
            implementation(libs.compose.runtime)
            implementation(libs.compose.ui)
        }

        androidHostTest.dependencies {
            implementation(libs.compose.ui.test)
            // ComponentActivity for the Robolectric Compose test host — see
            // kmptoolkit-systembars/build.gradle.kts for why this is a host-test dependency.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.manifest)
        }

        jvmTest.dependencies {
            implementation(libs.compose.ui.test)
            // The Skia runtime runComposeUiTest renders through on desktop. Test-only, and for the
            // machine running the tests — nothing here reaches a published artifact.
            implementation(compose.desktop.currentOs)
        }
    }
}
