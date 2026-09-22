# kmptoolkit-video-player — Guide

Scenarios in rough order of how soon you will hit them.

## The state machine

Seven states, the same seven as `kmptoolkit-audio-player`:

```
                prepare()            play()
   Idle ──────► Preparing ─────► Ready ─────► Playing ──┐
    ▲               │              ▲            │  ▲    │ end of source (RepeatMode.Off)
    │               │ failure      │ stop()     │  │    ▼
    │               ▼              └────────────┤  └── Completed
    │            Error ◄─── failure during playback     │
    │                                     pause()│      │ seekTo()
unload()/release()                               ▼      │
    └───────────────────────────────────────── Paused ◄─┘
```

| From | Call or event | To |
|---|---|---|
| any | `prepare(source)` | `Preparing` → `Ready(duration)`, or `Error(cause)` |
| `Ready` / `Paused` / `Completed` | `play()` | `Playing` (from `Completed`: from `0`) |
| `Playing` | `pause()` | `Paused(duration, position)` |
| any playable | `stop()` | `Ready`, playhead at `0`, source still loaded |
| `Completed` | `seekTo(n)` | `Paused` |
| `Playing` | end of source, `RepeatMode.Off` | `Completed` |
| `Playing` | end of source, `RepeatMode.One` | stays `Playing`, playhead back at `0` |
| any playable | `replay()` | `Playing` from `0` |
| any | `unload()` | `Idle`, ready for another `prepare` |
| any | `release()` | `Idle`, permanently |

`isPlayable` — `Ready`, `Playing`, `Paused`, `Completed` — decides whether a transport call does
anything. Every transport call in any other state is silently ignored: a tap on "play" while the
video is still loading is not an error worth propagating.

Two things a video screen needs vary independently of that state, so they are separate flows rather
than extra states: **buffering** (`isBufferingFlow`) and the **picture size** (`videoSizeFlow`). A
stream that stalls mid-playback is still `Playing` — it wants to run — with `isBufferingFlow` `true`.

## Handling errors

`prepare` never throws for a load failure:

```kotlin
player.prepare(VideoSource.Remote(url))

when (val state: VideoPlayerState = player.stateFlow.value) {
    is VideoPlayerState.Ready -> player.play()
    is VideoPlayerState.Error -> showRetry(state.cause)
    else -> Unit
}
```

The `Throwable` is the platform's own, passed through untouched — on Android a Media3
`PlaybackException` whose `errorCode` tells a network failure (`ERROR_CODE_IO_NETWORK_CONNECTION_FAILED`),
an HTTP error (`ERROR_CODE_IO_BAD_HTTP_STATUS`, e.g. an expired token) and an unsupported format
apart. Match on the types and codes you care about and fall through on the rest; the exact types a
platform produces are not part of this module's compatibility promise.

A failure *after* loading — a stream that dies mid-video — arrives the same way, as `Error`, when the
platform notices. Recovering means preparing again; there is no `retry()`, because the source to
retry with (perhaps with a refreshed token) is information only you have.

## The release contract

A player owns a decoder, a surface binding and a polling coroutine; garbage collection reclaims none
of them. Whoever creates a player releases it:

```kotlin
player.release()
player.release()     // no-op — the engine is freed exactly once
player.play()        // no-op
player.seekTo(500)   // no-op
```

```kotlin
player.release()
player.prepare(source)
player.stateFlow.value   // VideoPlayerState.Error(VideoPlayerReleasedException)
```

`prepare` is the one call that reports rather than ignores, because a caller genuinely waits on it.
Settings (`setVolume`, `setMuted`, `setPlaybackSpeed`, `setRepeatMode`) still record their value
after release — the flows update — but nothing reaches the platform. A released player cannot be
revived; create another.

`VideoPlayer` is `AutoCloseable` and `close()` is `release()`, so `use { }` works for a player whose
life fits in one block.

With Compose, `kmptoolkit-video-player-compose` releases a player **it** created when it leaves the
composition; a player you pass in stays yours to release.

## Unloading a long-lived player

