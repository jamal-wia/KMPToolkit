package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import io.github.jamal_wia.kmptoolkit.video.player.VideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking

/**
 * D1 round 11 fix2 (M2a). `04-api-reference.md`: `Error` is "load or playback failed", the size and the buffered position are
 * reset on a failure, and a call a collector makes from inside an update is never undone. A failure that comes while the
 * stream is stalled (the usual state when the network goes) must still end in `Error` carrying the very instance when a
 * collector of the buffering flag, which the failure writes first, pauses, seeks or stops the player from inside that write.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FailureNotLostFixD1R11Test {

    private val failure: Throwable = IllegalStateException("network gone")

    private fun failureWithReaction(reaction: (InlineRigD1R11) -> Unit) {
        val rig = InlineRigD1R11()
        try {
            rig.playingStalledWithSize()
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.isBufferingFlow) { reaction(rig) }
            val logBefore: Int = rig.engine.log.size
            checkNotNull(rig.engine.sink).onFailed(failure)
            assertTrue(fired.value, "the buffering collector ran inside the failure")
            val state: VideoPlayerState = rig.player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "04: the failure wins, was $state")
            assertSame(failure, state.cause, "04: the engine's own exception")
            assertEquals(null, rig.player.videoSizeFlow.value, "04: the size is reset on a failure")
            assertEquals(0L, rig.player.bufferedPositionFlow.value, "04: the buffered position is reset after a failure")
            assertEquals(false, rig.player.isBufferingFlow.value)
            rig.tick()
            rig.tick()
            assertEquals(emptyList(), rig.engine.log.drop(logBefore), "a failed player calls nothing on the engine, and nothing polls it")
            assertEquals(state, rig.player.stateFlow.value, "the player stays in Error")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a buffering collector that pauses inside a failure leaves the player in Error with the engine instance`() {
        failureWithReaction { rig: InlineRigD1R11 -> rig.player.pause() }
    }

    @Test
    fun `a buffering collector that seeks inside a failure leaves the player in Error with the engine instance`() {
        failureWithReaction { rig: InlineRigD1R11 -> rig.player.seekTo(1_000L) }
    }

    @Test
    fun `a buffering collector that stops inside a failure leaves the player in Error with the engine instance`() {
        failureWithReaction { rig: InlineRigD1R11 -> rig.player.stop() }
    }

    @Test
    fun `a poll reading that throws while the stream is stalled ends in Error even when the buffering collector pauses`() {
        val rig = InlineRigD1R11()
        try {
            rig.playingStalledWithSize()
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.isBufferingFlow) { rig.player.pause() }
            // Throws once, for the tick; the collector's own pause reads the position again and must find it healthy.
            var thrown = false
            rig.engine.positionReading = {
                if (!thrown) {
                    thrown = true
                    throw failure
                }
                rig.engine.position
            }
            rig.tick()
            assertTrue(thrown, "the tick read the position")
            assertTrue(fired.value, "the buffering collector ran inside the failure")
            val state: VideoPlayerState = rig.player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "was $state")
            assertSame(failure, state.cause)
            assertEquals(emptyList(), rig.escaped, "nothing reaches the handler")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an engine release that throws during a re-prepare while a position collector pauses inside the reset still ends in Error`() {
        val rig = InlineRigD1R11()
        try {
            rig.playingStalledWithSize()
            rig.engine.onRelease = { throw failure }
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) { rig.player.pause() }
            runBlocking { rig.player.prepare(VideoSource.Remote("https://example.test/b.mp4")) }
            assertTrue(fired.value, "the position collector ran inside the reset")
            val state: VideoPlayerState = rig.player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "04: prepare ends in Error when the engine cannot drop its source, was $state")
            assertSame(failure, state.cause)
            assertEquals(0L, rig.player.playbackPositionFlow.value)
        } finally {
            rig.engine.onRelease = {}
            rig.close()
        }
    }
}
