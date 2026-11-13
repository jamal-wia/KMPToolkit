package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeEvents
import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** B13 (the pulse and the picture) in virtual time. No JavaFX, no real media. */
class PictureModelTest {

    private val width: Int = 4
    private val height: Int = 3

    private fun FxModelScope.pictureRig(
        clip: ClipModel = ClipModel(),
        ready: Boolean = true,
    ): Pair<PartTestRig, PictureModel> {
        lateinit var model: PictureModel
        val rig: PartTestRig = partRig(clip = clip, ready = ready) { player ->
            model = PictureModel(player)
            player.picture = model
        }
        return rig to model
    }

    private fun pulses(rig: PartTestRig): List<Double> = rig.events.events.filter { it.name == "pulse" }.map { it.tMs }

    private fun snapshot(rig: PartTestRig, size: Int = width * height, fill: Int = SENTINEL): IntArray {
        val pixels = IntArray(size) { fill }
        rig.scope.onFx { rig.player.snapshotInto(pixels, width, height) }
        return pixels
    }

    // -- the pulse --

    @Test
    fun noPulseComesBeforeStartPulse() = fxModel {
        val (rig, model) = pictureRig()

        advanceBy(1_000)

        assertTrue(pulses(rig).isEmpty())
        assertEquals(0, model.pulsesDelivered)
    }

    @Test
    fun theFirstPulseComesOnePeriodAfterTheStart() = fxModel {
        val (rig, _) = pictureRig()
        val start: Double = rig.now()
        onFx { rig.player.startPulse() }

        advanceBy(16)
        assertTrue(pulses(rig).isEmpty())
        advanceBy(1)

        assertEquals(1, pulses(rig).size)
        assertEquals(start + 1_000.0 / 60.0, pulses(rig).single(), 1e-6)
    }

    @Test
    fun aSecondOfPulsesIsExactly60AndFiveSecondsExactly300() = fxModel {
        val (rig, model) = pictureRig()
        onFx { rig.player.startPulse() }

        advanceBy(1_000)
        assertEquals(60, pulses(rig).size)

        advanceBy(4_000)
        assertEquals(300, pulses(rig).size)
        assertEquals(300, model.pulsesDelivered)
    }

    @Test
    fun thePulsesAreOnTheirExactInstantsWithoutDrift() = fxModel {
        val (rig, _) = pictureRig()
        val start: Double = rig.now()
        onFx { rig.player.startPulse() }

        advanceBy(10_000)

        val times: List<Double> = pulses(rig)
        assertEquals(600, times.size)
        for ((index, t) in times.withIndex()) {
            assertEquals(start + (index + 1) * 1_000.0 / 60.0, t, 1e-5, "pulse ${index + 1}")
        }
    }

    @Test
    fun theHostClockIsTheArgumentOfOnPulse() = fxModel {
        val nowSeen: MutableList<Pair<Long, Long>> = mutableListOf()
        val rig: PartTestRig = partRig { player -> player.picture = PictureModel(player) }
        onFx {
            rig.player.setEvents(object : NativeEvents by rig.events {
                override fun onPulse(nowNanos: Long) { nowSeen += nowNanos to host.nanoTime() }
            })
            rig.player.startPulse()
        }

        advanceBy(100)

        assertEquals(6, nowSeen.size)
        assertTrue(nowSeen.all { (arg, clock) -> arg == clock })
    }

    @Test
    fun aStopDropsTheQueuedTickAndNoPulseFollows() = fxModel {
        val (rig, _) = pictureRig()
        onFx { rig.player.startPulse() }
        advanceBy(100)
        val before: Int = pulses(rig).size

        onFx { rig.player.stopPulse() }
        advanceBy(1_000)

        assertEquals(6, before)
        assertEquals(before, pulses(rig).size)
        assertEquals(0, host.pendingCount, "the queued tick was inert and left the queue")
    }

