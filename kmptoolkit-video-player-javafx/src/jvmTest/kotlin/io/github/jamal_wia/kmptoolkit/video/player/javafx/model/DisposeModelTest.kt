package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B12 (the late events of a disposed player) in virtual time. No JavaFX, no real media. */
class DisposeModelTest {

    private fun FxModelScope.disposeRig(
        lateStatus: NativeStatus? = null,
        lateStatusDelayMs: Double = DisposeModel.DEFAULT_LATE_DELAY_MS,
        lateEndOfMedia: Boolean = false,
        lateEndDelayMs: Double = DisposeModel.DEFAULT_LATE_DELAY_MS,
        lateEndIgnoresClearedHandler: Boolean = false,
    ): Pair<PartTestRig, DisposeModel> {
        lateinit var model: DisposeModel
        val rig: PartTestRig = partRig { player ->
            model = DisposeModel(player, lateStatus, lateStatusDelayMs, lateEndOfMedia, lateEndDelayMs, lateEndIgnoresClearedHandler)
            player.loop = model
        }
        return rig to model
    }

    @Test
    fun aPlainDisposeSaysNothingMoreAfterDisposed() = fxModel {
        val (rig, model) = disposeRig()

        onFx { rig.player.dispose() }
        advanceBy(10_000)

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertEquals(listOf("status:DISPOSED"), rig.events.names)
        assertFalse(model.lateStatusLanded)
        assertFalse(model.lateEndRan)
        assertTrue(model.violations.isEmpty())
    }

    @Test
    fun aLatePlayingOverwritesDisposedAtTheExactInstant() = fxModel {
        val (rig, model) = disposeRig(lateStatus = NativeStatus.PLAYING)
        onFx { rig.player.dispose() }
        val disposedAt: Double = rig.now()

        advanceBy(19)
        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertFalse(model.lateStatusLanded)

        advanceBy(1)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
        assertTrue(model.lateStatusLanded)
        assertEquals(listOf("status:DISPOSED", "status:PLAYING"), rig.events.names)
        assertEquals(disposedAt + 20.0, rig.events.events.last().tMs)
    }

    @Test
    fun aLatePausedWithItsOwnDelay() = fxModel {
        val (rig, _) = disposeRig(lateStatus = NativeStatus.PAUSED, lateStatusDelayMs = 5.0)
        onFx { rig.player.dispose() }

        advanceBy(5)

        assertEquals(NativeStatus.PAUSED, rig.player.status)
        assertEquals(listOf("status:DISPOSED", "status:PAUSED"), rig.events.names)
    }

    @Test
    fun aLateStatusOfZeroDelayLandsInTheNextTaskNotInsideTheDispose() = fxModel {
        val (rig, _) = disposeRig(lateStatus = NativeStatus.PLAYING, lateStatusDelayMs = 0.0)

        onFx { rig.player.dispose() }
        assertEquals(NativeStatus.DISPOSED, rig.player.status)

        advanceBy(0)
        assertEquals(NativeStatus.PLAYING, rig.player.status)
    }

    @Test
    fun aLateEndOfMediaReachesAnInstalledHandler() = fxModel {
        val (rig, model) = disposeRig(lateEndOfMedia = true)
        onFx { rig.player.dispose() }
        val disposedAt: Double = rig.now()

        advanceBy(19)
        assertFalse(model.lateEndRan)
        advanceBy(1)

        assertTrue(model.lateEndRan)
        assertTrue(model.lateEndDelivered)
        assertEquals(listOf("status:DISPOSED", "endOfMedia"), rig.events.names)
        assertEquals(disposedAt + 20.0, rig.events.events.last().tMs)
    }

    @Test
    fun aClearedHandlerReceivesNoLateEndOfMedia() = fxModel {
        val (rig, model) = disposeRig(lateEndOfMedia = true)
        onFx {
            rig.player.clearEndOfMedia()
            rig.player.dispose()
        }

        advanceBy(100)

        assertTrue(model.lateEndRan)
        assertFalse(model.lateEndDelivered)
        assertEquals(listOf("status:DISPOSED"), rig.events.names)
    }

