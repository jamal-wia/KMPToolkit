package io.github.jamal_wia.kmptoolkit.uploader.spi

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import kotlinx.coroutines.flow.Flow

/**
 * The persistence port of the uploader engine — **the one thing you must supply** to use this
 * module, and the reason it has no database dependency of its own.
 *
 * The engine owns every policy decision: which record runs next, how backoff is computed, when to
 * give up, how leases expire. A store owns none of them. It is a set of mechanical primitives over
 * a durable table, and the engine composes them. That split is what lets the same engine sit on
 * SQLDelight, Room, Realm, a file, or (in tests) a map — see `docs/kmptoolkit-uploader/07-custom-store.md`
 * for a walkthrough, and `kmptoolkit-uploader-testing`'s `InMemoryUploaderStore` for a complete
 * reference implementation you can read in one sitting.
 *
 * ## Contract
 *
 * These invariants are what the engine relies on. An implementation that breaks one does not fail
 * loudly — it loses or duplicates a delivery under a race, which is exactly the failure mode this
 * module exists to prevent. `kmptoolkit-uploader-testing` ships `AbstractUploaderStoreContractTest`,
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
 * That is why every decision the engine makes from a read and then acts on is a single
 * compare-and-set here rather than a read followed by a write: [markInFlight], [recordFailure] and
 * [park] write only if the row is still in the state and under the claim the caller read (and
 * [recordFailure] gives up in that same write), and [insertReplace] and [deleteByTag] hand back
 * exactly the rows they removed. Each must be one atomic read-modify-write.
 *
 * ### The claim counter
 * [UploaderItem.claim] is the version those three guards compare. A store keeps it as written on
 * insert (the engine inserts `0`) and **advances it by exactly one** — to the expected claim plus
 * one — in every guarded write it applies, and in no other way. The engine derives the claim it
 * hands an executor from that rule, so a store that skips an advance, or advances by anything else,
 * lets a superseded executor's report pass for the current one. Because every applied write moves
 * it, a row never returns to a (state, claim) pair it has left: the guards cannot be fooled by a
 * row that went away and came back looking the same.
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
 * tag) and **must treat an absent row as a no-op** (reported as `false`, or an empty list, where the
 * function returns something), never as an error. The settle and lease races
 * are designed around this: a late settle from an executor whose row was already superseded lands
 * on nothing and degrades to a no-op instead of corrupting a fresh claim. Calling any of them
 * twice with the same arguments must be indistinguishable from calling it once.
 *
 * ### Concurrency
 * Every function may be called concurrently, from different coroutines and different threads.
 * In practice the engine's drain is single-flight, but [Uploader.enqueue][io.github.jamal_wia.kmptoolkit.uploader.Uploader.enqueue]
 * runs on the caller's coroutine and `settle` on a platform executor's, so at least three callers
 * can overlap. Guard mutable state (a `Mutex`, a serialized database writer, whatever your engine
 * gives you).
 *
 * ### Dedup identity
 * The pair ([UploaderItem.type], [UploaderItem.uniqueKey]) is the dedup identity. A `null` unique key
 * never conflicts with anything — every keyless enqueue appends. See [insertKeep] for the exact
 * per-state behavior.
 *
 * ### Nothing here interprets a payload
 * [UploaderItem.payload] is an opaque string and [UploaderItem.tag] is an opaque label. A store stores
 * and returns them unchanged; it never parses, validates, or acts on either.
 */
public interface UploaderStore {

    /**
     * Inserts [record] unless an item with the same ([UploaderItem.type], [UploaderItem.uniqueKey])
     * already occupies the queue — the storage half of
     * [ConflictPolicy.KEEP][io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy.KEEP].
     *
     * Per-state behavior of an existing item with the same identity:
     * - [UploaderItemState.PENDING] — keep it, do not insert, return `false`.
     * - [UploaderItemState.IN_FLIGHT] — same: an in-flight delivery must win over a re-enqueue
     *   exactly like a waiting one, or a duplicate would be sent.
     * - [UploaderItemState.PARKED] — **replace it** and return `true`. A parked item is out of
     *   rotation with no other revive path, so letting it keep the key would make that key
     *   permanently dead and swallow every future enqueue. Replacing means deleting the parked row
     *   and inserting [record] at the tail of the insertion sequence, with its own fresh id.
     *
     * A `null` [UploaderItem.uniqueKey] never conflicts: always insert, always return `true`.
     *
     * @param record the new item, already carrying its final id and timestamps.
     * @return `true` if [record] was inserted, `false` if an existing PENDING/IN_FLIGHT item won.
     */
    public suspend fun insertKeep(record: UploaderItem): Boolean

