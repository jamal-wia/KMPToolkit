# kmptoolkit-video-player — Testing

Testing code that consumes a `VideoPlayer`, without a device, a simulator, or a video file.

## Why there is a fixture at all

`VideoPlayer` is an interface, but it is implemented by the library only: new members may be added
in any release, so implementing it needs an explicit `@OptIn(ToolkitInheritanceApi::class)` — and a
hand-rolled stub would reproduce only what its author remembered about the contract anyway. `kmptoolkit-video-player-testing` ships
`FakeVideoPlaybackEngine`, an in-memory implementation of the *platform seam* rather than of the
player. Feeding it to `createVideoPlayer(engine = …)` gives you the same state machine that ships to
production with only the native part swapped out, so your test asserts against real behavior.

## Add it

```kotlin
kotlin {
    sourceSets {
        commonTest.dependencies {
            implementation("io.github.jamal-wia:kmptoolkit-video-player-testing:<version>")
        }
    }
}
```

Test source sets only. It is a separate artifact so nothing test-shaped reaches a consumer's runtime
classpath. It publishes for Android, iOS and JVM, like the player.

## The shape of a test

```kotlin
@Test
fun `an expired link shows the retry state`() = runTest {
    val engine = FakeVideoPlaybackEngine().apply { loadFailure = IllegalStateException("403") }
    val player: VideoPlayer = createVideoPlayer(
        engine = engine,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )
    val viewModel = LessonViewModel(player)

    viewModel.open("https://example.test/lesson.m3u8")

    assertEquals(LessonUi.Retry, viewModel.ui.value)
    player.release()
}
```

Pass a `TestDispatcher` as `coroutineContext`: the polling coroutine runs there, and on the test
scheduler it advances only when you say so.

## Driving the fake

Nothing moves on its own — there is no wall clock inside.

| Member | Use it to |
|---|---|
| `FakeVideoPlaybackEngine(durationMs)` / `var durationMs` | Set the duration a load reports. `0` models a live stream. |
| `var loadFailure: Throwable?` | Make every `prepare` fail with exactly this cause, until set back to `null`. |
| `var loadDelayMs: Long` | Make loads take virtual time — a window to cancel or replace a `prepare`. |
| `var suspendLoads: Boolean`, `finishLoad()`, `failLoad(cause)`, `val isLoadPending` | Hold a load in `Preparing` until the test decides how it ends. |
| `var videoSizeOnLoad: VideoSize?` | Report a picture size while loading, as platforms do. |
| `advancePositionTo(ms)` / `advancePositionBy(ms)` | Move the playhead, as playback would between polls. `advancePositionBy` stops at a known duration. |
| `var bufferedPositionMs` | Set what the next poll reads as buffered. |
| `completePlayback()` | Play to the end: `Completed` — or, when the player asked for looping, back to `0` with no completion, like a real engine. |
| `failPlayback(cause)` | Fail after a successful load — a stream that dies mid-video. |
| `reportBuffering(isBuffering)` | Stall or resume. |
| `reportVideoSize(size)` | Report or change the picture size (`null`: no picture). |
| `val isPlaying`, `val positionMs`, `val seekTargets` | Assert what the player asked the platform to do. |
| `val appliedSpeed`, `val appliedVolume`, `val isLooping` | Assert the settings the platform received — `appliedVolume` is `0f` while the player is muted. |
| `val loadedSources` | Every source passed to `load`, in order, including failed ones. |
| `val releaseCount`, `val disposeCount`, `val hasListener` | Assert the release contract. `disposeCount` is `1` once the player is released, `0` before — never raised by an unload. |

## Observing position updates

The playhead reaches the player on a poll, so advance virtual time past one interval (250 ms by
default):

```kotlin
player.prepare(source)
player.play()
engine.advancePositionBy(4_000)
engine.bufferedPositionMs = 12_000
advanceTimeBy(251)

assertEquals(4_000L, player.playbackPositionFlow.value)
assertEquals(12_000L, player.bufferedPositionFlow.value)
player.release()
```

**Release every player your test starts playing.** A playing player owns a coroutine that delays
forever; `runTest` drains the shared scheduler when the body returns, so a leaked polling loop hangs
the run instead of failing it. A `try`/`finally` helper is the reliable form:

```kotlin
private fun playerTest(
    engine: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(),
    body: suspend TestScope.(VideoPlayer) -> Unit,
): TestResult = runTest {
    val player: VideoPlayer = createVideoPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
    try {
        body(player)
    } finally {
        player.release()
    }
}
```

## Buffering, picture size and "watched 95%"

```kotlin
player.prepare(source)
player.play()
engine.reportBuffering(true)
assertTrue(viewModel.ui.value.showsSpinner)

engine.reportVideoSize(VideoSize(1080, 1920))
assertEquals(9f / 16f, viewModel.ui.value.aspectRatio)

engine.advancePositionTo(engine.durationMs * 96 / 100)
advanceTimeBy(251)
assertTrue(viewModel.watchedEnough.value)
```

## Testing a load you control

```kotlin
val engine = FakeVideoPlaybackEngine().apply { suspendLoads = true }
val loading = launch { player.prepare(source) }
runCurrent()
assertEquals(VideoPlayerState.Preparing, player.stateFlow.value)   // show the loading UI

engine.failLoad(IOException("offline"))
loading.join()
assertIs<VideoPlayerState.Error>(player.stateFlow.value)
```

Cancelling `loading` instead leaves the player `Idle` (not `Error`) and counts one `releaseCount`.

## Testing the release contract in your own code

```kotlin
component.dispose()
component.dispose()

assertEquals(1, engine.releaseCount)
assertEquals(1, engine.disposeCount)
assertFalse(engine.hasListener)
```

A component that borrows a long-lived player should `unload()` it instead; the fake tells the two
apart — an unload frees the engine (`releaseCount` goes up) but neither disposes it (`disposeCount`
stays `0`) nor detaches the listener (`hasListener` stays `true`), and the next `prepare` works.

## What is not covered by these tests

The fake substitutes the platform, so nothing below it is exercised: Media3's and `AVPlayer`'s own
behavior, codecs, HLS parsing, asset resolution, cleartext policy, what the surface draws.

The module's own tests take the same split. The state machine, the release contract, settings,
buffering and picture size are covered in `commonTest`, on every target — including, with real
threads, an end of media, a pause and a release racing the position poll, and engine events
reported from inside one of the player's own calls. On Android, Robolectric
tests run the real Media3 engine over Media3's test ExoPlayer (fake renderers, a fake clock) and fake
media sources: loading, completion, looping, buffering on seek, the picture-size mapping, settings,
marshalling from other threads, `release` versus `dispose`, and the source-to-URI and header mapping.
What they cannot cover without a device: real decoding, a real network stream and HLS end to end,
and a playback failure *after* a successful load (Media3's fakes offer no clean way to inject one) —
that path is covered by the common tests against the engine contract.
