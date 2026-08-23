plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Audio Player Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-audio-player: FakePlaybackEngine, an in-memory " +
            "PlaybackEngine you drive by hand — fail a load, advance the playhead, fire " +
            "completion — so code that consumes AudioPlayer can be tested without a device, a " +
            "simulator, or an audio file. Pick this module as a testImplementation dependency " +
            "alongside kmptoolkit-audio-player."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.audio.player.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: FakePlaybackEngine implements PlaybackEngine and its
            // methods take AudioSource, so those types are part of this module's own public API.
            api(project(":kmptoolkit-audio-player"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
