package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/**
 * Round 3 (concurrency-storm): the process-wide classifier slot under a register storm and many waiters.
 *
 * The slot is process-wide and never cleared, so these tests only assert what holds whatever an earlier test
 * left in it: once any classifier is registered a wait, even a zero one, finds one.
 */
class UploadClassifierRegistryStormAdversarialTest {

    private val verdict: (UploadResult) -> SettleResult = { SettleResult.Delivered }

    @Test
    fun `a hundred waiters racing eight registrars all get a classifier and a zero wait finds one afterwards`() =
        runTest(timeout = 70.seconds) {
            withContext(Dispatchers.Default) {
                withTimeout(60.seconds) {
                    repeat(20) { round ->
                        val start = CompletableDeferred<Unit>()
                        val waiters: List<Deferred<((UploadResult) -> SettleResult)?>> = List(100) {
                            async { start.await(); UploadClassifierRegistry.await(5_000L) }
                        }
                        val registrars: List<Deferred<Unit>> = List(8) {
                            async { start.await(); repeat(100) { UploadClassifierRegistry.register(verdict) } }
                        }
                        start.complete(Unit)
                        registrars.awaitAll()

                        waiters.awaitAll().forEachIndexed { index, got -> assertNotNull(got, "round $round waiter $index timed out") }
                        assertTrue(UploadClassifierRegistry.await(0L) != null, "round $round: a zero wait missed a registered classifier")
                    }
                }
            }
        }
}
