package io.github.jamal_wia.kmptoolkit.outbox.sqldelight

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CloseableCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * The one thread every statement of one [OutboxStorage] runs on.
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
 * Identifies the thread the calling code is running on.
 *
 * Only used to *check* an invariant, never to make a scheduling decision — see
 * [DatabaseConfinement.threadId]. The value is opaque and comparable, nothing more.
 */
internal expect fun currentThreadId(): Long

/**
 * Present in the coroutine context while the current coroutine is running confined work for
 * [owner].
 *
 * It is what makes re-entry free: [onDatabaseThread] can skip the `withContext` hop when the marker
 * for the same storage is already there, and `SqlDelightTransactionRunner` can tell a nested
 * `inTransaction` to join the outer one instead of opening a second.
 *
 * Keyed by [owner] rather than being a bare flag because two [OutboxStorage] instances have two
 * different threads: being confined to one says nothing about the other, and treating it as if it
 * did would run the second one's statements on the first one's thread.
 *
 * **A marker is necessary but not sufficient**, which is the whole reason
 * [DatabaseConfinement.requireConfined] exists — see there.
 */
internal class DatabaseThreadMarker(
    val owner: DatabaseConfinement,
) : AbstractCoroutineContextElement(DatabaseThreadMarker) {

    companion object Key : CoroutineContext.Key<DatabaseThreadMarker>
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
): R = if (confinement.isReentrant(coroutineContext)) {
    confinement.requireConfined()
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

    /**
     * Built once rather than per call: it is immutable and identified by this confinement, so every
     * confined call can share the same instance instead of allocating one on the hot path.
     */
    val marker: DatabaseThreadMarker = DatabaseThreadMarker(this)

    /**
     * The database thread's identity, read once from the thread itself.
     *
     * Determined here, by blocking on the dispatcher during construction, rather than being
     * recorded on first use: a `val` written before anything else can observe it needs no
     * synchronization, whereas a field written later by one thread and read by others would need
     * exactly the kind of care this class exists to remove.
     */
    val threadId: Long = runBlockingOnCurrentThread(EmptyCoroutineContext) {
        withContext(dispatcher) { currentThreadId() }
    }

    /** Whether [context] says the caller is already doing confined work for this confinement. */
    fun isReentrant(context: CoroutineContext): Boolean =
        context[DatabaseThreadMarker]?.owner === this

    /**
     * Fails loudly when the marker says "confined" but the thread says otherwise.
     *
     * A coroutine context element is inherited across a dispatcher switch; the invariant it encodes
     * is not. `inTransaction { withContext(Dispatchers.IO) { store.insert(...) } }` therefore keeps
     * the marker while leaving the thread, and without this check the statement would run outside
     * the transaction that is open on the database thread — the caller's domain write and the
     * effect it owes committing separately, with nothing reporting it.
     *
     * Throwing rather than re-dispatching is deliberate on both counts: re-dispatching would
     * deadlock against the thread the open transaction is occupying, and this is a mistake in the
     * caller's code — like a blank name in [OutboxDatabaseConfig] — not a runtime condition an app
     * can recover from.
     */
    fun requireConfined() {
        check(currentThreadId() == threadId) {
            "an outbox storage statement left its database thread — do not switch dispatchers " +
                "inside inTransaction; see docs/kmptoolkit-outbox-sqldelight/03-guide.md"
        }
    }

    /**
     * Runs [finalizer] on the database thread, waits for it, then releases the thread.
     *
     * The wait is the point. The dispatcher's own `close` does not join: on Android it is
     * `ExecutorService.shutdown()`, which returns while the running task continues. Closing the
     * driver on the caller's thread meanwhile would close a connection another thread is mid-
     * statement on — a live cursor on Android, a borrowed SQLiter connection on iOS.
     *
     * Because the thread is single and its queue is FIFO, [finalizer] necessarily runs after every
     * statement submitted before this call.
     */
    fun closeAfterDraining(finalizer: () -> Unit) {
        runBlockingOnCurrentThread(EmptyCoroutineContext) {
            withContext(dispatcher) { finalizer() }
        }
        dispatcher.close()
    }
}
