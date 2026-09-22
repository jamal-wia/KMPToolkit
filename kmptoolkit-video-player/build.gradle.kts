plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Video Player")
    pomDescription.set(
        "A headless video player for shared Kotlin code: one VideoPlayer interface over Media3 " +
            "ExoPlayer on Android and AVPlayer on iOS, a typed state flow with position, buffering, " +
            "picture size, volume, mute and repeat mode, and an explicit release contract. Render it " +
            "with kmptoolkit-video-player-compose; on desktop add kmptoolkit-video-player-vlcj for " +
            "the playback engine."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.video.player"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // A desktop target, under docs/01-architecture.md § "Desktop targets": a video screen lives in
    // shared UI code, which could not compile for a desktop build if these types existed on only
    // two of its three targets. The desktop engine itself is not here — it is VLCJ (GPL-3) and ships
    // separately as kmptoolkit-video-player-vlcj, so the GPL dependency reaches only a consumer that
    // asks for it.
    jvm()

    sourceSets {
        commonMain.dependencies {
            // api: VideoPlayer exposes StateFlow in its own signatures.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // api: media3PlayerOrNull() returns androidx.media3.common.Player.
            api(libs.androidx.media3.exoplayer)
            implementation(libs.androidx.media3.exoplayer.hls)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.androidx.media3.test.utils)
            implementation(libs.androidx.media3.test.utils.robolectric)
        }
    }
}
