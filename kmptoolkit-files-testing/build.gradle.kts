plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
    // Robolectric for androidHostTest: the contract also runs against the real Android factory.
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Files Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-files: FakeFileAccess, an in-memory FileAccess that runs the real " +
            "engine so it cannot drift from the device behaviour, with fault injection, crash and " +
            "power-loss simulation; and FileAccessContract, every observable FileAccess contract as a " +
            "plain check for decorators and custom implementations. Pick this module as a " +
            "testImplementation dependency alongside kmptoolkit-files when you test code that writes " +
            "or deletes files, or that wraps FileAccess."
    )
}

kotlin {
    android {
        namespace = "io.github.jamal_wia.kmptoolkit.files.testing"
    }

    iosArm64()
    iosSimulatorArm64()

    // Published for every target kmptoolkit-files publishes, so a desktop test source set can use the
    // fake too.
    jvm()

    sourceSets {
        // The actuals that Android and the desktop JVM share (a lock and a blocking coroutine runner) live
        // in one source directory compiled into both targets, as in kmptoolkit-files.
        val jvmShared: String = "src/jvmShared/kotlin"
        androidMain {
            kotlin.srcDir(jvmShared)
        }
        jvmMain {
            kotlin.srcDir(jvmShared)
        }
        // The same trick for the tests that need java.io.File: real-file contract runs and symbolic links.
        val jvmSharedTest: String = "src/jvmSharedTest/kotlin"
        getByName("androidHostTest") {
            kotlin.srcDir(jvmSharedTest)
        }
        jvmTest {
            kotlin.srcDir(jvmSharedTest)
        }
        commonMain.dependencies {
            // api, not implementation: FakeFileAccess implements FileAccess and returns FileResult/FileError,
            // so those types are part of this module's own API; kmptoolkit-files exports kmptoolkit-core
            // (the SPI opt-in marker) the same way.
            api(project(":kmptoolkit-files"))
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
            implementation(project(":test-support"))
        }
    }
}
