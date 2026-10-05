plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Core")
    pomDescription.set(
        "Internal support artifact of the KMPToolkit suite: the cross-module internal API marker " +
            "and the state-machine lock the suite's players and recorder share. Not meant to be " +
            "depended on directly — it is pulled in transitively by the modules that need it, and " +
            "nothing in it carries a compatibility promise."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.core"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    // jvm() because kmptoolkit-video-player publishes a jvm target and depends on this module;
    // see docs/01-architecture.md § "Desktop targets".
    jvm()

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
