package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Refactor-regression round, third set: killers of the two registry mutants (L10 and L13 of the regress sweep) that
 * the whole uploader suite let through. The contract is [UploaderEngineRegistry]'s own: "`close` clears the slot if the
 * closing engine is the registered one" and "cancelling the scope the engine was created in ... is unregistered"
 * (04 `UploaderEngineRegistry`, the registry's class KDoc).
 *
 * The public peek (`current`, `await`) hides a closed engine whether or not the slot still holds it, so the clearing
 * itself is observable only on the slot: these tests read the private `slot` by reflection (a JVM-only observation,
 * hence the Android host source set). A slot that keeps a dead engine keeps it, its store and its handlers reachable
 * from a process-wide global, which is what the registry's KDoc promises not to do.
 *
 * Deterministic by construction: virtual time, a latch instead of a wait, reflection instead of a garbage-collection
 * probe, no sleep, no randomness.
 */
class RegressSurvivorKills3RegistrySlotAdversarialTest {

    @AfterTest
    fun cleanUp() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** What the process-wide slot holds right now, closed or not: the one view the public peek does not give. */
    private fun heldBySlot(): UploaderEngine? {
        val field = UploaderEngineRegistry::class.java.getDeclaredField("slot")
        field.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val slot: MutableStateFlow<UploaderEngine?> = field.get(null) as MutableStateFlow<UploaderEngine?>
        return slot.value
    }

    @Test
    fun `close clears the slot even while an attempt that ignores its cancellation still runs`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        try {
            val gate: CompletableDeferred<Unit> = CompletableDeferred()
            val handler = TestHandler(onExecute = { _, _ ->
                withContext(NonCancellable) { gate.await() }
                AttemptResult.Success
            })
            val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(handler), scopeOn(parent), TestClock(5_000_000L), idGenerator = SequentialIds("k3l10")::next)
            engine.start()
            UploaderEngineRegistry.register(engine)
            engine.enqueue(handler, "payload")
            runCurrent()
            assertEquals(1, handler.attempts.size, "setup: the attempt is running and ignores cancellation")
            assertSame(engine, heldBySlot(), "setup: the registered engine is in the slot")

            engine.close()

            assertNull(heldBySlot(), "close() clears the slot, whether or not the running attempt has ended")
            assertNull(UploaderEngineRegistry.current, "and a closed engine is never handed out")

            gate.complete(Unit)
            runCurrent()
        } finally {
            parent.cancel()
        }
    }

    @Test
    fun `cancelling the scope an engine was created in unregisters it`() = runTest(timeout = 30.seconds) {
        val parent = Job()
        val engine: UploaderEngine = testEngine(TestUploaderStore(), listOf(TestHandler()), scopeOn(parent), TestClock(5_000_000L))
        UploaderEngineRegistry.register(engine)
        assertNotNull(heldBySlot(), "setup: the engine is registered")

        parent.cancel()
        runCurrent()

        assertNull(heldBySlot(), "an engine stopped by cancelling its scope is unregistered, not merely hidden")
        assertNull(UploaderEngineRegistry.current, "and it is never handed out")
    }
}
