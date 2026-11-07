package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy
import io.github.jamal_wia.kmptoolkit.uploader.Uploader
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

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

    // Every piece of state is a StateFlow changed with update {}: the fake is used from engine and IO threads
    // as well as the test's own, and readers must never see a half-applied write. No lock is held while the
    // consumer's `enqueueResult` runs, so that hook may call back into the fake.
    private val recorded: MutableStateFlow<List<Enqueued>> = MutableStateFlow(emptyList())

    private val observed: MutableStateFlow<List<UploaderItem>> = MutableStateFlow(emptyList())

    private val triggers: MutableStateFlow<Int> = MutableStateFlow(0)

    private val cancelledIds: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    private val cancelledTags: MutableStateFlow<List<String>> = MutableStateFlow(emptyList())

    /** Every [enqueue] call so far, in order. */
    public val enqueued: List<Enqueued> get() = recorded.value

    /** How many times [trigger] was called. */
    public val triggerCount: Int get() = triggers.value

    /**
     * Every id passed to [cancel], in order, including ids this fake never returned.
     *
     * @since 2.0.0
     */
    public val cancelled: List<String> get() = cancelledIds.value

    /**
     * Every tag passed to [cancelByTag], in order.
     *
     * @since 2.0.0
     */
    public val cancelledByTag: List<String> get() = cancelledTags.value

    /** The single most recent [enqueue] call, or `null` if there has not been one. */
    public fun lastEnqueued(): Enqueued? = recorded.value.lastOrNull()

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
        recorded.value = emptyList()
        cancelledIds.value = emptyList()
        cancelledTags.value = emptyList()
        triggers.value = 0
        observed.value = emptyList()
    }

    override suspend fun <P : Any> enqueue(
        handler: UploaderHandler<P>,
        payload: P,
        uniqueKey: String?,
        tag: String?,
        conflictPolicy: ConflictPolicy,
    ): String? {
        var call: Enqueued? = null
        // The ordinal is taken inside the update, so concurrent calls get distinct, gapless numbers; a CAS
        // retry rebuilds the call, and only the one that landed is kept in `call`.
        recorded.update { calls ->
            val next = Enqueued(
                ordinal = calls.size + 1,
                handler = handler,
                payload = payload,
                uniqueKey = uniqueKey,
                tag = tag,
                conflictPolicy = conflictPolicy,
            )
            call = next
            calls + next
        }
        // After the write, outside any lock: a hook that calls back into this fake cannot deadlock it.
        return enqueueResult(checkNotNull(call))
    }

    override fun observe(type: String): Flow<List<UploaderItem>> =
        observed.map { items -> items.filter { it.type == type } }

    override suspend fun cancel(id: String) {
        cancelledIds.update { it + id }
    }

    override suspend fun cancelByTag(tag: String) {
        cancelledTags.update { it + tag }
    }

    override fun trigger() {
        triggers.update { it + 1 }
    }
}
