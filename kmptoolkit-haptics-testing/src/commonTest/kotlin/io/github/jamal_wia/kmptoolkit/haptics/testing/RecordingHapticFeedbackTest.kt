package io.github.jamal_wia.kmptoolkit.haptics.testing

import io.github.jamal_wia.kmptoolkit.haptics.HapticFeedback
import io.github.jamal_wia.kmptoolkit.haptics.HapticResult
import io.github.jamal_wia.kmptoolkit.haptics.HapticType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Pins the contract documented on [RecordingHapticFeedback]. */
class RecordingHapticFeedbackTest {

    @Test
    fun `a fresh double has recorded nothing`() {
        assertTrue(RecordingHapticFeedback().events.isEmpty())
    }

    @Test
    fun `calls are recorded in the order they arrived`() {
        val haptics = RecordingHapticFeedback()

        haptics.perform(HapticType.LIGHT)
        haptics.perform(HapticType.ERROR)
        haptics.perform(HapticType.LIGHT)

        assertContentEquals(
            listOf(HapticType.LIGHT, HapticType.ERROR, HapticType.LIGHT),
            haptics.events,
        )
    }

    @Test
    fun `it reports PERFORMED unless the test says otherwise`() {
        val haptics: HapticFeedback = RecordingHapticFeedback()

        HapticType.entries.forEach { type ->
            assertEquals(HapticResult.PERFORMED, haptics.perform(type), "type=$type")
        }
    }

    @Test
    fun `the configured result is returned for every type`() {
        HapticResult.entries.forEach { configured ->
            val haptics = RecordingHapticFeedback(result = configured)

            HapticType.entries.forEach { type ->
                assertEquals(configured, haptics.perform(type), "result=$configured type=$type")
            }
        }
    }

    @Test
    fun `a call is recorded even when the configured result says it could not play`() {
        val haptics = RecordingHapticFeedback(result = HapticResult.PERMISSION_DENIED)

        haptics.perform(HapticType.WARNING)

        assertContentEquals(listOf(HapticType.WARNING), haptics.events)
    }

    @Test
    fun `switching the result mid-test affects only later calls`() {
        val haptics = RecordingHapticFeedback()

        val before: HapticResult = haptics.perform(HapticType.MEDIUM)
        haptics.result = HapticResult.UNAVAILABLE
        val after: HapticResult = haptics.perform(HapticType.MEDIUM)

        assertEquals(HapticResult.PERFORMED, before)
        assertEquals(HapticResult.UNAVAILABLE, after)
        assertEquals(2, haptics.events.size)
    }

    @Test
    fun `clear drops the recording but keeps the configured result`() {
        val haptics = RecordingHapticFeedback(result = HapticResult.UNAVAILABLE)
        haptics.perform(HapticType.HEAVY)

        haptics.clear()

        assertTrue(haptics.events.isEmpty())
        assertEquals(HapticResult.UNAVAILABLE, haptics.result)
        assertEquals(HapticResult.UNAVAILABLE, haptics.perform(HapticType.HEAVY))
    }

    @Test
    fun `clear on an empty double is a no-op rather than a failure`() {
        val haptics = RecordingHapticFeedback()

        haptics.clear()
        haptics.clear()

        assertTrue(haptics.events.isEmpty())
    }

    @Test
    fun `an events snapshot taken earlier does not change when more calls arrive`() {
        val haptics = RecordingHapticFeedback()
        haptics.perform(HapticType.LIGHT)
        val snapshot: List<HapticType> = haptics.events

        haptics.perform(HapticType.ERROR)
        haptics.clear()

        assertContentEquals(listOf(HapticType.LIGHT), snapshot)
    }

    @Test
    fun `repeated identical calls are all recorded rather than deduplicated`() {
        val haptics = RecordingHapticFeedback()

        repeat(4) { haptics.perform(HapticType.SUCCESS) }

        assertEquals(List(4) { HapticType.SUCCESS }, haptics.events)
    }

    @Test
    fun `two doubles record independently`() {
        val first = RecordingHapticFeedback()
        val second = RecordingHapticFeedback()

        first.perform(HapticType.LIGHT)

        assertContentEquals(listOf(HapticType.LIGHT), first.events)
        assertTrue(second.events.isEmpty())
    }
}
