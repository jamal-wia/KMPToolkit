package io.github.jamal_wia.kmptoolkit.outbox.spi

import io.github.jamal_wia.kmptoolkit.outbox.OutboxItem
import io.github.jamal_wia.kmptoolkit.outbox.OutboxItemState
import kotlinx.coroutines.flow.Flow

/**
 * The persistence port of the outbox engine — **the one thing you must supply** to use this
 * module, and the reason it has no database dependency of its own.
 *
 * The engine owns every policy decision: which record runs next, how backoff is computed, when to
 * give up, how leases expire. A store owns none of them. It is a set of mechanical primitives over
 * a durable table, and the engine composes them. That split is what lets the same engine sit on
 * SQLDelight, Room, Realm, a file, or (in tests) a map — see `docs/kmptoolkit-outbox/07-custom-store.md`
 * for a walkthrough, and `kmptoolkit-outbox-testing`'s `InMemoryOutboxStore` for a complete
 * reference implementation you can read in one sitting.
 *
 * ## Contract
 *
 * These invariants are what the engine relies on. An implementation that breaks one does not fail
 * loudly — it loses or duplicates a delivery under a race, which is exactly the failure mode this
 * module exists to prevent. `kmptoolkit-outbox-testing` ships `AbstractOutboxStoreContractTest`,
 * which asserts all of them; extend it rather than trusting a read-through.
 *
 * ### Durability
 * Every write must be durable before the call returns. An item survives process death from the
 * moment [insertKeep]/[insertReplace] returns until [deleteById] (or a give-up drop) removes it.
 * A store that buffers writes in memory and flushes later turns a crash into silent data loss.
 *
 * ### Atomicity
 * Each individual function is atomic on its own: a concurrent reader sees the state before or
 * after, never halfway. Two calls are **not** atomic together — the engine never assumes they are.
 * The one exception the engine does depend on is the compare-and-set inside [recordFailure] (see
 * its `expectedLeaseUntilEpochMillis` parameter), which must be a single atomic read-modify-write.
 *
 * ### Ordering
 * [getAllActive] returns items in **insertion order**: a monotonic per-store sequence, not a
 * wall-clock timestamp. Two items enqueued in the same millisecond must still come back in the
 * order they were inserted, and the order must survive a process restart. The engine derives FIFO
 * channel heads from this list, so a store that orders by `created_at` alone silently degrades the
 * ordering guarantee under a fast burst. A SQL implementation should order by an
 * `INTEGER PRIMARY KEY AUTOINCREMENT` sequence column; an in-memory one by a counter.
 *
 * ### Idempotency and absent rows
 * [recordFailure], [markInFlight], [park], [deleteById] and [deleteByTag] are addressed by id (or
 * tag) and **must treat an absent row as a no-op**, never as an error. The settle and lease races
 * are designed around this: a late settle from an executor whose row was already superseded lands
 * on nothing and degrades to a no-op instead of corrupting a fresh claim. Calling any of them
 * twice with the same arguments must be indistinguishable from calling it once.
 *
 * ### Concurrency
 * Every function may be called concurrently, from different coroutines and different threads.
 * In practice the engine's drain is single-flight, but [Outbox.enqueue][io.github.jamal_wia.kmptoolkit.outbox.Outbox.enqueue]
 * runs on the caller's coroutine and `settle` on a platform executor's, so at least three callers
 * can overlap. Guard mutable state (a `Mutex`, a serialized database writer, whatever your engine
 * gives you).
 *
 * ### Dedup identity
 * The pair ([OutboxItem.type], [OutboxItem.uniqueKey]) is the dedup identity. A `null` unique key
 * never conflicts with anything — every keyless enqueue appends. See [insertKeep] for the exact
 * per-state behavior.
 *
 * ### Nothing here interprets a payload
 * [OutboxItem.payload] is an opaque string and [OutboxItem.tag] is an opaque label. A store stores
 * and returns them unchanged; it never parses, validates, or acts on either.
 */
public interface OutboxStore {

