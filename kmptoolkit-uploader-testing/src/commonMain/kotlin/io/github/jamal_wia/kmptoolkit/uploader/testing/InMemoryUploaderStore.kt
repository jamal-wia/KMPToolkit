package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A complete [UploaderStore] backed by a list in memory — durable for exactly as long as the object
 * lives.
 *
 * It exists for two jobs:
 *
 * - **Running the real engine in a test.** Retry pacing, ordering channels, lease expiry and the
 *   settle races are all engine behavior, and testing them against a real store means a database,
 *   a driver and a temp directory. Against this one they are a `runTest` block.
 * - **Being a reference implementation.** It is the shortest complete answer to
 *   `docs/kmptoolkit-uploader/07-custom-store.md`, and passes
 *   [AbstractUploaderStoreContractTest] unmodified.
 *
 * It is *not* a fake in the loose sense: it implements the SPI contract exactly, including
 * insertion ordering, the parked-conflict rule, the compare-and-set in
 * [recordFailure], and no-op-on-absent-row everywhere. A test that passes against this store and
 * fails against your database has found a bug in your store, which is the point.
 *
 * Thread-safe: every mutation runs under a [Mutex], so concurrent enqueues and settles behave the
 * way they would against a serialized database writer.
 *
 * @param initialItems items to start with, in insertion order — a convenient way to simulate "the
 *   previous process left this behind".
 */
public class InMemoryUploaderStore(
    initialItems: List<UploaderItem> = emptyList(),
) : UploaderStore {

    private val mutex = Mutex()

    private val state: MutableStateFlow<List<UploaderItem>> = MutableStateFlow(initialItems.toList())

    /**
     * Everything currently stored, in insertion order and in every state — the assertion surface a
     * test wants.
     *
     * Reading it is non-suspending, and it emits on every change.
     */
    public val items: StateFlow<List<UploaderItem>> = state

    /** A snapshot of [items] right now. */
    public fun snapshot(): List<UploaderItem> = state.value

    override suspend fun insertKeep(record: UploaderItem): Boolean = mutex.withLock {
        val existing: UploaderItem? = findConflict(record)
        when (existing?.state) {
            // An in-flight delivery must win over a re-enqueue exactly like a waiting one.
            UploaderItemState.PENDING, UploaderItemState.IN_FLIGHT -> false
            // A parked item has no other revive path, so a fresh enqueue supersedes it — otherwise
            // its unique key would be permanently dead.
            UploaderItemState.PARKED -> {
                state.value = state.value.filterNot { it.id == existing.id } + record
                true
            }

            null -> {
                state.value = state.value + record
                true
            }
        }
    }

    override suspend fun insertReplace(record: UploaderItem): Unit = mutex.withLock {
        val existing: UploaderItem? = findConflict(record)
        // Delete-and-append, never an in-place edit: the replacement enters at the tail of the
        // insertion sequence, so a repeatedly replaced key cannot hold its channel's head forever.
        state.value = state.value.filterNot { it.id == existing?.id } + record
    }

    override suspend fun getAllActive(): List<UploaderItem> = state.value.filter {
        it.state == UploaderItemState.PENDING || it.state == UploaderItemState.IN_FLIGHT
    }

    override suspend fun getById(id: String): UploaderItem? = state.value.firstOrNull { it.id == id }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedLeaseUntilEpochMillis: Long?,
    ): Boolean = mutex.withLock {
        val current: UploaderItem = state.value.firstOrNull { it.id == id } ?: return@withLock false
        // The optimistic guard: a lease that moved since the caller read it means the drain
        // re-handed this item, and the stale report must not clobber the fresh claim.
        if (expectedLeaseUntilEpochMillis != null &&
            current.leaseUntilEpochMillis != expectedLeaseUntilEpochMillis
        ) {
            return@withLock false
        }
        replace(
            current.copy(
                state = UploaderItemState.PENDING,
                attempts = attempts,
                nextRunAtEpochMillis = nextRunAtEpochMillis,
                lastError = lastError,
                leaseUntilEpochMillis = 0L,
            ),
        )
        true
    }

    override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long): Unit = mutex.withLock {
        val current: UploaderItem = state.value.firstOrNull { it.id == id } ?: return@withLock
        replace(
            current.copy(
                state = UploaderItemState.IN_FLIGHT,
                leaseUntilEpochMillis = leaseUntilEpochMillis,
            ),
        )
    }

    override suspend fun park(id: String, lastError: String?): Unit = mutex.withLock {
        val current: UploaderItem = state.value.firstOrNull { it.id == id } ?: return@withLock
        replace(
            current.copy(
                state = UploaderItemState.PARKED,
                lastError = lastError,
                leaseUntilEpochMillis = 0L,
            ),
        )
    }

    override suspend fun deleteById(id: String): Unit = mutex.withLock {
        state.value = state.value.filterNot { it.id == id }
    }

    override suspend fun deleteByTag(tag: String): Unit = mutex.withLock {
        state.value = state.value.filterNot { it.tag == tag }
    }

    override fun observeByType(type: String): Flow<List<UploaderItem>> =
        state.map { items -> items.filter { it.type == type } }

    override suspend fun clearAll(): Unit = mutex.withLock {
        state.value = emptyList()
    }

    /** The queued item sharing [record]'s dedup identity, or `null`. A null key never conflicts. */
    private fun findConflict(record: UploaderItem): UploaderItem? {
        val key: String = record.uniqueKey ?: return null
        return state.value.firstOrNull { it.type == record.type && it.uniqueKey == key }
    }

    /** Replaces an item **in place**, preserving its position in the insertion sequence. */
    private fun replace(updated: UploaderItem) {
        state.value = state.value.map { if (it.id == updated.id) updated else it }
    }
}
