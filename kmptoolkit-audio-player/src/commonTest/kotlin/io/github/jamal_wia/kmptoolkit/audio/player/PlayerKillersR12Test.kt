package io.github.jamal_wia.kmptoolkit.audio.player

import io.github.jamal_wia.kmptoolkit.audio.player.testing.FakePlaybackEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Round 12: two sentences of the player contract that no earlier test pinned (mutants M45 and M48 survived the
 * narrow subset).
 * - 04, `play`: from `Completed` nothing is left to resume, so playback starts over from 0, whatever position the
 *   platform reports after the end (AVPlayer can sit a few milliseconds before the end).
 * - 04, `setPlaybackSpeed`: the rate set while the player is not playing is stored and applies from the next
 *   `play()`; the player does not hand it to the engine while not playing (a third-party engine that applies a rate
 *   to a paused platform player would start it).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerKillersR12Test {

    private val source: AudioSource = AudioSource.File("/r12/killers.m4a")

    @Test
    fun `play from completed starts over even when the platform reports a position before the end`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            player.play()
            engine.completePlayback()
            runCurrent()
            assertEquals(PlayerState.Completed(10_000L), player.stateFlow.value)
            engine.advancePositionTo(9_990L)

            player.play()

            assertEquals(PlayerState.Playing(10_000L, 0L), player.stateFlow.value, "playing from Completed did not start over")
            assertEquals(0L, engine.positionMs)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a rate set while not playing waits for the next play and never reaches the engine before`() = runTest {
        val engine = FakePlaybackEngine(durationMs = 10_000L)
        val player: AudioPlayer = createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            player.prepare(source)
            assertEquals(1.0f, engine.appliedSpeed)

            player.setPlaybackSpeed(2.0f)
            assertEquals(2.0f, player.playbackSpeed)
            assertEquals(1.0f, engine.appliedSpeed, "the rate reached the engine while Ready")

            player.play()
            assertEquals(2.0f, engine.appliedSpeed)
            player.setPlaybackSpeed(2.5f)
            assertEquals(2.5f, engine.appliedSpeed, "a rate set while playing applies at once")

            player.pause()
            player.setPlaybackSpeed(0.5f)
            assertEquals(2.5f, engine.appliedSpeed, "the rate reached the engine while Paused")
            player.play()
            assertEquals(0.5f, engine.appliedSpeed)

            engine.completePlayback()
            runCurrent()
            player.setPlaybackSpeed(1.5f)
            assertEquals(0.5f, engine.appliedSpeed, "the rate reached the engine while Completed")
            assertEquals(1.5f, player.playbackSpeed)
        } finally {
            player.release()
        }
    }
}
