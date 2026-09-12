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
    pomName.set("KMPToolkit System Bars")
    pomDescription.set(
        "Status-bar and navigation-bar control for Compose Multiplatform: a SystemBarsController " +
            "you create and own, a base configuration one writer owns, and per-axis overrides a " +
            "screen pushes and releases through a SystemBarsEffect. Pick this module if two " +
            "screens in your app have ever fought over the bars — one sets light icons, the next " +
            "sets dark, and navigating back leaves the wrong one on screen. Overrides are " +
            "layered and scoped to composition, so leaving a screen restores exactly the state " +
            "underneath it, and a screen that owns only the status bar never clobbers one that " +
            "owns the navigation bar. It styles the bars; it is not a theming system and not an " +
            "insets library. Also includes AutoSystemBarsIconStyle, an opt-in composable that " +
            "derives each bar's icon style from the pixels actually drawn under it, and " +
            "ScreenWakeLockController, an unrelated keep-screen-awake primitive."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.systembars"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // The one module in the suite with a desktop target. A Compose Multiplatform app that shares a
    // UI tree between phone and desktop cannot compile that tree at all if the system-bars types it
    // references exist on only two of its three targets — and the whole point of this module is that
    // a screen states what it wants from the bars without caring where it is running. Desktop has no
    // system bars, so every platform call here is a no-op; the layer stack still behaves identically,
    // which is what makes the shared tree compile and behave the same way. See
    // `docs/kmptoolkit-systembars/05-platform-notes.md`.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: SystemBarsController exposes StateFlow<SystemBarsConfig>,
            // so a consumer cannot read the current configuration without coroutines-core on the
            // compile classpath.
            api(libs.kotlinx.coroutines.core)

            // Exactly what is used and nothing more. The state model (SystemBarsConfig, the
            // controller, the layer stack) is plain Kotlin with no Compose dependency at all;
            // `runtime` is for the effect's DisposableEffect/SideEffect, and `ui` is only for the
            // dialog-window effect, which needs LocalView and DialogWindowProvider on Android.
            implementation(compose.runtime)
            implementation(compose.ui)

            // AutoSystemBarsIconStyle's probe Box: Modifier.fillMaxSize(), WindowInsets.statusBars
            // / navigationBars.
            implementation(compose.foundation)

            // AutoSystemBarsIconStyle pauses sampling below Lifecycle.State.STARTED — a backgrounded
            // screen must be neither read nor written to. This is the one real dependency this pulls
            // in beyond compose.runtime/compose.ui/compose.foundation: LocalLifecycleOwner +
            // repeatOnLifecycle.
            implementation(libs.androidx.lifecycle.runtime.compose)
        }

        androidMain.dependencies {
            // WindowCompat / WindowInsetsControllerCompat — the whole Android implementation.
            implementation(libs.androidx.core.ktx)
        }

        commonTest.dependencies {
            // Only for the concurrency tests, which need real parallelism from Dispatchers.Default
            // on both the JVM and Kotlin/Native.
            implementation(libs.kotlinx.coroutines.test)
        }

        androidUnitTest.dependencies {
            implementation(compose.uiTest)
            // AutoSystemBarsIconStyleLifecycleTest drives the test clock frame by frame and needs a
            // real Activity to dispatch window insets into, which is `createAndroidComposeRule` —
            // the JUnit4 rule, not the `runComposeUiTest` the other UI tests here use. Versioned
            // through the Compose BOM, as the catalog entry carries no version of its own.
            implementation(project.dependencies.platform(libs.androidx.compose.bom))
            implementation(libs.androidx.compose.ui.test.junit4)
            implementation(libs.androidx.activity.compose)
        }
    }
}

dependencies {
    // Robolectric launches the Compose test host through ActivityScenario, which needs an
    // `androidx.activity.ComponentActivity` declared in the *merged debug manifest* — a test-only
    // configuration is merged too late for that, which is why this is `debugImplementation` and not
    // an androidUnitTest dependency. Without it every UI test dies with "Unable to resolve activity
    // for Intent ... ComponentActivity". ui-test-manifest is a manifest-only AAR, the debug variant
    // is never published (`publishLibraryVariants("release")`), so nothing reaches a consumer.
    //
    // Versioned through the Compose BOM because the catalog's ui-test-manifest entry carries no
    // version of its own.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
