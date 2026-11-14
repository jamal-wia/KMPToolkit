plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: AndroidRecordingFileSystem needs a real Context and a real
    // filesystem, and the "RECORD_AUDIO is not in the merged manifest" guarantee is only assertable
    // against a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Audio Recorder")
    pomDescription.set(
        "A headless microphone recorder for Kotlin Multiplatform: one AudioRecorder interface " +
            "over MediaRecorder and AVAudioRecorder, an explicit prepare/start/pause/resume/stop " +
            "state machine, and typed RecorderError results instead of platform exceptions. Pick " +
            "this module if you need voice notes or capture in shared code and want illegal " +
            "transitions, a missing RECORD_AUDIO grant, and a full disk to arrive as values you " +
            "can handle rather than crashes."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.audio.recorder"
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // implementation: the state-machine lock is an internal detail; nothing in the public
            // API mentions kmptoolkit-core.
            implementation(project(":kmptoolkit-core"))
            // implementation: the platform file systems of the recorder are kmptoolkit-files calls; nothing in
            // the public API mentions it.
            implementation(project(":kmptoolkit-files"))
            // api, not implementation: AudioRecorder exposes StateFlow in its own signatures, so
            // consumers need kotlinx-coroutines-core on their compile classpath.
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Not published; the path converter and host assumptions for the file-system tests.
            implementation(project(":test-support"))
            // Test scope only (never in the published POM): the fake-versus-real differential runs the real
            // recorder over its stand-ins and FakeAudioRecorder through one scenario table in one source set.
            implementation(project(":kmptoolkit-audio-recorder-testing"))
        }
    }
}
