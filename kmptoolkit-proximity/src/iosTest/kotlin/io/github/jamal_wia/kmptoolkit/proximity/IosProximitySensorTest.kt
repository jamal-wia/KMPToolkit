package io.github.jamal_wia.kmptoolkit.proximity

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest

/**
 * iOS has no hardware to test against, so this only pins the documented contract: an always-absent
 * sensor whose flow completes without ever emitting.
 */
class IosProximitySensorTest {

    @Test
    fun `is never available`() {
        assertFalse(createProximitySensor().isAvailable)
    }

    @Test
    fun `observe completes without emitting`() = runTest {
        assertTrue(createProximitySensor().observe().toList().isEmpty())
    }

    @Test
    fun `the factory returns a working instance every time`() {
        assertFalse(createProximitySensor().isAvailable)
        assertFalse(createProximitySensor().isAvailable)
    }
}
