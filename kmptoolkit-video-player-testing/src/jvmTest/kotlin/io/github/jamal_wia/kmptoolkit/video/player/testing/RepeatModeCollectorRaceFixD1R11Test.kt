package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.RepeatMode
import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 11 fix5 (F-S1 of the closing sweep). `04-api-reference.md` 04:101: a call a collector of any player flow makes
 * from inside an update is never undone. A `repeatModeFlow` collector that enforces a policy by calling
 * `setRepeatMode(Off)` inside the write of the consumer's `setRepeatMode(One)` must win: the flow ends `Off`, the engine
 * is told to stop looping last, and the source then completes (a looping engine never reports the end, so the
 * `Completed` that `Off` promises would never come).
 */
class RepeatModeCollectorRaceFixD1R11Test {

    @Test
    fun `a repeatModeFlow collector that sets Off inside a consumer's set One leaves the engine not looping and the end reaches Completed`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow()
            rig.player.play()
            assertEquals(false, rig.engine.lastLooping, "the setup: not looping")
            val fired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.repeatModeFlow) { rig.player.setRepeatMode(RepeatMode.Off) }
            rig.player.setRepeatMode(RepeatMode.One)
            assertTrue(fired.value, "the collector ran inside the write of One")
            assertEquals(RepeatMode.Off, rig.player.repeatModeFlow.value, "the collector's call stands")
            assertEquals(false, rig.engine.lastLooping, "the engine's last setLooping is false: ${rig.engine.log}")
            checkNotNull(rig.engine.sink).onCompleted()
            assertEquals(VideoPlayerState.Completed(10_000L), rig.player.stateFlow.value, "04: with Off the end gives Completed")
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }
}
