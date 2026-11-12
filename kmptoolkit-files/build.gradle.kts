plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: the Android factory and the app directories need a real Context.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Files")
    pomDescription.set(
        "File-system primitives for Kotlin Multiplatform shared code: atomic durable writes with a chosen " +
            "durability, safe recursive delete that never follows a symbolic link, bounded reads, leftover " +
            "sweeps, per-key coroutine locks, path and file-name rules including Windows, and app " +
            "directories with backup exclusion. Paths are plain Strings and every failure is a typed " +
            "FileError result instead of an exception. Runs on Android, iOS and the desktop JVM. " +
            "Pick this module if shared code writes small records that must survive a crash, or cleans " +
            "up files."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.files"
    }

    iosArm64()
    iosSimulatorArm64()

    // A desktop target, under the exception in docs/01-architecture.md § "Desktop targets":
    // FileAccess and KeyedMutex are types a consumer threads through shared code, and the jvm store
    // of kmptoolkit-storage depends on this module.
    jvm()

    sourceSets {
        // `src/jvmShared` holds the java.nio based code that Android and the desktop JVM share. It is
        // compiled into both targets by adding it as a source directory of each, rather than as an
        // intermediate source set: the Android KMP library target does not join a custom hierarchy group.
        val jvmShared: String = "src/jvmShared/kotlin"
        androidMain {
            kotlin.srcDir(jvmShared)
        }
        jvmMain {
            kotlin.srcDir(jvmShared)
        }
        // The same trick for the tests that need java.io.File / java.nio (real-file helpers, descriptor counts).
        val jvmSharedTest: String = "src/jvmSharedTest/kotlin"
        getByName("androidHostTest") {
            kotlin.srcDir(jvmSharedTest)
        }
        jvmTest {
            kotlin.srcDir(jvmSharedTest)
        }
        commonMain.dependencies {
            // api: CoroutineDispatcher appears in the signature of withIo.
            api(libs.kotlinx.coroutines.core)
            // api: @ToolkitInternalApi marks the public SPI.
            api(project(":kmptoolkit-core"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            // Not published; host path rules and the unwritable-directory helper for the file tests.
            implementation(project(":test-support"))
        }
    }
}
