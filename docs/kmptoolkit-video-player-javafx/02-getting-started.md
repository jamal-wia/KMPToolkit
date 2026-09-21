# kmptoolkit-video-player-javafx — Getting started

Five minutes to a video playing in a Compose Desktop window.

## 1. Add the dependency

The engine goes into your desktop source set only. The core module and the Compose surface are
common dependencies as usual.

```kotlin
// shared/build.gradle.kts
kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player:<version>")
            implementation("io.github.jamal-wia:kmptoolkit-video-player-compose:<version>")
        }
        jvmMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player-javafx:<version>")
        }
    }
}
```

With the BOM, drop the versions:

```kotlin
implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
implementation("io.github.jamal-wia:kmptoolkit-video-player-javafx")
```

## 2. Add the OpenJFX runtime

This artifact compiles against OpenJFX but does not bring it: the jars are published per operating
system and CPU, and only your app knows which ones it ships to. Supported: OpenJFX **21 or newer**
on **JDK 21 or newer** (the suite's `jvm` artifacts are compiled for Java 21).

OpenJFX's POMs choose their native jar through a Maven OS profile that Gradle does not evaluate, so
the plain coordinate resolves to an empty jar. Choose one of these instead.

### Option A — name the classifier yourself (no plugin)

Declare the three modules the engine uses with the classifier of the OS you are building on. A
desktop installer (`jpackage`, Compose's `packageDistributionForCurrentOS`) is built on the OS it
targets anyway, so the build host's classifier is the right one:

```kotlin
// desktopApp/build.gradle.kts
val javafxVersion = "21.0.12"
val javafxClassifier: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arm = System.getProperty("os.arch") in setOf("aarch64", "arm64")
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    for (module in listOf("javafx-base", "javafx-graphics", "javafx-media")) {
        runtimeOnly("org.openjfx:$module:$javafxVersion:$javafxClassifier")
    }
}
```

All three are needed: `javafx-media` alone resolves `javafx-graphics` and `javafx-base` as the empty
classifier-less jars, and the toolkit then fails to start.

### Option B — the OpenJFX Gradle plugin

```kotlin
plugins {
    id("org.openjfx.javafxplugin") version "<latest>"
}

javafx {
    version = "21.0.12"
    modules("javafx.media")
}
```

It does the classifier selection above for you (also for the build host only).

### Option C — a JDK that bundles JavaFX

A "full" JDK distribution that includes JavaFX (for example Liberica Full or Azul Zulu FX) needs
nothing added. Make sure the runtime you package is that JDK.

## 3. Create the player and show it

```kotlin
// jvmMain
import io.github.jamal_wia.kmptoolkit.video.player.javafx.createJavaFxVideoPlayer

val player: VideoPlayer = createJavaFxVideoPlayer()
```

Hand that player to the Compose surface of `kmptoolkit-video-player-compose` exactly as on the other
platforms — the surface finds the in-memory frames on its own; see that module's docs for how an app
chooses its desktop engine once at its root.

Then, from common code:

```kotlin
scope.launch {
    player.prepare(VideoSource.Asset("videos/intro.mp4")) // a classpath resource on desktop
    player.play()
}
```

`VideoSource.Asset` on desktop is a **classpath resource path** — a file under
`src/jvmMain/resources/videos/intro.mp4` (or `src/main/resources/`) is `"videos/intro.mp4"`.

Release the player when the screen goes away (`player.release()`), as on every platform.

## 4. Check it runs

If the video never appears and `stateFlow` shows `Error(JavaFxVideoPlayerException.RuntimeUnavailable)`,
the OpenJFX runtime is missing or the toolkit cannot start — the exception's `cause` says which. See
[`03-guide.md`](03-guide.md#choosing-an-engine-at-runtime) to fall back to another engine instead.
