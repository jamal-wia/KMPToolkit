package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/**
 * Carried review finding N2 of the A1 split step 1 re-review: `settle` triggers a drain pass even when the confirming
 * delete of a delivery throws, because a row held as delivered is owed its delete (rule P7) and only the drain can
 * retry it. Every earlier test drives `drain()` by hand and a started engine triggers by itself, so moving the trigger
 * out of the `finally` of the settle commit would survive them all. Here the engine runs its own loop with a heartbeat
 * an hour away and no alarm armed, so the settle's trigger is the only thing that can start the rescue pass.
 */
class EngineSplit2SettleTriggerAdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    /** Fails the next [deleteFailures] deletes (a locked database). */
    private class FlakyDeleteStore(private val inner: TestUploaderStore) : UploaderStore by inner {
        var deleteFailures: Int = 0
        override suspend fun deleteById(id: String) {
            if (deleteFailures > 0) {
                deleteFailures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    @Test
    fun `a settle whose confirming delete throws still triggers the pass that confirms it`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner)
            val contexts: MutableList<AttemptContext> = mutableListOf()
            val handler = TestHandler(
                type = "split2.trigger",
                onExecute = { context, _ ->
                    contexts += context
                    AttemptResult.Detached(60_000L)
                },
            )
            val clock = TestClock(3_000_000L)
            val engine: UploaderEngine = testEngine(
                store, listOf(handler), scopeOn(parent), clock,
                config = UploaderConfig(heartbeatInterval = 1.hours),
                idGenerator = SequentialIds("s2trig")::next,
            )
            engine.start()
            runCurrent()

            // Two executors hold claims of the item: the first one goes quiet, its lease runs out and a second is handed it.
            val id: String = assertNotNull(engine.enqueue(handler, "send-report"))
            runCurrent()
            assertEquals(1, contexts.size, "setup: the first executor was handed the item")
            clock.millis += 61_000L
            advanceTimeBy(61_000L)
            runCurrent()
            assertEquals(2, contexts.size, "setup: the lease ran out and a second executor was handed the item")

            // The current executor's verdict parks the row, which empties the active queue and disarms every alarm.
            engine.settle(id, contexts[1].claim, SettleResult.Park("rejected"))
            runCurrent()
            assertEquals(UploaderItemState.PARKED, inner.find(id)?.state, "setup: the verdict parked the row")

            // The first executor then reports its delivery, and the delete of the row fails: the row is held as delivered.
            store.deleteFailures = 1
            assertFailsWith<IllegalStateException> { engine.settle(id, contexts[0].claim, SettleResult.Delivered) }
            val held: UploaderItem? = inner.find(id)
            assertTrue(held != null && DeliveredUnconfirmed.matches(held), "setup: the delivered row is held and still in the store")

            // The store is back. No time passes: only the trigger of the settle that threw can start a pass now.
            runCurrent()

            assertEquals(null, inner.find(id), "the settle that threw triggered the pass that deleted the held row")
            assertEquals(2, handler.payloads.size, "nothing ran again")
        } finally {
            parent.cancel()
        }
    }
}
