package io.github.jamal_wia.kmptoolkit.video.player.testing

import io.github.jamal_wia.kmptoolkit.video.player.VideoPlayerState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/**
 * D1 round 11 fix2 (M2b). An A/B loop: a position collector that seeks back to A when the playhead reaches B. Run from
 * inside `pause()`, `stop()`, a completion and a play from the end, the loop's call stands: after `pause()` the state is
 * `Paused` (`04-api-reference.md`: "after `pause()`, `stateFlow` already holds `Paused`"), never a `Playing` over a paused
 * engine with no poll.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PausedStopSeekLoopFixD1R11Test {

    private val a: Long = 1_000L
    private val b: Long = 6_000L

    private fun InlineRigD1R11.loop(trigger: (Long) -> Boolean) {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            player.playbackPositionFlow.collect { p: Long -> if (trigger(p) && p != a) player.seekTo(a) }
        }
    }

    private fun playingAt(rig: InlineRigD1R11, position: Long) {
        rig.prepareNow()
        rig.player.play()
        rig.engine.position = 3_000L
        rig.tick()
        rig.engine.position = position
    }

    private fun assertNoPoll(rig: InlineRigD1R11) {
        var reads = 0
        rig.engine.positionReading = { reads += 1; rig.engine.position }
        repeat(10) { rig.scheduler.advanceTimeBy(1_000L) }
        rig.scheduler.runCurrent()
        assertEquals(0, reads, "no poll is running over 10 virtual seconds")
    }

    @Test
    fun `an A and B loop inside pause leaves the player Paused at A with the engine paused and no poll`() {
        val rig = InlineRigD1R11()
        try {
            playingAt(rig, 7_000L)
            rig.loop { p: Long -> p >= b }
            rig.player.pause()
            assertEquals(VideoPlayerState.Paused(10_000L, a), rig.player.stateFlow.value, "04: Paused, at the loop's A")
            assertEquals(false, rig.engine.playing, "the engine is paused")
            assertEquals(a, rig.engine.position)
            assertEquals(a, rig.player.playbackPositionFlow.value)
            assertNoPoll(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a seek to A made by a position collector inside stop leaves the player Ready with the playhead at A and the engine paused`() {
        val rig = InlineRigD1R11()
        try {
            playingAt(rig, 7_000L)
            rig.loop { p: Long -> p == 0L }
            rig.player.stop()
            assertEquals(VideoPlayerState.Ready(10_000L), rig.player.stateFlow.value, "a seek of a Ready player moves the playhead")
            assertEquals(a, rig.player.playbackPositionFlow.value)
            assertEquals(false, rig.engine.playing, "the engine is paused")
            assertEquals(a, rig.engine.position)
            assertNoPoll(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `an A and B loop inside a completion leaves the player Paused at A and the engine never started again`() {
        val rig = InlineRigD1R11()
        try {
            playingAt(rig, 3_000L)
            rig.loop { p: Long -> p >= b }
            rig.engine.position = 10_000L
            rig.engine.playing = false // a platform that stops at its end
            val logBefore: Int = rig.engine.log.size
            checkNotNull(rig.engine.sink).onCompleted()
            assertEquals(VideoPlayerState.Paused(10_000L, a), rig.player.stateFlow.value, "04: a seek of a Completed player is Paused at the target")
            assertTrue("start" !in rig.engine.log.drop(logBefore), "the engine is not started: ${rig.engine.log.drop(logBefore)}")
            assertEquals(false, rig.engine.playing)
            assertNoPoll(rig)
        } finally {
            rig.close()
        }
    }

    @Test
    fun `a seek to A made by a position collector inside a play from Completed leaves the player Playing at A with the engine started and the poll alive`() {
        val rig = InlineRigD1R11()
        try {
            playingAt(rig, 10_000L)
            checkNotNull(rig.engine.sink).onCompleted()
            assertEquals(VideoPlayerState.Completed(10_000L), rig.player.stateFlow.value)
            rig.loop { p: Long -> p == 0L }
            rig.player.play()
            val state: VideoPlayerState = rig.player.stateFlow.value
            assertEquals(VideoPlayerState.Playing(10_000L, a), state)
            assertEquals(true, rig.engine.playing, "the engine is started")
            var reads = 0
            rig.engine.positionReading = { reads += 1; a + 500L }
            rig.tick()
            assertEquals(1, reads, "the poll reads once per interval")
            assertEquals(VideoPlayerState.Playing(10_000L, a + 500L), rig.player.stateFlow.value)
        } finally {
            rig.close()
        }
    }
}
