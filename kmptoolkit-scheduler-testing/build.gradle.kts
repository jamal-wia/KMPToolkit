plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Scheduler Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-scheduler: RecordingAlarmScheduler, an in-memory " +
            "AlarmScheduler double that records what was armed and cancelled and lets a test " +
            "choose the AlarmScheduleResult it returns. Pick this module as a testImplementation " +
            "dependency alongside kmptoolkit-scheduler when you need to assert that your code " +
            "schedules the right alarms without an emulator or a device."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.scheduler.testing"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: RecordingAlarmScheduler implements AlarmScheduler and
            // exposes ScheduledAlarm, so those types are part of this module's own public API.
            api(project(":kmptoolkit-scheduler"))
        }
        commonTest.dependencies {
            // Only this module's own tests need it: the fixture itself pulls in no test framework,
            // but running a suspend function in a test does.
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
