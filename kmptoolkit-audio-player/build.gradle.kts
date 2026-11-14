plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest only: lets the Android engine be driven on the host JVM.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Audio Player")
    pomDescription.set(
        "A headless audio player for shared Kotlin code: one AudioPlayer interface over " +
            "MediaPlayer on Android and AVPlayer on iOS, a typed PlayerState flow with the " +
            "position in it, and an explicit release contract that makes double-release and " +
            "use-after-release inert instead of fatal. Pick this module if you need to play a " +
            "sound, a voice message, or a track from common code without pulling in ExoPlayer, a " +
            "UI framework, or a DI container."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.audio.player"
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // implementation: the state-machine lock is an internal detail; nothing in the public
            // API mentions kmptoolkit-core.
            implementation(project(":kmptoolkit-core"))
            // api, not implementation: AudioPlayer exposes StateFlow in its own signatures, and
            // createAudioPlayer takes a CoroutineContext, so consumers need coroutines-core on
            // their compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Test scope only (never in the published POM): the fake-versus-recording-engine differential runs
            // one scenario table through the player over both doubles, which needs both in one test source set.
            implementation(project(":kmptoolkit-audio-player-testing"))
        }
        // The tests here mostly use their own recording engine; FakePlaybackEngine is covered by its own module's
        // tests and, in test scope only, by the differential of this module.
        //
        // No kotlinx-coroutines-android either: nothing in androidMain touches Dispatchers.Main.
        // MediaPlayer is created on Dispatchers.IO precisely so its callbacks land on the main
        // looper without this module having to own a main dispatcher.
    }
}
