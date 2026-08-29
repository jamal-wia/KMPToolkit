package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.Uploader
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An [Uploader] that records what was enqueued instead of persisting or delivering anything.
 *
 * Use it to test the code that *decides* to enqueue — a repository, a use case — where the question
 * is "did this queue the right effect with the right key?" and the engine's behavior is somebody
 * else's test. When the question is about delivery, retries or ordering, use a real engine over
 * [InMemoryUploaderStore] instead.
 *
 * @param enqueueResult decides what [enqueue] returns for a given call: the new item's id, or
 *   `null` to simulate a [ConflictPolicy.KEEP] that found the effect already queued. The default
 *   returns a distinct `fake-id-N` for each call.
 */
public class FakeUploader(
    private val enqueueResult: (Enqueued) -> String? = { "fake-id-${it.ordinal}" },
) : Uploader {

    /**
     * One recorded [enqueue] call.
     *
     * @property ordinal 1-based position among the calls this fake has seen.
     * @property handler the handler the effect was enqueued with.
     * @property payload the payload, un-encoded — the fake never calls
     *   [UploaderHandler.encodePayload], so assertions compare against the object you passed in.
     * @property uniqueKey the dedup key, or `null`.
     * @property tag the bulk-delete tag, or `null`.
     * @property conflictPolicy the policy the call asked for.
     */
    public data class Enqueued(
        val ordinal: Int,
        val handler: UploaderHandler<*>,
        val payload: Any,
        val uniqueKey: String?,
        val tag: String?,
        val conflictPolicy: ConflictPolicy,
    )

    private val mutex = Mutex()

    private val recorded: MutableList<Enqueued> = mutableListOf()

    private val observed: MutableStateFlow<List<UploaderItem>> = MutableStateFlow(emptyList())

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
     * implementation of the store, which is what [InMemoryUploaderStore] is for. Collectors of
     * [observe] see the [UploaderItem.type] filter applied on top, so pass items of the type under
     * test.
     *
     * @param items what the flow should emit next.
     */
    public fun emitObserved(items: List<UploaderItem>) {
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
        handler: UploaderHandler<P>,
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

    override fun observe(type: String): Flow<List<UploaderItem>> =
        observed.map { items -> items.filter { it.type == type } }

    override fun trigger() {
        triggers++
    }
}
