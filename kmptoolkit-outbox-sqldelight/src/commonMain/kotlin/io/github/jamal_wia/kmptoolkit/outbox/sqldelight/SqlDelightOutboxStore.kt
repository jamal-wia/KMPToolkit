package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import io.github.jamal_wia.kmptoolkit.outbox.OutboxItem
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItemState
import io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.KmpToolkitOutboxDatabase
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.Kmptoolkit_outbox_item
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.OutboxItemQueries
import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The [OutboxStore] contract on one SQLDelight table.
 *
 * Internal: a consumer holds it through [OutboxStorage.store] as the SPI type. Exposing the class
 * would publish the row mapping and the query object, neither of which is a promise this module
 * wants to keep across a schema change.
 *
 * Three parts of this are load-bearing and are documented where they happen: the insertion sequence
 * (`OutboxItem.sq`), the compare-and-set in [recordFailure], and the thread confinement that lets a
 * nested call join an open transaction ([onDatabaseThread]).
 */
internal class SqlDelightOutboxStore(
    private val database: KmpToolkitOutboxDatabase,
    private val confinement: DatabaseConfinement,
) : OutboxStore {

    private val queries: OutboxItemQueries get() = database.outboxItemQueries

    override suspend fun insertKeep(record: OutboxItem): Boolean = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.INSERT_KEEP) {
            // The lookup and the insert have to be one atomic step: between them, another writer
            // could take the identity, and the KEEP decision would then be made against a row that
            // no longer describes the queue.
            database.transactionWithResult {
                val key: String? = record.uniqueKey
                if (key == null) {
                    // A null unique key never conflicts — it is not even in the identity index.
                    queries.insert(record)
                    return@transactionWithResult true
                }
                val holder: String? =
                    queries.selectStateByIdentity(type = record.type, uniqueKey = key)
                        .executeAsOneOrNull()
                when (holder) {
                    // Compared against the enum's own names rather than string constants, so a
                    // renamed state cannot drift apart from what is written into the column. The
                    // matching literals in `selectAllActive` cannot do the same — SQL has no way
                    // to see the enum — which is called out in OutboxItem.sq.
                    OutboxItemState.PENDING.name, OutboxItemState.IN_FLIGHT.name -> false
                    // Covers PARKED and — deliberately — any state string this build does not
                    // recognize, which is what a downgrade past a future state looks like. Neither
                    // is in rotation, so neither may keep the key: a held key with no revive path
                    // would swallow every future enqueue for it.
                    else -> {
                        if (holder != null) {
                            queries.deleteByIdentity(type = record.type, uniqueKey = key)
                        }
                        queries.insert(record)
                        true
                    }
                }
            }
        }
    }

    override suspend fun insertReplace(record: OutboxItem): Unit = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.INSERT_REPLACE) {
            database.transaction {
                // Delete plus insert, never an in-place edit. The replacement takes a fresh id,
                // attempt count, gate, lease and — because `sequence` is autoincrementing — a new
                // position at the tail, so a key that is replaced over and over cannot hold the
                // head of its ordering channel forever.
                record.uniqueKey?.let { key ->
                    queries.deleteByIdentity(type = record.type, uniqueKey = key)
                }
                queries.insert(record)
            }
        }
    }

    override suspend fun getAllActive(): List<OutboxItem> = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.GET_ALL_ACTIVE) {
            queries.selectAllActive().executeAsList().map { row -> row.toOutboxItem() }
        }
    }

    override suspend fun getById(id: String): OutboxItem? = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.GET_BY_ID) {
            queries.selectById(id).executeAsOneOrNull()?.toOutboxItem()
        }
    }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedLeaseUntilEpochMillis: Long?,
    ): Boolean = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.RECORD_FAILURE) {
            // One UPDATE carrying the guard in its WHERE clause, not a read followed by a write:
            // a read-then-write loses exactly the race the guard exists to win, because the lease
            // can move between the two. The transaction is here only to keep the UPDATE and the
            // changes() read on one connection — see `changes` in OutboxItem.sq.
            database.transactionWithResult {
                queries.recordFailure(
                    attempts = attempts.toLong(),
                    nextRunAt = nextRunAtEpochMillis,
                    lastError = lastError,
                    id = id,
                    expectedLease = expectedLeaseUntilEpochMillis,
                )
                queries.changes().executeAsOne() > 0L
            }
        }
    }

    override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long): Unit =
        onDatabaseThread(confinement) {
            mapFailures(OutboxStorageOperation.MARK_IN_FLIGHT) {
                queries.markInFlight(leaseUntil = leaseUntilEpochMillis, id = id)
            }
        }

    override suspend fun park(id: String, lastError: String?): Unit = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.PARK) {
            queries.park(lastError = lastError, id = id)
        }
    }

    override suspend fun deleteById(id: String): Unit = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.DELETE_BY_ID) {
            queries.deleteById(id)
        }
    }

    override suspend fun deleteByTag(tag: String): Unit = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.DELETE_BY_TAG) {
            queries.deleteByTag(tag)
        }
    }

    override fun observeByType(type: String): Flow<List<OutboxItem>> =
        queries.selectByType(type)
            .asFlow()
            // The same thread every write runs on, so an emission can never observe a half-applied
            // transaction, and SQLDelight's own listener bookkeeping stays confined.
            .mapToList(confinement.dispatcher)
            .map { rows -> rows.map { row -> row.toOutboxItem() } }
            .catch { cause ->
                throw if (cause is OutboxStorageException) {
                    cause
                } else {
                    OutboxStorageException(OutboxStorageOperation.OBSERVE_BY_TYPE, cause)
                }
            }

    override suspend fun clearAll(): Unit = onDatabaseThread(confinement) {
        mapFailures(OutboxStorageOperation.CLEAR_ALL) {
            queries.deleteAll()
        }
    }

    private fun OutboxItemQueries.insert(record: OutboxItem) {
        // `sequence` is omitted so SQLite assigns the next one — see OutboxItem.sq.
        insert(
            id = record.id,
            type = record.type,
            payload = record.payload,
            schemaVersion = record.schemaVersion.toLong(),
            uniqueKey = record.uniqueKey,
            orderingKey = record.orderingKey,
            tag = record.tag,
            state = record.state.name,
            attempts = record.attempts.toLong(),
            nextRunAt = record.nextRunAtEpochMillis,
            createdAt = record.createdAtEpochMillis,
            lastError = record.lastError,
            leaseUntil = record.leaseUntilEpochMillis,
        )
    }

    private fun Kmptoolkit_outbox_item.toOutboxItem(): OutboxItem = OutboxItem(
        id = id,
        type = type,
        payload = payload,
        schemaVersion = schema_version.toInt(),
        uniqueKey = unique_key,
        orderingKey = ordering_key,
        tag = tag,
        state = state.toStateOrParked(),
        attempts = attempts.toInt(),
        nextRunAtEpochMillis = next_run_at,
        createdAtEpochMillis = created_at,
        lastError = last_error,
        leaseUntilEpochMillis = lease_until,
    )

    /**
     * A `state` string this build does not know reads back as [OutboxItemState.PARKED] rather than
     * throwing.
     *
     * It is reachable without corruption: a user on a newer build enqueues a row in a state that
     * build added, then downgrades. PARKED is the conservative answer — out of rotation, still
     * visible, nothing lost — and it matches what `selectAllActive` already does, since an
     * unrecognized string satisfies neither literal in its `IN` clause.
     */
    private fun String.toStateOrParked(): OutboxItemState =
        OutboxItemState.entries.firstOrNull { it.name == this } ?: OutboxItemState.PARKED
}
