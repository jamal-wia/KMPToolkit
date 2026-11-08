package io.github.jamal_wia.kmptoolkit.uploader.testing

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 6 (contract lawyer): the KDoc of [InMemoryUploaderStore] says its calls never notice a cancelled caller. A call
 * with the lock free does not; the contended case, where the store's own lock reports a cancelled caller, is pinned by the storm test below.
 */
class InMemoryStoreCancellationRound6AdversarialTest {

    @Test
    fun `a call of the in-memory store whose caller is already cancelled is never refused`() =
        runTest(timeout = 60.seconds) {
            val store = InMemoryUploaderStore()
            val refused: Int = withContext(Dispatchers.Default) {
                withTimeout(30.seconds) {
                    val started = TimeSource.Monotonic.markNow()
                    List(8) {
                        async {
                            var noticed = 0
                            var iterations = 0
                            while (started.elapsedNow() < 4.seconds && iterations < 2_000_000) {
                                iterations++
                                coroutineScope {
                                    val call = launch(start = CoroutineStart.ATOMIC) {
                                        try {
                                            store.deleteById("x")
                                        } catch (e: CancellationException) {
                                            noticed++
                                            throw e
                                        }
                                    }
                                    call.cancel()
                                }
                            }
                            noticed
                        }
                    }.awaitAll().sum()
                }
            }
            assertEquals(0, refused, "calls of a cancelled caller were refused by the store's own lock")
        }

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
