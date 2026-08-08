plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Audio Recorder Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-audio-recorder: FakeAudioRecorder, an AudioRecorder double " +
            "that enforces the same state machine without a microphone, a file, or a clock, and " +
            "lets a test script permission denials and engine failures. Pick this module as a " +
            "testImplementation dependency alongside kmptoolkit-audio-recorder when you need to " +
            "test the code around a recorder rather than the recorder itself."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.audio.recorder.testing"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: FakeAudioRecorder implements AudioRecorder and returns
            // RecorderResult/RecorderState, so those types are part of this module's own API.
            api(project(":kmptoolkit-audio-recorder"))
        }
        commonTest.dependencies {
            // Only the fake's own suite needs this — `prepare` is a suspend function, so exercising
            // it takes a coroutine builder. It is deliberately not an `api` dependency: a consumer
            // brings whatever test runner they already use.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
