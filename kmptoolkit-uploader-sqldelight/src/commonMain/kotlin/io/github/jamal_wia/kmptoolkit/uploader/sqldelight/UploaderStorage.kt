package io.github.jamal_wia.kmptoolkit.uploader.sqldelight

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.uploader.sqldelight.db.KmpToolkitUploaderDatabase
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The two SPI implementations `kmptoolkit-uploader` needs, over one SQLite database.
 *
 * They come as a pair rather than as two factory functions because they are only correct together:
 * both confine every statement to the same single thread, and a store and a runner on two different
 * threads would give you a transaction that does not contain the writes made inside it, with
 * nothing reporting the mismatch. Taking them from one object removes that possibility.
 *
 * ```kotlin
 * val storage: UploaderStorage = createUploaderStorage(context)
 * val engine: UploaderEngine = createUploaderEngine(
 *     store = storage.store,
 *     transactionRunner = storage.transactionRunner,
 *     // ...
 * )
 * ```
 *
 * Hold one per queue for the life of the process and [close] it when that queue is done. Creating a
 * second one over the same file is not an error, but it opens a second connection and a second
 * thread for no benefit.
 */
public interface UploaderStorage : AutoCloseable {

    /**
     * The durable queue table, as the SPI's port type.
     *
     * Every write is committed before the call returns. Reads and writes are serialized on one
     * thread, so the store is safe to call from any coroutine on any thread.
     */
    public val store: UploaderStore

    /**
     * A real SQL transaction, and — unlike
     * [TransactionRunner.Direct][io.github.jamal_wia.kmptoolkit.uploader.spi.TransactionRunner.Direct]
     * — the thing that makes this a *transactional* uploader: a domain write and the effect it owes
     * commit together or not at all.
     *
     * That guarantee only extends to writes made through **this** database. If your domain tables
     * live in a different file, wrapping them in this runner buys nothing — see
     * `docs/kmptoolkit-uploader-sqldelight/03-guide.md`, and the `SqlDriver` overload of
     * `createUploaderStorage`, which is how you put the queue in the database you already have.
     */
    public val transactionRunner: TransactionRunner

    /**
     * Releases the database thread, and the connection if this storage opened one.
     *
     * **Blocks** until every statement already in flight has finished. It has to: releasing the
     * thread does not join it, so closing the connection without waiting would close it under a
     * live cursor. In exchange, `close()` returning means the database really is quiet.
     *
     * Idempotent, and safe to call from two threads at once. A storage created from a `SqlDriver`
     * you supplied never closes that driver — it is not this module's to close, and the database it
     * belongs to is probably still in use.
     *
     * Using the storage afterwards is a bug, and the failure it produces comes from the released
     * thread rather than from this module — it is not a documented shape to branch on. Close a
     * storage only when the queue itself is finished with, not on every screen teardown: the queue
     * outlives the UI by design.
     */
    override fun close()
}

/**
 * The queue table's schema — what a consumer embedding the queue in **their own** database has to
 * create and migrate.
 *
 * Only needed for the shared-driver route. The standalone factories create and migrate it for you.
 *
 * ```kotlin
 * // Inside your own database's migration to the version that adds the queue:
 * uploaderDatabaseSchema.create(driver)
 * ```
 *
 * The table is named `kmptoolkit_uploader_item` and its indices carry the same prefix, so nothing it
 * creates can collide with a table of yours. Its version is this module's own and moves with this
 * module's releases; it is not your database's version, and
 * `docs/kmptoolkit-uploader-sqldelight/03-guide.md` explains how the two are reconciled.
 */
public val uploaderDatabaseSchema: SqlSchema<QueryResult.Value<Unit>>
    get() = KmpToolkitUploaderDatabase.Schema

