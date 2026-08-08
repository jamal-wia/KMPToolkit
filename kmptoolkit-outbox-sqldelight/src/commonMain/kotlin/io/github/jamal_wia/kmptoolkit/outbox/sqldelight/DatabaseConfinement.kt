package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The one thread every statement of one [OutboxStorage] runs on, plus the marker that lets nested
 * calls recognize they are already on it.
 *
 * ### Why a dedicated thread rather than `Dispatchers.IO`
 *
 * SQLDelight transactions are **thread-confined**: `Transacter.Transaction` records the thread it
 * was created on and `checkThreadConfinement` fails if it is touched from another, and both the
 * Android and the native driver track the current transaction per thread. A suspending call that
 * resumes on a different thread of a pool would therefore either fail outright or — worse — see no
 * enclosing transaction and silently open a second one, which is exactly the failure
 * `TransactionRunner` exists to prevent.
 *
 * `Dispatchers.IO.limitedParallelism(1)` is not enough. It serializes tasks but does not pin a
 * thread: two tasks run one at a time, possibly on different threads, and a coroutine that suspends
 * mid-transaction can resume on the other one. One real thread is the only thing that makes the
 * confinement hold across a suspension point.
 *
 * The cost is one parked thread per [OutboxStorage]. That is why [OutboxStorage.close] exists.
 */
internal expect fun createConfinedDatabaseDispatcher(name: String): CloseableCoroutineDispatcher

/**
 * Present in the coroutine context exactly while the current coroutine is running on [owner]'s
 * database thread.
 *
 * It is what makes re-entry free: [onDatabaseThread] can skip the `withContext` hop when the marker
 * for the same storage is already there, and `SqlDelightTransactionRunner` can tell a nested
 * `inTransaction` to join the outer one instead of opening a second.
 *
 * Keyed by [owner] rather than being a bare flag because two [OutboxStorage] instances have two
 * different threads: being inside one's transaction says nothing about the other's, and treating it
 * as if it did would run the second one's statements on the first one's thread.
 */
internal class DatabaseThreadMarker(
    val owner: Any,
) : kotlin.coroutines.AbstractCoroutineContextElement(DatabaseThreadMarker) {

    companion object Key : kotlin.coroutines.CoroutineContext.Key<DatabaseThreadMarker>
}

/**
 * Runs [block] on [confinement]'s thread, or straight away if it is already running there.
 *
 * The re-entry check is not an optimization. Without it a store call made from inside
 * `inTransaction` would dispatch onto a thread that the outer transaction is currently occupying —
 * a deadlock on a single-threaded dispatcher — and even if it did get through, it would be a
 * different coroutine on the wrong side of the driver's transaction bookkeeping.
 */
internal suspend inline fun <R> onDatabaseThread(
    confinement: DatabaseConfinement,
    crossinline block: () -> R,
): R = if (coroutineContext[DatabaseThreadMarker]?.owner === confinement.owner) {
    block()
} else {
    withContext(confinement.dispatcher + confinement.marker) { block() }
}

/**
 * The thread one [OutboxStorage] confines its statements to, and the identity nested calls compare
 * against.
 *
 * A single object shared by the store and the transaction runner, rather than each taking a
 * dispatcher of its own: a store and a runner on two different threads would give a consumer a
 * transaction that does not actually contain the writes made inside it, and nothing would report
 * the mismatch. Making them share one object removes the possibility of wiring them up wrong.
 */
internal class DatabaseConfinement(val dispatcher: CloseableCoroutineDispatcher) {

    /** Identity of this confinement, carried by [marker]. */
    val owner: Any = this

    /**
     * Built once rather than per call: it is immutable and identified by [owner], so every
     * confined call can share the same instance instead of allocating one on the hot path.
     */
    val marker: DatabaseThreadMarker = DatabaseThreadMarker(owner)

    fun close() {
        dispatcher.close()
    }
}
