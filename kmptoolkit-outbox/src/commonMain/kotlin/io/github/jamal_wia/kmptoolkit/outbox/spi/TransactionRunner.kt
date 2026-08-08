package io.github.jamal_wia.kmptoolkit.outbox.spi

/**
 * Runs a block inside one durable write transaction — the primitive that makes this a
 * *transactional* outbox rather than just a retry queue.
 *
 * The point is a single atomicity guarantee: a domain write and the effect it owes commit together
 * or not at all. Without it there is a window where the local database says the message was sent
 * but nothing was queued to send it (or the reverse — an effect queued for a row that was rolled
 * back). Closing that window requires the queue and the domain tables to live in the **same**
 * database, which is why the store is a port you implement rather than something this library
 * ships.
 *
 * ## Contract
 *
 * - **Atomic.** Everything written inside [inTransaction] commits together. If [block] throws, all
 *   of it rolls back and the exception propagates unchanged.
 * - **Reentrant.** A nested [inTransaction] must join the outer transaction, not open a second
 *   one and not deadlock. The engine relies on this: your code opens a transaction, writes a
 *   domain row, then calls `outbox.enqueue(...)`, which opens one of its own. If nesting starts a
 *   separate transaction, the two halves can commit independently and the guarantee is gone.
 * - **Cancellation.** If the calling coroutine is cancelled inside [block], the transaction rolls
 *   back and `CancellationException` propagates. A half-applied transaction is never left behind.
 * - **Concurrency.** May be called from several coroutines at once. Serializing writers is a
 *   normal implementation (most embedded databases have a single writer anyway).
 * - **No suspension across the boundary that your database forbids.** [block] is suspending
 *   because the store's functions are. If your database requires all statements of a transaction
 *   to run on one thread, confine [block] to that thread's dispatcher inside your implementation.
 *
 * The default, [Direct], simply invokes the block. That is the honest behavior when the queue is
 * the only write — the overwhelmingly common case, since most handlers enqueue and nothing else —
 * and it keeps the engine usable with a store that has no transaction concept at all.
 */
public interface TransactionRunner {

    /**
     * Runs [block] inside one transaction and returns its result.
     *
     * @param block the work to run atomically; may itself call back into the store or the outbox.
     * @return whatever [block] returned.
     */
    public suspend fun <R> inTransaction(block: suspend () -> R): R

    /**
     * The no-transaction default: runs the block directly.
     *
     * Correct whenever enqueueing is the only write being made — the effect is still persisted
     * durably by the store, it simply is not bracketed with anything else. Substitute a real
     * implementation as soon as a feature needs a domain write and its owed effect to be atomic.
     */
    public object Direct : TransactionRunner {
        override suspend fun <R> inTransaction(block: suspend () -> R): R = block()
    }
}
