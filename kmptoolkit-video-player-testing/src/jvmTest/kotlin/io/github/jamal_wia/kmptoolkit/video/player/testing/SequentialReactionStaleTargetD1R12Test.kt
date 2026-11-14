package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

/**
 * D1 round 12, found by the re-entrancy fuzz. `04-api-reference.md` "Threading": a call a collector makes from inside an
 * update acts on the state the running transition is establishing, "the call is never undone", and `unload()` and `prepare`
 * called from inside a write own the source and the state afterwards. A SECOND call of the same collector chain, made
 * after the first one returned and changed the player, still acts on the target of the OUTER transition, which is stale by
 * then: the first call's result is undone or ignored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SequentialReactionStaleTargetD1R12Test {

    /** Each update of [flow] after this call runs the next reaction of [reactions] (an update past the list does nothing). */
    private fun InlineRigD1R11.react(flow: Flow<Any?>, vararg reactions: suspend () -> Unit) {
        var next = 0
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            flow.drop(1).collect { reactions.getOrNull(next++)?.invoke() }
        }
    }

    private fun InlineRigD1R11.pollReadsOver500ms(): Int {
        var reads = 0
        engine.positionReading = { reads += 1; engine.position }
        scheduler.advanceTimeBy(500L)
        scheduler.runCurrent()
        return reads
    }

    @Test
    fun `a seek made by the collector after its own pause is made on the paused player`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow()
            rig.player.play()
            rig.engine.position = 2_000L
            rig.tick()
            rig.react(rig.player.stateFlow, { rig.player.pause() }, { rig.player.seekTo(2_880L) })
            rig.player.seekTo(7_439L)
            val state: VideoPlayerState = rig.player.stateFlow.value
            assertEquals(VideoPlayerState.Paused(10_000L, 2_880L), state, "the collector paused, then sought: Paused at 2880; engine log ${rig.engine.log}")
            assertFalse(rig.engine.playing, "the engine stays paused")
            assertEquals(0, rig.pollReadsOver500ms(), "no poll while paused")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a play made by the collector after its own unload does not start an engine with no source`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow()
            rig.player.play()
            rig.engine.position = 10_000L
            rig.react(rig.player.playbackPositionFlow, { rig.player.unload() }, { rig.player.play() })
            rig.engine.playing = false
            checkNotNull(rig.engine.sink).onCompleted()
            val afterRelease: List<String> = rig.engine.log.dropWhile { it != "release" }.drop(1)
            assertEquals(VideoPlayerState.Idle, rig.player.stateFlow.value, "the unload stands; engine log ${rig.engine.log}")
            assertTrue(afterRelease.none { it == "start" || it == "seek" }, "nothing is started or sought after the unload released the source: $afterRelease")
        } finally {
            rig.close()
        }
    }

    @Test
    fun `settings and a play after a prepare the collector awaited inside an unload act on the loaded source`() {
        val rig = InlineRigD1R11()
        try {
            rig.prepareNow()
            rig.player.play()
            rig.react(
                rig.player.stateFlow,
                {
                    rig.player.prepare(rig.source)
                    rig.player.setMuted(true)
                    rig.player.play()
                },
            )
            rig.player.unload()
            assertEquals(0f, rig.engine.volumes.last(), "muted: the engine of the loaded source is silent; log ${rig.engine.log}")
            assertTrue(rig.player.stateFlow.value is VideoPlayerState.Playing, "the collector's play started the loaded source: ${rig.player.stateFlow.value}")
        } finally {
            rig.close()
        }
    }
}