    @Test
    fun aSecondStartWhileRunningChangesNothing() = fxModel {
        val (rig, _) = pictureRig()
        onFx { rig.player.startPulse() }
        advanceBy(50)
        onFx { rig.player.startPulse() }

        advanceBy(950)

        assertEquals(60, pulses(rig).size)
    }

    @Test
    fun aRestartBeginsANewCountOnePeriodAfterTheRestart() = fxModel {
        val (rig, _) = pictureRig()
        onFx { rig.player.startPulse() }
        advanceBy(100)
        onFx { rig.player.stopPulse() }
        advanceBy(500)
        rig.events.clear()
        val restart: Double = rig.now()

        onFx { rig.player.startPulse() }
        advanceBy(1_000)

        assertEquals(60, pulses(rig).size)
        assertEquals(restart + 1_000.0 / 60.0, pulses(rig).first(), 1e-6)
    }

    @Test
    fun aListenerThatStopsThePulseInsideTheCallEndsItAtOnce() = fxModel {
        val count = intArrayOf(0)
        val rig: PartTestRig = partRig { player -> player.picture = PictureModel(player) }
        onFx {
            rig.player.setEvents(object : NativeEvents by rig.events {
                override fun onPulse(nowNanos: Long) {
                    if (++count[0] == 3) rig.player.stopPulse()
                }
            })
            rig.player.startPulse()
        }

        advanceBy(1_000)

        assertEquals(3, count[0])
        assertEquals(0, host.pendingCount)
    }

    @Test
    fun aListenerThatRestartsThePulseInsideTheCallStartsANewCount() = fxModel {
        val times: MutableList<Long> = mutableListOf()
        val rig: PartTestRig = partRig { player -> player.picture = PictureModel(player) }
        onFx {
            rig.player.setEvents(object : NativeEvents by rig.events {
                override fun onPulse(nowNanos: Long) {
                    times += nowNanos
                    if (times.size == 2) {
                        rig.player.stopPulse()
                        rig.player.startPulse()
                    }
                }
            })
            rig.player.startPulse()
        }

        advanceBy(100)

        // 2 pulses, a restart at the second, then pulses again one period apart from the restart instant.
        assertEquals(6, times.size)
        assertEquals(1_000_000_000.0 / 60.0, (times[2] - times[1]).toDouble(), 1.0)
    }

    @Test
    fun thePulseDoesNotWaitForReady() = fxModel {
        val (rig, _) = pictureRig(ready = false)
        onFx { rig.player.startPulse() }

        advanceBy(40)

        assertEquals(2, pulses(rig).size)
        assertEquals(NativeStatus.UNKNOWN, rig.player.status)
    }

    @Test
    fun thePulseKeepsRunningAfterDisposeUntilItIsStopped() = fxModel {
        val (rig, _) = pictureRig()
        onFx { rig.player.startPulse() }
        advanceBy(100)

        onFx { rig.player.dispose() }
        rig.events.clear()
        advanceBy(1_000)

        assertEquals(60, pulses(rig).size)

        onFx { rig.player.stopPulse() }
        rig.events.clear()
        advanceBy(1_000)
        assertTrue(pulses(rig).isEmpty())
    }

    // -- the picture --

    @Test
    fun beforeReadyTheSnapshotIsTransparent() = fxModel {
        val (rig, _) = pictureRig(ready = false)

        advanceBy(49)

        assertTrue(snapshot(rig).all { it == PictureModel.TRANSPARENT })
    }

    @Test
    fun theFirstOpaquePictureComes40MsAfterReadyNotBefore() = fxModel {
        val (rig, model) = pictureRig()
        // The rig advanced to the ready instant, 50 ms in.
        assertEquals(host.elapsedNanos + 40_000_000L, (model.firstPictureAtNanos ?: 0L) - host.epochNanos)

        advanceBy(39)
        assertTrue(snapshot(rig).all { it == PictureModel.TRANSPARENT })

        advanceBy(1)
        assertTrue(snapshot(rig).all { it == PictureModel.OPAQUE_RED })
    }

