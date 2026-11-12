package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
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
 * (`UploaderItem.sq`), the compare-and-sets in [recordFailure], [markInFlight] and [park], and the thread confinement that lets a
 * nested call join an open transaction ([onDatabaseThread]).
 */
internal class SqlDelightUploaderStore(
    private val database: KmpToolkitUploaderDatabase,
    private val confinement: DatabaseConfinement,
) : UploaderStore {

    private val queries: UploaderItemQueries get() = database.uploaderItemQueries

    override suspend fun insertKeep(record: UploaderItem): KeepResult = onDatabaseThread(confinement, UploaderStorageOperation.INSERT_KEEP) {
        mapFailures(UploaderStorageOperation.INSERT_KEEP) {
            record.requireWithinRowLimit()
            // The lookup and the insert have to be one atomic step: between them, another writer
            // could take the identity, and the KEEP decision would then be made against a row that
            // no longer describes the queue.
            database.transactionWithResult {
                val uniqueKey: String? = record.uniqueKey
                if (uniqueKey == null) {
                    // A null unique key never conflicts — it is not even in the identity index.
                    queries.insert(record)
                    return@transactionWithResult KeepResult.Inserted(null)
                }
                // One identity for every ordinary text, up to four for a type or key that starts like a stored form
                // (see `spellings`): a 4.0.0 row and a 4.1.0 row of one logical identity must both be seen.
                val holders: List<Pair<StoredIdentity, Kmptoolkit_uploader_item>> = identitiesOf(record.type, uniqueKey).mapNotNull { identity ->
                    queries.selectByIdentity(type = identity.type, uniqueKey = identity.uniqueKey)
                        .executeAsOneOrNull()
                        ?.let { row -> identity to row }
                }
                // Compared against the enum's own names rather than string constants, so a
                // renamed state cannot drift apart from what is written into the column. The
                // matching literals in `selectAllActive` cannot do the same — SQL has no way
                // to see the enum — which is called out in UploaderItem.sq.
                if (holders.any { (_, row) -> row.state == UploaderItemState.PENDING.name || row.state == UploaderItemState.IN_FLIGHT.name }) {
                    KeepResult.Kept
                } else {
                    // Covers PARKED and — deliberately — any state string this build does not
                    // recognize, which is what a downgrade past a future state looks like. Neither
                    // is in rotation, so neither may keep the key: a held key with no revive path
                    // would swallow every future enqueue for it.
                    //
                    // The row handed back is the parked one the insert removed (the engine withdraws it, so its upload is
                    // cancelled). Residual, as for insertReplace: more than one row of one logical identity exists only if
                    // 4.0.0 stored both a raw text and its escaped form as keys; the earliest parked one is returned, the
                    // others, and a row of an unrecognized state (which cannot be mapped), are removed unreturned.
                    val superseded: UploaderItem? = holders
                        .map { (_, row) -> row }
                        .filter { row -> row.state == UploaderItemState.PARKED.name }
                        .minByOrNull { row -> row.sequence }
                        ?.toUploaderItem()
                    holders.forEach { (identity, _) ->
                        queries.deleteByIdentity(type = identity.type, uniqueKey = identity.uniqueKey)
                    }
                    queries.insert(record)
                    KeepResult.Inserted(superseded)
                }
            }
        }
    }

    override suspend fun insertReplace(record: UploaderItem): UploaderItem? = onDatabaseThread(confinement, UploaderStorageOperation.INSERT_REPLACE) {
        mapFailures(UploaderStorageOperation.INSERT_REPLACE) {
            record.requireWithinRowLimit()
            val uniqueKey: String? = record.uniqueKey
            if (uniqueKey == null) {
                // Nothing to supersede — a keyless item conflicts with nothing — so this is a plain
                // append and needs no transaction to be atomic.
                queries.insert(record)
                null
            } else {
                // Delete plus insert, never an in-place edit. The replacement takes a fresh id,
                // attempt count, gate, lease and — because `sequence` is autoincrementing — a new
                // position at the tail, so a key that is replaced over and over cannot hold the
                // head of its ordering channel forever. The superseded row is read in the same
                // transaction, so what is handed back is exactly what the delete removed.
                //
                // A marker-prefixed type or key is looked up under every spelling (see `spellings`). Residual: two live
                // rows of one logical identity exist only if 4.0.0 stored both a raw text and its escaped form as
                // keys; the earliest is returned and cancelled by the engine, the other is removed unreturned.
                database.transactionWithResult {
                    val identities: List<StoredIdentity> = identitiesOf(record.type, uniqueKey)
                    val superseded: UploaderItem? = identities
                        .mapNotNull { identity ->
                            queries.selectByIdentity(type = identity.type, uniqueKey = identity.uniqueKey).executeAsOneOrNull()
                        }
                        .minByOrNull { row -> row.sequence }
                        ?.toUploaderItem()
                    identities.forEach { identity -> queries.deleteByIdentity(type = identity.type, uniqueKey = identity.uniqueKey) }
                    queries.insert(record)
                    superseded
                }
            }
        }
    }

    override suspend fun getAllActive(): List<UploaderItem> = onDatabaseThread(confinement, UploaderStorageOperation.GET_ALL_ACTIVE) {
        mapFailures(UploaderStorageOperation.GET_ALL_ACTIVE) {
            queries.selectAllActive().executeAsList().map { row -> row.toUploaderItem() }
        }
    }

    override suspend fun getById(id: String): UploaderItem? = onDatabaseThread(confinement, UploaderStorageOperation.GET_BY_ID) {
        mapFailures(UploaderStorageOperation.GET_BY_ID) {
            queries.selectById(queries.storedId(id)).executeAsOneOrNull()?.toUploaderItem()
        }
    }

    override suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
        disposition: FailureDisposition,
    ): Boolean = onDatabaseThread(confinement, UploaderStorageOperation.RECORD_FAILURE) {
        mapFailures(UploaderStorageOperation.RECORD_FAILURE) {
            // One statement carrying the guard in its WHERE clause, not a read followed by a write:
            // a read-then-write loses exactly the race the guard exists to win, because the state
            // or the claim can move between the two. A give-up is that same one statement — an UPDATE to PARKED,
            // or a DELETE — so the row is never observable as PENDING once its budget is spent.
            //
            // The statement's own affected-row count answers "did a row change?", so there is no
            // second query and no transaction to keep two statements on one connection. That
            // matters beyond tidiness: `SELECT changes()` outside a transaction can be served by a
            // reader connection — where it is always 0 — on both of this module's drivers.
            val changed: Long = when (disposition) {
                FailureDisposition.DROP -> queries.dropOnFailure(
                    id = queries.storedId(id),
                    expectedState = expectedState.name,
                    expectedClaim = expectedClaim,
                ).value

                FailureDisposition.RETRY, FailureDisposition.PARK -> database.transactionWithResult {
                    queries.recordFailure(
                        state = if (disposition == FailureDisposition.PARK) {
                            UploaderItemState.PARKED.name
                        } else {
                            UploaderItemState.PENDING.name
                        },
                        attempts = attempts.toLong(),
                        nextRunAt = nextRunAtEpochMillis,
                        lastError = toStoredTextOrNull(fittedLastError(id, lastError)),
                        id = queries.storedId(id),
                        expectedState = expectedState.name,
                        expectedClaim = expectedClaim,
                    ).value
                }
            }
            changed > 0L
        }
    }

    override suspend fun markInFlight(
        id: String,
        leaseUntilEpochMillis: Long,
        expectedState: UploaderItemState,
        expectedClaim: Long,
    ): Boolean = onDatabaseThread(confinement, UploaderStorageOperation.MARK_IN_FLIGHT) {
        mapFailures(UploaderStorageOperation.MARK_IN_FLIGHT) {
            // The same shape as recordFailure: the guard lives in the WHERE clause and the
            // affected-row count is the answer, so the claim is one atomic step.
            queries.markInFlight(
                leaseUntil = leaseUntilEpochMillis,
                id = queries.storedId(id),
                expectedState = expectedState.name,
                expectedClaim = expectedClaim,
            ).value > 0L
        }
    }

    override suspend fun park(
        id: String,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
    ): Boolean = onDatabaseThread(confinement, UploaderStorageOperation.PARK) {
        mapFailures(UploaderStorageOperation.PARK) {
            // Guarded like markInFlight: the WHERE clause is the guard, the affected-row count the answer.
            database.transactionWithResult {
                queries.park(
                    lastError = toStoredTextOrNull(fittedLastError(id, lastError)),
                    id = queries.storedId(id),
                    expectedState = expectedState.name,
                    expectedClaim = expectedClaim,
                ).value > 0L
            }
        }
    }

    /**
     * [lastError] cut to what the row of [id] has room for, so a failure text written after the insert can never
     * take a row past the limit the insert enforced. Read inside the transaction that writes it, so the room is
     * computed from the very row the update then changes.
     */
    private fun fittedLastError(id: String, lastError: String?): String? {
        if (lastError == null) return null
        val row: UploaderItem = queries.selectById(queries.storedId(id)).executeAsOneOrNull()?.toUploaderItem() ?: return lastError
        return row.fitLastError(lastError)
    }

    override suspend fun deleteById(id: String): Unit = onDatabaseThread(confinement, UploaderStorageOperation.DELETE_BY_ID) {
        mapFailures(UploaderStorageOperation.DELETE_BY_ID) {
            queries.deleteById(queries.storedId(id))
        }
    }

    override suspend fun deleteByTag(tag: String): List<UploaderItem> = onDatabaseThread(confinement, UploaderStorageOperation.DELETE_BY_TAG) {
        mapFailures(UploaderStorageOperation.DELETE_BY_TAG) {
            // A read and a delete in one transaction, so the rows handed back are exactly the rows
            // removed: an enqueue landing between two separate statements would be deleted without
            // being returned, and its upload never cancelled. (`DELETE … RETURNING` would do it in
            // one statement, but needs SQLite 3.35 — see recordFailure.)
            database.transactionWithResult {
                val rows: List<Kmptoolkit_uploader_item> = spellings(tag).flatMap { stored -> queries.selectByTag(stored).executeAsList() }
                spellings(tag).forEach { stored -> queries.deleteByTag(stored) }
                rows.sortedBy { row -> row.sequence }.map { row -> row.toUploaderItem() }
            }
        }
    }

    override fun observeByType(type: String): Flow<List<UploaderItem>> {
        val spellings: List<String> = spellings(type)
        val extraSpellings: String? = spellings.getOrNull(1)
        return queries.selectByType(spellings[0])
            .asFlow()
            // `onDatabaseThread` rather than coroutines-extensions' `mapToList(dispatcher)`: both
            // run the query on the thread every write runs on — so an emission can never observe a
            // half-applied transaction — but only this one recognizes that it may already be there.
            // A plain dispatch would deadlock when the flow is collected from inside a transaction,
            // because the thread it dispatches to is the thread that transaction is occupying.
            // Every spelling of a marker-prefixed type is read inside the one block, so no write can interleave; the
            // notification is per table, so the first spelling's query is trigger enough.
            .map { query ->
                onDatabaseThread(confinement, UploaderStorageOperation.OBSERVE_BY_TYPE) {
                    val rows: List<Kmptoolkit_uploader_item> = query.executeAsList()
                    if (extraSpellings == null) rows else (rows + queries.selectByType(extraSpellings).executeAsList()).sortedBy { row -> row.sequence }
                }
            }
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
    }

    override suspend fun clearAll(): Unit = onDatabaseThread(confinement, UploaderStorageOperation.CLEAR_ALL) {
        mapFailures(UploaderStorageOperation.CLEAR_ALL) {
            queries.deleteAll()
        }
    }

    /**
     * The text the id column of the row of [id] holds. It is [toStoredText] of [id], except for a row an earlier version
     * stored under an id that merely starts like a stored form: that row holds the id as it is (see [fromStoredText]), and
     * is reached by it. Costs a read only for an id that starts with a marker.
     */
    private fun UploaderItemQueries.storedId(id: String): String {
        val stored: String = toStoredText(id)
        if (stored == id || !startsWithStoredFormMarker(id)) return stored
        if (selectById(stored).executeAsOneOrNull() != null) return stored
        return if (fromStoredText(id) == id && selectById(id).executeAsOneOrNull() != null) id else stored
    }

    /** One (type, unique key) pair as the table holds it. */
    private class StoredIdentity(val type: String, val uniqueKey: String)

    /** Every way the table can hold the identity of [type] and [uniqueKey]: the product of both texts' spellings. */
    private fun identitiesOf(type: String, uniqueKey: String): List<StoredIdentity> =
        spellings(type).flatMap { storedType -> spellings(uniqueKey).map { storedKey -> StoredIdentity(storedType, storedKey) } }

    /**
     * Every text the column of [text] can hold, the one a 4.1.0 write stores first: [toStoredText] of it, then [text] itself
     * when a row an earlier version stored under it reads back as it (see [fromStoredText]). That is the condition [storedId]
     * uses for an id: a text that starts like a stored form and is not exactly one. A text that is exactly an escaped form
     * has no second spelling, its raw row reads back as the text it decodes to. Every other text has one spelling.
     */
    private fun spellings(text: String): List<String> {
        val stored: String = toStoredText(text)
        return if (startsWithStoredFormMarker(text) && fromStoredText(text) == text) listOf(stored, text) else listOf(stored)
    }

    private fun UploaderItemQueries.insert(record: UploaderItem) {
        // `sequence` is omitted so SQLite assigns the next one — see UploaderItem.sq.
        insert(
            id = toStoredText(record.id),
            type = toStoredText(record.type),
            payload = toStoredText(record.payload),
            schemaVersion = record.schemaVersion.toLong(),
            uniqueKey = toStoredTextOrNull(record.uniqueKey),
            orderingKey = toStoredTextOrNull(record.orderingKey),
            tag = toStoredTextOrNull(record.tag),
            state = record.state.name,
            attempts = record.attempts.toLong(),
            nextRunAt = record.nextRunAtEpochMillis,
            createdAt = record.createdAtEpochMillis,
            lastError = toStoredTextOrNull(record.lastError),
            leaseUntil = record.leaseUntilEpochMillis,
            claim = record.claim,
        )
    }

    private fun Kmptoolkit_uploader_item.toUploaderItem(): UploaderItem = UploaderItem(
        id = fromStoredText(id),
        type = fromStoredText(type),
        payload = fromStoredText(payload),
        schemaVersion = schema_version.toInt(),
        uniqueKey = fromStoredTextOrNull(unique_key),
        orderingKey = fromStoredTextOrNull(ordering_key),
        tag = fromStoredTextOrNull(tag),
        state = state.toStateOrParked(),
        attempts = attempts.toInt(),
        nextRunAtEpochMillis = next_run_at,
        createdAtEpochMillis = created_at,
        lastError = fromStoredTextOrNull(last_error),
        leaseUntilEpochMillis = lease_until,
        claim = claim,
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
