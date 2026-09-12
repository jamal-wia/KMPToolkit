package io.github.jamal_wia.kmptoolkit.systembars.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecordingScreenWakeLockControllerTest {

    @Test
    fun `records a single call`() {
        val controller = RecordingScreenWakeLockController()

        controller.setKeepScreenOn(true)

        assertEquals(listOf(true), controller.calls)
    }

    @Test
    fun `records calls in order`() {
        val controller = RecordingScreenWakeLockController()

        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)
        controller.setKeepScreenOn(true)

        assertEquals(listOf(true, false, true), controller.calls)
    }

    @Test
    fun `isKeptOn reflects the most recent call`() {
        val controller = RecordingScreenWakeLockController()

        controller.setKeepScreenOn(true)
        assertTrue(controller.isKeptOn)

        controller.setKeepScreenOn(false)
        assertFalse(controller.isKeptOn)
    }

    @Test
    fun `isKeptOn defaults to false before any call`() {
        val controller = RecordingScreenWakeLockController()

        assertFalse(controller.isKeptOn)
    }

    @Test
    fun `clear empties the recorded calls`() {
        val controller = RecordingScreenWakeLockController()
        controller.setKeepScreenOn(true)
        controller.setKeepScreenOn(false)

        controller.clear()

        assertTrue(controller.calls.isEmpty())
    }
}
