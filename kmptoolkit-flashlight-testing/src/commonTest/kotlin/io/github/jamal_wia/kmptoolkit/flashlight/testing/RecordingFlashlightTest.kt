package io.github.jamal_wia.kmptoolkit.flashlight.testing

import io.github.jamal_wia.kmptoolkit.flashlight.FlashPattern
import io.github.jamal_wia.kmptoolkit.flashlight.Flashlight
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The fake every caller's test leans on. It has to answer two questions truthfully: what was
 * asked of the torch, in order, and whether anything is still blinking once the caller says it
 * stopped — a cue left running is the failure this fake exists to catch.
 */
class RecordingFlashlightTest {

    @Test
    fun `a fresh double has recorded nothing`() {
        assertTrue(RecordingFlashlight().events.isEmpty())
    }

    @Test
    fun `records starts and stops in the order they happened`() {
        val flashlight = RecordingFlashlight()

        flashlight.start(FlashPattern.Attention)
        flashlight.stop()
        flashlight.start(FlashPattern.Blink)

        assertEquals(listOf(FlashPattern.Attention, null, FlashPattern.Blink), flashlight.events)
    }

    @Test
    fun `blinking follows the last call`() {
        val flashlight = RecordingFlashlight()
        assertFalse(flashlight.isBlinking)

        flashlight.start(FlashPattern.Blink)
        assertTrue(flashlight.isBlinking)

        flashlight.stop()
        assertFalse(flashlight.isBlinking)
    }

    @Test
    fun `is available by default, matching a device with a torch`() {
        val flashlight: Flashlight = RecordingFlashlight()

        assertTrue(flashlight.isAvailable)
    }

    @Test
    fun `a device without a torch still records what was asked`() {
        // The real implementations go silent on a device with no flash unit, but the fake must
        // not: a test asserting the fallback needs to see that the caller tried the torch before
        // reaching for another cue.
        val flashlight = RecordingFlashlight(isAvailable = false)

        flashlight.start(FlashPattern.Blink)

        assertFalse(flashlight.isAvailable)
        assertEquals(listOf(FlashPattern.Blink), flashlight.events)
    }

    @Test
    fun `isAvailable can switch mid-test to play a device losing its torch`() {
        val flashlight = RecordingFlashlight()

        flashlight.isAvailable = false

        assertFalse(flashlight.isAvailable)
    }

    @Test
    fun `clear wipes both the timeline and the running state, leaving isAvailable untouched`() {
        val flashlight = RecordingFlashlight(isAvailable = false)
        flashlight.start(FlashPattern.Blink)

        flashlight.clear()

        assertEquals(emptyList(), flashlight.events)
        assertFalse(flashlight.isBlinking)
        assertFalse(flashlight.isAvailable)
    }

    @Test
    fun `clear on an empty double is a no-op rather than a failure`() {
        val flashlight = RecordingFlashlight()

        flashlight.clear()
        flashlight.clear()

        assertTrue(flashlight.events.isEmpty())
    }

    @Test
    fun `an events snapshot taken earlier does not change when more calls arrive`() {
        val flashlight = RecordingFlashlight()
        flashlight.start(FlashPattern.Attention)
        val snapshot: List<FlashPattern?> = flashlight.events

        flashlight.stop()
        flashlight.clear()

        assertEquals(listOf(FlashPattern.Attention), snapshot)
    }

    @Test
    fun `repeated identical starts are all recorded rather than deduplicated`() {
        val flashlight = RecordingFlashlight()

        repeat(4) { flashlight.start(FlashPattern.Blink) }

        assertEquals(List(4) { FlashPattern.Blink }, flashlight.events)
    }

    @Test
    fun `two doubles record independently`() {
        val first = RecordingFlashlight()
        val second = RecordingFlashlight()

        first.start(FlashPattern.Attention)

        assertEquals(listOf(FlashPattern.Attention), first.events)
        assertTrue(second.events.isEmpty())
    }
}
