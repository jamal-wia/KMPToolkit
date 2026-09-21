# kmptoolkit-video-player — Getting started

Five minutes to a playing video.

## 1. Add the dependency

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player:<version>")
            // To draw it with Compose:
            implementation("io.github.jamal-wia:kmptoolkit-video-player-compose:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player-testing:<version>")
        }
    }
}
```

With the BOM, drop the versions:

```kotlin
implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
implementation("io.github.jamal-wia:kmptoolkit-video-player")
```

Streaming on Android needs `android.permission.INTERNET` in **your** manifest — the library declares
no permission of its own. See [`05-platform-notes.md`](05-platform-notes.md).

## 2. Create the player on each platform

The factory differs per platform; everything after construction is common code.

```kotlin
// androidMain
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer

val player: VideoPlayer = createVideoPlayer(context)
```

```kotlin
// iosMain
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer

val player: VideoPlayer = createVideoPlayer()
```

Desktop has no built-in engine — add `kmptoolkit-video-player-vlcj` or
`kmptoolkit-video-player-javafx` and use its factory.

There is no DI module to install. With Koin:

```kotlin
val videoModule = module {
    factory<VideoPlayer> { createVideoPlayer(androidContext()) }
}
```

A `factory`, not a `single`: a player holds a decoder and has an owner that must release it.

## 3. Load a source and play

```kotlin
suspend fun openLesson(player: VideoPlayer, url: String) {
    player.prepare(VideoSource.Remote(url))
    if (player.stateFlow.value.isPlayable) player.play()
}
```

`prepare` suspends until the source is playable or has failed and reports a failure through the
state rather than throwing — branch on `isPlayable`, not on a `try`/`catch`.

The three source kinds:

```kotlin
VideoSource.Asset("videos/intro.mp4")                          // bundled with the app
VideoSource.File("/data/.../lesson-3.mp4")                     // already on device storage
VideoSource.Remote(
    url = "https://cdn.example.com/lesson-3/master.m3u8",      // progressive or HLS
    headers = mapOf("Authorization" to "Bearer $token"),       // optional
)
```

## 4. Render the state

```kotlin
player.stateFlow.collect { state: VideoPlayerState ->
    playButtonEnabled = state.isPlayable
    showsPauseIcon = state.isPlaying
    progressBar = state.progress                 // 0f..1f, never NaN
    if (state is VideoPlayerState.Error) reportToUser(state.cause)
}

player.isBufferingFlow.collect { stalled: Boolean -> spinnerVisible = stalled }
player.videoSizeFlow.collect { size: VideoSize? -> aspectRatio = size?.aspectRatio ?: 16f / 9f }
```

With Compose, `kmptoolkit-video-player-compose` renders the picture and, if you want them, controls
wired to these flows — see its own docs.

## 5. Release it

```kotlin
override fun onCleared() {
    player.release()
}
```

Releasing twice is harmless, and a tap arriving after release does nothing. Full contract in
[`03-guide.md`](03-guide.md#the-release-contract).

## Complete example — "watched 95%"

```kotlin
class LessonVideo(private val player: VideoPlayer, scope: CoroutineScope) {

    val state: StateFlow<VideoPlayerState> = player.stateFlow

    /** Becomes true once 95% of the lesson has played, and stays true. */
    val watchedEnough: StateFlow<Boolean> = player.stateFlow
        .map { it.progress >= 0.95f || it is VideoPlayerState.Completed }
        .runningReduce { seen: Boolean, now: Boolean -> seen || now }
        .stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun open(url: String, autoPlay: Boolean) {
        player.prepare(VideoSource.Remote(url))
        if (autoPlay && player.stateFlow.value.isPlayable) player.play()
    }

    fun toggle() = if (player.stateFlow.value.isPlaying) player.pause() else player.play()

    fun dispose() = player.release()
}
```

## Read next

- [`03-guide.md`](03-guide.md) — lifecycle, errors, buffering, settings, custom engines
- [`06-testing.md`](06-testing.md) — testing the class above without a device
