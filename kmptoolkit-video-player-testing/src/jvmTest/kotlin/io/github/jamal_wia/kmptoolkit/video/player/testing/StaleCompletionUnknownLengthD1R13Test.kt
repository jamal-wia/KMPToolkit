package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 13, mutant ML: a completion the platform reported while a seek was being applied is dropped as stale, also when
 * the source's length is not known yet (`04-api-reference.md` and the epoch comment of `seekToLocked`: a seek anywhere but onto the
 * end makes an earlier completion stale; with no length there is no end to land on).
 */
class StaleCompletionUnknownLengthD1R13Test {

    @Test
    fun `a completion reported inside a seek of a source with no known length is dropped and the player keeps playing`() {
        val rig = InlineRigD1R11()
        try {
            rig.engine.duration = 0L
            rig.prepareNow()
            rig.player.play()
            rig.engine.position = 3_000L
            rig.tick()
            val states: List<VideoPlayerState> = rig.recordStates()
            // The platform reaches the end just as the seek is being applied: the report is stamped before the seek's bump.
            rig.engine.onSeek = {
                rig.engine.playing = false
                checkNotNull(rig.engine.sink).onCompleted()
            }
            rig.player.seekTo(1_000L)
            rig.engine.onSeek = {}
            assertTrue(states.none { it is VideoPlayerState.Completed }, "the stale completion won over the seek: $states")
            assertTrue(rig.player.stateFlow.value is VideoPlayerState.Playing, "state ${rig.player.stateFlow.value}")
            assertEquals(true, rig.engine.playing, "the engine was started again, log ${rig.engine.log}")
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }
}
