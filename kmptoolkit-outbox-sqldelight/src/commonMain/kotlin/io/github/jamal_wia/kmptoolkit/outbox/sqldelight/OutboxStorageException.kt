package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import kotlin.coroutines.cancellation.CancellationException

/**
 * A database operation behind the outbox store failed.
 *
 * ### Why this type exists
 *
 * `OutboxStore` has no result type — every function returns a plain value, so the SPI has no
 * channel through which a store can report "the disk is full" or "the file is corrupt". That leaves
 * exactly two honest options, and swallowing the failure is not one of them: a write that silently
 * did not happen breaks the durability promise `enqueue` makes to its caller, and the engine would
 * go on believing the effect is queued.
 *
 * So a failure propagates — but as **this** type, never as the platform's own. A raw
 * `android.database.sqlite.SQLiteException` or a Darwin `sqlite3` error crossing the SPI would make
 * every consumer's error handling platform-specific, in a module whose entire purpose is to keep
 * shared code from being.
 *
 * ### What it is not
 *
 * Not a message to display. [operation] and [cause] are diagnostics; deciding what a user sees when
 * their queue cannot be written is the app's job, as everywhere else in this toolkit.
 *
 * ### What does not become one
 *
 * [CancellationException] passes through untouched. Wrapping it would break structured concurrency
 * — a cancelled coroutine would look like a database fault to every `catch` up the stack.
 *
 * @property operation which store function was running. The coarse label is deliberate: a consumer
 *   can branch on "a write failed" versus "a read failed" without this module promising a taxonomy
 *   of SQLite result codes it would then have to keep stable.
 */
public class OutboxStorageException internal constructor(
    public val operation: OutboxStorageOperation,
    override val cause: Throwable?,
) : RuntimeException("outbox storage operation $operation failed", cause)

/**
 * The store function an [OutboxStorageException] came out of. Mirrors
 * [OutboxStore][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore]'s own surface, plus the two
 * entries that are not store functions at all.
 */
public enum class OutboxStorageOperation {

    /** [OutboxStore.insertKeep][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.insertKeep]. */
    INSERT_KEEP,

    /** [OutboxStore.insertReplace][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.insertReplace]. */
    INSERT_REPLACE,

    /** [OutboxStore.getAllActive][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.getAllActive]. */
    GET_ALL_ACTIVE,

    /** [OutboxStore.getById][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.getById]. */
    GET_BY_ID,

    /** [OutboxStore.recordFailure][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.recordFailure]. */
    RECORD_FAILURE,

    /** [OutboxStore.markInFlight][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.markInFlight]. */
    MARK_IN_FLIGHT,

    /** [OutboxStore.park][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.park]. */
    PARK,

    /** [OutboxStore.deleteById][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.deleteById]. */
    DELETE_BY_ID,

    /** [OutboxStore.deleteByTag][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.deleteByTag]. */
    DELETE_BY_TAG,

    /** [OutboxStore.observeByType][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.observeByType]. */
    OBSERVE_BY_TYPE,

    /** [OutboxStore.clearAll][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.clearAll]. */
    CLEAR_ALL,

    /**
     * [TransactionRunner.inTransaction][io.github.jamal_wia.kmptoolkit.outbox.spi.TransactionRunner.inTransaction]
     * — the transaction itself failed to begin, commit or roll back.
     *
     * An exception thrown by the *block* is not wrapped: it is the caller's own, and rewriting it
     * would hide what actually went wrong. Only the transaction machinery's own failure lands here.
     */
    TRANSACTION,

    /** Opening the database, creating its schema, or migrating it. */
    OPEN,
}

/**
 * Runs [block], turning any platform database failure into an [OutboxStorageException].
 *
 * The `CancellationException` re-throw is the part that matters: `runCatching` and a bare
 * `catch (t: Throwable)` both swallow it, which turns a cancelled drain into a coroutine that
 * quietly keeps going.
 */
internal inline fun <R> mapFailures(operation: OutboxStorageOperation, block: () -> R): R = try {
    block()
} catch (cancellation: CancellationException) {
    throw cancellation
} catch (storage: OutboxStorageException) {
    // Already ours — re-wrapping would bury the operation that actually failed under the outer one.
    throw storage
} catch (failure: Throwable) {
    throw OutboxStorageException(operation, failure)
}
