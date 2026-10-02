package io.github.jamal_wia.kmptoolkit.uploader

import kotlinx.coroutines.flow.Flow

/**
 * The queue-facing half of the engine — the type your repositories and use cases should depend on.
 *
 * It is deliberately narrow: enqueue an effect, watch what is still owed, withdraw one, poke the
 * drain. Nothing about stores, leases, or platform wake-ups appears here, so a feature coupling to
 * this contract couples to five functions and can be faked in a test with `FakeUploader` from
 * `kmptoolkit-uploader-testing`. The lifecycle operations live one level up, on [UploaderEngine], and
 * belong to whoever owns the engine's lifetime — usually your application bootstrap.
 */
public interface Uploader {

    /**
     * Persists an effect and wakes the drain.
     *
     * Suspends because it writes to durable storage — by the time it returns, the effect survives
     * process death. That is the whole promise of the module, and it is why this is the one
     * operation on this interface that suspends.
     *
     * The payload is encoded with [handler]'s own [UploaderHandler.encodePayload], and the item's
     * ordering channel is derived once, here, from [UploaderHandler.orderingKey].
     *
     * @param handler the handler that will deliver this effect; also supplies the item's
     *   [UploaderItem.type], schema version, retry policy and constraints.
     * @param payload what to deliver.
     * @param uniqueKey the dedup identity, together with the handler's type. `null` (the default)
     *   means this item never conflicts with anything and always appends.
     * @param tag an opaque label for bulk deletion later — a session or account id, typically. The
     *   library never interprets it. See
     *   [UploaderStore.deleteByTag][io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore.deleteByTag].
     * @param conflictPolicy what to do when [uniqueKey] is already queued; ignored when it is
     *   `null`.
     * @return the new item's id — usable as an idempotency key or to correlate with
     *   [UploaderEngine.settle] — or `null` when [ConflictPolicy.KEEP] left an already-queued item in
     *   place and nothing was inserted.
     */
    public suspend fun <P : Any> enqueue(
        handler: UploaderHandler<P>,
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
     * @param type the [UploaderHandler.type] to watch.
     * @return items of that type in insertion order; never completes.
     */
    public fun observe(type: String): Flow<List<UploaderItem>>

    /**
     * Withdraws one queued effect: removes its item, in any state, and cancels its upload if an
     * [UploadHandler]'s transport is running it. Other items — of this handler or any other sharing
     * the transport — are untouched.
     *
     * The order is what makes this safe against the drain: the row goes first, so a transport job
     * that starts afterwards finds nothing owed, and a hand-off racing this call sees the row gone
     * right after it launches and cancels its own upload.
     *
     * **Best effort for the bytes.** An upload whose body already reached the server may have been
     * accepted; nothing can recall it. Use [AttemptContext.id] as an idempotency key, and treat a
     * withdrawal as "will not be sent from now on", not as "was never sent".
     *
     * A delivery that is not an [UploadHandler]'s — a plain handler returning
     * [AttemptResult.Detached] to an executor of its own — loses its row, so its later settle is a
     * no-op, but stopping that executor is the handler's business.
     *
     * Nothing is settled and no handler hook runs: the caller decided the item's fate, and is the
     * place to clean up after it — a source file, a "sending" badge.
     *
     * @param id the id [enqueue] returned. An unknown id — already delivered, dropped, or never
     *   queued — is a no-op.
     * @since 2.0.0
     */
    public suspend fun cancel(id: String)

    /**
     * [cancel] for every item whose [UploaderItem.tag] equals [tag], in any state — the logout wipe:
     * effects queued under one account must neither replay nor keep uploading under its credentials
     * once it has signed out.
     *
     * @param tag the exact tag to match; items with another tag or none are untouched.
     * @since 2.0.0
     */
    public suspend fun cancelByTag(tag: String)

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
