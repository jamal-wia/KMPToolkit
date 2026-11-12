plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Scheduler")
    pomDescription.set(
        "Exact-time, one-shot local alarms from shared Kotlin: AlarmManager on Android, " +
            "UNUserNotificationCenter on iOS, behind one AlarmScheduler interface that reports " +
            "what the OS actually granted. Pick this module if your shared code must fire " +
            "something at a wall-clock instant with no network and no server push — and not if " +
            "you need repeating schedules, background job execution, or retries, which it " +
            "deliberately does not do."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.scheduler"
    }

    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // The only dependency: the Android receiver bridges a broadcast to a suspend handler,
            // and the iOS scheduler awaits UNUserNotificationCenter's completion handlers. Neither
            // is expressible without coroutines, and the public API is suspend regardless.
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            // Test scope only (never in the published POM): the fidelity differential runs the real
            // schedulers and RecordingAlarmScheduler through one script, which needs both in one test source set.
            implementation(project(":kmptoolkit-scheduler-testing"))
        }
        androidHostTest.dependencies {
            implementation(libs.junit)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