    /**
     * Inserts [record], superseding any item with the same ([UploaderItem.type],
     * [UploaderItem.uniqueKey]) **in any state** — the storage half of
     * [ConflictPolicy.REPLACE][io.github.jamal_wia.kmptoolkit.uploader.ConflictPolicy.REPLACE].
     *
     * Superseding is a delete plus an insert, not an in-place edit: the old row's id, attempt
     * count, backoff gate and lease are all discarded, and the new row enters at the **tail** of
     * the insertion sequence. That is deliberate — a replaced item is new intent, and keeping the
     * old queue position would let a repeatedly-replaced key hold the head of its ordering channel
     * forever.
     *
     * A `null` [UploaderItem.uniqueKey] never conflicts: this is then a plain append.
     *
     * The superseded row comes back from the same atomic step that removed it. The engine cancels
     * that item's upload, so a read made beforehand would not do: an item enqueued between that read
     * and this delete would lose its row without its upload ever being cancelled.
     *
     * @param record the new item, already carrying its final id and timestamps.
     * @return the item [record] superseded, as it was just before its removal, or `null` if none held
     *   the identity.
     */
    public suspend fun insertReplace(record: UploaderItem): UploaderItem?

    /**
     * Every [UploaderItemState.PENDING] and [UploaderItemState.IN_FLIGHT] item, in insertion order.
     *
     * [UploaderItemState.PARKED] items are excluded: they are out of rotation, and including them
     * would let a parked item block the head of its ordering channel forever. They remain
     * observable through [observeByType].
     *
     * The engine calls this once per drain pass and again after every executed item, so it should
     * be cheap. Returning a defensive copy is expected — the engine iterates the list while
     * mutating the store through the other functions.
     *
     * @return the active queue, oldest first; empty when nothing is owed.
     */
    public suspend fun getAllActive(): List<UploaderItem>

    /**
     * Looks up a single item by [id], tolerating absence.
     *
     * This is the settle path's read: an executor reporting an outcome may be reporting on an item
     * that was superseded, wiped, or already settled, and `null` is the normal, expected answer in
     * those cases — not an error.
     *
     * @param id the item's [UploaderItem.id].
     * @return the item, or `null` if no row has that id.
     */
    public suspend fun getById(id: String): UploaderItem?

