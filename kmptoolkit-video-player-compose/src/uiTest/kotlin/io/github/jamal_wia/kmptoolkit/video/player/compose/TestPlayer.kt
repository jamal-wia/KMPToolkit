package io.github.jamal_wia.kmptoolkit.video.player.compose

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlaybackEngine
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerConfig
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import io.github.jamal_wia.kmptoolkit.video.player.createVideoPlayer
import io.github.jamal_wia.kmptoolkit.video.player.testing.FakeVideoPlaybackEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher

/**
 * The fake engine, recording the transport calls the player makes on it. Only the calls a control
 * can cause are recorded; the settings the player pushes onto every fresh load are not.
 */
class RecordingEngine(val fake: FakeVideoPlaybackEngine) : VideoPlaybackEngine by fake {

    /** Every recorded call, in order, as `name` or `name(argument)`. */
    val calls: MutableList<String> = mutableListOf()

    /** How many times playback was started. */
    val starts: Int get() = calls.count { it == "start" }

    override fun start() {
        calls += "start"
        fake.start()
    }

    override fun pause() {
        calls += "pause"
        fake.pause()
    }

    override fun seekTo(positionMs: Long) {
        calls += "seekTo($positionMs)"
        fake.seekTo(positionMs)
    }
}

/**
 * A real [VideoPlayer] — the library's own state machine, from `createVideoPlayer` — over a
 * [RecordingEngine], so the UI tests see exactly what an app sees. Everything runs on the test
 * thread: the player's position poll is parked on a [TestDispatcher] that only [movePlayhead]
 * advances, so no update ever races the test.
 *
 * @param wrap wraps the engine before the player gets it — to add a capability, such as rendering
 *   frames on desktop.
 */
class TestPlayer(
    durationMs: Long = DEFAULT_DURATION_MS,
    wrap: (RecordingEngine) -> VideoPlaybackEngine = { it },
) {

    val fake: FakeVideoPlaybackEngine = FakeVideoPlaybackEngine(durationMs)
    val engine: RecordingEngine = RecordingEngine(fake)
    private val poll: TestDispatcher = StandardTestDispatcher()
    val player: VideoPlayer = createVideoPlayer(
        engine = wrap(engine),
        config = VideoPlayerConfig(positionUpdateIntervalMs = POLL_INTERVAL_MS),
        coroutineContext = poll,
    )

    val state: VideoPlayerState get() = player.stateFlow.value

    /** The recorded transport calls; see [RecordingEngine]. */
    val calls: List<String> get() = engine.calls

    /** Loads [source]; the fake engine loads at once unless told otherwise. */
    fun prepare(source: VideoSource = SOURCE_A): TestPlayer = apply { runBlocking { player.prepare(source) } }

    /** Loads, then leaves the player [VideoPlayerState.Playing] at [positionMs], with no call recorded. */
    fun playing(positionMs: Long = 0L): TestPlayer = apply {
        prepare()
        if (positionMs != 0L) player.seekTo(positionMs)
        player.play()
        engine.calls.clear()
    }

    /** Loads, then leaves the player [VideoPlayerState.Paused] at [positionMs], with no call recorded. */
    fun paused(positionMs: Long = 0L): TestPlayer = apply {
        playing(positionMs)
        player.pause()
        engine.calls.clear()
    }

    /** Moves the engine's playhead and lets one position poll run, as time passing while playing does. */
    fun movePlayhead(positionMs: Long) {
        fake.advancePositionTo(positionMs)
        poll.scheduler.advanceTimeBy(POLL_INTERVAL_MS)
        poll.scheduler.runCurrent()
    }

    companion object {
        const val DEFAULT_DURATION_MS: Long = 100_000L
        const val POLL_INTERVAL_MS: Long = 1_000L
    }
}

/** Creates [TestPlayer]s and remembers them, for [rememberVideoPlayer] tests. */
class RecordingFactory(private val make: () -> TestPlayer = { TestPlayer() }) : VideoPlayerFactory {
    val created: MutableList<TestPlayer> = mutableListOf()
    val configs: MutableList<VideoPlayerConfig> = mutableListOf()

    override fun create(config: VideoPlayerConfig): VideoPlayer {
        configs += config
        return make().also { created += it }.player
    }
}
