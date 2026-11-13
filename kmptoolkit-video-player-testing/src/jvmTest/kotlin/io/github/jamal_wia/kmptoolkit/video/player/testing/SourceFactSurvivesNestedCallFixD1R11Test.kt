package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 11 fix3 (M1 of the second review). `04-api-reference.md` and `03-guide.md`: a stall of the just-loaded source
 * that the engine reports before `Ready` is published is kept and shown with `Ready`, and the buffered position is
 * snapshotted when a load finishes. What the engine reported about its source is a fact about the source: a transport
 * call (play, seek, stop) that a `bufferedPositionFlow` collector makes from inside the last step of `prepare` changes
 * the transport, not the source, so it must not drop the stall. The call's own effect stands as well.
 */
class SourceFactSurvivesNestedCallFixD1R11Test {

    /** A load that reports a stall and a buffered position of 4000 (the rig's engine default), then a collector that reacts to the latter. */
    private fun scenario(reaction: (InlineRigD1R11) -> Unit, check: (InlineRigD1R11) -> Unit) {
        val rig = InlineRigD1R11()
        try {
            rig.engine.loadAction = { checkNotNull(rig.engine.sink).onBufferingChanged(true) }
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.bufferedPositionFlow) { reaction(rig) }
            rig.prepareNow()
            assertTrue(fired.value, "the bufferedPositionFlow collector ran inside the last step of prepare")
            assertEquals(4_000L, rig.player.bufferedPositionFlow.value, "04: the buffered position is snapshotted when the load finishes")
            assertEquals(true, rig.player.isBufferingFlow.value, "04: the stall reported before Ready is kept and shown")
            check(rig)
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a play from a bufferedPositionFlow collector inside the last step of prepare keeps the stall and the player ends Playing`() {
        scenario(reaction = { it.player.play() }) { rig ->
            assertEquals(VideoPlayerState.Playing(10_000L, 0L), rig.player.stateFlow.value, "the call stands")
            assertEquals(true, rig.engine.playing, "the engine is started")
        }
    }

    @Test
    fun `a seek from a bufferedPositionFlow collector inside the last step of prepare keeps the stall and the playhead stands`() {
        scenario(reaction = { it.player.seekTo(1_000L) }) { rig ->
            assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "seeking a Ready player keeps it Ready")
            assertEquals(1_000L, rig.player.playbackPositionFlow.value, "the call stands")
            assertEquals(listOf(1_000L), rig.engine.seekTargets, "the engine was sought once, to the target")
        }
    }

    @Test
    fun `a stop from a bufferedPositionFlow collector inside the last step of prepare keeps the stall and the player ends Ready at zero`() {
        scenario(reaction = { it.player.stop() }) { rig ->
            assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "the call stands")
            assertEquals(0L, rig.player.playbackPositionFlow.value)
            assertEquals(false, rig.engine.playing, "the engine is paused")
            assertTrue("pause" in rig.engine.log, "the stop paused the engine: ${rig.engine.log}")
        }
    }
}
