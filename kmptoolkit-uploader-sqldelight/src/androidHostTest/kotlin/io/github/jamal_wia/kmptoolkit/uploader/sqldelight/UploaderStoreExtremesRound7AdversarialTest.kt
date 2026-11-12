package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.testing.InMemoryUploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Round 7 (persisted-data fidelity): every numeric column at the edges of its type goes through the in-memory store and the
 * SQLDelight store, through inserts and through every guarded write, and the rows and answers must be equal.
 */
@RunWith(AndroidJUnit4::class)
class UploaderStoreExtremesRound7AdversarialTest {

    private val ints: List<Int> = listOf(Int.MIN_VALUE, -1, 0, 1, Int.MAX_VALUE)
    private val longs: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, 4_102_444_800_000L, Long.MAX_VALUE)

    private fun record(id: String, schema: Int, attempts: Int, next: Long, created: Long, lease: Long, claim: Long): UploaderItem = UploaderItem(
        id = id, type = "t", payload = "p", schemaVersion = schema, uniqueKey = null, orderingKey = null, tag = null,
        state = UploaderItemState.PENDING, attempts = attempts, nextRunAtEpochMillis = next, createdAtEpochMillis = created, lastError = null,
        leaseUntilEpochMillis = lease, claim = claim,
    )

    @Test
    fun `numeric columns at the edges of their types read back equal in both stores`() = runTest {
        val storage: UploaderStorage = createInMemoryUploaderStorage()
        val sql: UploaderStore = storage.store
        val mem: UploaderStore = InMemoryUploaderStore()
        try {
            var n = 0
            for (schema in ints) for (attempts in ints) {
                for (time in longs) {
                    val id = "n${n++}"
                    val item: UploaderItem = record(id, schema, attempts, time, time, time, claim = if (n % 2 == 0) 0L else time.coerceAtLeast(0L).coerceAtMost(Long.MAX_VALUE - 2))
                    assertEquals(mem.insertKeep(item), sql.insertKeep(item), "insert $id")
                    assertEquals(mem.getById(id), sql.getById(id), "row $id (schema=$schema attempts=$attempts time=$time)")
                    val current: UploaderItem = checkNotNull(mem.getById(id))
                    val extremeAttempts: Int = ints[n % ints.size]
                    val gate: Long = longs[(n / 2) % longs.size]
                    assertEquals(
                        mem.recordFailure(id, extremeAttempts, gate, "e", UploaderItemState.PENDING, current.claim, FailureDisposition.RETRY),
                        sql.recordFailure(id, extremeAttempts, gate, "e", UploaderItemState.PENDING, current.claim, FailureDisposition.RETRY),
                        "recordFailure $id",
                    )
                    assertEquals(mem.getById(id), sql.getById(id), "row $id after recordFailure(attempts=$extremeAttempts gate=$gate)")
                    val claimNow: Long = checkNotNull(mem.getById(id)).claim
                    val lease: Long = longs[(n / 3) % longs.size]
                    assertEquals(
                        mem.markInFlight(id, lease, UploaderItemState.PENDING, claimNow),
                        sql.markInFlight(id, lease, UploaderItemState.PENDING, claimNow),
                        "markInFlight $id",
                    )
                    assertEquals(mem.getById(id), sql.getById(id), "row $id after markInFlight(lease=$lease)")
                }
            }
            assertEquals(mem.getAllActive(), sql.getAllActive(), "the active list in order")
        } finally {
            storage.close()
        }
    }
}
