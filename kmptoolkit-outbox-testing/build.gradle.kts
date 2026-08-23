plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Outbox Testing")
    pomDescription.set(
        "Test fixtures for kmptoolkit-outbox: InMemoryOutboxStore, a complete OutboxStore you " +
            "can run the real engine against without a database; OutboxStoreContract, which " +
            "checks every invariant the store SPI promises so your own implementation can prove " +
            "it holds them; FakeOutbox for testing code that merely enqueues; plus a recording " +
            "wake scheduler, a hand-driven constraint provider, and a movable clock. Pick this " +
            "module as a testImplementation dependency alongside kmptoolkit-outbox."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.outbox.testing"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            // api, not implementation: every fixture here implements a kmptoolkit-outbox interface
            // and names its types in public signatures.
            api(project(":kmptoolkit-outbox"))
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
        // No test framework in commonMain, deliberately. OutboxStoreContract is plain suspending
        // functions throwing a typed AssertionError rather than a kotlin.test base class: on the
        // JVM, kotlin.test's annotations are typealiases to a framework's own, so publishing a
        // base class would put JUnit on the compile classpath of everyone depending on this
        // artifact — including their iOS build, where it means nothing.
    }
}
