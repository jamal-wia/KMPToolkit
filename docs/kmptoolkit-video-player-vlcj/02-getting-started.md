# kmptoolkit-video-player-vlcj — Getting started

Five minutes to a playing video in a Compose Desktop app. Read the licence section of
[`01-overview.md`](01-overview.md) first — this engine brings GPL-3.0 VLCJ into your app.

## 1. Install VLC

On the development machine: VLC 3.x from [videolan.org](https://www.videolan.org/vlc/), installed to
its default location. On Apple silicon, take the **Apple Silicon or Universal** build — an Intel-only
VLC cannot be loaded by an arm64 JVM. Shipping to users is covered in
[`05-platform-notes.md`](05-platform-notes.md).

## 2. Add the dependency — desktop source set only

```kotlin
// shared/build.gradle.kts
kotlin {
    jvm("desktop")

    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player:<version>")
            implementation("io.github.jamal-wia:kmptoolkit-video-player-compose:<version>")
        }
        val desktopMain by getting {
            dependencies {
                implementation("io.github.jamal-wia:kmptoolkit-video-player-vlcj:<version>")
            }
        }
    }
}
```

Only the desktop source set names it, so the GPL dependency never reaches your Android or iOS
build. With the BOM, drop the versions:

```kotlin
implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
implementation("io.github.jamal-wia:kmptoolkit-video-player-vlcj")
```

## 3. Create the player

```kotlin
// desktopMain
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.vlcj.createVlcjVideoPlayer

val player: VideoPlayer = createVlcjVideoPlayer()
```

Creating the player never touches VLC, so it cannot fail. VLC is located on the first `prepare`.

## 4. Check for VLC where it matters

```kotlin
import io.github.jamal_wia.kmptoolkit.video.player.vlcj.isVlcAvailable

if (!isVlcAvailable()) {
    showInstallVlcScreen()   // your UI, your copy — the library ships no text
    return
}
```

If you skip the check, nothing crashes: `prepare` settles on
`VideoPlayerState.Error(VlcUnavailableException)` and your usual error UI shows.

## 5. Play something

From here on it is the shared API, exactly as on Android and iOS:

```kotlin
player.prepare(VideoSource.Remote("https://example.com/intro.mp4"))
player.play()

// ...

player.release()
```

Render it with `kmptoolkit-video-player-compose` — its docs show the surface and the ready-made
controls, and how the app picks this engine once at its root. The desktop surface draws the frames
this engine produces; nothing VLC-specific is needed in UI code.

## Next

[`03-guide.md`](03-guide.md) covers what behaves differently under VLC: headers, assets, the first
frame, looping, buffering.
