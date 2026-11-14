package io.github.jamal_wia.kmptoolkit.audio.player.testing

import io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayer
import io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayerConfig
import io.github.jamal_wia.kmptoolkit.audio.player.AudioPlayerReleasedException
import io.github.jamal_wia.kmptoolkit.audio.player.AudioSource
import io.github.jamal_wia.kmptoolkit.audio.player.DEFAULT_SEEK_AMOUNT_MS
import io.github.jamal_wia.kmptoolkit.audio.player.PlayerState
import io.github.jamal_wia.kmptoolkit.audio.player.createAudioPlayer
import io.github.jamal_wia.kmptoolkit.audio.player.duration
import io.github.jamal_wia.kmptoolkit.audio.player.isPlayable
import io.github.jamal_wia.kmptoolkit.audio.player.isPlaying
import io.github.jamal_wia.kmptoolkit.audio.player.playbackPosition
import io.github.jamal_wia.kmptoolkit.audio.player.progress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 7 (docs as a test): the code samples and every observable-behaviour sentence of the audio
 * player's getting-started page, guide, API reference and testing page, run against the real state
 * machine on the scripted engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerDocsAsTestR7Test {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")

    private fun playerTest(
        engine: FakePlaybackEngine = FakePlaybackEngine(),
        config: AudioPlayerConfig = AudioPlayerConfig(),
        body: suspend TestScope.(AudioPlayer, FakePlaybackEngine) -> Unit,
    ): TestResult = runTest {
        val player: AudioPlayer = createAudioPlayer(
            engine,
            config,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            body(player, engine)
        } finally {
            player.release()
        }
    }

    // --- the complete example of 02-getting-started ---

    private class VoiceNotePlayer(private val player: AudioPlayer) {
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

    @Test
    fun `the getting started example plays toggles scrubs and disposes`() = playerTest { player, engine ->
        val note = VoiceNotePlayer(player)
        note.scrub(0.5f)
        assertEquals(PlayerState.Idle, player.stateFlow.value, "scrub with nothing loaded must do nothing")
        note.toggle()
        assertEquals(PlayerState.Idle, player.stateFlow.value, "play on Idle must be inert")

        note.open("https://example.test/voice.m4a")
        assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
        note.toggle()
        assertIs<PlayerState.Playing>(player.stateFlow.value)
        note.scrub(0.25f)
        assertEquals(2_500L, engine.positionMs)
        assertIs<PlayerState.Playing>(player.stateFlow.value)
        note.toggle()
        assertIs<PlayerState.Paused>(player.stateFlow.value)

        note.dispose()
        note.dispose()
        note.toggle()
        assertEquals(1, engine.releaseCount)
        assertFalse(engine.hasListener)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertFalse(engine.isPlaying)
    }

    // --- 03-guide: the release contract ---

    @Test
    fun `a released player ignores every transport call and reports the same error instance forever`() =
        playerTest { player, engine ->
            player.prepare(source)
            player.play()
            player.release()
            player.release()
            player.play()
            player.pause()
            player.stop()
            player.seekTo(500)
            player.seekForward()
            player.seekBackward()
            player.replay()
            player.unload()
            assertEquals(1, engine.releaseCount)
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)

            player.prepare(source)
            val first: PlayerState = player.stateFlow.value
            assertIs<PlayerState.Error>(first)
            assertIs<AudioPlayerReleasedException>(first.cause)
            assertIs<IllegalStateException>(first.cause)
            player.prepare(source)
            assertSame(first, player.stateFlow.value)
            assertEquals(1, engine.loadedSources.size, "a released player must load nothing more")
        }

    @Test
    fun `use closes the player after the block and a short cue plays to its end`() = runTest {
        val engine = FakePlaybackEngine()
        var inside: AudioPlayer? = null
        createAudioPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler)).use { player: AudioPlayer ->
            inside = player
            player.prepare(AudioSource.Asset("chime.mp3"))
            player.play()
            engine.completePlayback()
            player.stateFlow.first { it is PlayerState.Completed }
        }
        assertEquals(1, engine.releaseCount)
        assertFalse(engine.hasListener)
        assertEquals(PlayerState.Idle, inside?.stateFlow?.value)
    }

    @Test
    fun `unload keeps the listener and the speed and the next prepare works`() = playerTest { player, engine ->
        player.setPlaybackSpeed(1.5f)
        player.prepare(source)
        player.play()

        player.unload()

        assertEquals(1, engine.releaseCount)
        assertTrue(engine.hasListener)
        assertEquals(PlayerState.Idle, player.stateFlow.value)
        assertEquals(1.5f, player.playbackSpeed)
        player.prepare(source)
        assertIs<PlayerState.Ready>(player.stateFlow.value)
        assertEquals(1.5f, engine.appliedSpeed, "the speed must survive prepare")
    }

    @Test
    fun `a newer prepare replaces one in flight and the replaced call returns without writing state`() = runTest {
        val engine = FakePlaybackEngine().apply { loadDelayMs = 500L }
        val player: AudioPlayer = createAudioPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            val ayah1 = AudioSource.File("/a/1.mp3")
            val ayah2 = AudioSource.File("/a/2.mp3")
            val first = launch { player.prepare(ayah1) }
            runCurrent()
            assertEquals(PlayerState.Preparing, player.stateFlow.value)
            val second = launch { player.prepare(ayah2) }
            runCurrent()
            advanceTimeBy(600)
            runCurrent()

            assertTrue(first.isCompleted && !first.isCancelled, "the replaced call must return normally")
            assertTrue(second.isCompleted && !second.isCancelled)
            assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
            assertEquals(listOf<AudioSource>(ayah1, ayah2), engine.loadedSources)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a cancelled prepare ends idle and frees the half loaded engine`() = runTest {
        val engine = FakePlaybackEngine().apply { loadDelayMs = 500L }
        val player: AudioPlayer = createAudioPlayer(engine, coroutineContext = StandardTestDispatcher(testScheduler))
        try {
            val loading = launch { player.prepare(source) }
            runCurrent()
            assertEquals(PlayerState.Preparing, player.stateFlow.value)

            loading.cancel()
            loading.join()

            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(1, engine.releaseCount)
        } finally {
            player.release()
        }
    }

    @Test
    fun `an engine cancellation while the caller is active is a load failure`() = playerTest { player, engine ->
        val own = CancellationException("engine timeout")
        engine.loadFailure = own

        player.prepare(source)

        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
        assertSame(own, state.cause)
    }

    @Test
    fun `a blank file path never reaches the engine`() = playerTest { player, engine ->
        player.prepare(AudioSource.File(""))
        player.prepare(AudioSource.File("   "))

        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
        assertIs<IllegalArgumentException>(state.cause)
        assertTrue(engine.loadedSources.isEmpty())
    }

    @Test
    fun `a load failure keeps the platform throwable and every transport call stays inert`() =
        playerTest { player, engine ->
            val failure = RuntimeException("offline")
            engine.loadFailure = failure

            player.prepare(source)
            player.play()
            player.seekTo(5)
            player.replay()

            val state: PlayerState = player.stateFlow.value
            assertIs<PlayerState.Error>(state)
            assertSame(failure, state.cause)
            assertFalse(engine.isPlaying)
            assertFalse(state.isPlayable)

            engine.loadFailure = null
            player.prepare(source)
            assertEquals(PlayerState.Ready(10_000L), player.stateFlow.value)
        }

    // --- 03-guide: seeking, speed, config ---

    @Test
    fun `seeks clamp and a completed player seeks into paused`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        player.seekTo(-1)
        assertEquals(0L, engine.positionMs)
        player.seekTo(Long.MAX_VALUE)
        assertEquals(10_000L, engine.positionMs)
        assertEquals(PlayerState.Playing(10_000L, 10_000L), player.stateFlow.value)

        engine.completePlayback()
        assertEquals(PlayerState.Completed(10_000L), player.stateFlow.value)
        player.seekTo(2_000)
        assertEquals(PlayerState.Paused(10_000L, 2_000L), player.stateFlow.value)

        player.seekTo(0L)
        player.seekForward(DEFAULT_SEEK_AMOUNT_MS / 2)
        assertEquals(DEFAULT_SEEK_AMOUNT_MS / 2, engine.positionMs)
        player.seekForward()
        assertEquals(10_000L, engine.positionMs, "the default skip is ten seconds and clamps at the end")
        player.seekBackward()
        assertEquals(0L, engine.positionMs, "the default skip back is ten seconds and clamps at the start")
        player.seekForward(Long.MAX_VALUE)
        assertEquals(10_000L, engine.positionMs)
        player.seekBackward(Long.MAX_VALUE)
        assertEquals(0L, engine.positionMs)
        player.seekForward(Long.MIN_VALUE)
        assertEquals(0L, engine.positionMs)
        player.seekBackward(Long.MIN_VALUE)
        assertEquals(10_000L, engine.positionMs, "backwards by Long.MIN_VALUE is forwards by 2^63: the end")
        assertEquals(10_000L, DEFAULT_SEEK_AMOUNT_MS)
    }

    @Test
    fun `play from completed or from a seek onto the end starts over from zero`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.completePlayback()

        player.play()
        assertEquals(0L, engine.positionMs)
        assertEquals(PlayerState.Playing(10_000L, 0L), player.stateFlow.value)

        player.pause()
        player.seekTo(10_000L)
        assertEquals(PlayerState.Paused(10_000L, 10_000L), player.stateFlow.value)
        player.play()
        assertEquals(0L, engine.positionMs, "a playhead sitting on the end must restart from 0")
    }

    @Test
    fun `an unknown length passes relative skips on and completes where it ended`() =
        playerTest(FakePlaybackEngine(durationMs = 0L)) { player, engine ->
            player.prepare(source)
            assertEquals(PlayerState.Ready(0L), player.stateFlow.value)
            player.play()
            engine.advancePositionTo(30_000L)
            player.seekForward(5_000L)
            assertEquals(35_000L, engine.positionMs)
            player.seekBackward(5_000L)
            assertEquals(30_000L, engine.positionMs)
            player.seekBackward(1_000_000L)
            assertEquals(0L, engine.positionMs)
            player.seekTo(Long.MAX_VALUE)
            assertEquals(0L, engine.positionMs, "an absolute seek clamps to 0..0 while the length is unknown")

            engine.advancePositionTo(42_000L)
            engine.completePlayback()

            assertEquals(PlayerState.Completed(42_000L), player.stateFlow.value)
            assertEquals(42_000L, player.playbackPositionFlow.value)
            player.stop()
            assertEquals(PlayerState.Ready(42_000L), player.stateFlow.value, "the learned length counts as known")
        }

    @Test
    fun `two huge forward skips on an unknown length never wrap around`() =
        playerTest(FakePlaybackEngine(durationMs = 0L)) { player, engine ->
            player.prepare(source)
            player.play()
            player.seekForward(Long.MAX_VALUE)
            player.seekForward(Long.MAX_VALUE)
            assertTrue(engine.positionMs >= 0L, "position wrapped to ${engine.positionMs}")
            assertEquals(Long.MAX_VALUE, engine.positionMs)
        }

    @Test
    fun `speed is clamped pushed only while playing and survives prepare`() = playerTest { player, engine ->
        assertEquals(1.0f, player.playbackSpeed)
        player.prepare(source)
        player.setPlaybackSpeed(2f)
        assertEquals(2f, player.playbackSpeed)
        assertEquals(1.0f, engine.appliedSpeed, "a speed change on a ready player must not reach the platform")
        player.play()
        assertEquals(2f, engine.appliedSpeed)
        player.setPlaybackSpeed(1.5f)
        assertEquals(1.5f, engine.appliedSpeed, "while playing the new rate is pushed at once")
        player.pause()
        player.setPlaybackSpeed(0.5f)
        assertEquals(0.5f, player.playbackSpeed)
        assertEquals(1.5f, engine.appliedSpeed, "a paused player must not be handed a rate")
        player.play()
        assertEquals(0.5f, engine.appliedSpeed)

        player.setPlaybackSpeed(100f)
        assertEquals(3.0f, player.playbackSpeed)
        player.setPlaybackSpeed(0f)
        assertEquals(0.25f, player.playbackSpeed)
        player.setPlaybackSpeed(-5f)
        assertEquals(0.25f, player.playbackSpeed)
        player.setPlaybackSpeed(Float.POSITIVE_INFINITY)
        assertEquals(3.0f, player.playbackSpeed)
        player.setPlaybackSpeed(Float.NEGATIVE_INFINITY)
        assertEquals(0.25f, player.playbackSpeed)
        player.setPlaybackSpeed(1.25f)
        player.setPlaybackSpeed(Float.NaN)
        assertEquals(1.25f, player.playbackSpeed, "NaN is ignored")

        player.prepare(source)
        assertEquals(1.25f, engine.appliedSpeed)
        player.release()
        player.setPlaybackSpeed(2.5f)
        assertEquals(2.5f, player.playbackSpeed, "the rate is recorded even after release")
        assertEquals(1, engine.releaseCount)
    }

    @Test
    fun `the initial speed is the normal rate clamped into a configured range`() {
        val engine = FakePlaybackEngine()
        val above: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(minPlaybackSpeed = 1.5f, maxPlaybackSpeed = 2f))
        assertEquals(1.5f, above.playbackSpeed)
        above.release()
        val below: AudioPlayer = createAudioPlayer(
            FakePlaybackEngine(),
            AudioPlayerConfig(minPlaybackSpeed = 0.1f, maxPlaybackSpeed = 0.5f),
        )
        assertEquals(0.5f, below.playbackSpeed)
        below.release()
    }

    @Test
    fun `every bad config argument throws an illegal argument exception`() {
        val bad: List<() -> Any> = listOf(
            { AudioPlayerConfig(positionUpdateIntervalMs = 0L) },
            { AudioPlayerConfig(positionUpdateIntervalMs = -1L) },
            { AudioPlayerConfig(positionUpdateIntervalMs = Long.MIN_VALUE) },
            { AudioPlayerConfig(minPlaybackSpeed = 0f) },
            { AudioPlayerConfig(minPlaybackSpeed = -1f) },
            { AudioPlayerConfig(minPlaybackSpeed = Float.NaN) },
            { AudioPlayerConfig(minPlaybackSpeed = Float.POSITIVE_INFINITY) },
            { AudioPlayerConfig(minPlaybackSpeed = Float.NEGATIVE_INFINITY) },
            { AudioPlayerConfig(maxPlaybackSpeed = Float.NaN) },
            { AudioPlayerConfig(maxPlaybackSpeed = Float.POSITIVE_INFINITY) },
            { AudioPlayerConfig(minPlaybackSpeed = 2f, maxPlaybackSpeed = 1f) },
            { AudioPlayerConfig(minPlaybackSpeed = 4f) },
            { AudioPlayerConfig(maxPlaybackSpeed = 0.1f) },
        )
        bad.forEachIndexed { index: Int, build: () -> Any ->
            assertFailsWith<IllegalArgumentException>("bad argument #$index was accepted") { build() }
        }
        AudioPlayerConfig(positionUpdateIntervalMs = Long.MAX_VALUE)
        AudioPlayerConfig(minPlaybackSpeed = 1f, maxPlaybackSpeed = 1f)
        AudioPlayerConfig(minPlaybackSpeed = Float.MIN_VALUE, maxPlaybackSpeed = Float.MAX_VALUE)
        assertEquals(100L, AudioPlayerConfig.DEFAULT_POSITION_UPDATE_INTERVAL_MS)
        assertEquals(0.25f, AudioPlayerConfig.DEFAULT_MIN_PLAYBACK_SPEED)
        assertEquals(3.0f, AudioPlayerConfig.DEFAULT_MAX_PLAYBACK_SPEED)
    }

    // --- 04-api-reference: state extensions ---

    @Test
    fun `the state extension properties follow the reference table`() {
        val error = PlayerState.Error(IllegalStateException())
        val all: List<PlayerState> = listOf(
            PlayerState.Idle, PlayerState.Preparing, PlayerState.Ready(100), PlayerState.Playing(100, 50),
            PlayerState.Paused(100, 25), PlayerState.Completed(100), error,
        )
        assertEquals(listOf(false, false, true, true, true, true, false), all.map { it.isPlayable })
        assertEquals(listOf(false, false, false, true, false, false, false), all.map { it.isPlaying })
        assertEquals(listOf(null, null, 100L, 100L, 100L, 100L, null), all.map { it.duration })
        assertEquals(listOf(null, null, null, 50L, 25L, 100L, null), all.map { it.playbackPosition })
        assertEquals(listOf(0f, 0f, 0f, 0.5f, 0.25f, 1f, 0f), all.map { it.progress })
        assertEquals(0f, PlayerState.Playing(0, 50).progress)
        assertEquals(0f, PlayerState.Playing(-5, 50).progress)
        assertEquals(1f, PlayerState.Playing(100, 500).progress)
        assertEquals(0f, PlayerState.Paused(100, -5).progress)
        assertEquals(0f, PlayerState.Completed(0).progress)
    }

    // --- 06-testing: samples ---

    @Test
    fun `position updates reach the flow after one interval`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()

        engine.advancePositionBy(4_000)
        advanceTimeBy(101)

        assertEquals(4_000L, player.playbackPositionFlow.value)
    }

    @Test
    fun `a tuned interval polls at that rate and a paused player stops polling`() =
        playerTest(config = AudioPlayerConfig(positionUpdateIntervalMs = 33L)) { player, engine ->
            player.prepare(source)
            player.play()
            engine.advancePositionTo(1_000L)
            advanceTimeBy(34)
            assertEquals(1_000L, player.playbackPositionFlow.value)

            player.pause()
            engine.advancePositionTo(5_000L)
            advanceTimeBy(1_000)
            assertEquals(1_000L, player.playbackPositionFlow.value)
        }

    @Test
    fun `completion and a mid track failure sample from the testing page`() = playerTest { player, engine ->
        player.prepare(source)
        player.play()
        engine.completePlayback()
        assertEquals(PlayerState.Completed(engine.durationMs), player.stateFlow.value)

        player.replay()
        val boom = RuntimeException("route lost")
        engine.failPlayback(boom)
        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
        assertSame(boom, state.cause)
    }

    @Test
    fun `a failure right after load returned ends in error and never in ready`() = playerTest { player, engine ->
        val boom = RuntimeException("dead")
        val job = launch { player.prepare(source) }
        runCurrent()
        engine.failPlayback(boom)
        runCurrent()
        job.join()

        val state: PlayerState = player.stateFlow.value
        assertIs<PlayerState.Error>(state)
    }
}
