package io.github.jamal_wia.kmptoolkit.coroutines

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Pins the contract [DefaultAppDispatchers] callers rely on. */
class AppDispatchersTest {

    @Test
    fun `DefaultAppDispatchers exposes three distinct dispatchers`() {
        val dispatchers = DefaultAppDispatchers()
        assertNotSame(dispatchers.io, dispatchers.main)
        assertNotSame(dispatchers.main, dispatchers.default)
        assertNotSame(dispatchers.io, dispatchers.default)
    }

    @Test
    fun `DefaultAppDispatchers returns the same instance on repeated access`() {
        // Callers — TestAppDispatchers' contract among them — may compare dispatchers by identity,
        // so a property must not allocate a new dispatcher per read.
        val dispatchers = DefaultAppDispatchers()
        assertSame(dispatchers.io, dispatchers.io)
        assertSame(dispatchers.main, dispatchers.main)
        assertSame(dispatchers.default, dispatchers.default)
    }
}
