package io.github.jamal_wia.kmptoolkit.coroutines.testing

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Pins the contract consumers of [TestAppDispatchers] rely on. */
class TestAppDispatchersTest {

    @Test
    fun `collapses io-main-default onto the same test dispatcher`() {
        val dispatchers = TestAppDispatchers()
        assertSame(dispatchers.testDispatcher, dispatchers.io)
        assertSame(dispatchers.testDispatcher, dispatchers.main)
        assertSame(dispatchers.testDispatcher, dispatchers.default)
    }

    @Test
    fun `two instances constructed without a scheduler stay independent`() {
        val first = TestAppDispatchers()
        val second = TestAppDispatchers()
        assertNotSame(first.testDispatcher, second.testDispatcher)
    }

    @Test
    fun `instances sharing one scheduler advance on the same virtual clock`() {
        val scheduler = TestCoroutineScheduler()
        val first = TestAppDispatchers(scheduler)
        val second = TestAppDispatchers(scheduler)

        // Distinct dispatcher objects, but one clock — this is what makes it safe to hand two
        // collaborators their own TestAppDispatchers built from a single scheduler, and it is the
        // reason the constructor takes one at all.
        assertNotSame(first.testDispatcher, second.testDispatcher)

        val completed: MutableList<String> = mutableListOf()
        runTest(scheduler) {
            launch(first.io) {
                delay(1_000)
                completed += "first"
            }
            launch(second.io) {
                delay(1_000)
                completed += "second"
            }

            assertEquals(emptyList(), completed)
            advanceTimeBy(1_001)
            assertEquals(listOf("first", "second"), completed)
        }
    }

    @Test
    fun `withContext on any of the three dispatchers runs on the test scheduler`() {
        val scheduler = TestCoroutineScheduler()
        val dispatchers = TestAppDispatchers(scheduler)

        runTest(scheduler) {
            val observed: List<String> = listOf(
                withContext(dispatchers.io) { "io" },
                withContext(dispatchers.main) { "main" },
                withContext(dispatchers.default) { "default" },
            )
            assertEquals(listOf("io", "main", "default"), observed)
        }
    }
}