    /**
     * Inserts [record] unless an item with the same ([OutboxItem.type], [OutboxItem.uniqueKey])
     * already occupies the queue — the storage half of
     * [ConflictPolicy.KEEP][io.github.jamal_wia.kmptoolkit.outbox.ConflictPolicy.KEEP].
     *
     * Per-state behavior of an existing item with the same identity:
     * - [OutboxItemState.PENDING] — keep it, do not insert, return `false`.
     * - [OutboxItemState.IN_FLIGHT] — same: an in-flight delivery must win over a re-enqueue
     *   exactly like a waiting one, or a duplicate would be sent.
     * - [OutboxItemState.PARKED] — **replace it** and return `true`. A parked item is out of
     *   rotation with no other revive path, so letting it keep the key would make that key
     *   permanently dead and swallow every future enqueue. Replacing means deleting the parked row
     *   and inserting [record] at the tail of the insertion sequence, with its own fresh id.
     *
     * A `null` [OutboxItem.uniqueKey] never conflicts: always insert, always return `true`.
     *
     * @param record the new item, already carrying its final id and timestamps.
     * @return `true` if [record] was inserted, `false` if an existing PENDING/IN_FLIGHT item won.
     */
    public suspend fun insertKeep(record: OutboxItem): Boolean

    /**
     * Inserts [record], superseding any item with the same ([OutboxItem.type],
     * [OutboxItem.uniqueKey]) **in any state** — the storage half of
     * [ConflictPolicy.REPLACE][io.github.jamal_wia.kmptoolkit.outbox.ConflictPolicy.REPLACE].
     *
     * Superseding is a delete plus an insert, not an in-place edit: the old row's id, attempt
     * count, backoff gate and lease are all discarded, and the new row enters at the **tail** of
     * the insertion sequence. That is deliberate — a replaced item is new intent, and keeping the
     * old queue position would let a repeatedly-replaced key hold the head of its ordering channel
     * forever.
     *
     * A `null` [OutboxItem.uniqueKey] never conflicts: this is then a plain append.
     *
     * @param record the new item, already carrying its final id and timestamps.
     */
    public suspend fun insertReplace(record: OutboxItem)

    /**
     * Every [OutboxItemState.PENDING] and [OutboxItemState.IN_FLIGHT] item, in insertion order.
     *
     * [OutboxItemState.PARKED] items are excluded: they are out of rotation, and including them
     * would let a parked item block the head of its ordering channel forever. They remain
     * observable through [observeByType].
     *
     * The engine calls this once per drain pass and again after every executed item, so it should
     * be cheap. Returning a defensive copy is expected — the engine iterates the list while
     * mutating the store through the other functions.
     *
     * @return the active queue, oldest first; empty when nothing is owed.
     */
    public suspend fun getAllActive(): List<OutboxItem>

    /**
     * Looks up a single item by [id], tolerating absence.
     *
     * This is the settle path's read: an executor reporting an outcome may be reporting on an item
     * that was superseded, wiped, or already settled, and `null` is the normal, expected answer in
     * those cases — not an error.
     *
     * @param id the item's [OutboxItem.id].
     * @return the item, or `null` if no row has that id.
     */
    public suspend fun getById(id: String): OutboxItem?

