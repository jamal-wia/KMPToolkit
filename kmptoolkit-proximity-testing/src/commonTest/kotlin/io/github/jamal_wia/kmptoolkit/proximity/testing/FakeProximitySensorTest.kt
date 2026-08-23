package io.github.jamal_wia.kmptoolkit.proximity.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * The fixture's own contract — what consumers' assertions rest on, so a fixture that quietly lies
 * about a reading is worse than no fixture.
 */
class FakeProximitySensorTest {

    @Test
    fun `is available by default`() {
        assertTrue(FakeProximitySensor().isAvailable)
    }

    @Test
    fun `starts from the availability it was given`() {
        assertFalse(FakeProximitySensor(isAvailable = false).isAvailable)
    }

    @Test
    fun `a collector sees the most recent emitted reading`() = runTest {
        val sensor = FakeProximitySensor()

        sensor.emit(true)

        assertEquals(true, sensor.observe().first())
    }

    @Test
    fun `a later emit overrides an earlier one for a new collector`() = runTest {
        val sensor = FakeProximitySensor()

        sensor.emit(true)
        sensor.emit(false)

        assertEquals(false, sensor.observe().first())
    }

    @Test
    fun `an unavailable sensor never emits`() = runTest {
        val sensor = FakeProximitySensor(isAvailable = false)

        sensor.emit(true)

        assertEquals(emptyList(), sensor.observe().toList())
    }

    @Test
    fun `a reading queued while unavailable surfaces once availability returns`() = runTest {
        val sensor = FakeProximitySensor(isAvailable = false)
        sensor.emit(true)

        sensor.isAvailable = true

        assertEquals(true, sensor.observe().first())
    }

    @Test
    fun `becoming unavailable mid-test stops new collectors from seeing readings`() = runTest {
        val sensor = FakeProximitySensor()
        sensor.emit(true)

        sensor.isAvailable = false

        assertEquals(emptyList(), sensor.observe().toList())
    }

    @Test
    fun `emit is counted even while unavailable`() {
        val sensor = FakeProximitySensor(isAvailable = false)

        sensor.emit(true)
        sensor.emit(false)

        assertEquals(2, sensor.emitCount)
    }

    @Test
    fun `emit count starts at zero`() {
        assertEquals(0, FakeProximitySensor().emitCount)
    }
}
