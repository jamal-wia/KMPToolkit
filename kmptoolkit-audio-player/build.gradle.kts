plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
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

android {
    namespace = "io.github.jamal_wia.kmptoolkit.audio.player"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: AudioPlayer exposes StateFlow in its own signatures, and
            // createAudioPlayer takes a CoroutineContext, so consumers need coroutines-core on
            // their compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // No dependency on :kmptoolkit-audio-player-testing — that module depends on this one, so
        // the reverse edge would be a project cycle. The tests here use their own recording engine;
        // FakePlaybackEngine is covered by that module's own tests.
        //
        // No kotlinx-coroutines-android either: nothing in androidMain touches Dispatchers.Main.
        // MediaPlayer is created on Dispatchers.IO precisely so its callbacks land on the main
        // looper without this module having to own a main dispatcher.
    }
}
