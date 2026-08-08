# kmptoolkit-audio-player — Guide

Scenarios in rough order of how soon you will hit them.

## The state machine

Seven states, and the transitions between them are the module's whole contract:

```
                prepare()            play()
   Idle ──────► Preparing ─────► Ready ─────► Playing ──┐
    ▲               │              ▲            │  ▲    │ end of source
    │               │ failure      │ stop()     │  │    ▼
    │               ▼              └────────────┤  └── Completed
    │            Error ◄─── failure during playback     │
    │                                     pause()│      │ seekTo()
release()                                        ▼      │
    └───────────────────────────────────────── Paused ◄─┘
```

Reading it as code:

| From | Call | To |
|---|---|---|
| any | `prepare(source)` | `Preparing` → `Ready`, or `Error` |
| `Ready` / `Paused` / `Completed` | `play()` | `Playing` |
| `Playing` | `pause()` | `Paused` |
| any playable | `stop()` | `Ready`, playhead at `0` |
| `Completed` | `seekTo(n)` | `Paused` |
| `Playing` | end of source | `Completed` |
| any playable | `replay()` | `Playing` from `0` |
| any | `release()` | `Idle`, permanently |

`isPlayable` is the predicate that decides whether a transport call does anything: `Ready`,
`Playing`, `Paused` and `Completed` are playable, the rest are not. Every transport call on a
non-playable state is silently ignored — a tap while a stream is still buffering is not an error
worth propagating.

## Handling errors

`prepare` never throws for a load failure. It reports:

```kotlin
player.prepare(AudioSource.Remote(url))

when (val state: PlayerState = player.stateFlow.value) {
    is PlayerState.Ready -> player.play()
    is PlayerState.Error -> when (state.cause) {
        is IOException -> showRetry()          // unreachable, timed out, decode failed
        is IllegalArgumentException -> showBroken()   // bad URL, asset not in the bundle
        else -> showGeneric()
    }
    else -> Unit
}
```

The `Throwable` is the platform's own, passed through untouched: an `IOException` from Android's
`MediaPlayer`, an `IllegalStateException` describing the `AVPlayerItem` failure on iOS. Match on
types you care about and fall through on the rest — the exact types a platform produces are not part
of this module's compatibility promise.

A failure *after* loading succeeded — a stream that dies mid-track — arrives the same way, as
`PlayerState.Error`, at whatever moment the platform notices.

Recovering means preparing again. There is no `retry()`: the source you want to retry with is
information only you have.

## The release contract

A player owns a native handle and a polling coroutine, and garbage collection reclaims neither.

**Who releases:** whoever created it. A screen that creates a player in `onCreate` releases it in
`onDestroy`; a component that creates one per voice note releases it when that note scrolls away.
Handing an `AudioPlayer` to a collaborator does not transfer that duty unless you say so.

**When you get it wrong, nothing crashes.** The three failure modes people actually hit are all
defined:

```kotlin
player.release()
player.release()     // no-op — the engine is freed exactly once
player.play()        // no-op — a stray tap after teardown does nothing
player.seekTo(500)   // no-op
```

```kotlin
player.release()
player.prepare(source)
player.stateFlow.value   // PlayerState.Error(AudioPlayerReleasedException)
```

`prepare` is the one call that reports rather than ignores, because a caller genuinely waits on it —
a silent no-op there is indistinguishable from a hang.

A released player cannot be revived; construct another one.

**`use { }` works**, since `AudioPlayer` is `AutoCloseable` and `close()` is `release()`:

```kotlin
createAudioPlayer(context).use { player: AudioPlayer ->
    player.prepare(AudioSource.Asset("chime.mp3"))
    player.play()
    player.stateFlow.first { it is PlayerState.Completed }
}
```

Useful for a short cue you play to its end; a screen-scoped player outlives any single block, so
release it from the lifecycle callback instead.

## Cancellation

`prepare` is the only suspending call, and it honors cancellation. If the coroutine that called it
goes away mid-load — a `LaunchedEffect` whose key changed, a scope that was cancelled — the
half-loaded engine is released, the state returns to `Idle`, and the `CancellationException`
propagates.

```kotlin
LaunchedEffect(url) {
    player.prepare(AudioSource.Remote(url))   // cancelled cleanly when url changes
    player.play()
}
```

