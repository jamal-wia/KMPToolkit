package io.github.jamal_wia.kmptoolkit.audio.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout

/**
 * `prepare` is documented to report a load failure as `PlayerState.Error` and never to throw it; only
 * the caller's own cancellation propagates. An engine whose load gives up with a cancellation of its own
 * (a deadline of its own, a platform wrapper that reports "cancelled") fails the load while the caller
 * is still active.
 */
class AudioPlayerEngineCancellationTest {

    private val source: AudioSource = AudioSource.File("/a/b.mp3")

    private class GivingUpEngine(private val giveUp: suspend () -> Unit) : PlaybackEngine {
        override fun setListener(listener: PlaybackEngineListener?) = Unit
        override suspend fun load(source: AudioSource) = giveUp()
        override fun start() = Unit
        override fun pause() = Unit
        override fun seekTo(positionMs: Long) = Unit
        override fun setSpeed(speed: Float) = Unit
        override fun durationMs(): Long = 0L
        override fun positionMs(): Long = 0L
        override fun release() = Unit
    }

    @Test
    fun `an engine that fails the load with a cancellation of its own is reported as an error and not thrown`() = runTest {
        val cause = CancellationException("the engine gave up")
        val player: AudioPlayer = createAudioPlayer(GivingUpEngine { throw cause }, AudioPlayerConfig(), StandardTestDispatcher(testScheduler))
        try {
            val thrown: Throwable? = runCatching { player.prepare(source) }.exceptionOrNull()

            assertNull(thrown, "prepare threw $thrown into a caller that was never cancelled")
            val state: PlayerState = player.stateFlow.value
            assertTrue(state is PlayerState.Error && state.cause === cause, "state was $state")
        } finally {
            player.release()
        }
    }

    @Test
    fun `an engine whose own deadline expires fails the load with an error and not with a cancellation`() = runTest {
        val player: AudioPlayer = createAudioPlayer(
            GivingUpEngine { withTimeout(50L) { delay(10_000L) } },
            AudioPlayerConfig(),
            StandardTestDispatcher(testScheduler),
        )
        try {
            val thrown: Throwable? = runCatching { player.prepare(source) }.exceptionOrNull()

            assertNull(thrown, "prepare threw $thrown into a caller that was never cancelled")
            val state: PlayerState = player.stateFlow.value
            assertIs<PlayerState.Error>(state)
            assertTrue(state.cause is TimeoutCancellationException, "cause was ${state.cause}")
        } finally {
            player.release()
        }
    }

    @Test
    fun `control - the caller's own cancellation still returns to idle and propagates`() = runTest {
        val player: AudioPlayer = createAudioPlayer(
            GivingUpEngine { delay(10_000L) },
            AudioPlayerConfig(),
            StandardTestDispatcher(testScheduler),
        )
        try {
            val thrown: Throwable? = runCatching { withTimeout(50L) { player.prepare(source) } }.exceptionOrNull()

            assertTrue(thrown is TimeoutCancellationException, "thrown was $thrown")
            assertEquals(PlayerState.Idle, player.stateFlow.value)
        } finally {
            player.release()
        }
    }
}
