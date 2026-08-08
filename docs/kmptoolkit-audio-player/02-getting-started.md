# kmptoolkit-audio-player — Getting started

Five minutes to a playing sound.

## 1. Add the dependency

```kotlin
// shared/build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-audio-player:<version>")
        }
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-audio-player-testing:<version>")
        }
    }
}
```

With the BOM, drop the version:

```kotlin
implementation(platform("io.github.jamal-wia:kmptoolkit-bom:<version>"))
implementation("io.github.jamal-wia:kmptoolkit-audio-player")
```

No permission or manifest entry is needed for local playback. Streaming over plain `http://` does
need one — see [`05-platform-notes.md`](05-platform-notes.md).

## 2. Create the player on each platform

The factory differs per platform, because Android needs a `Context` and iOS needs to know which
bundle holds your assets. Everything after construction is common code.

```kotlin
// androidMain
import io.github.jamal_wia.kmptoolkit.audio.player.createAudioPlayer

val player: AudioPlayer = createAudioPlayer(context)
```

```kotlin
// iosMain
import io.github.jamal_wia.kmptoolkit.audio.player.createAudioPlayer

val player: AudioPlayer = createAudioPlayer()
```

There is no DI module to install. If you use Koin, one line wraps it:

```kotlin
val audioModule = module {
    factory<AudioPlayer> { createAudioPlayer(androidContext()) }
}
```

A `factory`, not a `single`: a player is a resource with an owner, and a process-wide singleton has
nobody to release it.

## 3. Load a source and play

```kotlin
suspend fun playVoiceNote(player: AudioPlayer, url: String) {
    player.prepare(AudioSource.Remote(url))
    if (player.stateFlow.value.isPlayable) {
        player.play()
    }
}
```

`prepare` suspends until the source is playable or has failed, and reports the failure through the
state rather than throwing — so the `isPlayable` check above is how you branch, not a `try`/`catch`.

The three source kinds:

```kotlin
AudioSource.Asset("sounds/chime.mp3")      // bundled in the app package
AudioSource.File("/data/.../voice.m4a")    // already on device storage
AudioSource.Remote("https://…/track.mp3")  // streamed
```

## 4. Render the state

`stateFlow` carries everything a player UI needs, including the playhead:

```kotlin
player.stateFlow.collect { state: PlayerState ->
    playButtonEnabled = state.isPlayable
    showsPauseIcon = state.isPlaying
    progressBar = state.progress                 // 0f..1f, never NaN
    elapsedMs = state.playbackPosition ?: 0L
    totalMs = state.duration ?: 0L

    if (state is PlayerState.Error) reportToUser(state.cause)
}
```

`progress` is clamped and zero-safe, so a source whose duration the platform never reports renders
as `0f` rather than crashing a progress bar.

## 5. Release it

The player holds a native handle. Release it when whatever owns it goes away — once, explicitly:

```kotlin
override fun onDestroy() {
    player.release()
}
```

Releasing twice is harmless, and a stray tap arriving after release does nothing. Full contract in
[`03-guide.md`](03-guide.md#the-release-contract).

## Complete example

```kotlin
class VoiceNotePlayer(private val player: AudioPlayer) {

    val state: StateFlow<PlayerState> = player.stateFlow

    suspend fun open(url: String) {
        player.prepare(AudioSource.Remote(url))
    }

    fun toggle() {
        when {
            player.stateFlow.value.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun scrub(fraction: Float) {
        val duration: Long = player.stateFlow.value.duration ?: return
        player.seekTo((duration * fraction).toLong())
    }

    fun dispose() {
        player.release()
    }
}
```

## Read next

- [`03-guide.md`](03-guide.md) — lifecycle, errors, seeking, speed, custom engines
- [`06-testing.md`](06-testing.md) — testing the class above without a device