    /**
     * Records a failed attempt: sets [OutboxItem.attempts] to [attempts], [OutboxItem.nextRunAtEpochMillis]
     * to [nextRunAtEpochMillis], [OutboxItem.lastError] to [lastError], moves the item back to
     * [OutboxItemState.PENDING], and **clears the lease** ([OutboxItem.leaseUntilEpochMillis] to
     * `0`). All five in one atomic write.
     *
     * Clearing the lease is what returns a detached item to rotation, so a
     * [SettleResult.Failed][io.github.jamal_wia.kmptoolkit.outbox.SettleResult.Failed] on an
     * IN_FLIGHT row travels through here.
     *
     * ### The optimistic guard
     * [expectedLeaseUntilEpochMillis] makes this a compare-and-set. When non-`null`, the write
     * must apply **only if** the row's current [OutboxItem.leaseUntilEpochMillis] still equals it;
     * otherwise nothing is written and the function returns `false`. This closes the
     * lease-expiry-races-a-settle window: if the drain re-handed the item between the settle path's
     * read and this write, the lease changed, and the stale failure report must not clobber the
     * fresh claim (which would let two executors both believe they own the delivery).
     *
     * `null` skips the guard entirely — that is the drain's own path, where it already holds
     * single-flight ownership of the item.
     *
     * @param id the item's [OutboxItem.id]; an absent row is a no-op returning `false`.
     * @param attempts the new absolute attempt count (not a delta).
     * @param nextRunAtEpochMillis epoch millis before which the item must not be attempted again.
     * @param lastError a short diagnostic string, or `null`.
     * @param expectedLeaseUntilEpochMillis the lease value the caller observed, or `null` for no
     *   guard.
     * @return `true` if a row was updated; `false` if the row was absent or the guard rejected the
     *   write.
     */
    public suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedLeaseUntilEpochMillis: Long? = null,
    ): Boolean

    /**
     * Moves the item to [OutboxItemState.IN_FLIGHT] with [OutboxItem.leaseUntilEpochMillis] set to
     * [leaseUntilEpochMillis] — delivery now belongs to an external executor.
     *
     * The attempt counter must **not** change: handing off is not a failure. See
     * [AttemptResult.Detached][io.github.jamal_wia.kmptoolkit.outbox.AttemptResult.Detached] for
     * the protocol this is one step of.
     *
     * @param id the item's [OutboxItem.id]; an absent row is a no-op.
     * @param leaseUntilEpochMillis epoch millis at which the claim expires.
     */
    public suspend fun markInFlight(id: String, leaseUntilEpochMillis: Long)

    /**
     * Moves the item to [OutboxItemState.PARKED], stores [lastError], and clears any lease
     * ([OutboxItem.leaseUntilEpochMillis] to `0`) — out of rotation, still persisted, still visible
     * through [observeByType].
     *
     * Parking must not delete anything, and must not touch the attempt counter. It is the engine's
     * answer to "this cannot be delivered and retrying will not help, but throwing it away would
     * lose the user's data".
     *
     * @param id the item's [OutboxItem.id]; an absent row is a no-op.
     * @param lastError why it was parked, or `null`.
     */
    public suspend fun park(id: String, lastError: String?)

    /**
     * Deletes the item — the effect is no longer owed, either because it was delivered or because
     * a handler deliberately dropped it.
     *
     * @param id the item's [OutboxItem.id]; an absent row is a no-op.
     */
    public suspend fun deleteById(id: String)

    /**
     * Deletes every item whose [OutboxItem.tag] equals [tag], in any state.
     *
     * The tag is opaque to this library — it exists so the consuming app can wipe a whole class of
     * queued effects in one call. The canonical use is a logout wipe: effects enqueued under one
     * account must never replay under another's credentials.
     *
     * @param tag the exact tag to match; items with a different tag or a `null` tag are untouched.
     */
    public suspend fun deleteByTag(tag: String)

    /**
     * A live view of every item of one [OutboxItem.type], **in any state**, in insertion order.
     *
     * Unlike [getAllActive] this includes [OutboxItemState.PARKED] items — a parked effect is
     * precisely what a user-facing "failed to send" indicator needs to show.
     *
     * The flow must emit the current contents on collection and again on every change to the items
     * of that type. It must not complete on its own. Emitting more often than strictly necessary
     * is acceptable; missing a change is not.
     *
     * @param type the [OutboxItem.type] to watch.
     * @return a hot-or-cold flow of the matching items; never completes.
     */
    public fun observeByType(type: String): Flow<List<OutboxItem>>

    /**
     * Deletes every item, regardless of tag or state.
     *
     * A reset hatch for debug menus and test teardown. Production code should reach for
     * [deleteByTag] instead, which wipes a scope rather than everything.
     */
    public suspend fun clearAll()
}
