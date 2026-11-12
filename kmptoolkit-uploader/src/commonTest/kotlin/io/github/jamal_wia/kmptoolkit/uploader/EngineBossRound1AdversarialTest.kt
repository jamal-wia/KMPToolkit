package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * A1 final boss round 1: two documented promises read together.
 *
 * The engine KDoc and guide 03 promise that "within one process an effect whose handler succeeded but whose row could
 * not be deleted is never run again". The close KDoc and P6 describe a replacement engine started in the same process
 * after close() (P3 even keeps its claim book, [ClaimsInDoubt], per process for exactly that case). These tests run the
 * delivered-but-unconfirmed effect through such a replacement. Committed here: the control (the engine that saw the
 * delivery); the replacement case is suspect boss1-S1, kept in adversarial/A1/suspects.
 */
class EngineBossRound1AdversarialTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    /** Fails the next [failures] confirming deletes as a locked database would: nothing written. */
    private class FlakyDeleteStore(private val inner: TestUploaderStore, var failures: Int) : UploaderStore by inner {
        override suspend fun deleteById(id: String) {
            if (failures > 0) {
                failures--
                throw IllegalStateException("database is locked")
            }
            inner.deleteById(id)
        }
    }

    private fun TestScope.scopeOn(parent: Job): CoroutineScope = CoroutineScope(StandardTestDispatcher(testScheduler) + parent)

    @Test
    fun `control - the engine that saw the delivery retries only the delete`() = runTest {
        val parent = Job()
        try {
            val inner = TestUploaderStore()
            val store = FlakyDeleteStore(inner, failures = 1)
            val handler = TestHandler()
            val clock = TestClock(1_000_000L)
            val engine: UploaderEngine = testEngine(store, listOf(handler), scopeOn(parent), clock)
            val id: String = engine.enqueue(handler, "send-message")!!
            engine.drain()
            assertEquals(1, handler.payloads.size)
            clock.millis += 60_000L
            engine.drain()
            assertEquals(1, handler.payloads.size, "the same engine ran the delivered effect again")
            assertNull(inner.find(id), "the confirming delete was never retried")
        } finally {
            parent.cancel()
        }
    }
}
