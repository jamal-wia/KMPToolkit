package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Boss round 1 of unit C: one consumer flow through the public [AudioPlayer] surface only, asserting
 * only what the KDoc of [AudioPlayer] promises: a speed chosen while paused does not start playback
 * and applies on the next play; play from Completed starts over; a seek out of Completed pauses;
 * stop rewinds to Ready; unload ends in Idle with the position at 0 and the speed unchanged, and the
 * next prepare works as on a fresh player. Virtual time, no real media.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerConsumerFlowBoss1Test {

    @Test
    fun `a player shared across screens behaves as its KDoc says from speed slider to unload`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(AudioSource.File("/boss1/a.m4a"))
            runCurrent()
            assertIs<PlayerState.Ready>(player.stateFlow.value)

            // The speed slider moves before the first play: nothing starts.
            player.setPlaybackSpeed(1.5f)
            runCurrent()
            assertFalse(engine.isPlaying, "a speed change started playback")
            assertIs<PlayerState.Ready>(player.stateFlow.value)
            assertEquals(1.5f, player.playbackSpeed)

            player.play()
            runCurrent()
            assertTrue(engine.isPlaying)
            assertEquals(1.5f, engine.appliedSpeed, "the chosen speed was not applied on play")

            player.pause()
            player.setPlaybackSpeed(0.5f)
            runCurrent()
            assertFalse(engine.isPlaying, "a speed change while paused resumed playback")
            assertIs<PlayerState.Paused>(player.stateFlow.value)

            player.play()
            engine.completePlayback()
            runCurrent()
            assertIs<PlayerState.Completed>(player.stateFlow.value)

            // Seeking out of Completed pauses.
            player.seekTo(4_000L)
            runCurrent()
            assertIs<PlayerState.Paused>(player.stateFlow.value)
            assertEquals(4_000L, player.playbackPositionFlow.value)

            player.play()
            engine.completePlayback()
            runCurrent()
            // Play from Completed starts over from 0.
            player.play()
            runCurrent()
            assertIs<PlayerState.Playing>(player.stateFlow.value)
            assertEquals(0L, engine.positionMs, "play from Completed did not start over")

            player.stop()
            runCurrent()
            assertIs<PlayerState.Ready>(player.stateFlow.value)
            assertFalse(engine.isPlaying)

            player.unload()
            runCurrent()
            assertEquals(PlayerState.Idle, player.stateFlow.value)
            assertEquals(0L, player.playbackPositionFlow.value)
            assertEquals(0.5f, player.playbackSpeed, "unload changed the speed")

            player.prepare(AudioSource.File("/boss1/b.m4a"))
            runCurrent()
            assertIs<PlayerState.Ready>(player.stateFlow.value)
            player.play()
            runCurrent()
            assertEquals(0.5f, engine.appliedSpeed, "the kept speed was not applied after unload and prepare")
        } finally {
            player.release()
        }
    }
}
