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
    pomName.set("KMPToolkit Uploader SQLDelight")
    pomDescription.set(
        "The SQLDelight-backed UploaderStore for kmptoolkit-uploader: a durable queue table, an " +
            "autoincrementing insertion sequence so same-millisecond enqueues keep their FIFO " +
            "order, a single-statement compare-and-set behind recordFailure's lease guard, and a " +
            "reentrant TransactionRunner that lets a domain write and the effect it owes commit " +
            "together. Runs standalone on its own database file — named after your app, so two " +
            "uploaders never collide — or on a SqlDriver you already own, which is what makes it " +
            "a genuinely transactional uploader. Pick this module if you use kmptoolkit-uploader and " +
            "do not want to implement its storage port yourself."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.uploader.sqldelight"
}

sqldelight {
    databases {
        // Named and packaged for KMPToolkit rather than for any consumer: the generated database
        // class is part of this module's published API (a consumer embedding the queue in their
        // own database needs its Schema), so it must not carry a name that could collide with a
        // database they generate themselves.
        create("KmpToolkitUploaderDatabase") {
            packageName.set("io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db")
            // Sync generation, deliberately: android-driver and native-driver are both synchronous,
            // and SuspendingTransacter would only add a suspension point per statement. The
            // suspending half of TransactionRunner is handled by confining every statement to one
            // thread instead — see SqlDelightTransactionRunner.
            generateAsync.set(false)
            // The dumped schema is what a future .sqm migration is verified against. Committed, so
            // a schema change that forgets its migration fails the build instead of a consumer's
            // upgrade.
            //
            // The path is SQLDelight's convention and not a free choice: verifyMigrations looks for
            // the dump inside the .sq source directory regardless of what this is set to. See the
            // task-ordering rule below for the one consequence of it living in a generator input.
            schemaOutputDirectory.set(file("src/commonMain/sqldelight/databases"))
            verifyMigrations.set(true)
        }
    }
}

// The schema dump lands inside src/commonMain/sqldelight — SQLDelight's convention, and not
// negotiable: the migration verifier looks for it there whatever schemaOutputDirectory says. That
// directory is also the code generator's *input*, so Gradle 9 refuses to run the dump alongside
// anything that reads it unless the order is stated. Without this, regenerating the schema in the
// same invocation as a build fails with "uses this output ... without declaring an explicit or
// implicit dependency" — which is exactly the invocation anyone regenerating it would reach for.
tasks.matching { task ->
    task.name.endsWith("KmpToolkitUploaderDatabaseInterface") ||
        task.name.endsWith("KmpToolkitUploaderDatabaseMigration")
}.configureEach {
    mustRunAfter(tasks.matching { it.name.endsWith("KmpToolkitUploaderDatabaseSchema") })
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: this module's whole purpose is to hand back an UploaderStore
            // and a TransactionRunner, both of which are kmptoolkit-uploader types.
            api(project(":kmptoolkit-uploader"))

            // api: createUploaderStorage(SqlDriver) names SqlDriver, and uploaderDatabaseSchema names
            // SqlSchema — a consumer embedding the queue in their own database needs both on their
            // compile classpath.
            api(libs.sqldelight.runtime)

            implementation(libs.sqldelight.coroutines.extensions)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // UploaderStoreContract lives here. No cycle: kmptoolkit-uploader-testing depends on
            // kmptoolkit-uploader, never on this module.
            implementation(project(":kmptoolkit-uploader-testing"))
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
