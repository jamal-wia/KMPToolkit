package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * The documentation says a length, once known, is never reset to `0` in `Playing`, and that a length
 * the platform reports late replaces a `0`. These tests follow a length through every transition that
 * publishes one, not only the poll, the pause and the seek.
 */
class AudioPlayerLengthFlapContractTest {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")

    private fun TestScope.newPlayer(engine: RecordingPlaybackEngine): AudioPlayer =
        createAudioPlayer(engine, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))

    @Test
    fun `resuming after the length flapped to zero keeps the length the paused state showed`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            player.pause()
            assertEquals(10_000L, (player.stateFlow.value as PlayerState.Paused).duration)
            engine.duration = 0L

            player.play()

            val playing: PlayerState = player.stateFlow.value
            assertTrue(playing is PlayerState.Playing && playing.duration == 10_000L, "resumed with the length reset: $playing")
        } finally {
            player.release()
        }
    }

    @Test
    fun `an absolute seek on a paused known length keeps that length when the engine reports zero`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            player.pause()
            engine.duration = 0L

            player.seekTo(5_000L)

            assertEquals(PlayerState.Paused(10_000L, 5_000L), player.stateFlow.value)
            assertEquals(5_000L, engine.seekTargets.last())
        } finally {
            player.release()
        }
    }

    @Test
    fun `seeking out of a completed state picks up a length that was not known at the end`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 0L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.listener!!.onCompleted()
            runCurrent()
            assertEquals(PlayerState.Completed(0L), player.stateFlow.value)
            engine.duration = 30_000L // the platform reports the length late

            player.seekTo(1_000L)

            assertEquals(PlayerState.Paused(30_000L, 1_000L), player.stateFlow.value)
        } finally {
            player.release()
        }
    }

    @Test
    fun `a length that flaps to zero before the end does not reset the finished state`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            engine.position = 4_000L
            advanceTimeBy(100L); runCurrent()
            engine.duration = 0L
            advanceTimeBy(100L); runCurrent()
            assertEquals(10_000L, (player.stateFlow.value as PlayerState.Playing).duration)

            engine.listener!!.onCompleted(); runCurrent()

            val completed: PlayerState = player.stateFlow.value
            assertTrue(completed is PlayerState.Completed && completed.duration == 10_000L, "completed with the length reset: $completed")
        } finally {
            player.release()
        }
    }

    @Test
    fun `stopping after the length flapped to zero keeps the length the player already showed`() = runTest {
        val engine = RecordingPlaybackEngine(duration = 10_000L)
        val player: AudioPlayer = newPlayer(engine)
        try {
            player.prepare(source)
            player.play()
            advanceTimeBy(100L); runCurrent()
            engine.duration = 0L

            player.stop()

            val stopped: PlayerState = player.stateFlow.value
            assertTrue(stopped is PlayerState.Ready && stopped.duration == 10_000L, "stopped with the length reset: $stopped")
        } finally {
            player.release()
        }
    }
}
