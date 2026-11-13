package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch

/**
 * D1 round 11 fix3 (S1 of the second review). `04-api-reference.md`: `unload` resets the source flows and leaves the
 * state to a `prepare` called from inside it, and the buffered position is snapshotted when a load finishes. When the
 * load of that nested `prepare` returns without suspending (a local source, a fake engine), the new source is already
 * `Ready` with its buffered position when the reset of `unload` goes on; the rest of that reset must not overwrite the
 * values of the new source.
 */
class UnloadResetStopsAtNestedPrepareFixD1R11Test {

    @Test
    fun `an unload whose position collector prepares inline leaves the buffered position of the new source`() {
        val rig = InlineRigD1R11()
        try {
            rig.playingStalledWithSize()
            // The rig's engine reports 4000 buffered for every source, the new one included.
            assertEquals(4_000L, rig.engine.buffered, "the setup's engine reports a buffered position")
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.playbackPositionFlow) {
                rig.scope.launch(start = CoroutineStart.UNDISPATCHED) { rig.player.prepare(rig.source) }
            }
            rig.player.unload()
            assertTrue(fired.value, "the collector prepared from inside the reset of unload")
            assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "the nested prepare owns the state and loaded")
            assertEquals(4_000L, rig.player.bufferedPositionFlow.value, "04: snapshotted when the load finishes, not reset by the unload that was left behind")
            assertEquals(false, rig.player.isBufferingFlow.value)
            assertEquals(null, rig.player.videoSizeFlow.value, "the new source reported no size")
            assertEquals(0L, rig.player.playbackPositionFlow.value)
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }
}
