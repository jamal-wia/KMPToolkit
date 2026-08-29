package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState

/**
 * A driver over a database that exists only for this test.
 *
 * One expect for every case, taking the schema rather than assuming this module's: the shared-driver
 * route has to be testable too, and that starts from a database whose schema is somebody else's.
 *
 * @param schema created when the database is new and migrated when it is older.
 * @param name a file name, or `null` for in-memory. In-memory is right wherever a check only needs a
 *   fresh empty database; it is wrong wherever durability is the thing being claimed, because
 *   reopening an in-memory database gives you a different, empty one and the check would pass for a
 *   store that persisted nothing.
 */
internal expect fun createDriver(
    schema: SqlSchema<QueryResult.Value<Unit>>,
    name: String?,
): SqlDriver

/** Removes the file [createDriver] opened, if the platform can. */
internal expect fun deleteDatabaseFile(name: String)

/**
 * A storage over a fresh, empty, in-memory queue — what
 * [UploaderStoreContract][io.github.jamal_wia.kmptoolkit.uploader.testing.UploaderStoreContract] requires
 * of its factory.
 */
internal fun createInMemoryUploaderStorage(): UploaderStorage =
    standaloneStorage(createDriver(uploaderDatabaseSchema, name = null))

/**
 * A storage over a real file, so a check can close it and open it again.
 *
 * @param name a name unique to the check using it; two checks sharing one would see each other's
 *   rows.
 */
internal fun createFileUploaderStorage(name: String): UploaderStorage =
    standaloneStorage(createDriver(uploaderDatabaseSchema, name))

/**
 * A schema that creates nothing — a stand-in for a consumer's own database in the checks that
 * exercise adding the queue table to a database this module did not create.
 */
internal val emptySchema: SqlSchema<QueryResult.Value<Unit>> =
    object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Unit
        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: app.cash.sqldelight.db.AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Unit
    }

/**
 * A file name no other run of this check will pick.
 *
 * Not a fixed name: a leftover file from a failed run would otherwise make the *next* run start with
 * rows it did not write, and the failure would look like a store bug.
 */
internal fun uniqueDatabaseName(prefix: String): String =
    "$prefix-${kotlin.random.Random.nextLong(from = 0L, until = Long.MAX_VALUE)}.db"

/**
 * An [UploaderItem] with defaults, so a check names only the fields it cares about.
 *
 * Deliberately not `UploaderStoreContract.item`: that one belongs to the contract and its defaults are
 * the contract's to change.
 */
internal fun anItem(
    id: String,
    type: String = "checks.type",
    uniqueKey: String? = null,
    orderingKey: String? = null,
    tag: String? = null,
    attempts: Int = 0,
    nextRunAtEpochMillis: Long = 0L,
    createdAtEpochMillis: Long = 0L,
): UploaderItem = UploaderItem(
    id = id,
    type = type,
    payload = "payload-$id",
    schemaVersion = 1,
    uniqueKey = uniqueKey,
    orderingKey = orderingKey,
    tag = tag,
    state = UploaderItemState.PENDING,
    attempts = attempts,
    nextRunAtEpochMillis = nextRunAtEpochMillis,
    createdAtEpochMillis = createdAtEpochMillis,
    lastError = null,
    leaseUntilEpochMillis = 0L,
)