    @Test
    fun aQueuedEndOfMediaThatSurvivesTheClearingIsDeliveredWhenTheFlagSaysSo() = fxModel {
        val (rig, model) = disposeRig(lateEndOfMedia = true, lateEndIgnoresClearedHandler = true)
        onFx {
            rig.player.clearEndOfMedia()
            rig.player.dispose()
        }

        advanceBy(100)

        assertTrue(model.lateEndDelivered)
        assertEquals(listOf("status:DISPOSED", "endOfMedia"), rig.events.names)
    }

    @Test
    fun aLateEndOnAPlayerWithMoreThanOneCycleIsAViolation() = fxModel {
        val (rig, model) = disposeRig(lateEndOfMedia = true)
        onFx {
            rig.player.cycleCount = -1
            rig.player.dispose()
        }

        advanceBy(100)

        assertEquals(1, model.violations.size)
        assertTrue("cycleCount=-1" in model.violations.single(), model.violations.toString())
    }

    @Test
    fun aLateEndOnASingleCyclePlayerIsNoViolation() = fxModel {
        val (rig, model) = disposeRig(lateEndOfMedia = true)
        onFx {
            rig.player.cycleCount = 1
            rig.player.dispose()
        }

        advanceBy(100)

        assertTrue(model.violations.isEmpty())
    }

    @Test
    fun theCycleCountIsReadWhenTheLateEndRunsNotWhenTheDisposeWas() = fxModel {
        val (rig, model) = disposeRig(lateEndOfMedia = true, lateEndDelayMs = 50.0)
        onFx {
            rig.player.cycleCount = -1
            rig.player.dispose()
        }
        advanceBy(10)
        // A write on a disposed player is ignored, so the count stays INDEFINITE.
        onFx { rig.player.cycleCount = 1 }
        advanceBy(100)

        assertEquals(-1, rig.player.cycleCount)
        assertEquals(1, model.violations.size)
    }

    @Test
    fun bothLateEventsRunInTheOrderOfTheirDelays() = fxModel {
        val (rig, _) = disposeRig(
            lateStatus = NativeStatus.PLAYING, lateStatusDelayMs = 30.0, lateEndOfMedia = true, lateEndDelayMs = 10.0,
        )

        onFx { rig.player.dispose() }
        advanceBy(100)

        assertEquals(listOf("status:DISPOSED", "endOfMedia", "status:PLAYING"), rig.events.names)
    }

    @Test
    fun aSecondDisposeSchedulesNothingMore() = fxModel {
        val (rig, _) = disposeRig(lateStatus = NativeStatus.PLAYING, lateEndOfMedia = true)

        onFx {
            rig.player.dispose()
            rig.player.dispose()
        }
        advanceBy(100)

        assertEquals(listOf("status:DISPOSED", "status:PLAYING", "endOfMedia"), rig.events.names)
    }

    @Test
    fun noLateEventIsScheduledBeforeADispose() = fxModel {
        val (rig, model) = disposeRig(lateStatus = NativeStatus.PLAYING, lateEndOfMedia = true)

        advanceBy(10_000)

        assertTrue(rig.events.events.isEmpty())
        assertFalse(model.lateStatusLanded)
        assertFalse(model.lateEndRan)
    }

    @Test
    fun theLateStatusIsWhatTheStatusReadGives() = fxModel {
        val (rig, _) = disposeRig(lateStatus = NativeStatus.PAUSED)
        onFx { rig.player.dispose() }
        advanceBy(20)

        // The player read says PAUSED although the native half is gone: the guards of the caller must not trust it.
        assertEquals(NativeStatus.PAUSED, rig.player.status)
    }

    @Test
    fun badArgumentsAreRejected() = fxModel {
        val rig: PartTestRig = partRig()

        assertFailsWith<IllegalArgumentException> { DisposeModel(rig.player, lateStatus = NativeStatus.READY) }
        assertFailsWith<IllegalArgumentException> { DisposeModel(rig.player, lateStatus = NativeStatus.DISPOSED) }
        assertFailsWith<IllegalArgumentException> { DisposeModel(rig.player, lateStatusDelayMs = -1.0) }
        assertFailsWith<IllegalArgumentException> { DisposeModel(rig.player, lateEndDelayMs = Double.NaN) }
    }
}