`Idle`, not `Error`: nothing failed, the caller simply left. Reserving `Error` for real failures is
what lets a UI show a retry button on `Error` without also showing one after every navigation.

## Seeking and scrubbing

Positions are milliseconds and are always clamped — `seekTo(-1)` goes to `0`, `seekTo(Long.MAX_VALUE)`
goes to the end. A scrubber can therefore forward raw values without range checks:

```kotlin
fun onScrub(fraction: Float) {
    val duration: Long = player.stateFlow.value.duration ?: return
    player.seekTo((duration * fraction).toLong())
}
```

`seekForward()` / `seekBackward()` default to ten seconds (`DEFAULT_SEEK_AMOUNT_MS`); pass an
explicit amount for a different skip button.

Seeking out of `Completed` lands in `Paused`, not `Completed` — once the playhead has moved off the
end, "completed" no longer describes the player, and `progress` would otherwise keep reading `1f`
with the playhead at zero.

## Playback speed

```kotlin
player.setPlaybackSpeed(1.5f)
player.playbackSpeed   // 1.5f — the clamped value actually in effect
```

Out-of-range values are clamped rather than rejected, so a UI offering fixed speed buttons never has
to know the platform's limits. The bounds come from `AudioPlayerConfig` (`0.25f..3.0f` by default),
and the chosen rate survives `prepare`, so a podcast app that loads the next episode keeps the
listener's speed.

The rate is pushed to the platform only while playing and re-applied on the next `play()`. Both
`MediaPlayer` and `AVPlayer` treat "set a non-zero rate" as "start playing", so applying it to a
paused player would silently turn a speed change into playback.

## Tuning the position poll

```kotlin
val player: AudioPlayer = createAudioPlayer(
    context = context,
    config = AudioPlayerConfig(positionUpdateIntervalMs = 33L),   // ~30 fps for a waveform
)
```

The default is 100 ms. Each tick reads the platform's position once and updates two `StateFlow`s;
polling stops entirely whenever the state is not `Playing`, so a paused player costs nothing.

If a component only draws a scrubber, collect `playbackPositionFlow` instead of `stateFlow` — it
changes on position ticks only.

## Two sounds at once

One player plays one source. For overlapping audio, create two players and release both:

```kotlin
val music: AudioPlayer = createAudioPlayer(context)
val effects: AudioPlayer = createAudioPlayer(context)
```

The donor this was ported from solved the same need with a second, named DI binding. With a plain
factory function there is nothing to name — call it twice.

## Bringing your own engine

`PlaybackEngine` is the seam the built-in players sit on, and it is public. Implement it to keep this
state machine while changing the backend:

```kotlin
class ExoPlaybackEngine(context: Context) : PlaybackEngine {
    override suspend fun load(source: AudioSource) { /* … */ }
    override fun start() { /* … */ }
    // …
}

val player: AudioPlayer = createAudioPlayer(engine = ExoPlaybackEngine(context))
```

Read `PlaybackEngine`'s KDoc before you start — four rules matter: `release()` must be idempotent,
the listener must never fire after `release()`, the state-changing calls must tolerate arriving in
the wrong platform state, and `load()` reports failure by throwing.

The player takes ownership of the engine you pass: it installs itself as the listener and releases
the engine from its own `release()`. Do not release the engine yourself, and do not share one engine
between two players.

## Common mistakes

- **Holding a player in a DI singleton.** Nothing then owns its release, and the native handle lives
  as long as the process. Scope it to the screen or component that uses it.
- **Calling `play()` right after `prepare()` without checking the state.** `prepare` can end in
  `Error`; `play()` on an `Error` is a no-op, so the UI sits there looking like it is loading.
  Branch on `isPlayable`.
- **Treating `duration == 0` as an error.** It means the platform has not reported a length yet —
  normal for live streams and briefly normal for remote sources.
- **Collecting `stateFlow` from a leaked scope.** The flow never completes, by design. Collect it
  from a scope that is cancelled with the screen.
- **Expecting `play()` to restart a `Completed` source.** It resumes from the end and completes
  again. `replay()` is the restart.

## Read next

- [`04-api-reference.md`](04-api-reference.md) — every public symbol
- [`05-platform-notes.md`](05-platform-notes.md) — permissions, cleartext HTTP, audio sessions
- [`06-testing.md`](06-testing.md) — `FakePlaybackEngine`
