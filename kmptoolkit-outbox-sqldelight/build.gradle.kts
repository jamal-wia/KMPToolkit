plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidUnitTest: the contract has to run against a real SQLite, and the
    // Android driver needs a real Context to open one. It is also what makes LibraryManifestTest
    // able to read the merged manifest through a real PackageManager.
    id("kmptoolkit.androidtest")
    alias(libs.plugins.sqldelight)
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Outbox SQLDelight")
    pomDescription.set(
        "The SQLDelight-backed OutboxStore for kmptoolkit-outbox: a durable queue table, an " +
            "autoincrementing insertion sequence so same-millisecond enqueues keep their FIFO " +
            "order, a single-statement compare-and-set behind recordFailure's lease guard, and a " +
            "reentrant TransactionRunner that lets a domain write and the effect it owes commit " +
            "together. Runs standalone on its own database file — named after your app, so two " +
            "outboxes never collide — or on a SqlDriver you already own, which is what makes it " +
            "a genuinely transactional outbox. Pick this module if you use kmptoolkit-outbox and " +
            "do not want to implement its storage port yourself."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.outbox.sqldelight"
}

sqldelight {
    databases {
        // Named and packaged for KMPToolkit rather than for any consumer: the generated database
        // class is part of this module's published API (a consumer embedding the queue in their
        // own database needs its Schema), so it must not carry a name that could collide with a
        // database they generate themselves.
        create("KmpToolkitOutboxDatabase") {
            packageName.set("io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db")
            // Sync generation, deliberately: android-driver and native-driver are both synchronous,
            // and SuspendingTransacter would only add a suspension point per statement. The
            // suspending half of TransactionRunner is handled by confining every statement to one
            // thread instead — see SqlDelightTransactionRunner.
            generateAsync.set(false)
            // The dumped schema is what a future .sqm migration is verified against. Committed, so
            // a schema change that forgets its migration fails the build instead of a consumer's
            // upgrade.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: this module's whole purpose is to hand back an OutboxStore
            // and a TransactionRunner, both of which are kmptoolkit-outbox types.
            api(project(":kmptoolkit-outbox"))

            // api: createOutboxStorage(SqlDriver) names SqlDriver, and outboxDatabaseSchema names
            // SqlSchema — a consumer embedding the queue in their own database needs both on their
            // compile classpath.
            api(libs.sqldelight.runtime)

            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // OutboxStoreContract lives here. No cycle: kmptoolkit-outbox-testing depends on
            // kmptoolkit-outbox, never on this module.
            implementation(project(":kmptoolkit-outbox-testing"))
        }
        androidMain.dependencies {
            implementation(libs.sqldelight.android.driver)
        }
        iosMain.dependencies {
            implementation(libs.sqldelight.native.driver)
        }
        androidUnitTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
