plugins {
    // JVM only: VLCJ is a desktop binding to a native VLC install, so an Android or iOS target here
    // would be an empty artifact. See build-logic's JvmLibraryConventionPlugin.
    id("kmptoolkit.library.jvm")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Video Player VLCJ")
    pomDescription.set(
        "The desktop (JVM) playback engine for kmptoolkit-video-player, built on VLCJ: " +
            "createVlcjVideoPlayer() returns the same VideoPlayer as on Android and iOS, rendering " +
            "decoded frames into memory for kmptoolkit-video-player-compose to draw. Licence: this " +
            "artifact is MIT, but it depends on VLCJ, which is GPL-3.0 — an application that ships " +
            "it takes on the GPL-3.0 obligations (or needs a commercial VLCJ licence from Caprica). " +
            "Requires VLC 3.x (libvlc) installed on the machine, or bundled with the application " +
            "and matching the JVM's CPU architecture; isVlcAvailable() checks without crashing."
    )
}

kotlin {
    sourceSets {
        jvmMain.dependencies {
            // api: createVlcjVideoPlayer() returns the core module's VideoPlayer.
            api(project(":kmptoolkit-video-player"))
            // implementation: no VLCJ type appears in this module's public API, so the consumer's
            // compile classpath never sees it — only their runtime does.
            implementation(libs.vlcj)
        }
        jvmTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
