package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B10 (the stall part) in virtual time. No JavaFX, no real media. */
class StallModelTest {

    /** A player that is PLAYING at the position given, with the stall part installed, events cleared. */
    private fun FxModelScope.stallRig(positionMs: Double = 0.0, bufferAheadMs: Double = StallModel.DEFAULT_BUFFER_AHEAD_MS): Pair<PartTestRig, StallModel> {
        lateinit var model: StallModel
        val rig: PartTestRig = partRig { player ->
            model = StallModel(player, bufferAheadMs)
            player.loop = model
        }
        rig.playToPlaying()
        rig.player.positionMs = positionMs
        rig.events.clear()
        return rig to model
    }

    @Test
    fun nothingStallsUnlessScripted() = fxModel {
        val (rig, stall) = stallRig()

        advanceBy(5_000)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertEquals(0, stall.stalls)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun aPlayingPlayerStallsAtTheExactInstant() = fxModel {
        val (rig, stall) = stallRig()
        val start: Double = rig.now()

        stall.stallAt(100.0)
        advanceBy(99)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertFalse(stall.isStalled)

        advanceBy(1)
        assertEquals(NativeStatus.STALLED, rig.player.status)
        assertTrue(stall.isStalled)
        assertEquals(listOf("status:STALLED"), rig.events.names)
        assertEquals(start + 100.0, rig.events.events.single().tMs)
        assertEquals(1, stall.stalls)
    }

    @Test
    fun theBufferStopsAtThePlayheadWhileStalled() = fxModel {
        val (rig, stall) = stallRig(positionMs = 1_234.0)
        rig.player.bufferMs = 2_000.0

        stall.stallAt(10.0)
        advanceBy(10)

        assertEquals(1_234.0, rig.player.bufferProgressMs)
        assertEquals(listOf("bufferProgress", "status:STALLED"), rig.events.names)
    }

    @Test
    fun aResumeGivesPlayingAgainAndTheBufferRunsAhead() = fxModel {
        val (rig, stall) = stallRig(positionMs = 1_000.0)
        stall.stallAt(10.0)
        stall.resumeAt(500.0)

        advanceBy(499)
        assertEquals(NativeStatus.STALLED, rig.player.status)
        assertTrue(stall.isStalled)
        advanceBy(1)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertFalse(stall.isStalled)
        assertEquals(2_000.0, rig.player.bufferProgressMs)
        assertEquals(listOf("bufferProgress", "status:STALLED", "bufferProgress", "status:PLAYING"), rig.events.names)
    }

    @Test
    fun theBufferNeverPassesTheDuration() = fxModel {
        val (rig, stall) = stallRig(positionMs = 2_800.0)

        stall.stallAt(1.0)
        stall.resumeAt(2.0)
        advanceBy(2)

        assertEquals(3_000.0, rig.player.bufferProgressMs)
    }

    @Test
    fun theBufferIsNeverNegativeOrBeyondTheDurationWhateverThePosition() = fxModel {
        val (rig, stall) = stallRig(positionMs = 5_000.0)

        stall.stallAt(1.0)
        advanceBy(1)
        assertEquals(3_000.0, rig.player.bufferProgressMs)

        rig.player.positionMs = -5.0
        stall.resumeAt(1.0)
        advanceBy(1)
        assertEquals(995.0, rig.player.bufferProgressMs)
    }

    @Test
    fun aBufferThatDidNotChangeFiresNoBufferEvent() = fxModel {
        val (rig, stall) = stallRig(positionMs = 0.0)

        stall.stallAt(1.0)
        advanceBy(1)

        assertEquals(listOf("status:STALLED"), rig.events.names)
    }

    @Test
    fun aPausedPlayerDoesNotStall() = fxModel {
        val (rig, stall) = stallRig()
        onFx { rig.player.pause() }
        advanceBy(30)
        rig.events.clear()

        stall.stallAt(10.0)
        advanceBy(100)

        assertEquals(NativeStatus.PAUSED, rig.player.status)
        assertEquals(0, stall.stalls)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun aResumeOfAPlayerThatIsNotStalledDoesNothing() = fxModel {
        val (rig, stall) = stallRig()

        stall.resumeAt(10.0)
        advanceBy(100)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertTrue(rig.events.events.isEmpty())
        assertEquals(0.0, rig.player.bufferProgressMs)
    }

    @Test
    fun aPauseDuringTheStallWinsAndTheLaterResumeDoesNothing() = fxModel {
        val (rig, stall) = stallRig(positionMs = 500.0)
        stall.stallAt(10.0)
        stall.resumeAt(500.0)
        advanceBy(20)
        assertEquals(NativeStatus.STALLED, rig.player.status)

        onFx { rig.player.pause() }
        advanceBy(30)
        assertEquals(NativeStatus.PAUSED, rig.player.status)
        assertFalse(stall.isStalled)

        advanceBy(1_000)
        assertEquals(NativeStatus.PAUSED, rig.player.status)
    }

    @Test
    fun aPlayDuringTheStallMovesTheStatusToPlayingAndEndsTheStall() = fxModel {
        val (rig, stall) = stallRig()
        stall.stallAt(10.0)
        advanceBy(10)
        assertTrue(stall.isStalled)

        onFx { rig.player.play() }
        advanceBy(30)

        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertFalse(stall.isStalled)
    }

    @Test
    fun aDisposeCancelsAPendingStallAndResume() = fxModel {
        val (rig, stall) = stallRig()
        stall.stallAt(100.0)
        stall.resumeAt(200.0)

        onFx { rig.player.dispose() }
        rig.events.clear()
        advanceBy(1_000)

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertEquals(0, stall.stalls)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun aDisposeDuringTheStallEndsItAndTheResumeDoesNothing() = fxModel {
        val (rig, stall) = stallRig()
        stall.stallAt(10.0)
        stall.resumeAt(100.0)
        advanceBy(10)

        onFx { rig.player.dispose() }
        advanceBy(1_000)

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertFalse(stall.isStalled)
    }

    @Test
    fun severalStallsAreCounted() = fxModel {
        val (rig, stall) = stallRig()

        repeat(3) {
            stall.stallAt(10.0)
            stall.resumeAt(20.0)
            advanceBy(20)
        }

        assertEquals(3, stall.stalls)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertEquals(3, rig.events.names.count { it == "status:STALLED" })
        assertEquals(3, rig.events.names.count { it == "status:PLAYING" })
    }

    @Test
    fun theBufferAheadIsConfigurable() = fxModel {
        val (rig, stall) = stallRig(positionMs = 100.0, bufferAheadMs = 250.0)

        stall.stallAt(1.0)
        stall.resumeAt(2.0)
        advanceBy(2)

        assertEquals(350.0, rig.player.bufferProgressMs)
    }

    @Test
    fun badArgumentsAreRejected() = fxModel {
        val (rig, stall) = stallRig()

        assertFailsWith<IllegalArgumentException> { stall.stallAt(-1.0) }
        assertFailsWith<IllegalArgumentException> { stall.resumeAt(Double.NaN) }
        assertFailsWith<IllegalArgumentException> { StallModel(rig.player, -1.0) }
    }
}
