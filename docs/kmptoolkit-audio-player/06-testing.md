# kmptoolkit-audio-player — Testing

Testing code that consumes an `AudioPlayer`, without a device, a simulator, or an audio file.

## Why there is a fixture at all

`AudioPlayer` is an interface, so you *could* hand-roll a stub. You should not have to: what your
tests want to exercise is a real player's behavior — that a failed load produces `Error`, that
completion produces `Completed`, that a released player ignores a late tap — and a hand-rolled stub
only reproduces what its author remembered.

`kmptoolkit-audio-player-testing` ships `FakePlaybackEngine`, an in-memory implementation of the
*platform seam* rather than of the player. Feeding it to `createAudioPlayer` gives you the same state
machine that ships to production, with the native part swapped out. Your test then asserts against
real behavior, not against a second implementation of it.

## Add it

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-audio-player-testing:<version>")
        }
    }
}
```

`testImplementation`/`commonTest`, never `implementation`. It is a separate artifact precisely so
nothing test-shaped reaches a consumer's runtime classpath — see
[`docs/01-architecture.md`](../01-architecture.md).

## The shape of a test

```kotlin
@Test
fun `a failed download shows the retry state`() = runTest {
    val engine = FakePlaybackEngine().apply { loadFailure = IOException("offline") }
    val player: AudioPlayer = createAudioPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )
    val viewModel = VoiceNoteViewModel(player)

    viewModel.open("https://example.test/voice.m4a")

    assertEquals(VoiceNoteUi.Retry, viewModel.ui.value)
    player.release()
}
```

Pass a `TestDispatcher` as `coroutineContext`: that is where the position-polling coroutine runs, and
on the test scheduler it advances only when you say so.

## Driving the fake

Nothing moves on its own — there is no wall clock inside, which is what makes these tests instant and
deterministic.

| Member | Use it to |
|---|---|
| `FakePlaybackEngine(durationMs)` / `var durationMs` | Set the duration a load reports. `0` models a source whose length the platform never reports. |
| `var loadFailure: Throwable?` | Make the next `prepare` fail with exactly this cause. Set back to `null` to let the next one succeed. |
| `var loadDelayMs: Long` | Give the test a window in which to cancel the coroutine that called `prepare`. |
| `advancePositionTo(ms)` / `advancePositionBy(ms)` | Move the playhead, as playback would between two polls. |
| `completePlayback()` | Play to the end: playhead to `durationMs`, then `onCompleted`. |
| `failPlayback(cause)` | Fail *after* a successful load — a stream that dies mid-track. |
| `val positionMs`, `val isPlaying`, `val appliedSpeed` | Assert what the player asked the platform to do. |
| `val loadedSources` | Every source passed to `load`, in order, including ones that then failed. |
| `val releaseCount`, `val hasListener` | Assert the release contract — see below. |

## Observing position updates

The playhead only reaches the player on a poll, so advance virtual time past one interval:

```kotlin
player.prepare(source)
player.play()

engine.advancePositionBy(4_000)
advanceTimeBy(101)                    // one default 100 ms interval

assertEquals(4_000L, player.playbackPositionFlow.value)
player.release()
```

**Release every player your test starts.** A playing player owns a coroutine that delays forever;
`runTest` drains the shared scheduler when the body returns, so a leaked polling loop hangs the run
rather than failing it. A `try`/`finally` helper is the reliable form:

```kotlin
private fun playerTest(
    engine: FakePlaybackEngine = FakePlaybackEngine(),
    body: suspend TestScope.(AudioPlayer) -> Unit,
): TestResult = runTest {
    val player: AudioPlayer = createAudioPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
    try {
        body(player)
    } finally {
        player.release()
    }
}
```

## Testing completion and mid-track failure

```kotlin
player.prepare(source)
player.play()
engine.completePlayback()

assertEquals(PlayerState.Completed(engine.durationMs), player.stateFlow.value)
```

```kotlin
engine.failPlayback(IOException("route lost"))

val state: PlayerState = player.stateFlow.value
assertIs<PlayerState.Error>(state)
assertIs<IOException>(state.cause)
```

## Testing the release contract in your own code

The two things worth asserting about a component that owns a player:

```kotlin
@Test
fun `disposing the component releases the player exactly once`() = runTest {
    val engine = FakePlaybackEngine()
    val component = VoiceNotePlayer(createAudioPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler)))

    component.dispose()
    component.dispose()

    assertEquals(1, engine.releaseCount)
    assertFalse(engine.hasListener)
}
```

```kotlin
@Test
fun `a tap after disposal does nothing`() = runTest {
    // …
    component.dispose()
    component.toggle()

    assertEquals(PlayerState.Idle, player.stateFlow.value)
    assertFalse(engine.isPlaying)
}
```

## Testing cancellation

```kotlin
val engine = FakePlaybackEngine().apply { loadDelayMs = 500L }
val loading = launch { player.prepare(source) }
runCurrent()
assertEquals(PlayerState.Preparing, player.stateFlow.value)

loading.cancel()
loading.join()

assertEquals(PlayerState.Idle, player.stateFlow.value)   // Idle, not Error — nothing failed
assertEquals(1, engine.releaseCount)                     // the half-loaded engine was freed
```

## What is not covered by these tests

`FakePlaybackEngine` substitutes the platform, so nothing below it is exercised: `MediaPlayer`'s and
`AVPlayer`'s own behavior, asset resolution inside a bundle, cleartext-HTTP policy, audio-session
category effects, codec support. Those need a device or a simulator, and no fake can stand in for
them — [`05-platform-notes.md`](05-platform-notes.md) lists what each platform actually does.

The module's own tests take the same split: the state machine, the release contract, clamping and
cancellation are covered in `commonTest`; the two engines are thin enough to be verified by reading
them against that document.
