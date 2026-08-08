# kmptoolkit-audio-player — API reference

Package `io.github.jamal_wia.kmptoolkit.audio.player`. Every symbol below is public API and covered
by the module's ABI dump; anything not listed here is `internal` and may change in any release.

All positions and durations are **milliseconds**.

---

## `AudioPlayer`

```kotlin
public interface AudioPlayer : AutoCloseable
```

The headless player. Obtain one from a platform factory; never implement it yourself — implement
[`PlaybackEngine`](#playbackengine) instead, so the state machine stays shared.

### Properties

| Member | Contract |
|---|---|
| `val stateFlow: StateFlow<PlayerState>` | Current state. Starts at `Idle`, never completes. Safe to collect from any thread. |
| `val playbackPositionFlow: StateFlow<Long>` | Playhead in ms, refreshed every `positionUpdateIntervalMs` while playing, `0` otherwise. |
| `val playbackSpeed: Float` | Rate in effect, already clamped to the configured range. `1.0` until `setPlaybackSpeed`. Survives `prepare`. |

### `suspend fun prepare(source: AudioSource)`

Loads `source`, suspending until it is playable or has failed. Discards any previously loaded
source, resets the playhead to `0`, and re-applies `playbackSpeed`.

- Passes through `Preparing`, settles on `Ready(duration)` or `Error(cause)`.
- **Does not throw on failure** — the platform `Throwable` arrives as `PlayerState.Error`.
- **Honors cancellation** — the partially loaded engine is released, the state returns to `Idle`,
  and `CancellationException` propagates.
- After `release()`: sets `Error(AudioPlayerReleasedException)` and loads nothing.

### Transport

Every call below is **ignored** unless the current state satisfies `isPlayable`, and ignored
entirely after `release()`.

| Member | Contract |
|---|---|
| `fun play()` | `Ready`/`Paused`/`Completed` → `Playing`, starts position polling. Ignored while already `Playing`. From `Completed` it resumes at the end — use `replay()` to restart. |
| `fun pause()` | `Playing` → `Paused(duration, position)`, stops polling. Ignored in any other state. |
| `fun stop()` | Any playable → `Ready(duration)` with the playhead at `0`; the source stays loaded. |
| `fun seekTo(positionMs: Long)` | Moves the playhead, clamped to `0..duration`. `Playing` stays `Playing`, `Paused` stays `Paused`, `Completed` becomes `Paused`. |
| `fun seekForward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)` | `seekTo(position + amountMs)`. |
| `fun seekBackward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)` | `seekTo(position - amountMs)`. |
| `fun replay()` | Seek to `0` and play. Valid from any playable state, including `Completed`. |
| `fun setPlaybackSpeed(speed: Float)` | Clamps into `minPlaybackSpeed..maxPlaybackSpeed` and stores it. Pushed to the platform immediately while playing, on the next `play()` otherwise. Never rejects a value. Records the clamped rate even after release. |

### Lifecycle

| Member | Contract |
|---|---|
| `fun release()` | Frees the native handle, cancels polling, detaches the engine listener, resets to `Idle`/`0`. **Idempotent.** Not reversible. |
| `override fun close()` | Alias for `release()`, so a player works with `use { }`. |

**Threading.** Transport calls are not synchronized — drive one player from one thread. The two
flows are safe to collect anywhere. `release()` cannot double-free, but a transport call racing a
release may be applied or dropped.

---

## `PlayerState`

```kotlin
public sealed interface PlayerState
```

| Case | Data | Meaning |
|---|---|---|
| `Idle` | — | Nothing loaded. Initial state, and the state after `release()`. |
| `Preparing` | — | Loading or buffering. No duration yet. |
| `Ready` | `duration` | Loaded, never started (or stopped back to the start). |
| `Playing` | `duration`, `currentPosition` | Advancing; position refreshed each poll. |
| `Paused` | `duration`, `currentPosition` | Suspended at `currentPosition`. |
| `Completed` | `duration` | Played to the end; the source stays loaded. |
| `Error` | `cause: Throwable` | Load or playback failed. **No display string** — the cause is the platform's own exception. |

A `duration` of `0` means the platform has not reported one, not an error.

### Extension properties

| Symbol | Returns |
|---|---|
| `val PlayerState.isPlayable: Boolean` | `true` for `Ready`, `Playing`, `Paused`, `Completed`. The predicate the transport calls use. |
| `val PlayerState.isPlaying: Boolean` | `true` only for `Playing`. |
| `val PlayerState.duration: Long?` | Duration, or `null` where no source is loaded. |
| `val PlayerState.playbackPosition: Long?` | Position for `Playing`/`Paused`, `duration` for `Completed`, `null` otherwise — including `Ready`, where "loaded, never started" is deliberately not reported as position `0`. |
| `val PlayerState.progress: Float` | `playbackPosition / duration`, clamped to `0f..1f`. `0f` whenever either is missing or the duration is non-positive, so it is never `NaN`. |

---

## `AudioSource`

```kotlin
public sealed interface AudioSource
```

| Case | Data | Resolution |
|---|---|---|
| `Asset(path)` | path relative to the bundled-resource root, **with extension** | Android `assets/`; iOS bundle lookup — see [`05-platform-notes.md`](05-platform-notes.md) |
| `File(path)` | absolute file path | Read directly. The library never creates, downloads, or deletes it. |
| `Remote(url)` | absolute URL | Streamed. Cleartext `http://` is blocked by default on both platforms. |

---

## `AudioPlayerConfig`

```kotlin
public data class AudioPlayerConfig(
    val positionUpdateIntervalMs: Long = 100L,
    val minPlaybackSpeed: Float = 0.25f,
    val maxPlaybackSpeed: Float = 3.0f,
)
```

Validated in `init`; each rule throws `IllegalArgumentException`:

- `positionUpdateIntervalMs > 0`
- `minPlaybackSpeed > 0`
- `maxPlaybackSpeed >= minPlaybackSpeed`

The defaults are exposed as `AudioPlayerConfig.DEFAULT_POSITION_UPDATE_INTERVAL_MS`,
`DEFAULT_MIN_PLAYBACK_SPEED` and `DEFAULT_MAX_PLAYBACK_SPEED`.

---

## `AudioPlayerReleasedException`

```kotlin
public class AudioPlayerReleasedException : IllegalStateException
```

Carried by `PlayerState.Error` when `prepare` is called after `release`. Never thrown — it exists so
"the player is dead" is a type check rather than a string match.

---

## Factories

### Common

```kotlin
public fun createAudioPlayer(
    engine: PlaybackEngine,
    config: AudioPlayerConfig = AudioPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioPlayer
```

For a caller-supplied engine — a test fake, or your own backend. The returned player **takes
ownership** of `engine`: it installs itself as the listener and releases the engine from its own
`release()`. One engine per player.

`coroutineContext` hosts the position-polling coroutine only; pass a `TestDispatcher` to make polling
deterministic in tests.

### Android (`androidMain`)

```kotlin
public fun createAudioPlayer(
    context: Context,
    config: AudioPlayerConfig = AudioPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioPlayer
```

Backed by `android.media.MediaPlayer`. Only `context.applicationContext` is retained, so a player
outliving its screen cannot leak an `Activity` — it still has to be released.

### iOS (`iosMain`)

```kotlin
public fun createAudioPlayer(
    config: AudioPlayerConfig = AudioPlayerConfig(),
    assetBundle: NSBundle = NSBundle.mainBundle,
    assetSubdirectories: List<String> = emptyList(),
    managesAudioSession: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.Default,
): AudioPlayer
```

Backed by `AVFoundation.AVPlayer`.

- `assetBundle` / `assetSubdirectories` — where `AudioSource.Asset` is looked up: the bundle root
  first, then each subdirectory in order. Compose Multiplatform apps pass
  `listOf("compose-resources")`.
- `managesAudioSession` — whether to switch the shared `AVAudioSession` to the playback category.
  See [`05-platform-notes.md`](05-platform-notes.md) before turning it off, and before leaving it on
  in an app that also records.

---

## `PlaybackEngine`

```kotlin
public interface PlaybackEngine
```

The platform seam. Implement it to back `AudioPlayer` with something other than `MediaPlayer` /
`AVPlayer`; it holds no state machine of its own.

| Member | Contract |
|---|---|
| `fun setListener(listener: PlaybackEngineListener?)` | Installs or (with `null`) detaches the event sink. |
| `suspend fun load(source: AudioSource)` | Loads and suspends until playable. **Throws to report failure**, leaving nothing playable behind. |
| `fun start()` / `fun pause()` | Start/suspend output. Must tolerate arriving in an unexpected platform state. |
| `fun seekTo(positionMs: Long)` | Move the playhead; the caller has already clamped the value. |
| `fun setSpeed(speed: Float)` | Set the rate; the caller has already clamped the value. |
| `fun durationMs(): Long` / `fun positionMs(): Long` | Cheap; return `0` when unknown, never negative. |
| `fun release()` | Free everything. **Must be idempotent**, and safe after a failed `load`. |

```kotlin
public interface PlaybackEngineListener {
    public fun onCompleted()
    public fun onFailed(cause: Throwable)
}
```

For the two things an engine cannot report by returning. Failures raised *while loading* are thrown
from `load` instead. An engine must not call the listener after `release()`.

---

## Constants

| Symbol | Value |
|---|---|
| `DEFAULT_SEEK_AMOUNT_MS` | `10_000L` — default skip for `seekForward` / `seekBackward` |
