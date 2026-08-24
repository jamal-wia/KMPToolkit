plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Storage Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-storage: InMemoryKeyValueStorage, a map-backed " +
            "SecureKeyValueStorage double that needs no Context, no SharedPreferences file and " +
            "no Keychain, and lets a test script StorageError failures per operation. Pick this " +
            "module as a testImplementation dependency alongside kmptoolkit-storage when you " +
            "need to test the code around a store rather than the store itself."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.storage.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: InMemoryKeyValueStorage implements SecureKeyValueStorage and
            // returns StorageResult/StorageError, so those types are part of this module's own API.
            api(project(":kmptoolkit-storage"))
        }
    }
}
