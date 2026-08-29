package io.github.jamal_wia.kmptoolkit.uploader

/**
 * One queued effect — something the app owes the outside world and has not yet delivered.
 *
 * The record is the source of truth. It is persisted before the enqueue call returns, it survives
 * process death, and it is deleted only once its handler confirms the effect landed (or
 * deliberately drops it). Everything effect-specific lives in [payload] and in the handler; the
 * record itself is transport-agnostic.
 *
 * You normally read these — through [Uploader.observe] — rather than construct them. The constructor
 * is public because an [UploaderStore][io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore]
 * implementation has to materialize one per row.
 *
 * @property id client-generated unique id, stable across every retry. It doubles as an idempotency
 *   key a handler can forward to a server so an at-least-once replay is deduplicated there.
 * @property type the owning handler's [UploaderHandler.type]. Renaming a type orphans every queued
 *   row of that type — they park with "no handler registered".
 * @property payload the encoded payload, produced by [UploaderHandler.encodePayload]. Opaque to the
 *   engine and to the store.
 * @property schemaVersion the [UploaderHandler.schemaVersion] in force when this row was written. A
 *   row whose version exceeds the current handler's is parked rather than mis-decoded.
 * @property uniqueKey dedup identity together with [type], or `null` for an item that never
 *   conflicts. See [ConflictPolicy].
 * @property orderingKey FIFO channel this item belongs to, derived once at enqueue time by
 *   [UploaderHandler.orderingKey], or `null` for unordered delivery. Channels are scoped per [type].
 * @property tag an opaque label the library never interprets, used for bulk deletion via
 *   [UploaderStore.deleteByTag][io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore.deleteByTag] —
 *   a logout wipe, for instance.
 * @property state where the item sits in its lifecycle. See [UploaderItemState].
 * @property attempts how many attempts have failed so far; `0` until the first failure.
 * @property nextRunAtEpochMillis epoch millis before which the item must not be attempted again —
 *   the persisted backoff gate. `0` means "attemptable now".
 * @property createdAtEpochMillis when the item was enqueued. Diagnostic only: ordering comes from
 *   the store's insertion sequence, not from this field.
 * @property lastError a short diagnostic from the most recent failure or park, or `null`.
 * @property leaseUntilEpochMillis epoch millis at which a detached executor's claim expires.
 *   Meaningful only in [UploaderItemState.IN_FLIGHT]; `0` otherwise.
 */
public data class UploaderItem(
    val id: String,
    val type: String,
    val payload: String,
    val schemaVersion: Int,
    val uniqueKey: String?,
    val orderingKey: String?,
    val tag: String?,
    val state: UploaderItemState,
    val attempts: Int,
    val nextRunAtEpochMillis: Long,
    val createdAtEpochMillis: Long,
    val lastError: String?,
    val leaseUntilEpochMillis: Long = 0L,
)

/**
 * The persisted lifecycle of an [UploaderItem].
 *
 * There is deliberately **no `RUNNING` state**. "A coroutine is executing this right now" lives
 * only in the engine's memory, so a crash mid-execution needs no recovery pass: the row is still
 * [PENDING] on the next launch and is simply attempted again. Handlers must tolerate at-least-once
 * execution regardless — the send can succeed a microsecond before the process dies, before the
 * row is deleted — so a persisted running flag would add a stuck-row failure mode without buying a
 * guarantee.
 *
 * [IN_FLIGHT] is not that missing state: it means "delivery was handed to an external executor
 * with its own durability", and unlike a flag it cannot get stuck, because the lease that
 * accompanies it self-expires.
 */
public enum class UploaderItemState {

    /** Waiting to be attempted, possibly gated by [UploaderItem.nextRunAtEpochMillis]. */
    PENDING,

    /**
     * Delivery was detached to an external executor that will report the outcome later through
     * [UploaderEngine.settle]. The row stays persisted — the effect is still owed until the executor
     * confirms.
     *
     * The accompanying [UploaderItem.leaseUntilEpochMillis] is a **claim, not a lock**: while it is
     * unexpired the drain leaves the item alone; once it expires the item becomes attemptable
     * again and the handler's `execute` runs afresh. That is what makes a silently dead executor
     * recoverable, and it is why a hand-off must be idempotent — re-launching delivery for the same
     * item id must rejoin the possibly still-running executor rather than start a second one.
     */
    IN_FLIGHT,

    /**
     * Out of rotation without being deleted: the give-up policy fired, the handler asked for it, or
     * the payload could not be decoded (an unknown type after a downgrade, a corrupt row).
     *
     * A parked item never loses data silently. It stays visible through [Uploader.observe], and a
     * fresh enqueue with the same ([UploaderItem.type], [UploaderItem.uniqueKey]) revives the key under
     * either [ConflictPolicy]. The engine simply stops spending retries on it.
     */
    PARKED,
}
