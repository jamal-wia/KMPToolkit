// `compose.uiTest` is still behind this opt-in as of Compose Multiplatform 1.11.
@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

plugins {
    id("kmptoolkit.library")
    id("kmptoolkit.compose")
    id("kmptoolkit.publish")
    id("kmptoolkit.androidtest")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Video Player Compose")
    pomDescription.set(
        "Compose Multiplatform UI for kmptoolkit-video-player: VideoPlayerSurface renders a player " +
            "on Android, iOS and desktop, and VideoPlayer adds ready-made controls that every part of " +
            "can be replaced — a quick start for apps that do not care about the look, and building " +
            "blocks for apps with their own design."
    )
}

android {
    namespace = "io.github.jamal_wia.kmptoolkit.video.player.compose"
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    jvm()

    sourceSets {
        commonMain.dependencies {
            api(project(":kmptoolkit-video-player"))
            implementation(compose.runtime)
            implementation(compose.ui)
            implementation(compose.foundation)
            implementation(compose.animation)
            implementation(libs.androidx.lifecycle.runtime.compose)
        }
        // The Compose UI tests are written once, in src/uiTest, and compiled into both JVM-hosted
        // test compilations: Robolectric on Android, Skia on desktop. Each side adds only a thin
        // subclass per suite (the Robolectric runner annotation exists on one side only).
        androidUnitTest {
            kotlin.srcDir("src/uiTest/kotlin")
            dependencies {
                implementation(compose.uiTest)
            }
        }
        jvmTest {
            kotlin.srcDir("src/uiTest/kotlin")
            dependencies {
                implementation(compose.uiTest)
                // The Skia runtime runComposeUiTest renders through on desktop. Test-only, and for
                // the machine running the tests — nothing here reaches a published artifact.
                implementation(compose.desktop.currentOs)
            }
        }
    }
}

dependencies {
    // See kmptoolkit-systembars/build.gradle.kts for why this is debugImplementation.
    debugImplementation(platform(libs.androidx.compose.bom))
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
