package io.github.jamal_wia.kmptoolkit.video.player

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Speed, volume, mute and repeat mode: clamped, reflected in their flows at once, pushed to the
 * engine whenever a source is loaded, and owned by the player rather than the source — so they
 * survive `prepare`, `unload` and even `release`.
 */
class VideoPlayerSettingsTest {

    private val source: VideoSource = VideoSource.Asset("intro.mp4")
    private val other: VideoSource = VideoSource.Remote("https://example.test/other.m3u8")

    private fun playerTest(
        engine: RecordingVideoPlaybackEngine = RecordingVideoPlaybackEngine(),
        config: VideoPlayerConfig = VideoPlayerConfig(),
        body: suspend TestScope.(RecordingVideoPlaybackEngine, VideoPlayer) -> Unit,
    ): TestResult = runTest {
        val player: VideoPlayer = createVideoPlayer(
            engine = engine,
            config = config,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )
        try {
            body(engine, player)
        } finally {
            player.release()
        }
    }

    // --- Speed ---

    @Test
    fun `setPlaybackSpeed clamps into the configured range`() = playerTest(
        config = VideoPlayerConfig(minPlaybackSpeed = 0.5f, maxPlaybackSpeed = 2.0f),
    ) { _, player ->
        player.setPlaybackSpeed(10.0f)
        assertEquals(2.0f, player.playbackSpeedFlow.value)

        player.setPlaybackSpeed(0.01f)
        assertEquals(0.5f, player.playbackSpeedFlow.value)

        player.setPlaybackSpeed(-3.0f)
        assertEquals(0.5f, player.playbackSpeedFlow.value)
    }

    @Test
    fun `a NaN speed is ignored`() = playerTest { _, player ->
        player.setPlaybackSpeed(1.5f)
        player.setPlaybackSpeed(Float.NaN)

        assertEquals(1.5f, player.playbackSpeedFlow.value)
    }

    @Test
    fun `the chosen speed is applied to the engine on load and on play`() = playerTest { engine, player ->
        player.setPlaybackSpeed(1.5f)
        player.prepare(source)
        assertEquals(1.5f, engine.appliedSpeed)

        player.play()
        assertEquals(listOf(1.5f, 1.5f), engine.appliedSpeeds)
    }

    @Test
    fun `a speed change while playing reaches the engine at once`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.setPlaybackSpeed(2.0f)

