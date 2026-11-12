package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * Round 7 (limits of persisted numbers): a stored row can carry any `attempts` an earlier build or a damaged store left.
 * The count of failed attempts only ever grows and `ParkAfterAttempts(n)` parks a row whose count reached `n`; a row that
 * already holds `Int.MAX_VALUE` failures and fails once more must park, not wrap to a negative count that never reaches `n`.
 */
class UploaderEngineAttemptCountEdgesRound7RegressionTest {

    @AfterTest
    fun clearRegistry() {
        UploaderEngineRegistry.current?.let { UploaderEngineRegistry.unregister(it) }
    }

    private fun row(attempts: Int): UploaderItem = UploaderItem(
        id = "edge-1", type = TestHandler.DEFAULT_TYPE, payload = "p", schemaVersion = 1, uniqueKey = null, orderingKey = null, tag = null,
        state = UploaderItemState.PENDING, attempts = attempts, nextRunAtEpochMillis = 0L, createdAtEpochMillis = 0L, lastError = null,
    )

    @Test
    fun `a row at the maximum failure count that fails again is parked and its count never goes down`() = runTest {
        for (start in listOf(Int.MAX_VALUE, Int.MAX_VALUE - 1)) {
            val store = TestUploaderStore(listOf(row(start)))
            val handler = failingHandler(retryPolicy = FixedRetryPolicy(delayMillis = 0L, giveUp = GiveUpPolicy.ParkAfterAttempts(5)))
            val engine: UploaderEngine = testEngine(store, listOf(handler), backgroundScope, clock = TestClock(1_000L))
            try {
                engine.drain()
                val after: UploaderItem = checkNotNull(store.find("edge-1"))
                assertTrue(after.attempts >= start, "the failure count went from $start to ${after.attempts}")
                assertEquals(UploaderItemState.PARKED, after.state, "a row with $start failures was not parked by a budget of 5 (count now ${after.attempts})")
            } finally {
                engine.close()
            }
        }
    }
}
