package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** Constraint gating: what waits, what fires, and what happens when the wiring is wrong. */
class UploaderConstraintTest {

    @Test
    fun `an item is not attempted while its constraint is unsatisfied`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val handler = TestHandler(constraintKeys = setOf("network"))
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.enqueue(handler, "p")

        engine.drain()

        assertEquals(0, handler.attempts.size)
        assertEquals(0, store.find("item-1")?.attempts, "waiting is not failing")
    }

    @Test
    fun `an item is attempted once its constraint is satisfied`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val handler = TestHandler(constraintKeys = setOf("network"))
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.enqueue(handler, "p")
        engine.drain()

        network.set(true)
        engine.drain()

        assertEquals(1, handler.attempts.size)
    }

    @Test
    fun `a started engine drains on the false to true transition`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val handler = TestHandler(constraintKeys = setOf("network"))
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.start()
        engine.enqueue(handler, "p")
        runCurrent()
        assertEquals(0, handler.attempts.size)

        network.set(true)
        runCurrent()

        assertEquals(1, handler.attempts.size, "connectivity returning must wake the queue itself")
    }

    @Test
    fun `every named constraint must hold`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = true)
        val socket = TestConstraint("socket", satisfied = false)
        val handler = TestHandler(constraintKeys = setOf("network", "socket"))
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            constraintProviders = listOf(network, socket),
        )
        engine.enqueue(handler, "p")

        engine.drain()
        assertEquals(0, handler.attempts.size)

        socket.set(true)
        engine.drain()
        assertEquals(1, handler.attempts.size)
    }

    @Test
    fun `an unconstrained handler is unaffected by an unsatisfied provider`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val handler = TestHandler()
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.enqueue(handler, "p")

        engine.drain()

        assertEquals(1, handler.attempts.size)
    }

    @Test
    fun `a key with no provider fails open rather than stalling the queue`() = runTest {
        val store = TestUploaderStore()
        val handler = TestHandler(constraintKeys = setOf("typo-in-the-key"))
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope)
        engine.enqueue(handler, "p")

        engine.drain()

        assertEquals(1, handler.attempts.size, "a wiring typo must not silently freeze effects")
    }

    @Test
    fun `a gated item still blocks its ordering channel`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val gated = TestHandler(
            type = "gated",
            constraintKeys = setOf("network"),
            ordering = { "thread" },
        )
        val engine: UploaderEngine = testEngine(
            store,
            listOf(gated),
            backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.enqueue(gated, "first")
        engine.enqueue(gated, "second")

        engine.drain()
        network.set(true)
        engine.drain()

        assertEquals(listOf("first", "second"), gated.payloads, "order survives the wait")
    }

    @Test
    fun `an unconstrained channel keeps flowing while another waits`() = runTest {
        val store = TestUploaderStore()
        val network = TestConstraint("network", satisfied = false)
        val gated = TestHandler(type = "gated", constraintKeys = setOf("network"))
        val free = TestHandler(type = "free")
        val engine: UploaderEngine = testEngine(
            store,
            listOf(gated, free),
            backgroundScope,
            constraintProviders = listOf(network),
        )
        engine.enqueue(gated, "waiting")
        engine.enqueue(free, "going")

        engine.drain()

        assertTrue(free.payloads == listOf("going"))
        assertEquals(0, gated.attempts.size)
    }
}