A player can outlive the screens that use it — one instance a feed hands from item to item. Between
videos, `unload()` frees the loaded source and its decoder but keeps the player usable:

```kotlin
player.unload()                                  // Idle, decoder freed, still usable
player.prepare(VideoSource.Remote(nextUrl))      // works exactly as on a fresh player
```

It keeps every setting — speed, volume, mute, repeat mode — abandons a `prepare` still in flight
(that call returns without touching the state), and drops a completion, failure, buffering or size
report the platform had already posted for the old source. On Android the ExoPlayer instance itself
survives `unload()`, so a surface attached to it stays attached.

## Replacing a load in flight

A `prepare` that arrives while another is still loading replaces it. The older load is cancelled and
fully unwound before the newer one starts — the platform never loads two sources on one decoder — and
the replaced call returns normally without writing any state:

```kotlin
scope.launch { player.prepare(clip1) }   // returns quietly once replaced
scope.launch { player.prepare(clip2) }   // settles on Ready or Error for clip2
```

That is what makes "next video" safe to call from anywhere without first cancelling the coroutine
that loaded the current one.

## Cancellation

`prepare` is the only suspending call, and it honors cancellation. If its coroutine goes away
mid-load — a `LaunchedEffect` whose key changed — the half-loaded source is freed, the state returns
to `Idle` (not `Error`: nothing failed), and the `CancellationException` propagates.

```kotlin
LaunchedEffect(url) {
    player.prepare(VideoSource.Remote(url))   // cancelled cleanly when url changes
    if (autoPlay) player.play()
}
```

## Buffering and the buffered position

```kotlin
player.isBufferingFlow      // true while playback is stalled waiting for data
player.bufferedPositionFlow // how far ahead the platform has data, in ms — a seek bar's second track
```

- `isBufferingFlow` is reported for a **loaded** source only. Waiting for data while loading is what
  `Preparing` already says, so it stays `false` then. It is `false` whenever the player is idle, has
  completed, or has failed.
- `bufferedPositionFlow` is polled together with the playhead, only while `Playing`, and snapshotted
  when a source finishes loading and on `pause()`. It is `0` when nothing is loaded; for a local file
  it is the duration.

## Remote sources: headers and HLS

```kotlin
VideoSource.Remote(
    url = signedPlaylistUrl,                          // no .m3u8 in the path
    headers = mapOf("Authorization" to "Bearer $token"),
    format = RemoteFormat.Hls,                        // tell Android it is HLS
)
```

`format` defaults to `RemoteFormat.Auto`, which is right whenever an HLS URL ends in `.m3u8` or the
stream is a plain file. Android decides from the URL path alone, so a signed or rewritten HLS URL
needs `RemoteFormat.Hls`; see [`05-platform-notes.md`](05-platform-notes.md). The source's
`toString()` redacts header values, so logging a source does not leak the token — but the URL is
printed as given.

## Picture size

```kotlin
player.videoSizeFlow.collect { size: VideoSize? ->
    aspectRatio = size?.aspectRatio ?: DEFAULT_ASPECT
}
```

`null` until the platform reports a size — usually while preparing, before `Ready` — and `null`
again after `unload()`, a failure, or when a new source starts preparing. The size is the displayed
one: rotated for a video recorded in portrait, and widened or narrowed for a source with non-square
pixels. An adaptive (HLS) stream that switches rendition may report a new size mid-playback. The
size survives `Completed`, since the source is still loaded and its last frame still on screen.

## Volume and mute

```kotlin
player.setVolume(0.5f)     // clamped to 0f..1f
player.setMuted(true)      // silence; volumeFlow still reads 0.5
player.setMuted(false)     // back to 0.5
```

Mute is separate from volume, so a mute button never has to remember the level it replaced. The
platform receives one number — the volume, or `0f` while muted. This is the player's own output
level, not the device volume. A `NaN` volume is ignored.

## Looping

```kotlin
player.setRepeatMode(RepeatMode.One)
```

