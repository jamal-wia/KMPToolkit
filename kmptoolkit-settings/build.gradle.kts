plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the Android LanguageApplier picks between the framework
    // LocaleManager (API 33+) and the Locale/LocaleList defaults below it, which is only testable
    // against a real Context at two SDK levels, and the "no permission is in the merged manifest"
    // guarantee is only assertable against a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Settings")
    pomDescription.set(
        "App-level display preferences for Kotlin Multiplatform: font scale, theme mode and " +
            "app language, persisted through kmptoolkit-storage and exposed as StateFlows the " +
            "UI can collect. The font scale is an open value type rather than a fixed set of " +
            "steps and the language is a BCP 47 tag rather than a closed enum, so the scale " +
            "steps and the language list stay yours; a LanguageApplier applies the chosen " +
            "language process-wide through the framework LocaleManager on Android and " +
            "AppleLanguages on iOS. Pick this module if your shared code needs those three " +
            "preferences to survive a restart with load and write failures reported as typed " +
            "SettingsError values instead of silently falling back to defaults."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.settings"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: every setting is exposed as a StateFlow, so
            // kotlinx-coroutines-core is on this module's own public surface.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: createAppSettings takes a KeyValueStorage and
            // SettingsError wraps a StorageError, so both are part of this module's public
            // signatures and a consumer needs them on their compile classpath.
            api(project(":kmptoolkit-storage"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)

            // The store double this module's tests need already exists as a published artifact —
            // see docs/kmptoolkit-settings/01-overview.md on why this module ships no -testing
            // artifact of its own.
            implementation(project(":kmptoolkit-storage-testing"))
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
