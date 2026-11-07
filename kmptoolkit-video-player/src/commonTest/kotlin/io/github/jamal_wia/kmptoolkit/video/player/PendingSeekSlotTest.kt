package io.github.jamal_wia.kmptoolkit.video.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The clearing rule of the AVPlayer engine's pending seek, without an AVPlayer (common code, so every host runs it). */
class PendingSeekSlotTest {

    @Test
    fun `nothing is pending before a seek begins`() {
        assertNull(PendingSeekSlot().targetMs)
    }

    @Test
    fun `the completion of the only seek clears it`() {
        val slot = PendingSeekSlot()
        val seek: PendingSeekSlot.Token = slot.begin(1_000L)
        assertEquals(1_000L, slot.targetMs)

        slot.complete(seek)

        assertNull(slot.targetMs)
    }

    @Test
    fun `an interrupted seek to the same position does not clear the seek that interrupted it`() {
        val slot = PendingSeekSlot()
        val first: PendingSeekSlot.Token = slot.begin(0L)
        val second: PendingSeekSlot.Token = slot.begin(0L)

        slot.complete(first) // AVFoundation runs it with finished = NO when the second is issued

        assertEquals(0L, slot.targetMs, "the second seek is still in flight")
        slot.complete(second)
        assertNull(slot.targetMs)
    }

    @Test
    fun `an older completion arriving late does not clear a newer seek back to an earlier position`() {
        val slot = PendingSeekSlot()
        val first: PendingSeekSlot.Token = slot.begin(10_000L)
        val second: PendingSeekSlot.Token = slot.begin(20_000L)
        val third: PendingSeekSlot.Token = slot.begin(10_000L)

        slot.complete(first)
        slot.complete(second)

        assertEquals(10_000L, slot.targetMs)
        slot.complete(third)
        assertNull(slot.targetMs)
    }
}