    @Test
    fun theFirstPictureFollowsTheClip() = fxModel {
        val (rig, _) = pictureRig(clip = ClipModel(firstPictureAfterReadyMs = 0.0))

        assertTrue(snapshot(rig).all { it == PictureModel.OPAQUE_RED })

        val (late, _) = pictureRig(clip = ClipModel(firstPictureAfterReadyMs = 500.0))
        advanceBy(499)
        assertTrue(snapshot(late).all { it == PictureModel.TRANSPARENT })
        advanceBy(1)
        assertTrue(snapshot(late).all { it == PictureModel.OPAQUE_RED })
    }

    @Test
    fun theOpaqueColourIsRedWithFullAlpha() = fxModel {
        val (rig, _) = pictureRig()
        advanceBy(40)

        val pixel: Int = snapshot(rig)[0]

        assertEquals(0xFF, pixel ushr 24)
        assertEquals(0xFF, (pixel shr 16) and 0xFF)
        assertEquals(0, (pixel shr 8) and 0xFF)
        assertEquals(0, pixel and 0xFF)
    }

    @Test
    fun theSnapshotWritesOnlyTheCallersArrayAndOnlyWidthTimesHeight() = fxModel {
        val (rig, _) = pictureRig()
        advanceBy(40)

        val pixels: IntArray = snapshot(rig, size = width * height + 5)

        assertTrue(pixels.take(width * height).all { it == PictureModel.OPAQUE_RED })
        assertTrue(pixels.drop(width * height).all { it == SENTINEL }, "nothing past width*height is touched")
    }

    @Test
    fun theSnapshotKeepsNoReferenceToAnEarlierArray() = fxModel {
        val (rig, _) = pictureRig()
        advanceBy(40)
        val first: IntArray = snapshot(rig)
        first.fill(SENTINEL)

        val second: IntArray = snapshot(rig)

        assertTrue(first.all { it == SENTINEL }, "the earlier array is not written again")
        assertTrue(second.all { it == PictureModel.OPAQUE_RED })
    }

    @Test
    fun aTransparentSnapshotOverwritesWhatTheArrayHeld() = fxModel {
        val (rig, _) = pictureRig(ready = false)

        assertTrue(snapshot(rig, fill = PictureModel.OPAQUE_RED).all { it == PictureModel.TRANSPARENT })
    }

    @Test
    fun aDetachedViewShowsNothing() = fxModel {
        val (rig, _) = pictureRig()
        advanceBy(40)
        assertTrue(snapshot(rig).all { it == PictureModel.OPAQUE_RED })

        onFx { rig.player.detachView() }

        assertTrue(snapshot(rig).all { it == PictureModel.TRANSPARENT })
    }

    @Test
    fun aDisposedPlayerShowsNothing() = fxModel {
        val (rig, _) = pictureRig()
        advanceBy(40)

        onFx { rig.player.dispose() }

        assertTrue(snapshot(rig).all { it == PictureModel.TRANSPARENT })
    }

    @Test
    fun aSnapshotTooBigForTheArrayIsRejected() = fxModel {
        val (rig, _) = pictureRig()

        assertFailsWith<IllegalArgumentException> { onFx { rig.player.snapshotInto(IntArray(width * height - 1), width, height) } }
        assertFailsWith<IllegalArgumentException> { onFx { rig.player.snapshotInto(IntArray(4), 0, 3) } }
    }

    @Test
    fun withNoPictureSlotTheArrayIsUntouched() = fxModel {
        val rig: PartTestRig = partRig()

        assertTrue(snapshot(rig).all { it == SENTINEL })
    }

    @Test
    fun badPulsePeriodsAreRejected() = fxModel {
        val rig: PartTestRig = partRig()

        assertFailsWith<IllegalArgumentException> { PictureModel(rig.player, 0.0) }
        assertFailsWith<IllegalArgumentException> { PictureModel(rig.player, Double.NaN) }
    }

    private companion object {
        const val SENTINEL: Int = 0x12345678
    }
}
