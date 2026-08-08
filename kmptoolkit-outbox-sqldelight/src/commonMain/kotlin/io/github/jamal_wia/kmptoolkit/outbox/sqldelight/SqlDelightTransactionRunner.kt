package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import app.cash.sqldelight.db.SqlDriver
import io.github.jamal_wia.kmptoolkit.outbox.spi.TransactionRunner
import io.github.jamal_wia.kmptoolkit.outbox.sqldelight.db.KmpToolkitOutboxDatabase
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.withContext

/**
 * One real SQL transaction per [inTransaction], joined rather than nested by a re-entrant call.
 *
 * ### Reentrancy
 *
 * The SPI requires a nested `inTransaction` to **join** the outer one. This implementation does not
 * rely on SQLDelight's own nesting to get that: it joins when the caller is confined to this
 * storage's thread *and* the driver reports an open transaction, and simply runs the block. That
 * is stricter than SQLDelight's behavior in the way that matters — SQLDelight would open an inner
 * transaction whose rollback semantics differ from a plain join (an inner failure marks the outer
 * one unsuccessful, so it can roll back writes the caller believed were committed) — and it is also
 * what makes the check work for the case the engine actually produces: a consumer's
 * `inTransaction { domainWrite(); outbox.enqueue(...) }`, where `enqueue` opens a transaction of its
 * own several frames down.
 *
 * ### Why the block is bridged rather than passed through
 *
 * `TransactionRunner.inTransaction` takes a **suspending** block; SQLDelight's `Transacter`
 * transaction body is not suspending, and the transaction object is confined to the thread that
 * opened it. The bridge below runs the block on that same thread with an event loop, so a
 * suspension inside it resumes there instead of migrating to another thread and losing the
 * transaction.
 *
 * In practice nothing in this module's own path suspends once it is on the database thread — every
 * store call re-entered under the marker runs straight through, so the event loop never actually
 * parks. A consumer who awaits something genuinely slow inside `inTransaction` — a network call —
 * will hold the database thread and the SQLite write lock for its duration. That is a bad idea in
 * any database, which is why it is documented in
 * `docs/kmptoolkit-outbox-sqldelight/03-guide.md` rather than defended against here.
 *
 * ### Failure
 *
 * A throwing block rolls the transaction back and propagates unchanged — including
 * `CancellationException`, so a cancelled coroutine never leaves a half-applied transaction behind
 * and never looks like a database fault. Only a failure of the transaction machinery itself becomes
 * an [OutboxStorageException].
 */
internal class SqlDelightTransactionRunner(
    private val driver: SqlDriver,
    private val database: KmpToolkitOutboxDatabase,
    private val confinement: DatabaseConfinement,
) : TransactionRunner {

    override suspend fun <R> inTransaction(block: suspend () -> R): R {
        if (confinement.isReentrant(coroutineContext)) {
            confinement.requireConfined()
            // Both conditions matter. The marker says the caller is doing confined work for this
            // storage, but every store call sets it, not only a transaction — so a transaction has
            // to be confirmed with the driver as well. Without that, a call reaching here from
            // inside a plain store operation would run its block with no transaction at all and
            // report success.
            if (driver.currentTransaction() != null) {
                // Already inside this storage's transaction: join it. Opening a second one here is
                // the exact failure the SPI's reentrancy clause exists to prevent.
                return block()
            }
        }
        return withContext(confinement.dispatcher + confinement.marker) {
            // The context the bridged block runs under: the marker (so nested store calls know they
            // are already confined) and the Job (so cancelling the caller cancels the block), but
            // no interceptor — that is what makes the bridge use an event loop on this very thread
            // rather than dispatching the block somewhere else.
            val bridged: CoroutineContext = coroutineContext.minusKey(ContinuationInterceptor)
            try {
                database.transactionWithResult {
                    try {
                        runBlockingOnCurrentThread(bridged) { block() }
                    } catch (failure: Throwable) {
                        // Marked on the way out so the catch below can tell the caller's own
                        // failure apart from the transaction machinery's. Throwing it — rather
                        // than returning it — is what rolls the transaction back.
                        throw BlockFailure(failure)
                    }
                }
            } catch (fromBlock: BlockFailure) {
                throw fromBlock.actual
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (machinery: Throwable) {
                throw OutboxStorageException(OutboxStorageOperation.TRANSACTION, machinery)
            }
        }
    }
}

/**
 * Carries a failure thrown by the caller's own block out through SQLDelight's transaction frame.
 *
 * Without it, `inTransaction` could not distinguish "your block threw" from "the transaction could
 * not commit", and would rewrite the caller's exception as an [OutboxStorageException] — hiding
 * what actually went wrong behind a database error that did not happen.
 */
private class BlockFailure(val actual: Throwable) : Throwable(actual)

/**
 * Runs [block] to completion on the calling thread, driving an event loop for its suspensions.
 *
 * `kotlinx.coroutines.runBlocking` lives in the `concurrent` source set, which a module targeting
 * only Android and iOS cannot see from common code — hence the two one-line actuals rather than a
 * shared implementation.
 *
 * @param context must not carry a [ContinuationInterceptor]; supplying one would dispatch the
 *   block off this thread, which is the entire thing this function exists to avoid.
 */
internal expect fun <R> runBlockingOnCurrentThread(
    context: CoroutineContext,
    block: suspend () -> R,
): R