        assertEquals(2.0f, engine.appliedSpeed)
    }

    @Test
    fun `a speed change while paused waits for play`() = playerTest { engine, player ->
        player.prepare(source)
        player.play()
        player.pause()
        val before: Int = engine.appliedSpeeds.size
        player.setPlaybackSpeed(2.0f)
        assertEquals(before, engine.appliedSpeeds.size)

        player.play()
        assertEquals(2.0f, engine.appliedSpeed)
    }

    @Test
    fun `the chosen speed survives preparing another source`() = playerTest { engine, player ->
        player.setPlaybackSpeed(2.0f)
        player.prepare(source)
        player.prepare(other)

        assertEquals(2.0f, player.playbackSpeedFlow.value)
        assertEquals(2.0f, engine.appliedSpeed)
    }

    // --- Volume and mute ---

    @Test
    fun `setVolume clamps into zero to one`() = playerTest { _, player ->
        player.setVolume(1.7f)
        assertEquals(1.0f, player.volumeFlow.value)

        player.setVolume(-0.2f)
        assertEquals(0.0f, player.volumeFlow.value)
    }

    @Test
    fun `a NaN volume is ignored`() = playerTest { _, player ->
        player.setVolume(0.4f)
        player.setVolume(Float.NaN)

        assertEquals(0.4f, player.volumeFlow.value)
    }

    @Test
    fun `the volume is pushed to the engine once loaded and on every change while loaded`() =
        playerTest { engine, player ->
            player.setVolume(0.3f)
            assertTrue(engine.appliedVolumes.isEmpty(), "nothing is loaded yet")

            player.prepare(source)
            assertEquals(0.3f, engine.appliedVolume)

            player.setVolume(0.8f)
            assertEquals(0.8f, engine.appliedVolume)
        }

    @Test
    fun `setVolume does not change the mute flag`() = playerTest { _, player ->
        player.setMuted(true)
        player.setVolume(0.5f)

        assertEquals(true, player.isMutedFlow.value)
        assertEquals(0.5f, player.volumeFlow.value)
    }

    @Test
    fun `muting outputs silence and keeps the volume`() = playerTest { engine, player ->
        player.prepare(source)
        player.setVolume(0.6f)
        player.setMuted(true)

        assertEquals(0.0f, engine.appliedVolume)
        assertEquals(0.6f, player.volumeFlow.value)
        assertEquals(true, player.isMutedFlow.value)
    }

    @Test
    fun `unmuting restores the kept volume`() = playerTest { engine, player ->
        player.prepare(source)
        player.setVolume(0.6f)
        player.setMuted(true)
        player.setMuted(false)

        assertEquals(0.6f, engine.appliedVolume)
    }

    @Test
    fun `a volume change while muted is kept but stays silent`() = playerTest { engine, player ->
        player.prepare(source)
        player.setMuted(true)
        player.setVolume(0.2f)
        assertEquals(0.0f, engine.appliedVolume)

        player.setMuted(false)
        assertEquals(0.2f, engine.appliedVolume)
    }

    @Test
    fun `a player muted before prepare loads silent`() = playerTest { engine, player ->
        player.setMuted(true)
        player.prepare(source)

        assertEquals(0.0f, engine.appliedVolume)
    }

    // --- Repeat mode ---

    @Test
    fun `repeat mode one sets the engine looping once loaded`() = playerTest { engine, player ->
        player.setRepeatMode(RepeatMode.One)
        assertTrue(engine.appliedLooping.isEmpty(), "nothing is loaded yet")

        player.prepare(source)
        assertEquals(RepeatMode.One, player.repeatModeFlow.value)
        assertEquals(true, engine.looping)
    }

    @Test
    fun `changing the repeat mode takes effect for the source already loaded`() = playerTest { engine, player ->
        player.prepare(source)
        assertEquals(false, engine.looping)

        player.setRepeatMode(RepeatMode.One)
        assertEquals(true, engine.looping)

        player.setRepeatMode(RepeatMode.Off)
        assertEquals(false, engine.looping)
    }

    @Test
    fun `completed is never reached in repeat mode one`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 5_000L),
    ) { engine, player ->
        player.setRepeatMode(RepeatMode.One)
        player.prepare(source)
        player.play()
        engine.position = 5_000L
        // An engine should loop without reporting the end; one that reports it anyway — the mode
        // switched just as the end was posted — must still not complete.
        requireNotNull(engine.listener).onCompleted()

        assertEquals(VideoPlayerState.Playing(duration = 5_000L, currentPosition = 0L), player.stateFlow.value)
        assertEquals(0L, engine.seekTargets.last())
        assertEquals(2, engine.started)
    }

    @Test
    fun `a looping player keeps polling across the wrap`() = playerTest(
        engine = RecordingVideoPlaybackEngine(duration = 5_000L),
        config = VideoPlayerConfig(positionUpdateIntervalMs = 100L),
    ) { engine, player ->
        player.setRepeatMode(RepeatMode.One)
        player.prepare(source)
        player.play()
        requireNotNull(engine.listener).onCompleted()
        engine.position = 300L
        advanceTimeBy(101L)

        assertEquals(300L, player.playbackPositionFlow.value)
    }

    // --- Survival ---

    @Test
    fun `every setting survives preparing another source`() = playerTest { engine, player ->
        player.setPlaybackSpeed(1.25f)
        player.setVolume(0.4f)
        player.setMuted(true)
        player.setRepeatMode(RepeatMode.One)
        player.prepare(source)
        player.prepare(other)

        assertSettings(player, speed = 1.25f, volume = 0.4f, muted = true, repeat = RepeatMode.One)
        assertEquals(0.0f, engine.appliedVolume)
        assertEquals(true, engine.looping)
        assertEquals(1.25f, engine.appliedSpeed)
    }

    @Test
    fun `every setting survives unload and is reapplied on the next load`() = playerTest { engine, player ->
        player.prepare(source)
        player.setPlaybackSpeed(0.5f)
        player.setVolume(0.7f)
        player.setRepeatMode(RepeatMode.One)
        player.unload()

        assertSettings(player, speed = 0.5f, volume = 0.7f, muted = false, repeat = RepeatMode.One)

        engine.appliedVolumes.clear()
        engine.appliedLooping.clear()
        engine.appliedSpeeds.clear()
        player.prepare(other)

        assertEquals(listOf(0.7f), engine.appliedVolumes)
        assertEquals(listOf(true), engine.appliedLooping)
        assertEquals(listOf(0.5f), engine.appliedSpeeds)
    }

    @Test
    fun `settings changed while preparing are applied when the load lands`() =
        playerTest(RecordingVideoPlaybackEngine().apply { loadDelayMs = 1_000L }) { engine, player ->
            launch { player.prepare(source) }
            runCurrent()
            player.setVolume(0.2f)
            player.setRepeatMode(RepeatMode.One)
            assertTrue(engine.appliedVolumes.isEmpty(), "the engine is mid-load")
            advanceUntilIdle()

            assertEquals(0.2f, engine.appliedVolume)
            assertEquals(true, engine.looping)
        }

    @Test
    fun `settings after release are recorded but never reach the engine`() = playerTest { engine, player ->
        player.prepare(source)
        player.release()
        val calls: Int = engine.calls.size

        player.setPlaybackSpeed(2.0f)
        player.setVolume(0.1f)
        player.setMuted(true)
        player.setRepeatMode(RepeatMode.One)

        assertSettings(player, speed = 2.0f, volume = 0.1f, muted = true, repeat = RepeatMode.One)
        assertEquals(calls, engine.calls.size)
    }

    private fun assertSettings(
        player: VideoPlayer,
        speed: Float,
        volume: Float,
        muted: Boolean,
        repeat: RepeatMode,
    ) {
        assertEquals(speed, player.playbackSpeedFlow.value)
        assertEquals(volume, player.volumeFlow.value)
        assertEquals(muted, player.isMutedFlow.value)
        assertEquals(repeat, player.repeatModeFlow.value)
    }
}
