package io.github.jamal_wia.kmptoolkit.outbox

import kotlinx.coroutines.flow.Flow

/**
 * The queue-facing half of the engine — the type your repositories and use cases should depend on.
 *
 * It is deliberately narrow: enqueue an effect, watch what is still owed, poke the drain. Nothing
 * about stores, leases, or platform wake-ups appears here, so a feature coupling to this contract
 * couples to three functions and can be faked in a test with `FakeOutbox` from
 * `kmptoolkit-outbox-testing`. The lifecycle operations live one level up, on [OutboxEngine], and
 * belong to whoever owns the engine's lifetime — usually your application bootstrap.
 */
public interface Outbox {

    /**
     * Persists an effect and wakes the drain.
     *
     * Suspends because it writes to durable storage — by the time it returns, the effect survives
     * process death. That is the whole promise of the module, and it is why this is the one
     * operation on this interface that suspends.
     *
     * The payload is encoded with [handler]'s own [OutboxHandler.encodePayload], and the item's
     * ordering channel is derived once, here, from [OutboxHandler.orderingKey].
     *
     * @param handler the handler that will deliver this effect; also supplies the item's
     *   [OutboxItem.type], schema version, retry policy and constraints.
     * @param payload what to deliver.
     * @param uniqueKey the dedup identity, together with the handler's type. `null` (the default)
     *   means this item never conflicts with anything and always appends.
     * @param tag an opaque label for bulk deletion later — a session or account id, typically. The
     *   library never interprets it. See
     *   [OutboxStore.deleteByTag][io.github.jamal_wia.kmptoolkit.outbox.spi.OutboxStore.deleteByTag].
     * @param conflictPolicy what to do when [uniqueKey] is already queued; ignored when it is
     *   `null`.
     * @return the new item's id — usable as an idempotency key or to correlate with
     *   [OutboxEngine.settle] — or `null` when [ConflictPolicy.KEEP] left an already-queued item in
     *   place and nothing was inserted.
     */
    public suspend fun <P : Any> enqueue(
        handler: OutboxHandler<P>,
        payload: P,
        uniqueKey: String? = null,
        tag: String? = null,
        conflictPolicy: ConflictPolicy = ConflictPolicy.KEEP,
    ): String?

    /**
     * A live view of every queued item of one effect type, in any state, so a screen can bind to
     * "still owed" — a pending-message tick, a parked-and-needs-attention badge.
     *
     * Does not suspend: it hands back the store's flow, and the query runs when you collect it.
     *
     * @param type the [OutboxHandler.type] to watch.
     * @return items of that type in insertion order; never completes.
     */
    public fun observe(type: String): Flow<List<OutboxItem>>

    /**
     * Asks the drain to run.
     *
     * Cheap, non-suspending and safe from anywhere, including from a hot path: triggers are
     * conflated, so a burst of them collapses into one pass and calling it while a drain is
     * already running never starts a second.
     *
     * You rarely need it — enqueueing triggers a drain, and so does a constraint becoming
     * satisfied. It exists for the "user pulled to refresh" and "we just got a push telling us to
     * sync" cases.
     */
    public fun trigger()
}
