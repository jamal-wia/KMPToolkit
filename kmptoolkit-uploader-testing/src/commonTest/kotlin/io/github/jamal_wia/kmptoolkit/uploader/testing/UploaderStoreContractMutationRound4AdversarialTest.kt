package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest

/**
 * Round 4 (contract-lawyer): the published `UploaderStoreContract` claims to assert every invariant of the
 * `UploaderStore` KDoc. Each test here is a store with exactly one deliberate bug that the KDoc forbids; the
 * contract must reject it. A mutant that passes is an invariant the contract does not check.
 */
class UploaderStoreContractMutationRound4AdversarialTest {

    private enum class Bug {
        NONE,
        ORDER_BY_CREATED_AT,
        OBSERVE_ONE_SHOT,
        OBSERVE_HIDES_IN_FLIGHT,
        PAYLOAD_TRIM,
        PAYLOAD_LOWERCASE,
        TAG_LOWERCASE,
        KEY_LOWERCASE,
        WIPE_IGNORES_CASE,
        WIPE_BY_PREFIX,
        MARK_CLEARS_TAG,
        MARK_CLEARS_LAST_ERROR,
        FAILURE_CLEARS_TAG,
        FAILURE_CLEARS_UNIQUE_KEY,
        FAILURE_RESETS_CREATED_AT,
        FAILURE_CLEARS_ORDERING_KEY,
        FAILURE_ADDS_ATTEMPTS,
        FAILURE_KEEPS_LARGER_GATE,
        PARK_CLEARS_TAG,
        PARK_CLEARS_ORDERING_KEY,
        KEEP_PARKED_IN_PLACE,
        GET_BY_ID_PREFIX,
        MARK_ADDS_LEASE,
    }

    /** A copy of the reference store's logic with one switchable bug. Not thread-safe by itself: SerializedStore adds the lock. */
    private class MutantStore(private val bug: Bug) : UploaderStore {
        private val state = MutableStateFlow<List<UploaderItem>>(emptyList())
        private val rows: List<UploaderItem> get() = state.value

        private fun normalize(record: UploaderItem): UploaderItem = record.copy(
            payload = when (bug) {
                Bug.PAYLOAD_TRIM -> record.payload.trim()
                Bug.PAYLOAD_LOWERCASE -> record.payload.lowercase()
                else -> record.payload
            },
            tag = if (bug == Bug.TAG_LOWERCASE) record.tag?.lowercase() else record.tag,
            uniqueKey = if (bug == Bug.KEY_LOWERCASE) record.uniqueKey?.lowercase() else record.uniqueKey,
        )

        private fun conflict(record: UploaderItem): UploaderItem? {
            val key: String = record.uniqueKey ?: return null
            return rows.firstOrNull { it.type == record.type && it.uniqueKey == key }
        }

        override suspend fun insertKeep(record: UploaderItem): Boolean {
            val new: UploaderItem = normalize(record)
            val existing: UploaderItem? = conflict(new)
            return when (existing?.state) {
                UploaderItemState.PENDING, UploaderItemState.IN_FLIGHT -> false
                UploaderItemState.PARKED -> {
                    state.value = if (bug == Bug.KEEP_PARKED_IN_PLACE) {
                        rows.map { if (it.id == existing.id) new else it }
                    } else {
                        rows.filterNot { it.id == existing.id } + new
                    }
                    true
                }

                null -> {
                    state.value = rows + new
                    true
                }
            }
        }

        override suspend fun insertReplace(record: UploaderItem): UploaderItem? {
            val new: UploaderItem = normalize(record)
            val existing: UploaderItem? = conflict(new)
            state.value = rows.filterNot { it.id == existing?.id } + new
            return existing
        }

        override suspend fun getAllActive(): List<UploaderItem> {
            val active: List<UploaderItem> = rows.filter {
                it.state == UploaderItemState.PENDING || it.state == UploaderItemState.IN_FLIGHT
            }
            return if (bug == Bug.ORDER_BY_CREATED_AT) active.sortedBy { it.createdAtEpochMillis } else active
        }

