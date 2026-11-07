package io.github.jamal_wia.kmptoolkit.uploader

import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** One item's failure outside its handler must not abort the drain pass for the items behind it. */
class UploaderDrainIsolationTest {

    /** Fails the first delete of [failId], as a store does when a write fails after the effect ran. */
    private class FlakyDeleteStore(private val delegate: TestUploaderStore, private val failId: String) :
        UploaderStore by delegate {
        var failures: Int = 0

        override suspend fun deleteById(id: String) {
            if (id == failId && failures == 0) {
                failures++
                throw IllegalStateException("disk full")
            }
            delegate.deleteById(id)
        }
    }

    @Test
    fun `a store failure after one item's effect does not stop the items behind it or the wake`() = runTest {
        val backing = TestUploaderStore()
        val store = FlakyDeleteStore(backing, failId = "item-1")
        val handler = TestHandler()
        val wake = TestWakeScheduler()
        val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, wakeScheduler = wake)
        engine.enqueue(handler, "A")
        engine.enqueue(handler, "B")

        engine.drain()

        assertTrue("B" in handler.payloads, "the second eligible item ran in the same pass")
        assertEquals(1, handler.payloads.count { it == "A" }, "the failed item's effect is not repeated in a tight loop")
        assertEquals(null, backing.find("item-2"), "B was delivered and removed")
        assertTrue(wake.armed, "the item left behind keeps the platform wake armed and an alarm retries it")
    }
}
