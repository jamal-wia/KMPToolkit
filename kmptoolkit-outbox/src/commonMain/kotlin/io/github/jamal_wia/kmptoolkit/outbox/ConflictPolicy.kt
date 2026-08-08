package io.github.jamal_wia.kmptoolkit.outbox

/**
 * What happens when an effect is enqueued while an item with the same ([OutboxItem.type],
 * [OutboxItem.uniqueKey]) is already in the queue.
 *
 * Meaningless — and ignored — when the unique key is `null`: keyless items never conflict, so every
 * such enqueue appends.
 *
 * Both policies treat a [OutboxItemState.PARKED] conflict the same way: the fresh enqueue
 * supersedes it. A parked item is out of rotation with no other revive path, so letting it keep its
 * key would make that key permanently dead — [KEEP] would then silently swallow every future
 * enqueue for it. A fresh enqueue is new intent, and it revives the key with a new payload and a
 * clean retry budget.
 */
public enum class ConflictPolicy {

    /**
     * The already-queued effect wins and the new enqueue is a no-op returning `null`.
     *
     * Applies to a [OutboxItemState.PENDING] conflict and equally to an
     * [OutboxItemState.IN_FLIGHT] one — a delivery already handed to an executor must win over a
     * re-enqueue exactly like a waiting one, or the same effect goes out twice.
     *
     * This is the right default for "make sure this eventually happens": a second tap on *send*
     * does not queue a second send.
     */
    KEEP,

    /**
     * The new effect supersedes the queued one in any state.
     *
     * Payload and retry state reset, and the queue position resets too — the item re-enters at the
     * **tail** of its ordering channel, because superseding is a new enqueue rather than an
     * in-place edit. Without that, a key replaced over and over could hold the head of its channel
     * indefinitely and starve everything behind it.
     *
     * This is the right choice for "only the latest value matters": a draft autosave, a presence
     * update, a settings sync.
     */
    REPLACE,
}
