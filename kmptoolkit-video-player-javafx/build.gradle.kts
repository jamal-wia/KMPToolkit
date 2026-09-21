@file:OptIn(org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation::class)

// JVM only, so this module cannot use `kmptoolkit.library` — that convention always adds the
// Android target. It restates the two things that convention contributes and a JVM-only module
// still needs — strict explicitApi() and ABI validation — and nothing else. If the suite grows a
// shared JVM-only convention, this block is what it replaces.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("kmptoolkit.publish")
}

kmptoolkitPublish {
    pomName.set("KMPToolkit Video Player — JavaFX engine")
    pomDescription.set(
        "A desktop (JVM) playback engine for kmptoolkit-video-player built on JavaFX Media: " +
            "createJavaFxVideoPlayer() returns the same VideoPlayer as on Android and iOS, and its " +
            "frames render through kmptoolkit-video-player-compose. OpenJFX is licensed GPL-2.0 " +
            "with the Classpath Exception and is a compileOnly dependency — the app adds the " +
            "OpenJFX runtime jars for each OS it ships to. The alternative desktop engine is " +
            "kmptoolkit-video-player-vlcj."
    )
}

/**
 * OpenJFX publishes its classes only in OS-specific jars (`-mac`, `-mac-aarch64`, `-linux`,
 * `-linux-aarch64`, `-win`) selected by a Maven profile Gradle cannot evaluate, so the
 * classifier-less coordinate resolves to an empty jar. The API is identical in every one of them,
 * so this module compiles against the build host's jars — as compileOnly, which never reaches the
 * published POM. What runs is whatever the consuming app puts on its classpath for its own target
 * OS; see docs/kmptoolkit-video-player-javafx/02-getting-started.md.
 */
val hostJavaFxClassifier: String = run {
    val os: String = System.getProperty("os.name").lowercase()
    val arm: Boolean = System.getProperty("os.arch").let { it == "aarch64" || it == "arm64" }
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

fun openJfx(library: Provider<MinimalExternalModuleDependency>): String {
    val dependency: MinimalExternalModuleDependency = library.get()
    return "${dependency.module}:${dependency.versionConstraint.requiredVersion}:$hostJavaFxClassifier"
}

kotlin {
    explicitApi()

    // No Android, no iOS: this artifact exists only to put a desktop engine behind the core
    // module's jvm target. Its licence (GPL-2 + Classpath Exception) therefore reaches only an app
    // that adds this artifact, never one that uses the core module alone.
    jvm()

    abiValidation {
        referenceDumpDir.set(layout.projectDirectory.dir("api"))
    }

    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
        jvmMain.dependencies {
            // api: createJavaFxVideoPlayer() returns VideoPlayer, and the engine implements the
            // core module's VideoPlaybackEngine and VideoFrameSource.
            api(project(":kmptoolkit-video-player"))
            implementation(libs.kotlinx.coroutines.core)
            // Not transitive: the POMs name a `${javafx.platform}` classifier that only a Maven OS
            // profile fills in. The three jars below are the whole of what this module touches.
            compileOnly(openJfx(libs.openjfx.base)) { isTransitive = false }
            compileOnly(openJfx(libs.openjfx.graphics)) { isTransitive = false }
            compileOnly(openJfx(libs.openjfx.media)) { isTransitive = false }
        }
        jvmTest.dependencies {
            implementation(libs.junit)
            implementation(openJfx(libs.openjfx.base)) { isTransitive = false }
            implementation(openJfx(libs.openjfx.graphics)) { isTransitive = false }
            implementation(openJfx(libs.openjfx.media)) { isTransitive = false }
        }
    }
}
