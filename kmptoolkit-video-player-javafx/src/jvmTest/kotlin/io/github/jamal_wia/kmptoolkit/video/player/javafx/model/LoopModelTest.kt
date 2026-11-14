package io.github.jamal_wia.kmptoolkit.video.player.javafx.model

import io.github.jamal_wia.kmptoolkit.video.player.javafx.NativeStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** B8 (the loop part) and the [ModelPartGroup] that shares a slot, in virtual time. No JavaFX, no real media. */
class LoopModelTest {

    private fun FxModelScope.loopRig(
        cycleCount: Int = LoopModel.INDEFINITE,
        holdMs: Double = LoopModel.DEFAULT_PUBLISH_HOLD_MS,
    ): Pair<PartTestRig, LoopModel> {
        lateinit var model: LoopModel
        val rig: PartTestRig = partRig { player ->
            model = LoopModel(player, holdMs)
            player.loop = model
            player.cycleCount = cycleCount
        }
        return rig to model
    }

    @Test
    fun aSingleCycleSourceDoesNotWrapAndNothingChanges() = fxModel {
        val (rig, loop) = loopRig(cycleCount = 1)
        rig.player.positionMs = 3_000.0

        val wrapped: Boolean = onFx { loop.endCycle() }

        assertFalse(wrapped)
        assertEquals(3_000.0, rig.player.positionMs)
        assertEquals(1, loop.cycle)
        assertFalse(loop.isPublishHeld())
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun anIndefiniteSourceWrapsToTheStartTimeAndDeliversTheEndOfMedia() = fxModel {
        val (rig, loop) = loopRig()
        rig.player.startMs = 500.0
        rig.player.positionMs = 3_000.0

        val wrapped: Boolean = onFx { loop.endCycle() }

        assertTrue(wrapped)
        assertEquals(500.0, rig.player.positionMs)
        assertEquals(2, loop.cycle)
        assertEquals(listOf("endOfMedia"), rig.events.names)
    }

    @Test
    fun theWatchersRunAfterThePlayheadWrappedAndBeforeTheEndOfMedia() = fxModel {
        val (rig, loop) = loopRig()
        val seen: MutableList<Pair<Double, List<String>>> = mutableListOf()
        loop.onWrap { seen += rig.player.positionMs to rig.events.names }
        rig.player.positionMs = 3_000.0

        onFx { loop.endCycle() }

        assertEquals(listOf(0.0 to emptyList<String>()), seen)
        assertEquals(listOf("endOfMedia"), rig.events.names)
    }

    @Test
    fun theWatcherIsToldTheNewCycleAndTheEndOfTheHold() = fxModel {
        val (rig, loop) = loopRig()
        val wraps: MutableList<LoopWrap> = mutableListOf()
        loop.onWrap { wraps += it }
        val before: Long = host.nanoTime()

        onFx { loop.endCycle() }
        advanceBy(1_000)
        onFx { loop.endCycle() }

        assertEquals(listOf(2, 3), wraps.map { it.cycle })
        assertEquals(before + 300_000_000L, wraps[0].holdUntilNanos)
        assertEquals(before + 1_000_000_000L + 300_000_000L, wraps[1].holdUntilNanos)
        assertEquals(2, rig.events.names.count { it == "endOfMedia" })
    }

    @Test
    fun theEndOfMediaIsDeliveredAtTheEndOfEveryCycle() = fxModel {
        val (rig, loop) = loopRig()

        repeat(5) {
            onFx { assertTrue(loop.endCycle()) }
            advanceBy(3_000)
        }

        assertEquals(List(5) { "endOfMedia" }, rig.events.names)
        assertEquals(6, loop.cycle)
    }

    @Test
    fun aClearedEndOfMediaHandlerReceivesNothingButTheSourceStillWraps() = fxModel {
        val (rig, loop) = loopRig()
        rig.player.positionMs = 3_000.0
        onFx { rig.player.clearEndOfMedia() }

        val wrapped: Boolean = onFx { loop.endCycle() }

        assertTrue(wrapped)
        assertEquals(0.0, rig.player.positionMs)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun thePublishedTimeHoldsFor300MsAndNotOneInstantLonger() = fxModel {
        val (_, loop) = loopRig()
        assertFalse(loop.isPublishHeld())

        onFx { loop.endCycle() }
        assertTrue(loop.isPublishHeld())

        advanceBy(299)
        assertTrue(loop.isPublishHeld())
        host.advanceByNanos(999_999)
        assertTrue(loop.isPublishHeld())
        host.advanceByNanos(1)
        assertFalse(loop.isPublishHeld())
    }

    /**
     * The hold of the published time is a span of the clock, so it lasts the same 300 ms wherever the clock stands: on one that
     * starts negative, at 0, at one thousand seconds, and on the two that wrap during the hold (100 ms below the wrap, and at
     * the wrap itself), where the end of the hold is a number below the reading at the start of it.
     */
    @Test
    fun thePublishedTimeHoldsFor300MsOnEveryClockEvenWhenTheHoldCrossesTheWrap() {
        val epochs: List<Long> = listOf(-1_000_000_000_000L, 0L, 1_000_000_000_000L, Long.MAX_VALUE - 100_000_000L, Long.MAX_VALUE)
        for (epoch: Long in epochs) {
            fxModel(epochNanos = epoch) {
                val (_, loop) = loopRig()
                assertFalse(loop.isPublishHeld(), "no hold before the first wrap (clock starts at $epoch ns)")

                onFx { loop.endCycle() }
                assertTrue(loop.isPublishHeld(), "held at the wrap (clock starts at $epoch ns)")

                advanceBy(299)
                assertTrue(loop.isPublishHeld(), "held 299 ms after the wrap (clock starts at $epoch ns)")
                host.advanceByNanos(999_999)
                assertTrue(loop.isPublishHeld(), "held 1 ns short of 300 ms after the wrap (clock starts at $epoch ns)")
                host.advanceByNanos(1)
                assertFalse(loop.isPublishHeld(), "still held 300 ms after the wrap (clock starts at $epoch ns)")
                advanceBy(5_000)
                assertFalse(loop.isPublishHeld(), "held again long after the wrap (clock starts at $epoch ns)")
            }
        }
    }

    @Test
    fun theHeldValueIsTheStartTimeOfTheNewCycle() = fxModel {
        val (rig, loop) = loopRig()
        rig.player.startMs = 250.0

        onFx { loop.endCycle() }

        assertEquals(250.0, loop.heldValueMs)
    }

    @Test
    fun theHoldIsConfigurable() = fxModel {
        val (_, loop) = loopRig(holdMs = 120.0)

        onFx { loop.endCycle() }
        advanceBy(119)
        assertTrue(loop.isPublishHeld())
        advanceBy(1)
        assertFalse(loop.isPublishHeld())
    }

    @Test
    fun aZeroHoldIsNeverHeld() = fxModel {
        val (_, loop) = loopRig(holdMs = 0.0)

        onFx { loop.endCycle() }

        assertFalse(loop.isPublishHeld())
    }

    @Test
    fun aWrapInsideAHoldRestartsTheHold() = fxModel {
        val (_, loop) = loopRig()

        onFx { loop.endCycle() }
        advanceBy(200)
        onFx { loop.endCycle() }
        advanceBy(200)

        assertTrue(loop.isPublishHeld())
        advanceBy(100)
        assertFalse(loop.isPublishHeld())
    }

    @Test
    fun aFiniteCountWrapsUntilItsLastCycleAndTheLastEndIsAPlainEnd() = fxModel {
        val (rig, loop) = loopRig(cycleCount = 3)
        rig.player.positionMs = 3_000.0

        assertTrue(onFx { loop.endCycle() })
        assertTrue(onFx { loop.endCycle() })
        rig.player.positionMs = 3_000.0
        rig.events.clear()
        val third: Boolean = onFx { loop.endCycle() }

        assertFalse(third)
        assertEquals(3, loop.cycle)
        assertEquals(3_000.0, rig.player.positionMs)
        assertTrue(rig.events.events.isEmpty())
    }

    @Test
    fun theCountIsReadAtEveryEndSoALaterWriteOfIndefiniteLoopsOn() = fxModel {
        val (rig, loop) = loopRig(cycleCount = 2)

        assertTrue(onFx { loop.endCycle() })
        assertFalse(onFx { loop.endCycle() })
        onFx { rig.player.cycleCount = LoopModel.INDEFINITE }

        assertTrue(onFx { loop.endCycle() })
        assertEquals(3, loop.cycle)
    }

    @Test
    fun aSeekDoesNotChangeTheCycle() = fxModel {
        val (rig, loop) = loopRig()
        onFx { loop.endCycle() }

        onFx { rig.player.seek(1_000.0) }

        assertEquals(2, loop.cycle)
        assertTrue(loop.isLooping)
    }

    @Test
    fun aDisposeEndsTheHold() = fxModel {
        val (rig, loop) = loopRig()
        onFx { loop.endCycle() }
        assertTrue(loop.isPublishHeld())

        onFx { rig.player.dispose() }

        assertEquals(NativeStatus.DISPOSED, rig.player.status)
        assertFalse(loop.isPublishHeld())
    }

    @Test
    fun aNegativeOrNonFiniteHoldIsRejected() = fxModel {
        val rig: PartTestRig = partRig()

        assertFailsWith<IllegalArgumentException> { LoopModel(rig.player, -1.0) }
        assertFailsWith<IllegalArgumentException> { LoopModel(rig.player, Double.NaN) }
    }

    // -- ModelPartGroup --

    private class Recorder(val name: String, val sink: MutableList<String>) : ModelPart {
        override fun onReady(player: ScriptedNativePlayer) { sink += "$name:ready" }
        override fun onPlay(player: ScriptedNativePlayer) { sink += "$name:play" }
        override fun onPause(player: ScriptedNativePlayer) { sink += "$name:pause" }
        override fun onSeek(player: ScriptedNativePlayer, millis: Double) { sink += "$name:seek:$millis" }
        override fun onRateChange(player: ScriptedNativePlayer, old: Double, new: Double) { sink += "$name:rate:$old>$new" }
        override fun onStatusChange(player: ScriptedNativePlayer, old: NativeStatus, new: NativeStatus) { sink += "$name:status:$new" }
        override fun onDispose(player: ScriptedNativePlayer) { sink += "$name:dispose" }
        override fun onStartPulse(player: ScriptedNativePlayer) { sink += "$name:startPulse" }
        override fun onStopPulse(player: ScriptedNativePlayer) { sink += "$name:stopPulse" }
        override fun onSnapshot(player: ScriptedNativePlayer, pixels: IntArray, width: Int, height: Int) { sink += "$name:snapshot:${width}x$height" }
    }

    @Test
    fun aGroupForwardsEveryHookToEveryMemberInTheOrderGiven() = fxModel {
        val seen: MutableList<String> = mutableListOf()
        val rig: PartTestRig = partRig(ready = false) { it.loop = ModelPartGroup(Recorder("a", seen), Recorder("b", seen)) }
        rig.advance(50)
        seen.clear()

        onFx {
            rig.player.play()
            rig.player.pause()
            rig.player.seek(7.0)
            rig.player.rate = 2.0
            rig.player.startPulse()
            rig.player.stopPulse()
            rig.player.snapshotInto(IntArray(6), 3, 2)
            rig.player.dispose()
        }

        val expected: List<String> = listOf(
            "play", "pause", "seek:7.0", "rate:1.0>2.0", "startPulse", "stopPulse", "snapshot:3x2", "status:DISPOSED", "dispose",
        )
        // The status hook of the dispose runs inside dispose(), before onDispose; everything else runs in call order.
        val perMember: (String) -> List<String> = { who -> seen.filter { it.startsWith("$who:") }.map { it.substringAfter(':') } }
        assertEquals(expected.sorted(), perMember("a").sorted())
        assertEquals(perMember("a"), perMember("b"))
        // a is called before b for every hook.
        val pairs: List<Pair<Int, Int>> = expected.map { hook -> seen.indexOf("a:$hook") to seen.indexOf("b:$hook") }
        assertTrue(pairs.all { (a, b) -> a >= 0 && b == a + 1 }, "a then b for every hook: $seen")
    }

    @Test
    fun aGroupForwardsTheReadyHook() = fxModel {
        val seen: MutableList<String> = mutableListOf()
        val rig: PartTestRig = partRig(ready = false) { it.loop = ModelPartGroup(Recorder("a", seen), Recorder("b", seen)) }

        rig.advance(50)

        assertEquals(listOf("a:ready", "b:ready", "a:status:READY", "b:status:READY"), seen)
    }
}