        override suspend fun getById(id: String): UploaderItem? =
            if (bug == Bug.GET_BY_ID_PREFIX) {
                rows.firstOrNull { it.id == id } ?: rows.firstOrNull { it.id.startsWith(id) }
            } else {
                rows.firstOrNull { it.id == id }
            }

        private fun guarded(id: String, expectedState: UploaderItemState, expectedClaim: Long): UploaderItem? =
            rows.firstOrNull { it.id == id }?.takeIf { it.state == expectedState && it.claim == expectedClaim }

        private fun put(updated: UploaderItem) {
            state.value = rows.map { if (it.id == updated.id) updated else it }
        }

        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean {
            val current: UploaderItem = guarded(id, expectedState, expectedClaim) ?: return false
            when (disposition) {
                FailureDisposition.DROP -> state.value = rows.filterNot { it.id == id }
                FailureDisposition.RETRY, FailureDisposition.PARK -> put(
                    current.copy(
                        state = if (disposition == FailureDisposition.PARK) UploaderItemState.PARKED else UploaderItemState.PENDING,
                        attempts = if (bug == Bug.FAILURE_ADDS_ATTEMPTS) current.attempts + attempts else attempts,
                        nextRunAtEpochMillis = if (bug == Bug.FAILURE_KEEPS_LARGER_GATE) {
                            maxOf(current.nextRunAtEpochMillis, nextRunAtEpochMillis)
                        } else {
                            nextRunAtEpochMillis
                        },
                        lastError = lastError,
                        leaseUntilEpochMillis = 0L,
                        claim = expectedClaim + 1,
                        tag = if (bug == Bug.FAILURE_CLEARS_TAG) null else current.tag,
                        uniqueKey = if (bug == Bug.FAILURE_CLEARS_UNIQUE_KEY) null else current.uniqueKey,
                        orderingKey = if (bug == Bug.FAILURE_CLEARS_ORDERING_KEY) null else current.orderingKey,
                        createdAtEpochMillis = if (bug == Bug.FAILURE_RESETS_CREATED_AT) 0L else current.createdAtEpochMillis,
                    ),
                )
            }
            return true
        }

        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            val current: UploaderItem = guarded(id, expectedState, expectedClaim) ?: return false
            put(
                current.copy(
                    state = UploaderItemState.IN_FLIGHT,
                    leaseUntilEpochMillis = if (bug == Bug.MARK_ADDS_LEASE) {
                        current.leaseUntilEpochMillis + leaseUntilEpochMillis
                    } else {
                        leaseUntilEpochMillis
                    },
                    claim = expectedClaim + 1,
                    tag = if (bug == Bug.MARK_CLEARS_TAG) null else current.tag,
                    lastError = if (bug == Bug.MARK_CLEARS_LAST_ERROR) null else current.lastError,
                ),
            )
            return true
        }

        override suspend fun park(
            id: String,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean {
            val current: UploaderItem = guarded(id, expectedState, expectedClaim) ?: return false
            put(
                current.copy(
                    state = UploaderItemState.PARKED,
                    lastError = lastError,
                    leaseUntilEpochMillis = 0L,
                    claim = expectedClaim + 1,
                    tag = if (bug == Bug.PARK_CLEARS_TAG) null else current.tag,
                    orderingKey = if (bug == Bug.PARK_CLEARS_ORDERING_KEY) null else current.orderingKey,
                ),
            )
            return true
        }

        override suspend fun deleteById(id: String) {
            state.value = rows.filterNot { it.id == id }
        }

        override suspend fun deleteByTag(tag: String): List<UploaderItem> {
            val matches: (UploaderItem) -> Boolean = when (bug) {
                Bug.WIPE_IGNORES_CASE -> { item -> item.tag.equals(tag, ignoreCase = true) }
                Bug.WIPE_BY_PREFIX -> { item -> item.tag?.startsWith(tag) == true }
                else -> { item -> item.tag == tag }
            }
            val (removed: List<UploaderItem>, kept: List<UploaderItem>) = rows.partition(matches)
            state.value = kept
            return removed
        }

        override fun observeByType(type: String): Flow<List<UploaderItem>> = when (bug) {
            Bug.OBSERVE_ONE_SHOT -> flow { emit(rows.filter { it.type == type }) }
            Bug.OBSERVE_HIDES_IN_FLIGHT ->
                state.map { all -> all.filter { it.type == type && it.state != UploaderItemState.IN_FLIGHT } }

            else -> state.map { all -> all.filter { it.type == type } }
        }

        override suspend fun clearAll() {
            state.value = emptyList()
        }
    }

