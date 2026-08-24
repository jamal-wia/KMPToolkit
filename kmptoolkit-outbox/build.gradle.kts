plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the WorkManager wake adapter is only meaningful against a
    // real Context, and the "no permission of our own is in the merged manifest" guarantee is only
    // assertable through a real PackageManager.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Outbox")
    pomDescription.set(
        "A transactional outbox for shared Kotlin code: enqueue an outgoing effect, and it is " +
            "persisted before the call returns and retried with exponential backoff until its " +
            "handler confirms delivery — across process death, offline stretches, and OS-level " +
            "wake-ups on Android WorkManager and iOS BGTaskScheduler. Storage is a port you " +
            "implement (OutboxStore), so the module ships with no database dependency at all; " +
            "ordering keys give strict FIFO channels per entity, and detached delivery hands a " +
            "long upload to an external executor under a self-expiring lease. Pick this module " +
            "if your app must not lose a message the user already believes was sent."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.outbox"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: Outbox.observe returns a Flow, createOutboxEngine takes a
            // CoroutineScope, and ConstraintProvider exposes a StateFlow — all of them public
            // signatures, so consumers need coroutines-core on their compile classpath.
            api(libs.kotlinx.coroutines.core)

            // api, not implementation: createOutboxEngine takes a Logger, so the type is part of
            // this module's public signature. kmptoolkit-logging itself has no dependencies, so
            // this adds nothing else to a consumer's graph.
            api(project(":kmptoolkit-logging"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        androidMain.dependencies {
            // The Android wake adapter. See LibraryManifestTest for the permissions this merges
            // into a consumer's manifest, and docs/kmptoolkit-outbox/05-platform-notes.md for why
            // they cannot be removed.
            implementation(libs.androidx.work.runtime.ktx)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // No dependency on :kmptoolkit-outbox-testing — that module depends on this one, so the
        // reverse edge would be a project cycle. The tests here use their own store fixture, which
        // is deliberate beyond the cycle: two independent OutboxStore implementations (this one and
        // InMemoryOutboxStore) cross-check the SPI contract instead of one confirming itself.
        //
        // No :kmptoolkit-coroutines either: the engine takes the CoroutineScope it runs on, so it
        // never names a dispatcher and has nothing to abstract behind AppDispatchers.
    }
}
