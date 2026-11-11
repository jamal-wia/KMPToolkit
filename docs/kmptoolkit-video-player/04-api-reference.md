# kmptoolkit-video-player — API reference

Package `io.github.jamal_wia.kmptoolkit.video.player`. Every symbol below is public API and covered by
the module's ABI dump; anything not listed is `internal` (or `@ToolkitInternalApi`, see the end) and
may change in any release.

All positions and durations are **milliseconds**.

---

## `VideoPlayer`

```kotlin
@SubclassOptInRequired(ToolkitInheritanceApi::class)
public interface VideoPlayer : AutoCloseable
```

The headless player. Obtain one from a factory; do not implement it yourself — implement
[`VideoPlaybackEngine`](#videoplaybackengine) instead, so the state machine stays shared. New members
may be added to this interface in any release, so implementing it requires opting in to
[`@ToolkitInheritanceApi`](#toolkitinheritanceapi). For a test double, build a real player over
`FakeVideoPlaybackEngine` (see [`06-testing.md`](06-testing.md)).

### Flows

All are `StateFlow`s: they start at the value shown, never complete, and are safe to collect from
any thread.

| Member | Initial | Contract |
|---|---|---|
| `stateFlow: StateFlow<VideoPlayerState>` | `Idle` | The transport state. |
| `playbackPositionFlow: StateFlow<Long>` | `0` | Playhead, refreshed every `positionUpdateIntervalMs` while `Playing`; set directly by seeks, `stop()` and completion (to the length, or, on a source of unknown length, to where it ended); `0` after `prepare`, `unload`, `release`. |
| `bufferedPositionFlow: StateFlow<Long>` | `0` | Buffered-ahead position. Polled with the playhead while `Playing`, snapshotted when a load finishes and on `pause()`; `0` when nothing is loaded and after a playback failure. |
| `isBufferingFlow: StateFlow<Boolean>` | `false` | Whether a **loaded** source is stalled waiting for data. Independent of `stateFlow` (a stalled stream is still `Playing`). Not shown while preparing (a stall of the just-loaded source that the engine reports before `Ready` is published is kept and shown with `Ready`); `false` when idle, on completion and on failure; a stall the engine reports after the end is ignored while the state is `Completed`. |
| `videoSizeFlow: StateFlow<VideoSize?>` | `null` | Displayed picture size once the platform reports one — usually while `Preparing`. Kept on `Completed`; `null` again when a new `prepare` starts, on a failure, `unload`, `release`. |
| `playbackSpeedRange: ClosedFloatingPointRange<Float>` | the configured `minPlaybackSpeed..maxPlaybackSpeed` | Not a flow: fixed for the player's life. Every value of `playbackSpeedFlow` lies in it. *(since 4.0.0)* |
| `playbackSpeedFlow: StateFlow<Float>` | `1.0`, clamped into the configured range | Rate in effect, clamped to the configured range — the initial value too, so a range that excludes `1.0` starts at its nearest bound. |
| `volumeFlow: StateFlow<Float>` | `1.0` | Output volume in `0f..1f`, independent of mute. |
| `isMutedFlow: StateFlow<Boolean>` | `false` | Whether output is muted. |
| `repeatModeFlow: StateFlow<RepeatMode>` | `Off` | What happens at the end of the source. |

### `suspend fun prepare(source: VideoSource)`

Loads `source`, suspending until it is playable or has failed. Discards any previous source and
resets the playhead, buffered position, buffering and picture size.

- Passes through `Preparing`, settles on `Ready(duration)` or `Error(cause)`.
- **Does not throw on failure** — the platform's `Throwable` arrives as `VideoPlayerState.Error`,
  unchanged.
- **Honors cancellation** — the partially loaded source is freed, the state returns to `Idle`, and
  `CancellationException` propagates.
- **A newer `prepare` replaces this one** — the older load is cancelled and fully unwound before the
  newer one starts; the replaced call returns normally without writing any state.
- **Frees the previous source first, whatever state it left the player in** (including `Error`), so a source that failed during playback is released before the next load.
- On success, pushes the current volume (or `0f` if muted), repeat mode and speed to the platform.
- After `release()`: sets `Error(VideoPlayerReleasedException)` and loads nothing.

### Transport

Every call below is **ignored** unless the state `isPlayable`, and ignored entirely after `release()`.

| Member | Contract |
|---|---|
| `fun play()` | `Ready`/`Paused`/`Completed` → `Playing`, starts polling, applies the speed. Ignored while already `Playing`. From `Completed`, or with the playhead on the end of the source, it starts over from `0`. |
| `fun pause()` | `Playing` → `Paused(duration, position)`, stops polling. Ignored in any other state. |
| `fun stop()` | Any playable → `Ready(duration)` with the playhead at `0`; the source stays loaded. |
| `fun seekTo(positionMs: Long)` | Moves the playhead, clamped to `0..duration`. `Playing` stays `Playing`, `Paused` stays `Paused`, `Completed` becomes `Paused`, `Ready` stays `Ready` (picking up a length learned since the load). |
| `fun seekForward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)` | `seekTo(position + amountMs)`, saturating instead of overflowing. On a source of unknown length (`duration` `0`) there is no end to clamp to: the target is `position + amountMs` floored at `0` and capped at `Long.MAX_VALUE / 1_000_000` (about 292 years), not `0`. |
| `fun seekBackward(amountMs: Long = DEFAULT_SEEK_AMOUNT_MS)` | `seekTo(position - amountMs)`, saturating instead of overflowing; on a source of unknown length the target is floored at `0` and capped at `Long.MAX_VALUE / 1_000_000`. |
| `fun replay()` | Seek to `0` and play, from any playable state. |

### Settings

Settings belong to the player: they take effect in their flow at once, even with nothing loaded and
even after `release()`, survive `prepare` and `unload`, and are pushed to every newly loaded source.

| Member | Contract |
|---|---|
| `fun setPlaybackSpeed(speed: Float)` | Clamps into `minPlaybackSpeed..maxPlaybackSpeed`; `NaN` is ignored. Reaches the platform immediately while `Playing`, otherwise on the next `play()` or load. |
| `fun setVolume(volume: Float)` | Clamps into `0f..1f`; `NaN` is ignored. Does not change `isMutedFlow`. Reaches the platform at once while a source is loaded (as `0f` while muted). |
| `fun setMuted(muted: Boolean)` | Mutes or unmutes, keeping the volume, so unmuting restores it. |
| `fun setRepeatMode(mode: RepeatMode)` | Takes effect for the source already loaded. With `One`, `Completed` is never reached. |

### Lifecycle

| Member | Contract |
|---|---|
| `fun unload()` | Abandons a load in flight (that `prepare` returns without writing state), frees the loaded source, stops polling, resets to `Idle` and every source flow to its initial value. Keeps the settings; the next `prepare` works. Drops platform callbacks already posted for the old source. Resets to `Idle` even when the engine's `release()` throws (rethrown afterwards), so the next `prepare` never waits on it. No effect after `release()`. |
| `fun release()` | Frees every native resource, cancels polling, detaches from the engine, resets to `Idle` — even when the engine throws (the exception is rethrown afterwards). **Idempotent.** Not reversible. |
| `override fun close()` | Alias for `release()`, for `use { }`. |

**Threading.** Every member is safe to call from any thread, and each call is one atomic transition:
calls, poll ticks and the engine's events are serialized, so none overwrites a state another just
wrote (a poll tick can no longer turn `Completed` or `Paused` back into `Playing`). A call returns with
its transition applied — after `pause()`, `stateFlow` already holds `Paused`. An engine event that
arrives while a call is running is applied right after it. The flows are safe to collect anywhere.
`release()` cannot double-free; a call racing a release from another thread is applied or dropped
as a whole, never half-way, and nothing reaches the engine after the release. A completion the platform reported before a seek away from the end (or a `stop()`, or the rewind of a `play()` from the end) is dropped as stale; a seek onto the end does not make a completion stale. When the player drops a completion as stale while it is `Playing`, it calls `VideoPlaybackEngine.start()` and `setSpeed` again, so a seek made just as the source reached its end wins: an engine whose platform stops at its end must then resume output from the seek's target (not replay from the start, not ignore it). A `stateFlow` collector on an immediate dispatcher may call the player from inside the update; every transition has finished its other writes by then, so such a call is never undone.

---

## `VideoPlayerState`

```kotlin
public sealed interface VideoPlayerState
```

| Case | Data | Meaning |
|---|---|---|
| `Idle` | — | Nothing loaded. Initial state; the state after `unload()` and `release()`. |
| `Preparing` | — | A source is loading. |
| `Ready` | `duration` | Loaded, never started, or stopped back to the start. |
| `Playing` | `duration`, `currentPosition` | Running (or stalled wanting to run — see `isBufferingFlow`). |
| `Paused` | `duration`, `currentPosition` | Suspended at `currentPosition`. |
| `Completed` | `duration` | Played to the end with `RepeatMode.Off`; the source stays loaded. |
| `Error` | `cause: Throwable` | Load or playback failed. **No display string.** |

A `duration` of `0` means the platform has not reported one (a live stream), not an error. A length the platform reports later replaces it at the next position update, pause or seek; once a length is known for the loaded source, no state reverts it to `0` (only a new `prepare` starts from the engine's value again). A source of unknown length that plays to its end completes with the position where it ended as its length: `Completed(duration = end)` with `playbackPositionFlow` left at `end`; that length then counts as known (`replay` and `stop` keep it, an absolute seek clamps to `0..end`). If the end is still `0`, it is `Completed(0)`.

| Extension | Returns |
|---|---|
| `VideoPlayerState.isPlayable: Boolean` | `true` for `Ready`, `Playing`, `Paused`, `Completed`. |
| `VideoPlayerState.isPlaying: Boolean` | `true` only for `Playing`. |
| `VideoPlayerState.duration: Long?` | Duration, or `null` where nothing is loaded. |
| `VideoPlayerState.playbackPosition: Long?` | Position for `Playing`/`Paused`, the duration for `Completed`, `null` otherwise — including `Ready`. |
| `VideoPlayerState.progress: Float` | `playbackPosition / duration` clamped to `0f..1f`; `0f` whenever either is missing or the duration is not positive — never `NaN`. |

---

## `VideoSource`

```kotlin
public sealed interface VideoSource
```

| Case | Data | Resolution |
|---|---|---|
| `Asset(path)` | path relative to the bundled-resource root, **with extension** | Android `assets/`; iOS bundle lookup — see [`05-platform-notes.md`](05-platform-notes.md). |
| `File(path)` | absolute file path | Read directly. Never created, downloaded or deleted by the library. |
| `Remote(url, headers = emptyMap(), format = RemoteFormat.Auto)` | absolute URL, extra HTTP request headers, packaging hint | Streamed; progressive files and HLS. `headers` go with every request for the source — for HLS, the playlists and every segment — and are copied, so a later change to the map passed in has no effect. Cleartext `http://` is blocked by default on both platforms. |

Each case is a plain class with value equality (`equals`/`hashCode` over every property), not a
`data class` — so a case can gain an optional property in a minor release without breaking callers
compiled against the previous one (there is no `copy` or `componentN` to change shape).
`toString()` prints the properties, except that `Remote` prints its header **names** only, each
value replaced with `<redacted>`, so a source logged by accident does not leak an `Authorization`
token. The URL is printed as given — a signed URL is itself a credential, so do not log it.

### `RemoteFormat`

```kotlin
public enum class RemoteFormat { Auto, Progressive, Hls }
```

How a `Remote` source is packaged — a hint, not a conversion. More formats may be added in a minor
release.

| Value | Meaning |
|---|---|
| `Auto` | The default: the platform decides. Android goes by the URL path (`.m3u8` → HLS, anything else progressive); iOS also reads the server's content type. |
| `Progressive` | A single file (MP4, WebM, …), even if the path ends in `.m3u8`. |
| `Hls` | An HLS playlist whatever the URL looks like — use it for a signed, rewritten or extension-less HLS URL, which Android would otherwise try to play as a progressive file and fail. |

How each engine honours the hint is in [`05-platform-notes.md`](05-platform-notes.md).

---

## `VideoSize`

```kotlin
public data class VideoSize(val width: Int, val height: Int)
```

The displayed picture size in pixels — rotated for portrait recordings, corrected for non-square
pixels. Both dimensions must be positive (`IllegalArgumentException` otherwise).
`val aspectRatio: Float` is `width / height`.

---

## `RepeatMode`

| Entry | Meaning |
|---|---|
| `Off` | Stop at the end: the state becomes `Completed`. The default. |
| `One` | Start the same source over, without passing through `Completed`. Only a source that was playing restarts: a completion reported while paused rewinds to `0` and stays paused, and one reported while `Ready` or `Completed` is ignored. |

---

## `VideoPlayerConfig`

```kotlin
public class VideoPlayerConfig(
    public val positionUpdateIntervalMs: Long = 250L,
    public val minPlaybackSpeed: Float = 0.25f,
    public val maxPlaybackSpeed: Float = 3.0f,
)
```

A plain class, so a later release can add a setting without a binary break. Validated in `init`,
each rule throwing `IllegalArgumentException`: `positionUpdateIntervalMs > 0`,
`minPlaybackSpeed` finite and `> 0`, `maxPlaybackSpeed` finite and `>= minPlaybackSpeed` (an infinite bound is refused: no platform reproduces an infinite rate). The defaults are exposed as
`DEFAULT_POSITION_UPDATE_INTERVAL_MS`, `DEFAULT_MIN_PLAYBACK_SPEED`, `DEFAULT_MAX_PLAYBACK_SPEED`.

---

## `VideoPlayerReleasedException`

```kotlin
public class VideoPlayerReleasedException : IllegalStateException
```

Carried by `VideoPlayerState.Error` when `prepare` is called after `release`. Never thrown.

---

## Factories

### Common

```kotlin
public fun createVideoPlayer(
    engine: VideoPlaybackEngine,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer
```

For a caller-supplied engine — a test fake, a desktop engine, your own backend. The player **takes
ownership** of `engine`: it installs itself as the listener and releases the engine from its own
`release()`. One engine per player. `coroutineContext` hosts the polling coroutine only; pass a
`TestDispatcher` to make polling deterministic in tests.

### Android (`androidMain`)

```kotlin
public fun createVideoPlayer(
    context: Context,
    config: VideoPlayerConfig = VideoPlayerConfig(),
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer
```

Backed by Media3 ExoPlayer, which lives on the main thread; callable from any thread. Only
`context.applicationContext` is retained.

### iOS (`iosMain`)

```kotlin
public fun createVideoPlayer(
    config: VideoPlayerConfig = VideoPlayerConfig(),
    assetBundle: NSBundle = NSBundle.mainBundle,
    assetSubdirectories: List<String> = emptyList(),
    managesAudioSession: Boolean = true,
    coroutineContext: CoroutineContext = Dispatchers.Default,
): VideoPlayer
```

Backed by `AVPlayer`. `assetBundle` / `assetSubdirectories` say where `VideoSource.Asset` is looked
up (bundle root first, then each subdirectory — Compose Multiplatform apps pass
`listOf("compose-resources")`); `managesAudioSession` whether to switch the shared `AVAudioSession`
to the playback category. See [`05-platform-notes.md`](05-platform-notes.md#ios).

### Desktop

No built-in factory; the engine artifacts `kmptoolkit-video-player-vlcj` and
`kmptoolkit-video-player-javafx` provide theirs.

---

## `VideoPlaybackEngine`

```kotlin
public interface VideoPlaybackEngine
```

The platform seam. It holds no state machine of its own.

| Member | Contract |
|---|---|
| `fun setListener(listener: VideoPlaybackEngineListener?)` | Installs, replaces, or (with `null`) detaches the event sink. |
| `suspend fun load(source: VideoSource)` | Loads and suspends until playable. **Throws to report failure**, leaving nothing playable. Honors cancellation, leaving nothing loaded. Works again after `release()`. Never called while another `load` still runs. |
| `fun start()` / `fun pause()` | Start or suspend output. Tolerate the wrong platform state. |
| `fun seekTo(positionMs: Long)` | Move the playhead. Always in `0..Long.MAX_VALUE / 1_000_000` (about 292 years); also clamped to `0..durationMs` while the duration is known. While it is unknown (`0`) a relative `seekForward` / `seekBackward` arrives as `position ± amount`, so a target past the real end can arrive: clamp it to the end as the platform defines it, or ignore it, never throw. |
| `fun setSpeed(speed: Float)` | Set the rate; already clamped. |
| `fun setVolume(volume: Float)` | Effective output volume in `0f..1f` — `0f` while the player is muted. |
| `fun setLooping(looping: Boolean)` | Restart the source at its end instead of completing. |
| `fun durationMs()` / `fun positionMs()` / `fun bufferedPositionMs()` | Cheap (polled off the platform's thread); `0` when unknown, never negative. |
| `fun release()` | Free the loaded source and keep the engine reusable: `load` works after it. **Idempotent**, safe after a failed `load`. |
| `fun dispose()` | Free what the engine keeps across sources — the platform player, a native library instance. Called **exactly once**, from `VideoPlayer.release()`, after the last `release()`; no method is called after it. Default: no-op, for an engine whose `release()` already frees everything. |

```kotlin
public interface VideoPlaybackEngineListener {
    public fun onCompleted()                          // never while looping
    public fun onFailed(cause: Throwable)             // after a successful load only
    public fun onBufferingChanged(isBuffering: Boolean)
    public fun onVideoSizeChanged(size: VideoSize?)   // null: no picture
}
```

Failures while loading are thrown from `load` instead. Only `load` reports failure by throwing. If another method throws anyway, the player still settles: `prepare` ends in `Error`, `release()` disposes the engine and resets to `Idle`, `unload()` resets to `Idle`; the last two then rethrow the engine's exception. An engine must not call the listener after
`release()`, including callbacks already queued on a platform thread. The player drops reports that
arrive when no source is loaded, so a late one cannot resurrect a state. It also drops every report of a source it has since told the engine to drop (an `unload`, a `release`, or the start of a newer `prepare`), including one the engine made while that `release()` was running, so a report of the old source can never become the next `prepare`'s outcome. A call already running on another thread must have returned before `release()` returns.

**Threading.** The player calls the engine from whichever thread a transition runs on — the app's,
the polling coroutine's, or the thread an event was reported on — but never two calls at once; an
engine confined to one thread marshals onto it. The engine may call the listener from any thread,
even while holding a lock of its own and even from inside one of the calls above: the player never
blocks in a callback, it applies an event that arrives during another transition right after that
transition.

---

## Constants

| Symbol | Value |
|---|---|
| `DEFAULT_SEEK_AMOUNT_MS` | `10_000L` — default skip for `seekForward` / `seekBackward` |

---

## `@ToolkitInheritanceApi`

```kotlin
@RequiresOptIn(level = RequiresOptIn.Level.ERROR)
@Target(AnnotationTarget.CLASS)
public annotation class ToolkitInheritanceApi
```

Marks, through `@SubclassOptInRequired`, the interfaces only KMPToolkit implements — `VideoPlayer`
here, and `VideoControlsScope` in `kmptoolkit-video-player-compose`. Using them needs nothing;
implementing one outside the library is a compile error unless the implementing class opts in
(`@OptIn(ToolkitInheritanceApi::class)`), which is accepting that a new abstract member in any
release may break it.

---

## `@ToolkitInternalApi`

Opt-in (`RequiresOptIn.Level.ERROR`) marker, declared in `kmptoolkit-core` (package
`io.github.jamal_wia.kmptoolkit.core`; this module exposes that artifact as an `api` dependency, so
the marker is on your classpath without declaring it) for what `kmptoolkit-video-player-compose` needs to
render a player — not part of the public contract, may change in any release:

| Symbol | Platform | What it is |
|---|---|---|
| `VideoPlayer.media3PlayerOrNull(): androidx.media3.common.Player?` | Android | The ExoPlayer behind a player this module created. Main thread only; the same instance for the player's whole life (across `unload`/`prepare`), created on first call on the main thread; `null` from the moment `release()` has returned (also while a load still unwinding keeps the engine alive a moment longer) or for another engine. |
| `VideoPlayer.avPlayerOrNull(): AVPlayer?` | iOS | The `AVPlayer` behind a player this module created. Main thread only. |
| `VideoFrameSource`, `VideoFrame`, `VideoPlayer.frameSourceOrNull()` | JVM | A desktop engine that renders decoded ARGB frames into memory, for the Compose surface to draw; `frameSourceOrNull()` returns the engine of any player whose engine implements `VideoFrameSource`. |