    /**
     * The KDoc requires every store function to be callable concurrently, and the contract now checks the one-winner
     * races, so the reference copy takes a lock per call like the real stores do. The mutant under test is inside.
     */
    private class SerializedStore(private val inner: UploaderStore) : UploaderStore {
        private val lock = Mutex()

        override suspend fun insertKeep(record: UploaderItem): Boolean = lock.withLock { inner.insertKeep(record) }
        override suspend fun insertReplace(record: UploaderItem): UploaderItem? = lock.withLock { inner.insertReplace(record) }
        override suspend fun getAllActive(): List<UploaderItem> = lock.withLock { inner.getAllActive() }
        override suspend fun getById(id: String): UploaderItem? = lock.withLock { inner.getById(id) }
        override suspend fun recordFailure(
            id: String,
            attempts: Int,
            nextRunAtEpochMillis: Long,
            lastError: String?,
            expectedState: UploaderItemState,
            expectedClaim: Long,
            disposition: FailureDisposition,
        ): Boolean = lock.withLock {
            inner.recordFailure(id, attempts, nextRunAtEpochMillis, lastError, expectedState, expectedClaim, disposition)
        }

        override suspend fun markInFlight(
            id: String,
            leaseUntilEpochMillis: Long,
            expectedState: UploaderItemState,
            expectedClaim: Long,
        ): Boolean = lock.withLock { inner.markInFlight(id, leaseUntilEpochMillis, expectedState, expectedClaim) }

        override suspend fun park(id: String, lastError: String?, expectedState: UploaderItemState, expectedClaim: Long): Boolean =
            lock.withLock { inner.park(id, lastError, expectedState, expectedClaim) }

        override suspend fun deleteById(id: String) = lock.withLock { inner.deleteById(id) }
        override suspend fun deleteByTag(tag: String): List<UploaderItem> = lock.withLock { inner.deleteByTag(tag) }
        override fun observeByType(type: String): Flow<List<UploaderItem>> = inner.observeByType(type)
        override suspend fun clearAll() = lock.withLock { inner.clearAll() }
    }

    private suspend fun assertRejected(bug: Bug, forbiddenBy: String) {
        val rejected: Boolean = try {
            UploaderStoreContract { SerializedStore(MutantStore(bug)) }.verifyAll()
            false
        } catch (_: UploaderStoreContractViolation) {
            true
        }
        assertTrue(rejected, "the contract accepted a store that breaks: $forbiddenBy")
    }

    @Test
    fun `the unmodified copy passes the contract`() = runTest {
        assertEquals(true, UploaderStoreContract { SerializedStore(MutantStore(Bug.NONE)) }.verifyAll() > 0)
    }

    @Test
    fun `a store ordering by creation time is rejected`() = runTest {
        assertRejected(Bug.ORDER_BY_CREATED_AT, "getAllActive is insertion order, not a timestamp (a clock stepped back)")
    }

    @Test
    fun `a store whose observation flow completes after one emission is rejected`() = runTest {
        assertRejected(Bug.OBSERVE_ONE_SHOT, "observeByType emits on every change and never completes")
    }

    @Test
    fun `a store whose observation hides in flight items is rejected`() = runTest {
        assertRejected(Bug.OBSERVE_HIDES_IN_FLIGHT, "observeByType shows items in any state")
    }

