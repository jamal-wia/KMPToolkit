package io.github.jamal_wia.kmptoolkit.outbox.testing

import io.github.jamal_wia.kmptoolkit.outbox.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.outbox.Outbox
import io.github.jamal_wia.kmptoolkit.outbox.OutboxHandler
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An [Outbox] that records what was enqueued instead of persisting or delivering anything.
 *
 * Use it to test the code that *decides* to enqueue — a repository, a use case — where the question
 * is "did this queue the right effect with the right key?" and the engine's behavior is somebody
 * else's test. When the question is about delivery, retries or ordering, use a real engine over
 * [InMemoryOutboxStore] instead.
 *
 * @param enqueueResult decides what [enqueue] returns for a given call: the new item's id, or
 *   `null` to simulate a [ConflictPolicy.KEEP] that found the effect already queued. The default
 *   returns a distinct `fake-id-N` for each call.
 */
public class FakeOutbox(
    private val enqueueResult: (Enqueued) -> String? = { "fake-id-${it.ordinal}" },
) : Outbox {

    /**
     * One recorded [enqueue] call.
     *
     * @property ordinal 1-based position among the calls this fake has seen.
     * @property handler the handler the effect was enqueued with.
     * @property payload the payload, un-encoded — the fake never calls
     *   [OutboxHandler.encodePayload], so assertions compare against the object you passed in.
     * @property uniqueKey the dedup key, or `null`.
     * @property tag the bulk-delete tag, or `null`.
     * @property conflictPolicy the policy the call asked for.
     */
    public data class Enqueued(
        val ordinal: Int,
        val handler: OutboxHandler<*>,
        val payload: Any,
        val uniqueKey: String?,
        val tag: String?,
        val conflictPolicy: ConflictPolicy,
    )

    private val mutex = Mutex()

    private val recorded: MutableList<Enqueued> = mutableListOf()

    private val observed: MutableStateFlow<List<OutboxItem>> = MutableStateFlow(emptyList())

    private var triggers: Int = 0

    /** Every [enqueue] call so far, in order. */
    public val enqueued: List<Enqueued> get() = recorded.toList()

    /** How many times [trigger] was called. */
    public val triggerCount: Int get() = triggers

    /** The single most recent [enqueue] call, or `null` if there has not been one. */
    public fun lastEnqueued(): Enqueued? = recorded.lastOrNull()

    /**
     * Sets what [observe] emits, for every type.
     *
     * Kept deliberately type-blind: a fake that indexed items by type would be a second
     * implementation of the store, which is what [InMemoryOutboxStore] is for. Collectors of
     * [observe] see the [OutboxItem.type] filter applied on top, so pass items of the type under
     * test.
     *
     * @param items what the flow should emit next.
     */
    public fun emitObserved(items: List<OutboxItem>) {
        observed.value = items
    }

    /** Forgets every recorded call and resets the trigger count. */
    public suspend fun reset() {
        mutex.withLock {
            recorded.clear()
            triggers = 0
        }
        observed.value = emptyList()
    }

    override suspend fun <P : Any> enqueue(
        handler: OutboxHandler<P>,
        payload: P,
        uniqueKey: String?,
        tag: String?,
        conflictPolicy: ConflictPolicy,
    ): String? = mutex.withLock {
        val call = Enqueued(
            ordinal = recorded.size + 1,
            handler = handler,
            payload = payload,
            uniqueKey = uniqueKey,
            tag = tag,
            conflictPolicy = conflictPolicy,
        )
        recorded += call
        enqueueResult(call)
    }

    override fun observe(type: String): Flow<List<OutboxItem>> =
        observed.map { items -> items.filter { it.type == type } }

    override fun trigger() {
        triggers++
    }
}
