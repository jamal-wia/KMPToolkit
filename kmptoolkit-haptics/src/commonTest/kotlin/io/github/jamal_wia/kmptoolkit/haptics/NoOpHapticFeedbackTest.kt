package io.github.jamal_wia.kmptoolkit.haptics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/** Pins the contract documented on [noOpHapticFeedback]. */
class NoOpHapticFeedbackTest {

    @Test
    fun `every haptic type reports UNAVAILABLE`() {
        val haptics: HapticFeedback = noOpHapticFeedback()

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.UNAVAILABLE, haptics.perform(type), "type=$type")
        }
    }

    @Test
    fun `repeated calls keep reporting UNAVAILABLE rather than degrading to something else`() {
        val haptics: HapticFeedback = noOpHapticFeedback()

        repeat(3) { assertEquals(HapticResult.UNAVAILABLE, haptics.perform(HapticType.ERROR)) }
    }

    @Test
    fun `the factory hands back one stateless instance`() {
        assertSame(noOpHapticFeedback(), noOpHapticFeedback())
    }
}

/** Pins the shape of the two enums every implementation and every consumer branches on. */
class HapticContractTest {

    @Test
    fun `the six semantic types are declared in ascending impact order`() {
        assertEquals(
            listOf(
                HapticType.LIGHT,
                HapticType.MEDIUM,
                HapticType.HEAVY,
                HapticType.SUCCESS,
                HapticType.WARNING,
                HapticType.ERROR,
            ),
            HapticType.entries,
        )
    }

    @Test
    fun `there are exactly three documented outcomes`() {
        assertEquals(
            listOf(
                HapticResult.PERFORMED,
                HapticResult.UNAVAILABLE,
                HapticResult.PERMISSION_DENIED,
            ),
            HapticResult.entries,
        )
    }
}
