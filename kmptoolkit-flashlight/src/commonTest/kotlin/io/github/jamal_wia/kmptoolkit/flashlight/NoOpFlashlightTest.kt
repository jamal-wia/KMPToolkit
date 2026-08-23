package io.github.jamal_wia.kmptoolkit.flashlight

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** Pins the contract documented on [noOpFlashlight]. */
class NoOpFlashlightTest {

    @Test
    fun `isAvailable is always false`() {
        assertFalse(noOpFlashlight().isAvailable)
    }

    @Test
    fun `start and stop do nothing and neither throws`() {
        val flashlight: Flashlight = noOpFlashlight()

        flashlight.start(FlashPattern.Blink)
        flashlight.stop()
        flashlight.start(FlashPattern.Attention)
    }

    @Test
    fun `the factory hands back one stateless instance`() {
        assertSame(noOpFlashlight(), noOpFlashlight())
    }
}

/** Pins the shape of [FlashPattern], the one type every implementation branches on. */
class FlashPatternContractTest {

    @Test
    fun `both patterns are declared in this order`() {
        assertEquals(listOf(FlashPattern.Attention, FlashPattern.Blink), FlashPattern.entries)
    }

    @Test
    fun `every pattern has a positive on-time and a positive off-time`() {
        FlashPattern.entries.forEach { pattern: FlashPattern ->
            assertTrue(pattern.on.isPositive(), "pattern=$pattern has a non-positive on-time")
            assertTrue(pattern.off.isPositive(), "pattern=$pattern has a non-positive off-time")
        }
    }
}
