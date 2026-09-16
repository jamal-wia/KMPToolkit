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
    pomName.set("KMPToolkit Hardware Keys")
    pomDescription.set(
        "Hardware-key interception for Compose dialog-class windows on Android. A Dialog, " +
            "ModalBottomSheet or focusable Popup renders in its own platform window with its own key " +
            "dispatch, so an Activity's onKeyDown never sees a volume or back key pressed while one " +
            "is open — and the system volume panel appears over it. Pick this module if your app " +
            "already handles keys in its Activity and needs the same policy to hold inside those " +
            "windows: register one interceptor from Application.onCreate and call " +
            "DialogWindowHardwareKeyEffect() inside each dialog window. It decides nothing about " +
            "which keys to consume — that stays your policy — and it is a no-op on iOS and desktop, " +
            "which have no such key dispatch to intercept."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.hardwarekeys"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // A desktop target, under the exception in docs/01-architecture.md § "Desktop targets". The
    // effect is called from inside dialog content, and dialog content is exactly the kind of UI a
    // Compose Multiplatform app shares with desktop; if the effect existed on only two of its three
    // targets, that shared tree would not compile at all. Desktop has no Android-style per-window key
    // routing, so the jvm actual is a no-op. See `docs/kmptoolkit-hardware-keys/05-platform-notes.md`.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // `runtime` for DisposableEffect; `ui` for LocalView and DialogWindowProvider on Android.
            implementation(compose.runtime)
            implementation(compose.ui)
        }

        androidMain.dependencies {
            // ViewCompat.addOnUnhandledKeyEventListener — the Popup-window hook.
            implementation(libs.androidx.core.ktx)

            // api, not implementation: DialogWindowHardwareKeyPolicy.install takes a
            // `logger: Logger = NoopLogger`. Android only, because the policy is Android-only API —
            // which is also why this module's jvm target needs no logging artifact of its own.
            api(project(":kmptoolkit-logging"))
        }

        androidUnitTest.dependencies {
            implementation(compose.uiTest)
        }
    }
}

dependencies {
    // Robolectric launches the Compose test host through ActivityScenario, which needs an
    // `androidx.activity.ComponentActivity` declared in the *merged debug manifest* — a test-only
    // configuration is merged too late for that. The debug variant is never published, so nothing
    // reaches a consumer. See kmptoolkit-systembars/build.gradle.kts for the long form.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