    /**
     * Records a failed attempt: sets [UploaderItem.attempts] to [attempts], [UploaderItem.nextRunAtEpochMillis]
     * to [nextRunAtEpochMillis], [UploaderItem.lastError] to [lastError], moves the item to the state
     * [disposition] names, and **clears the lease** ([UploaderItem.leaseUntilEpochMillis] to `0`). All
     * five in one atomic write, which also advances [UploaderItem.claim] to [expectedClaim] `+ 1` — or,
     * for [FailureDisposition.DROP], the row's deletion instead.
     *
     * Clearing the lease is what returns a detached item to rotation, so a
     * [SettleResult.Failed][io.github.jamal_wia.kmptoolkit.uploader.SettleResult.Failed] on an
     * IN_FLIGHT row travels through here.
     *
     * ### Giving up in the same write
     * When the failure exhausts the retry budget, [disposition] says so and the row goes straight to
     * [UploaderItemState.PARKED] (keeping the final attempt count and error) or is deleted. It must not
     * pass through [UploaderItemState.PENDING] on the way: a drain reading the row in between would hand
     * it off once more — an attempt past `maxAttempts`, on an item already given up.
     *
     * ### The optimistic guard
     * [expectedState] and [expectedClaim] make this a compare-and-set, exactly like [markInFlight]'s:
     * the write must apply **only if** the row is still in [expectedState] under [expectedClaim], the
     * state and claim the caller read it in; otherwise nothing is written and the function returns
     * `false`. Every caller has such a read, and every one can be overtaken between that read and this
     * write:
     * - a settle reporting a failure, by the drain re-handing the item after its lease expired — the
     *   claim moved, and the report must not clobber the fresh claim (which would let two executors
     *   both believe they own the delivery);
     * - the drain's own attempt, while the handler runs, by a settle from a detached executor of an
     *   earlier attempt — a park moves the state and the claim, and the failure must not return a
     *   known-permanent failure to rotation.
     *
     * The claim, not the lease, is what is compared: two claims can carry the same lease — written in
     * the same millisecond, or across a wall clock that stepped backwards — and a park or a failure
     * clears the lease to `0`, the lease a pending row carries too.
     *
     * There is no unguarded form. The drain's single-flight ownership covers other drain passes, not a
     * settle arriving on an executor's own coroutine.
     *
     * @param id the item's [UploaderItem.id]; an absent row is a no-op returning `false`.
     * @param attempts the new absolute attempt count (not a delta).
     * @param nextRunAtEpochMillis epoch millis before which the item must not be attempted again.
     * @param lastError a short diagnostic string, or `null`.
     * @param expectedState the state the caller read the item in — [UploaderItemState.IN_FLIGHT] for a
     *   settle or a failed hand-off, the drain's read for an attempt it ran itself.
     * @param expectedClaim the [UploaderItem.claim] the caller read alongside [expectedState].
     * @param disposition where the failure leaves the row: back in rotation ([FailureDisposition.RETRY],
     *   the default), out of it ([FailureDisposition.PARK]), or gone ([FailureDisposition.DROP]). The
     *   guard applies to all three alike.
     * @return `true` if a row was updated (or, for [FailureDisposition.DROP], deleted); `false` if the
     *   row was absent or the guard rejected the write.
     */
    public suspend fun recordFailure(
        id: String,
        attempts: Int,
        nextRunAtEpochMillis: Long,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
        disposition: FailureDisposition = FailureDisposition.RETRY,
    ): Boolean

    /**
     * Claims the item for an external executor: moves it to [UploaderItemState.IN_FLIGHT] with
     * [UploaderItem.leaseUntilEpochMillis] set to [leaseUntilEpochMillis] and [UploaderItem.claim]
     * advanced to [expectedClaim] `+ 1` — **only if** it is still in [expectedState] under
     * [expectedClaim], the state and claim the caller read it in. Otherwise nothing is written and the
     * function returns `false`.
     *
     * The advanced claim is the one the new executor holds: the engine handed it out as
     * [AttemptContext.claim][io.github.jamal_wia.kmptoolkit.uploader.AttemptContext.claim], and a report
     * carrying any earlier one is superseded from this write on.
     *
     * The guard is what keeps a claim made from a stale read from reviving a settled item. The drain
     * reads the queue, then claims; in between, a detached executor may report its outcome — parking
     * the item on a permanent failure, or returning it to [UploaderItemState.PENDING] under a backoff
     * gate. An unguarded claim would overwrite either with a fresh lease, and the transport would upload
     * a known-permanent failure again, or retry ahead of its gate. It must be one atomic
     * read-modify-write, like [recordFailure]'s.
     *
     * The attempt counter must **not** change: handing off is not a failure. See
     * [AttemptResult.Detached][io.github.jamal_wia.kmptoolkit.uploader.AttemptResult.Detached] for
     * the protocol this is one step of.
     *
     * @param id the item's [UploaderItem.id]; an absent row is a no-op returning `false`.
     * @param leaseUntilEpochMillis epoch millis at which the new claim expires.
     * @param expectedState the state the caller read the item in — [UploaderItemState.PENDING] for a
     *   first hand-off, [UploaderItemState.IN_FLIGHT] for a re-hand after an expired lease.
     * @param expectedClaim the [UploaderItem.claim] the caller read alongside [expectedState].
     * @return `true` if the claim was written; `false` if the row was absent or had moved.
     */
    public suspend fun markInFlight(
        id: String,
        leaseUntilEpochMillis: Long,
        expectedState: UploaderItemState,
        expectedClaim: Long,
    ): Boolean