    @Test
    fun `a store that trims the payload is rejected`() = runTest {
        assertRejected(Bug.PAYLOAD_TRIM, "a payload is stored and returned unchanged")
    }

    @Test
    fun `a store that lowercases the payload is rejected`() = runTest {
        assertRejected(Bug.PAYLOAD_LOWERCASE, "a payload is stored and returned unchanged")
    }

    @Test
    fun `a store that lowercases the tag is rejected`() = runTest {
        assertRejected(Bug.TAG_LOWERCASE, "a tag is an opaque label, stored and returned unchanged")
    }

    @Test
    fun `a store that lowercases the unique key is rejected`() = runTest {
        assertRejected(Bug.KEY_LOWERCASE, "the unique key is the dedup identity, compared exactly")
    }

    @Test
    fun `a store whose tag wipe ignores case is rejected`() = runTest {
        assertRejected(Bug.WIPE_IGNORES_CASE, "deleteByTag matches the exact tag (one account's wipe must not take another's)")
    }

    @Test
    fun `a store whose tag wipe matches by prefix is rejected`() = runTest {
        assertRejected(Bug.WIPE_BY_PREFIX, "deleteByTag matches the exact tag, not a prefix")
    }

    @Test
    fun `a store whose claim clears the tag is rejected`() = runTest {
        assertRejected(Bug.MARK_CLEARS_TAG, "markInFlight changes state, lease and claim only (a logout wipe must still find the row)")
    }

    @Test
    fun `a store whose claim clears the last error is rejected`() = runTest {
        assertRejected(Bug.MARK_CLEARS_LAST_ERROR, "markInFlight changes state, lease and claim only")
    }

    @Test
    fun `a store whose failure write clears the tag is rejected`() = runTest {
        assertRejected(Bug.FAILURE_CLEARS_TAG, "recordFailure leaves the tag alone (a logout wipe must still find the row)")
    }

    @Test
    fun `a store whose failure write clears the unique key is rejected`() = runTest {
        assertRejected(Bug.FAILURE_CLEARS_UNIQUE_KEY, "recordFailure leaves the dedup identity alone")
    }

    @Test
    fun `a store whose failure write clears the ordering key is rejected`() = runTest {
        assertRejected(Bug.FAILURE_CLEARS_ORDERING_KEY, "recordFailure leaves the ordering channel alone")
    }

    @Test
    fun `a store whose failure write resets the creation time is rejected`() = runTest {
        assertRejected(Bug.FAILURE_RESETS_CREATED_AT, "recordFailure leaves the creation time alone")
    }

    @Test
    fun `a store whose failure write adds the attempts instead of storing them is rejected`() = runTest {
        assertRejected(Bug.FAILURE_ADDS_ATTEMPTS, "recordFailure stores the absolute attempt count passed in")
    }

    @Test
    fun `a store whose failure write keeps the larger backoff gate is rejected`() = runTest {
        assertRejected(Bug.FAILURE_KEEPS_LARGER_GATE, "recordFailure stores the gate passed in (a clock stepped back writes a smaller one)")
    }

    @Test
    fun `a store whose park clears the tag is rejected`() = runTest {
        assertRejected(Bug.PARK_CLEARS_TAG, "park leaves the tag alone")
    }

    @Test
    fun `a store whose park clears the ordering key is rejected`() = runTest {
        assertRejected(Bug.PARK_CLEARS_ORDERING_KEY, "park leaves the ordering channel alone")
    }

    @Test
    fun `a store that revives a parked key in place instead of at the tail is rejected`() = runTest {
        assertRejected(Bug.KEEP_PARKED_IN_PLACE, "insertKeep over a parked item deletes it and inserts at the tail")
    }

    @Test
    fun `a store whose point read matches by id prefix is rejected`() = runTest {
        assertRejected(Bug.GET_BY_ID_PREFIX, "getById matches the exact id")
    }

    @Test
    fun `a store whose re-hand adds the new lease to the old one is rejected`() = runTest {
        assertRejected(Bug.MARK_ADDS_LEASE, "markInFlight stores the lease passed in")
    }
}
