package io.github.jamal_wia.kmptoolkit.haptics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame

/**
 * What is actually observable about the iOS implementation from a test: UIKit reports nothing back
 * about whether a device buzzed, so the assertions here are about the contract this module states —
 * every type is accepted, nothing throws, and the call returns without waiting for the main queue.
 */
class IosHapticFeedbackTest {

    @Test
    fun `every haptic type is accepted and reports PERFORMED`() {
        val haptics: HapticFeedback = createHapticFeedback()

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
        }
    }

    @Test
    fun `repeated calls keep succeeding`() {
        val haptics: HapticFeedback = createHapticFeedback()

        repeat(5) { assertEquals(HapticResult.PERFORMED, haptics.perform(HapticType.SUCCESS)) }
    }

    @Test
    fun `the factory hands back an independent instance each time`() {
        assertNotSame(createHapticFeedback(), createHapticFeedback())
    }

    @Test
    fun `the no-op implementation stays distinguishable from the real one`() {
        assertEquals(HapticResult.UNAVAILABLE, noOpHapticFeedback().perform(HapticType.LIGHT))
        assertEquals(HapticResult.PERFORMED, createHapticFeedback().perform(HapticType.LIGHT))
    }
}
