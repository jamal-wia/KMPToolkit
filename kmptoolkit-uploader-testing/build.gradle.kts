plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Uploader Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-uploader: InMemoryUploaderStore, a complete UploaderStore you " +
            "can run the real engine against without a database; UploaderStoreContract, which " +
            "checks every invariant the store SPI promises so your own implementation can prove " +
            "it holds them; FakeUploader for testing code that merely enqueues; plus a recording " +
            "wake scheduler, a hand-driven constraint provider, and a movable clock. Pick this " +
            "module as a testImplementation dependency alongside kmptoolkit-uploader."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.uploader.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: every fixture here implements a kmptoolkit-uploader interface
            // and names its types in public signatures.
            api(project(":kmptoolkit-uploader"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // No test framework in commonMain, deliberately. UploaderStoreContract is plain suspending
        // functions throwing a typed AssertionError rather than a kotlin.test base class: on the
        // JVM, kotlin.test's annotations are typealiases to a framework's own, so publishing a
        // base class would put JUnit on the compile classpath of everyone depending on this
        // artifact — including their iOS build, where it means nothing.
    }
}
