package io.github.jamal_wia.kmptoolkit.outbox

/**
 * Everything specific to **one kind** of owed effect: how its payload is encoded, how it is
 * delivered, how long it is worth retrying, and what makes it stale.
 *
 * Everything generic — persistence, triggers, backoff arithmetic, single-flight, FIFO ordering,
 * leases — lives in the engine. Write one handler per effect type and register it with
 * [createOutboxEngine].
 *
 * ```kotlin
 * class SendMessageHandler(private val api: ChatApi) : OutboxHandler<SendMessage> {
 *     override val type: String = "chat.send_message"
 *     override fun encodePayload(payload: SendMessage): String = Json.encodeToString(payload)
 *     override fun decodePayload(raw: String): SendMessage = Json.decodeFromString(raw)
 *     override fun orderingKey(payload: SendMessage): String = payload.threadId
 *
 *     override suspend fun execute(context: AttemptContext, payload: SendMessage): AttemptResult =
 *         when (val response = api.send(payload, idempotencyKey = context.id)) {
 *             is Ok -> AttemptResult.Success
 *             is ServerError -> AttemptResult.Retry(response.cause)
 *             is Rejected -> AttemptResult.Park(response.message)
 *         }
 * }
 * ```
 *
 * ## Contract
 *
 * - **[execute] must tolerate being run more than once for the same effect.** The process can die
 *   after the send succeeded but before the row was deleted. Make the server side idempotent where
 *   you can — [AttemptContext.id] is a stable id fit for an `Idempotency-Key` header.
 * - **Decide staleness inside [execute]** and return [AttemptResult.Drop]. The state an effect
 *   guards may have moved on while it waited in the queue; never deliver a stale effect just
 *   because it is still queued.
 * - **Return, do not throw.** Map failures onto [AttemptResult]. A thrown exception is treated as
 *   [AttemptResult.Retry] so a handler bug cannot stall the queue, but a returned result carries a
 *   decision and an exception does not.
 * - **Keep [execute] bounded.** It runs sequentially within a drain pass, so an indefinitely
 *   hanging call starves every other effect. Wrap network calls in a timeout.
 * - **[encodePayload] and [decodePayload] must round-trip**, and [decodePayload] must keep
 *   decoding payloads written by older versions of the app — those rows are already on disk.
 *   Bump [schemaVersion] when you change the shape in a way older code cannot read.
 * - **[type] and [orderingKey] are evaluated for their persistence effects.** [type] is written
 *   into every row (renaming it orphans queued rows); [orderingKey] is called once, at enqueue
 *   time, and the result is persisted, so changing the implementation does not re-shuffle rows
 *   already in the queue.
 *
 * @param P the payload type this handler delivers.
 */
public interface OutboxHandler<P : Any> {

    /**
     * The stable queue key stored in [OutboxItem.type]. Must be unique across the handlers given
     * to one engine — a duplicate is rejected at construction.
     *
     * Treat it like a database column name, not like a class name: renaming it leaves every queued
     * row of the old name without a handler, and those rows park.
     */
    public val type: String

    /**
     * The payload schema version stamped onto rows this handler enqueues.
     *
     * A row whose stored version is **newer** than this — written by a later app version that the
     * user then downgraded from — is parked instead of mis-decoded, because decoding it with older
     * code could send something wrong. Older versions must stay decodable by [decodePayload].
     */
    public val schemaVersion: Int
        get() = 1

    /** How failures of this effect are paced and when the engine gives up. */
    public val retryPolicy: RetryPolicy
        get() = ExponentialBackoffRetryPolicy()

    /**
     * Keys of the [ConstraintProvider][io.github.jamal_wia.kmptoolkit.outbox.spi.ConstraintProvider]s
     * that must all report satisfied before this effect is attempted — `"network"`, say.
     *
     * Empty (the default) means always attemptable. A key with no registered provider is treated
     * as satisfied and logged as an error: failing open beats stalling a queue on a typo.
     */
    public val constraintKeys: Set<String>
        get() = emptySet()

    /**
     * Encodes [payload] into the string stored in [OutboxItem.payload].
     *
     * The format is entirely yours — `kotlinx.serialization` JSON, protobuf in Base64, a single id.
     * This module has no serialization dependency and never looks inside the result.
     *
     * @param payload the value passed to [Outbox.enqueue].
     * @return the encoded form to persist.
     */
    public fun encodePayload(payload: P): String

    /**
     * Decodes what [encodePayload] wrote.
     *
     * Throwing is the correct way to report an undecodable payload: the engine parks that item
     * with the failure attached instead of retrying it forever or deleting it. Configure your
     * decoder to tolerate unknown fields so a row written by a newer app version with an added
     * field still reads.
     *
     * @param raw the stored [OutboxItem.payload].
     * @return the decoded payload.
     * @throws Throwable if the payload cannot be decoded; the item is parked.
     */
    public fun decodePayload(raw: String): P

    /**
     * The FIFO channel this payload belongs to, or `null` (the default) for unordered delivery.
     *
     * Items sharing a channel are delivered strictly oldest-first — a failing head blocks its tail
     * until it succeeds, drops, or parks. Items in different channels are independent and the
     * engine gives no ordering guarantee between them.
     *
     * Channels are scoped **per [type]**: two handlers returning the same raw key still get
     * separate channels, so one effect type's backoff can never stall another's.
     *
     * Return a payload-derived key (a thread id, a document id) for entity-scoped ordering, or a
     * constant for one global FIFO across the type. Called once at enqueue time; the result is
     * persisted on the row.
     *
     * @param payload the value being enqueued.
     * @return the channel key, or `null` for no ordering.
     */
    public fun orderingKey(payload: P): String? = null

    /**
     * Performs one delivery attempt.
     *
     * @param context metadata about this item and this attempt.
     * @param payload the decoded payload.
     * @return what the engine should do with the row. See the class contract.
     */
    public suspend fun execute(context: AttemptContext, payload: P): AttemptResult
}

/**
 * What the engine tells a handler about the item it is being asked to deliver.
 *
 * @property id the item's stable id, unchanged across every retry — suitable as an
 *   `Idempotency-Key`.
 * @property attempts how many attempts have already failed; `0` on the first try.
 * @property uniqueKey the item's dedup key, or `null`.
 * @property orderingKey the item's FIFO channel, or `null`.
 * @property wasDetached `true` when this execution is a **re-**hand-off: the item was
 *   [OutboxItemState.IN_FLIGHT] and its lease expired without anyone settling it. The previous
 *   executor may in fact have completed and merely failed to report (the app died right after the
 *   send landed), so a detaching handler should rejoin or probe rather than blindly starting a
 *   fresh delivery. `false` for ordinary first attempts and for re-runs after
 *   [AttemptResult.Retry].
 */
public data class AttemptContext(
    val id: String,
    val attempts: Int,
    val uniqueKey: String?,
    val orderingKey: String?,
    val wasDetached: Boolean = false,
)
