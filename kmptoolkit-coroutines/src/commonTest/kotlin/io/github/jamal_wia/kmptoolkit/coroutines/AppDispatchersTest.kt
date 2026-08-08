package io.github.jamal_wia.kmptoolkit.coroutines

import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the contract [TestAppDispatchers] consumers rely on: all three dispatchers collapse onto
 * one deterministic dispatcher, and two independently constructed instances stay independent.
 */
class AppDispatchersTest {

    @Test
    fun `TestAppDispatchers collapses io-main-default onto the same test dispatcher`() {
        val dispatchers = TestAppDispatchers()
        assertSame(dispatchers.testDispatcher, dispatchers.io)
        assertSame(dispatchers.testDispatcher, dispatchers.main)
        assertSame(dispatchers.testDispatcher, dispatchers.default)
    }

    @Test
    fun `two independent TestAppDispatchers instances do not share a dispatcher`() {
        val first = TestAppDispatchers()
        val second = TestAppDispatchers()
        assertNotSame(first.testDispatcher, second.testDispatcher)
    }

    @Test
    fun `DefaultAppDispatchers exposes three distinct dispatchers`() {
        val dispatchers = DefaultAppDispatchers()
        assertNotSame(dispatchers.io, dispatchers.main)
        assertNotSame(dispatchers.main, dispatchers.default)
        assertNotSame(dispatchers.io, dispatchers.default)
    }
}
