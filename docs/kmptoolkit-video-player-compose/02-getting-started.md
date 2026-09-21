# kmptoolkit-video-player-compose — Getting started

## 1. Add the dependency

```kotlin
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project.dependencies.platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
            implementation("io.github.jamal-wia:kmptoolkit-video-player-compose")
            // kmptoolkit-video-player comes with it (an `api` dependency).
        }
        // Desktop only — pick one engine (see 05-platform-notes.md):
        jvmMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player-vlcj")
        }
    }
}
```

Android: declare `android.permission.INTERNET` in your app manifest if you stream `Remote`
sources. The library does not declare it for you — see
[`05-platform-notes.md`](05-platform-notes.md#android).

## 2. Show a video

```kotlin
@Composable
fun ExerciseVideo(url: String) {
    VideoPlayer(
        source = VideoSource.Remote(url),
        modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f),
        autoPlay = true,
    )
}
```

That is a complete player: tap the picture to show the controls, which hide again three seconds
after the last interaction while playing. The player is created on first composition, loads `url`,
starts once loaded, pauses when the app goes to the background, and is released when
`ExerciseVideo` leaves the composition. A new `url` loads in place of the old one.

## 3. Give the controls words for screen readers

The controls carry no text of their own. Pass your localized labels:

```kotlin
VideoPlayer(source = VideoSource.Remote(url), modifier = …) {
    DefaultVideoControls(
        labels = VideoControlsLabels(
            play = stringResource(Res.string.video_play),
            pause = stringResource(Res.string.video_pause),
            replay = stringResource(Res.string.video_replay),
            seekForward = stringResource(Res.string.video_forward_10),
            seekBackward = stringResource(Res.string.video_back_10),
            mute = stringResource(Res.string.video_mute),
            unmute = stringResource(Res.string.video_unmute),
            seekBar = stringResource(Res.string.video_position),
            playbackSpeed = stringResource(Res.string.video_speed),
            buffering = stringResource(Res.string.video_loading),
        ),
    )
}
```

## 4. Desktop: pick the engine once

Desktop has no built-in engine. Provide one at the root of your desktop UI:

```kotlin
fun main() = application {
    Window(onCloseRequest = ::exitApplication) {
        CompositionLocalProvider(
            LocalVideoPlayerFactory provides VideoPlayerFactory { config -> createVlcjVideoPlayer(config) },
        ) {
            App() // the same shared UI your phones run
        }
    }
}
```

Without it, the first `VideoPlayer(source = …)` throws an `IllegalStateException` saying exactly
this. (`createVlcjVideoPlayer` is the VLCJ artifact's factory; see its own docs for the exact name
and for installing VLC.)

## Next

- A view model owns the player, reports progress, survives rotation: [`03-guide.md`](03-guide.md#who-owns-the-player)
- Your own controls: [`03-guide.md`](03-guide.md#replace-any-part-of-the-controls)
