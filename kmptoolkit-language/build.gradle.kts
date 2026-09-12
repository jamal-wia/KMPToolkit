plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Language")
    pomDescription.set(
        "App language selection and the platform-locale side effect that goes with it: an " +
            "AppLanguageHolder you create and own, an AppLanguage of just a BCP-47 code and a " +
            "reading direction, and applyLanguageGlobally() to push it onto Locale.setDefault / " +
            "AppleLanguages. Pick this module if your app lets a user choose a language and you " +
            "need that choice to reach string-resource lookups outside Compose too. It carries no " +
            "language list, no display names and no storage of its own — those are your app's " +
            "call; see kmptoolkit-language-compose for the Compose-side layout-direction wiring."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.language"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // Desktop, for the same reason kmptoolkit-systembars publishes it (docs/01-architecture.md §
    // "Desktop targets"): AppLanguage is the kind of type a consumer threads through the public API of
    // its shared modules — a settings repository, a language picker, a screen state — and a shared
    // module compiled for desktop cannot mention it at all while the type resolves on only some of
    // its targets. The JVM half is real, not a stub: a desktop JVM has a process-wide default locale,
    // and the operating system's language to fall back to.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: AppLanguageHolder exposes StateFlow<AppLanguage>, so a
            // consumer cannot read the current language without coroutines-core on the compile
            // classpath.
            api(libs.kotlinx.coroutines.core)
        }
    }
}
