package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * D1 round 11 fix4 (R1 of the re-check of fix3). `04-api-reference.md` 04:97 and 04:101: `Playing` holds exactly while the
 * engine is started and the poll runs, and a call a collector makes from inside an update is never undone. The load
 * reports a stall, so the last step of `prepare` writes the buffered position and then the stall. A
 * `bufferedPositionFlow` collector plays, which cuts the first write; a second call, from an `isBufferingFlow`
 * collector inside the stall write, must act on the state the first call published (`Playing`), not on the `Ready` the
 * last step was heading for.
 */
class SourceFactAfterTransportCallFixD1R11Test {

    private fun prepareWithTwoCollectors(rig: InlineRigD1R11, second: () -> Unit): Pair<InlineRigD1R11.Fired, InlineRigD1R11.Fired> {
        rig.engine.loadAction = { checkNotNull(rig.engine.sink).onBufferingChanged(true) }
        val first: InlineRigD1R11.Fired = rig.reactOnce(rig.player.bufferedPositionFlow) { rig.player.play() }
        val secondFired: InlineRigD1R11.Fired = rig.reactOnce(rig.player.isBufferingFlow) { second() }
        rig.prepareNow()
        assertTrue(first.value, "the bufferedPositionFlow collector played inside the last step of prepare")
        assertTrue(secondFired.value, "the isBufferingFlow collector ran inside the stall write")
        assertEquals(true, rig.player.isBufferingFlow.value, "04: the stall reported before Ready is kept")
        assertEquals(4_000L, rig.player.bufferedPositionFlow.value)
        return first to secondFired
    }

    @Test
    fun `a seek from an isBufferingFlow collector after a play from a bufferedPositionFlow collector leaves the player Playing at the target with the poll running`() {
        val rig = InlineRigD1R11()
        try {
            prepareWithTwoCollectors(rig) { rig.player.seekTo(500L) }
            assertEquals(VideoPlayerState.Playing(10_000L, 500L), rig.player.stateFlow.value, "the play stands and the seek moved the playhead")
            assertEquals(true, rig.engine.playing, "the engine is started")
            assertEquals(listOf(500L), rig.engine.seekTargets, "the engine was sought once, to the target")
            var reads = 0
            rig.engine.positionReading = { reads += 1; 700L }
            rig.tick()
            assertEquals(1, reads, "the poll reads once per interval")
            assertEquals(VideoPlayerState.Playing(10_000L, 700L), rig.player.stateFlow.value, "the poll refreshes a Playing player")
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a pause from an isBufferingFlow collector after a play from a bufferedPositionFlow collector leaves the player Paused with the engine paused`() {
        val rig = InlineRigD1R11()
        try {
            prepareWithTwoCollectors(rig) { rig.player.pause() }
            assertEquals(VideoPlayerState.Paused(10_000L, 0L), rig.player.stateFlow.value, "the pause is not lost")
            assertEquals(false, rig.engine.playing, "the engine is paused")
            var reads = 0
            rig.engine.positionReading = { reads += 1; 700L }
            repeat(10) { rig.scheduler.advanceTimeBy(1_000L) }
            rig.scheduler.runCurrent()
            assertEquals(0, reads, "no poll runs over a paused player")
            assertEquals(emptyList(), rig.escaped)
        } finally {
            rig.close()
        }
    }
}
