plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Downloader Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-downloader: FakeDownloader for testing code that merely " +
            "asks for a resource, FakeDownloaderStorage for testing storage-facing code, plus a " +
            "recording notifier, an in-memory DownloadStateStore, a test-only unit/group " +
            "catalogue, and a virtual-clock DownloadDispatchers so a stall timeout costs no real " +
            "time. Pick this module as a testImplementation dependency alongside " +
            "kmptoolkit-downloader."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.downloader.testing"
}

kotlin {
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: every fixture here implements a kmptoolkit-downloader
            // interface and names its types in public signatures.
            api(project(":kmptoolkit-downloader"))
            // A main-source dependency on purpose, like :kmptoolkit-outbox-testing's clock and
            // wake-scheduler fixtures: TestDownloadDispatchers exists to be used from tests, and
            // the virtual-clock dispatcher it wraps is the whole point of shipping it.
            implementation(libs.kotlinx.coroutines.test)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