/**
 * Puts the queue in a database **you** own, addressed through [driver].
 *
 * This is the route that makes the uploader genuinely transactional: because the queue rows and your
 * domain rows are in one database behind one driver, a write made inside
 * [UploaderStorage.transactionRunner] commits with the enqueue or not at all.
 *
 * ```kotlin
 * val storage: UploaderStorage = createUploaderStorage(myDriver)
 *
 * storage.transactionRunner.inTransaction {
 *     myQueries.markMessageSending(id)   // your table
 *     uploader.enqueue(SendMessage(id))    // the queue table
 * }
 * ```
 *
 * ### Two things you own
 *
 * - **The schema.** This function does not create the queue table; a `CREATE TABLE` against a
 *   database that already has it would fail, and adding a table to your database is a migration you
 *   have to version. Run [uploaderDatabaseSchema]`.create(driver)` from your own migration — see
 *   `docs/kmptoolkit-uploader-sqldelight/03-guide.md`.
 * - **The driver.** [UploaderStorage.close] releases the database thread but never closes [driver].
 *
 * ### One thing this function requires of you
 *
 * Every statement of this storage runs on a thread of its own, and SQLDelight transactions are
 * confined to the thread that opened them. Your own writes must therefore go through
 * [UploaderStorage.transactionRunner] to share a transaction with an enqueue — running them on
 * another thread produces two independent transactions, which is precisely the window a
 * transactional uploader exists to close.
 *
 * @param driver an open, synchronous `SqlDriver` — `AndroidSqliteDriver` or `NativeSqliteDriver`.
 *   An asynchronous driver is not supported: this module reads query results synchronously, which
 *   is what lets a transaction body be a plain block.
 * @return a storage over [driver], which it will not close.
 */
public fun createUploaderStorage(driver: SqlDriver): UploaderStorage =
    SqlDelightUploaderStorage(driver = driver, ownsDriver = false)

/**
 * The [UploaderStorage] implementation both routes end at.
 *
 * Internal: the public surface is the interface and the factories. The class also owns the
 * confinement thread, which is why it — rather than the store — is what [close] hangs off.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class SqlDelightUploaderStorage(
    private val driver: SqlDriver,
    private val ownsDriver: Boolean,
) : UploaderStorage {

    private val confinement: DatabaseConfinement =
        DatabaseConfinement(createConfinedDatabaseDispatcher(DATABASE_THREAD_NAME))

    private val database: KmpToolkitUploaderDatabase = KmpToolkitUploaderDatabase(driver)

    override val store: UploaderStore = SqlDelightUploaderStore(database, confinement)

    override val transactionRunner: TransactionRunner =
        SqlDelightTransactionRunner(driver, database, confinement)

    /**
     * Atomic rather than a plain flag: [close] is documented as idempotent and callable from any
     * thread, and two concurrent calls on a plain `var` would both get past the guard and close the
     * driver twice.
     */
    private val closed: AtomicBoolean = AtomicBoolean(false)

    override fun close() {
        if (!closed.compareAndSet(expectedValue = false, newValue = true)) return
        // The driver is closed from the database thread, after everything queued ahead of it has
        // run — see DatabaseConfinement.closeAfterDraining for why that ordering is not optional.
        confinement.closeAfterDraining { if (ownsDriver) driver.close() }
    }

    private companion object {
        const val DATABASE_THREAD_NAME: String = "kmptoolkit-uploader-db"
    }
}

/**
 * Opens [driver]'s connection now, so that a failure to create or migrate the schema is reported by
 * the factory that asked for it.
 *
 * Both drivers open lazily. Without this, a corrupt file or a failed migration would surface much
 * later as a failed `insertKeep` — which is true but useless: the consumer would be looking at an
 * enqueue for the cause of a problem that happened at startup.
 *
 * `PRAGMA user_version` is the cheapest statement that forces the connection: it touches no table,
 * so it works on a database whose schema was created a microsecond earlier.
 */
internal fun forceOpen(driver: SqlDriver) {
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { QueryResult.Value(Unit) },
        parameters = 0,
    ).value
}

/**
 * A storage that owns [driver] and closes it with itself — the shape both standalone platform
 * factories produce, once they have built the driver their platform needs.
 */
internal fun standaloneStorage(driver: SqlDriver): UploaderStorage =
    SqlDelightUploaderStorage(driver = driver, ownsDriver = true)
