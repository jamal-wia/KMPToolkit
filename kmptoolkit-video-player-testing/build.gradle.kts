plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Video Player Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-video-player: a scriptable in-memory VideoPlaybackEngine — fail " +
            "a load, advance the playhead, report buffering, a picture size or completion — so code " +
            "that consumes VideoPlayer can be tested without a device, a simulator or a video file."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.video.player.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmptoolkit-video-player"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
