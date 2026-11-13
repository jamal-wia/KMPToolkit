package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi

/**
 * D1 round 11 fix2 (S2). The engine readings never suspend, so a `CancellationException` thrown by `positionMs()` during a
 * poll tick is the engine's failure in a cancellation's type, not the tick's own cancellation: the player settles on
 * `Error` carrying it, nothing reaches the handler, and a consumer's call is not handed it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PollCancellationReadingFixD1R11Test {

    @Test
    fun `a positionMs that throws a CancellationException during a poll tick settles the player on Error carrying it`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow()
            rig.player.play()
            val failure = CancellationException("the engine's own timeout")
            rig.engine.positionReading = { throw failure }
            rig.tick()
            val state: VideoPlayerState = rig.player.stateFlow.value
            assertIs<VideoPlayerState.Error>(state, "was $state")
            assertSame(failure, state.cause, "the engine's own exception, unchanged")
            assertEquals(emptyList(), rig.escaped, "nothing reaches the handler")
            // A consumer's call after it does not throw (it would cancel the consumer's coroutine silently).
            rig.player.seekTo(1_000L)
            rig.player.pause()
            rig.tick()
            assertEquals(state, rig.player.stateFlow.value)
        } finally {
            rig.close()
        }
    }
}