    /**
     * Moves the item to [UploaderItemState.PARKED], stores [lastError], clears any lease
     * ([UploaderItem.leaseUntilEpochMillis] to `0`) and advances [UploaderItem.claim] to
     * [expectedClaim] `+ 1` — out of rotation, still persisted, still visible through [observeByType] —
     * **only if** the item is still in [expectedState] under [expectedClaim]. Otherwise nothing is
     * written and the function returns `false`.
     *
     * Parking must not delete anything, and must not touch the attempt counter. It is the engine's
     * answer to "this cannot be delivered and retrying will not help, but throwing it away would
     * lose the user's data".
     *
     * Guarded like [recordFailure], and for the same reason: a park is a verdict on the attempt the
     * caller read, and a re-hand-off that landed since then has made it a verdict on an attempt the
     * engine no longer runs. An unguarded park would take the item from the executor now holding it.
     *
     * @param id the item's [UploaderItem.id]; an absent row is a no-op returning `false`.
     * @param lastError why it was parked, or `null`.
     * @param expectedState the state the caller read the item in.
     * @param expectedClaim the [UploaderItem.claim] the caller read alongside [expectedState].
     * @return `true` if the row was parked; `false` if it was absent or had moved.
     */
    public suspend fun park(
        id: String,
        lastError: String?,
        expectedState: UploaderItemState,
        expectedClaim: Long,
    ): Boolean

    /**
     * Deletes the item — the effect is no longer owed, either because it was delivered or because
     * a handler deliberately dropped it.
     *
     * @param id the item's [UploaderItem.id]; an absent row is a no-op.
     */
    public suspend fun deleteById(id: String)

    /**
     * Deletes every item whose [UploaderItem.tag] equals [tag], in any state.
     *
     * The tag is opaque to this library — it exists so the consuming app can wipe a whole class of
     * queued effects in one call. The canonical use is a logout wipe: effects enqueued under one
     * account must never replay under another's credentials.
     *
     * The removed rows come back from the same atomic step that removed them, for the same reason as
     * [insertReplace]'s: the engine cancels the upload of every item the wipe removed, and an item
     * enqueued while the wipe ran must be among them — a read made beforehand would miss it.
     *
     * @param tag the exact tag to match; items with a different tag or a `null` tag are untouched.
     * @return every item removed, in every state and in insertion order, as each was just before its
     *   removal; empty when nothing carried [tag].
     */
    public suspend fun deleteByTag(tag: String): List<UploaderItem>

    /**
     * A live view of every item of one [UploaderItem.type], **in any state**, in insertion order.
     *
     * Unlike [getAllActive] this includes [UploaderItemState.PARKED] items — a parked effect is
     * precisely what a user-facing "failed to send" indicator needs to show.
     *
     * The flow must emit the current contents on collection and again on every change to the items
     * of that type. It must not complete on its own. Emitting more often than strictly necessary
     * is acceptable; missing a change is not.
     *
     * @param type the [UploaderItem.type] to watch.
     * @return a hot-or-cold flow of the matching items; never completes.
     */
    public fun observeByType(type: String): Flow<List<UploaderItem>>

    /**
     * Deletes every item, regardless of tag or state.
     *
     * A reset hatch for debug menus and test teardown. Production code should reach for
     * [deleteByTag] instead, which wipes a scope rather than everything.
     */
    public suspend fun clearAll()
}

/**
 * Where [UploaderStore.recordFailure] leaves a row whose attempt failed. The engine derives it from the
 * handler's [GiveUpPolicy][io.github.jamal_wia.kmptoolkit.uploader.GiveUpPolicy], so a failure and the
 * give-up it triggers are one atomic write.
 */
public enum class FailureDisposition {

    /** Back to [UploaderItemState.PENDING], to be retried once its backoff gate passes. */
    RETRY,

    /** To [UploaderItemState.PARKED]: the retry budget is spent; the item is kept but not retried. */
    PARK,

    /** Deleted: the retry budget is spent, and the handler's policy drops what it cannot deliver. */
    DROP,
}
