package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.KmpToolkitUploaderDatabase
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.Kmptoolkit_uploader_item
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.UploaderItemQueries
import app.cash.sqldelight.coroutines.asFlow
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

/**
 * The [UploaderStore] contract on one SQLDelight table.
 *
 * Internal: a consumer holds it through [UploaderStorage.store] as the SPI type, so the mapping
 * between a row and an [UploaderItem] stays this module's business.
 *
 * That is only half true of the generated types it is built on. SQLDelight has no way to generate
 * `internal`, so `KmpToolkitUploaderDatabase`, `UploaderItemQueries` and `Kmptoolkit_uploader_item` are in
 * the published ABI whether or not they are meant to be used — which means a `.sq` change is an ABI
 * change, and a consumer *can* reach past the store into the table. Neither is intended; both are
 * stated plainly in `docs/kmptoolkit-uploader-sqldelight/04-api-reference.md` rather than papered over.
 *
 * Three parts of this are load-bearing and are documented where they happen: the insertion sequence
 * (`UploaderItem.sq`), the compare-and-set in [recordFailure], and the thread confinement that lets a
 * nested call join an open transaction ([onDatabaseThread]).
 */
internal class SqlDelightUploaderStore(
    private val database: KmpToolkitUploaderDatabase,
    private val confinement: DatabaseConfinement,
) : UploaderStore {

    private val queries: UploaderItemQueries get() = database.uploaderItemQueries

    override suspend fun insertKeep(record: UploaderItem): Boolean = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.INSERT_KEEP) {
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
                    // to see the enum — which is called out in UploaderItem.sq.
                    UploaderItemState.PENDING.name, UploaderItemState.IN_FLIGHT.name -> false
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

    override suspend fun insertReplace(record: UploaderItem): Unit = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.INSERT_REPLACE) {
            val key: String? = record.uniqueKey
            if (key == null) {
                // Nothing to supersede — a keyless item conflicts with nothing — so this is a plain
                // append and needs no transaction to be atomic.
                queries.insert(record)
            } else {
                // Delete plus insert, never an in-place edit. The replacement takes a fresh id,
                // attempt count, gate, lease and — because `sequence` is autoincrementing — a new
                // position at the tail, so a key that is replaced over and over cannot hold the
                // head of its ordering channel forever.
                database.transaction {
                    queries.deleteByIdentity(type = record.type, uniqueKey = key)
                    queries.insert(record)
                }
            }
        }
    }

    override suspend fun getAllActive(): List<UploaderItem> = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.GET_ALL_ACTIVE) {
            queries.selectAllActive().executeAsList().map { row -> row.toUploaderItem() }
        }
    }

    override suspend fun getById(id: String): UploaderItem? = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.GET_BY_ID) {
            queries.selectById(id).executeAsOneOrNull()?.toUploaderItem()
        }
    }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedLeaseUntilEpochMillis: Long?,
    ): Boolean = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.RECORD_FAILURE) {
            // One UPDATE carrying the guard in its WHERE clause, not a read followed by a write:
            // a read-then-write loses exactly the race the guard exists to win, because the lease
            // can move between the two.
            //
            // The statement's own affected-row count answers "did a row change?", so there is no
            // second query and no transaction to keep two statements on one connection. That
            // matters beyond tidiness: `SELECT changes()` outside a transaction can be served by a
            // reader connection — where it is always 0 — on both of this module's drivers.
            queries.recordFailure(
                attempts = attempts.toLong(),
                nextRunAt = nextRunAtEpochMillis,
                lastError = lastError,
                id = id,
                expectedLease = expectedLeaseUntilEpochMillis,
            ).value > 0L
        }
    }

    override suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long): Unit =
        onDatabaseThread(confinement) {
            mapFailures(UploaderStorageOperation.MARK_IN_FLIGHT) {
                queries.markInFlight(leaseUntil = leaseUntilEpochMillis, id = id)
            }
        }

    override suspend fun park(id: String, lastError: String?): Unit = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.PARK) {
            queries.park(lastError = lastError, id = id)
        }
    }

    override suspend fun deleteById(id: String): Unit = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.DELETE_BY_ID) {
            queries.deleteById(id)
        }
    }

    override suspend fun deleteByTag(tag: String): Unit = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.DELETE_BY_TAG) {
            queries.deleteByTag(tag)
        }
    }

    override fun observeByType(type: String): Flow<List<UploaderItem>> =
        queries.selectByType(type)
            .asFlow()
            // `onDatabaseThread` rather than coroutines-extensions' `mapToList(dispatcher)`: both
            // run the query on the thread every write runs on — so an emission can never observe a
            // half-applied transaction — but only this one recognizes that it may already be there.
            // A plain dispatch would deadlock when the flow is collected from inside a transaction,
            // because the thread it dispatches to is the thread that transaction is occupying.
            .map { query -> onDatabaseThread(confinement) { query.executeAsList() } }
            .map { rows -> rows.map { row -> row.toUploaderItem() } }
            .catch { cause ->
                throw when {
                    // Not a database failure — rewrapping it would make a cancelled collector look
                    // like a broken queue to every catch up the stack.
                    cause is CancellationException -> cause
                    cause is UploaderStorageException -> cause
                    else -> UploaderStorageException(UploaderStorageOperation.OBSERVE_BY_TYPE, cause)
                }
            }

    override suspend fun clearAll(): Unit = onDatabaseThread(confinement) {
        mapFailures(UploaderStorageOperation.CLEAR_ALL) {
            queries.deleteAll()
        }
    }

    private fun UploaderItemQueries.insert(record: UploaderItem) {
        // `sequence` is omitted so SQLite assigns the next one — see UploaderItem.sq.
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

    private fun Kmptoolkit_uploader_item.toUploaderItem(): UploaderItem = UploaderItem(
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
     * A `state` string this build does not know reads back as [UploaderItemState.PARKED] rather than
     * throwing.
     *
     * It is reachable without corruption: a user on a newer build enqueues a row in a state that
     * build added, then downgrades. PARKED is the conservative answer — out of rotation, still
     * visible, nothing lost — and it matches what `selectAllActive` already does, since an
     * unrecognized string satisfies neither literal in its `IN` clause.
     */
    private fun String.toStateOrParked(): UploaderItemState =
        UploaderItemState.entries.firstOrNull { it.name == this } ?: UploaderItemState.PARKED
}