With `RepeatMode.One` the platform restarts the source at its end and the state never becomes
`Completed` — an exercise demo loops forever, and a "watched it" check keyed on `Completed` never
fires, so use `progress` for that instead. Changing the mode takes effect for the source already
loaded. Setting `One` on a player that is already `Completed` leaves it there until the next `play()`.

## Playback speed

```kotlin
player.setPlaybackSpeed(1.5f)
player.playbackSpeedFlow.value   // 1.5f — the clamped value in effect
```

Clamped to `VideoPlayerConfig`'s `minPlaybackSpeed..maxPlaybackSpeed` (`0.25f..3.0f` by default),
never rejected; `NaN` is ignored. As in the audio player, the rate reaches the platform only while
playing, on every `play()` and when a source loads — on `AVPlayer` a non-zero rate on a paused player
starts it.

## Every setting outlives the source

Speed, volume, mute and repeat mode belong to the player, not to what it plays. They survive
`prepare`, `unload`, and a failed load, and are pushed onto every newly loaded source — so a playlist
keeps the viewer's speed and a muted feed stays muted as it scrolls. Changing a setting while a source
is still `Preparing` is fine: it is applied the moment the load lands.

## Seeking and scrubbing

Positions are milliseconds and are always clamped — `seekTo(-1)` goes to `0`,
`seekTo(Long.MAX_VALUE)` goes to the end — so a scrubber can forward raw values:

```kotlin
fun onScrub(fraction: Float) {
    val duration: Long = player.stateFlow.value.duration ?: return
    player.seekTo((duration * fraction).toLong())
}
```

`seekForward()` / `seekBackward()` default to ten seconds (`DEFAULT_SEEK_AMOUNT_MS`) and never
overflow, whatever amount they are given. Seeking out of `Completed` lands in `Paused`. Seeking a
`Ready` player moves the playhead (`playbackPositionFlow`) and leaves it `Ready`; the next `play()`
starts from there.

## Tuning the position poll

```kotlin
createVideoPlayer(context, config = VideoPlayerConfig(positionUpdateIntervalMs = 100L))
```

The default is 250 ms — enough for a seek bar and a "watched 95%" check. Polling stops entirely
whenever the state is not `Playing`. Collect `playbackPositionFlow` rather than `stateFlow` in a
component that only draws a seek bar.

## Bringing your own engine

`VideoPlaybackEngine` is the seam the built-in players sit on, and it is public:

```kotlin
val player: VideoPlayer = createVideoPlayer(engine = MyEngine())
```

Read its KDoc first. The rules that matter: `release()` frees the loaded source, is idempotent, and
`load()` must work after it; `dispose()` (a no-op by default) frees what the engine keeps across
sources — a platform player, a native library instance — and is called exactly once, from the
player's `release()`, after the last `release()`, with nothing called after it; `load()` honors cancellation and throws to report failure, leaving nothing playable; the listener
is never called after `release()`; transport calls tolerate the wrong platform state;
`durationMs()`/`positionMs()`/`bufferedPositionMs()` are cheap and `0` when unknown;
`setLooping(true)` means the platform restarts the source itself and never reports completion. Any
method may be called from any thread (never two at once), and the listener may be called from any
thread — even while your engine holds its own lock, even from inside one of its methods: the player
never blocks in a callback.

The player takes ownership of the engine: it installs itself as the listener, and its own
`release()` releases and then disposes the engine. One engine per player.

## Common mistakes

- **Calling `play()` right after `prepare()` without checking the state.** `prepare` can end in
  `Error`, and `play()` there is a no-op.
- **Treating `duration == 0` as an error.** It means the platform has not reported a length — normal
  for a live stream.
- **Keying "watched" on `Completed` with `RepeatMode.One`.** A looping player never completes.
- **Releasing a shared player from one of its screens.** `release()` is permanent; use `unload()` for
  a player you do not own.
- **Collecting the flows from a leaked scope.** They never complete; collect from a scope cancelled
  with the screen.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — every public symbol
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, HLS, threading
- [`06-testing.md`](06-testing.md) — `FakeVideoPlaybackEngine`
