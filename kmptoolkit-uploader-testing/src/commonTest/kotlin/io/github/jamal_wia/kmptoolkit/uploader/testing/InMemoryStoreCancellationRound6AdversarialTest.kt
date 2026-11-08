package io.github.jamal_wia.kmptoolkit.uploader.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest

/**
 * Round 6 (contract lawyer): the KDoc of [InMemoryUploaderStore] says its calls never notice a cancelled caller. A call
 * with the lock free does not; the contended case, where the store's own lock reports a cancelled caller, is the
 * suspect in adversarial/A1/suspects/r6-R6-06-*.
 */
class InMemoryStoreCancellationRound6AdversarialTest {

    @Test
    fun `a lone call of the in-memory store whose caller was cancelled before it began is not refused`() =
        runTest {
            val store = InMemoryUploaderStore()
            var refused = false
            val call = launch(start = CoroutineStart.ATOMIC) {
                try {
                    store.deleteById("x") // nobody else touches the store: the lock is free
                } catch (e: CancellationException) {
                    refused = true
                    throw e
                }
            }
            call.cancel()
            call.join()
            assertEquals(false, refused, "an uncontended call noticed its cancelled caller")
        }
}
