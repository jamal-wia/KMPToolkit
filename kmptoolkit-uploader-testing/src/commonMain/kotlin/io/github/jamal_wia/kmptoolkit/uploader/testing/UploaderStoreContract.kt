package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlinx.coroutines.flow.first

/**
 * Every invariant [UploaderStore] promises, as runnable checks — so a store implementation can prove
 * it holds them instead of being reviewed for it.
 *
 * Point it at a factory that produces a fresh, empty store and call [verifyAll] from a single test.
 * A violation throws [UploaderStoreContractViolation] naming the invariant that broke:
 *
 * ```kotlin
 * class MyUploaderStoreContractTest {
 *     @Test
 *     fun `it satisfies the UploaderStore contract`() = runTest {
 *         UploaderStoreContract { MyUploaderStore(freshDatabase()) }.verifyAll()
 *     }
 * }
 * ```
 *
 * Every check is also public on its own, so you can map them onto individual test methods and get
 * per-invariant reporting:
 *
 * ```kotlin
 * private val contract = UploaderStoreContract { MyUploaderStore(freshDatabase()) }
 *
 * @Test fun `insertion order survives same-millisecond inserts`() = runTest {
 *     contract.insertionOrderSurvivesSameMillisecondInserts()
 * }
 * ```
 *
 * ### Why this is not a JUnit base class
 *
 * It deliberately carries no test framework: `kotlin.test`'s JVM annotations are typealiases to a
 * framework's own, so a published base class would drag JUnit onto the compile classpath of every
 * consumer of this artifact, including their iOS build. Plain suspending functions and a typed
 * failure work with any framework, on any target.
 *
 * ### What it cannot check
 *
 * Durability across a process restart — no in-process check can observe that. If your store buffers
 * writes anywhere, prove that separately, because the engine's central promise depends on it.
 *
 * @param createStore produces a fresh, empty store. Called once per check, so no check can be
 *   influenced by another's leftovers.
 */
public class UploaderStoreContract(private val createStore: () -> UploaderStore) {

    /**
     * Runs every check in this class, in order, and returns the number that passed.
     *
     * @throws UploaderStoreContractViolation at the first invariant that does not hold.
     */
    public suspend fun verifyAll(): Int {
        val checks: List<suspend () -> Unit> = listOf(
            ::freshStoreIsEmpty,
            ::getByIdReturnsNullForUnknownId,
            ::insertedItemRoundTripsUnchanged,
            ::insertionOrderSurvivesSameMillisecondInserts,
            ::getAllActiveExcludesParked,
            ::getAllActiveIncludesInFlight,
            ::nullUniqueKeyNeverConflicts,
            ::insertKeepIsRefusedByPending,
            ::insertKeepIsRefusedByInFlight,
            ::insertKeepSupersedesParked,
            ::uniqueKeysAreScopedPerType,
            ::insertReplaceSupersedesPendingAtTheTail,
            ::insertReplaceSupersedesInFlightAndResetsIt,
            ::insertReplaceSupersedesParked,
            ::insertReplaceReturnsWhatItSuperseded,
            ::insertReplaceReturnsNullWhenNothingWasSuperseded,
            ::recordFailureWritesEveryFieldAndClearsTheLease,
            ::recordFailureAppliesWhenTheLeaseMatches,
            ::recordFailureIsRejectedWhenTheLeaseMoved,
            ::recordFailureCanParkInTheSameWrite,
            ::recordFailureCanDropInTheSameWrite,
            ::recordFailureGivingUpIsRejectedWhenTheLeaseMoved,
            ::recordFailureOnAnAbsentRowReturnsFalse,
            ::markInFlightLeavesAttemptsAlone,
            ::markInFlightRehandsAnExpiredClaim,
            ::markInFlightIsRejectedWhenTheLeaseMoved,
            ::markInFlightIsRejectedForAParkedItem,
            ::markInFlightIsRejectedForAnItemThatFailedSinceTheRead,
            ::markInFlightOnAnAbsentRowIsANoOp,
            ::parkKeepsTheItemAndClearsTheLease,
            ::parkOnAnAbsentRowIsANoOp,
            ::deleteByIdRemovesOnlyThatItem,
            ::deleteByIdIsIdempotent,
            ::deleteByTagRemovesEveryStateOfThatTag,
            ::deleteByTagLeavesOtherTagsAlone,
            ::deleteByTagReturnsWhatItRemoved,
            ::observeByTypeFiltersAndOrders,
            ::observeByTypeIncludesParked,
            ::observeByTypeEmitsEmptyForAnUnknownType,
            ::clearAllEmptiesTheStore,
        )
        checks.forEach { check -> check() }
        return checks.size
    }

    /** A fresh store reports nothing active. */
    public suspend fun freshStoreIsEmpty() {
        val store: UploaderStore = createStore()
        expect(store.getAllActive().isEmpty()) { "a fresh store must have no active items" }
    }

    /** An id that was never inserted reads back as `null` rather than failing. */
    public suspend fun getByIdReturnsNullForUnknownId() {
        val store: UploaderStore = createStore()
        expect(store.getById("never-inserted") == null) {
            "getById must return null for an unknown id"
        }
    }

    /** Every field written is the field read back — nothing is normalized, defaulted, or dropped. */
    public suspend fun insertedItemRoundTripsUnchanged() {
        val store: UploaderStore = createStore()
        val original: UploaderItem = item(id = "a", uniqueKey = "u", orderingKey = "o", tag = "t").copy(
            payload = "{\"body\":\"hi\"}",
            schemaVersion = 7,
            attempts = 3,
            nextRunAtEpochMillis = 1_234L,
            createdAtEpochMillis = 99L,
            lastError = "boom",
            leaseUntilEpochMillis = 555L,
        )
        store.insertKeep(original)
        val stored: UploaderItem? = store.getById("a")
        expect(stored == original) { "an inserted item must round-trip unchanged; got $stored" }
    }

    /**
     * Insertion order is a sequence, not a timestamp.
     *
     * Every item here claims the same creation time, so a store that orders by `created_at` shuffles
     * them and fails — which is exactly the bug this check exists to catch, since the engine derives
     * its FIFO channel heads from this order.
     */
    public suspend fun insertionOrderSurvivesSameMillisecondInserts() {
        val store: UploaderStore = createStore()
        repeat(RAPID_INSERT_COUNT) { index ->
            store.insertKeep(item(id = "id-$index", createdAtEpochMillis = 1_000L))
        }
        val actual: List<String> = store.getAllActive().map { it.id }
        expect(actual == List(RAPID_INSERT_COUNT) { "id-$it" }) {
            "getAllActive must preserve insertion order even for same-millisecond inserts; got $actual"
        }
    }

    /** A parked item leaves the active set, so it stops blocking its ordering channel. */
    public suspend fun getAllActiveExcludesParked() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        store.insertKeep(item(id = "b"))
        store.insertKeep(item(id = "c"))
        store.park("b", "parked")
        val actual: List<String> = store.getAllActive().map { it.id }
        expect(actual == listOf("a", "c")) {
            "getAllActive must exclude parked items and keep the rest in order; got $actual"
        }
    }

    /** An in-flight item is still owed, so it stays in the active set. */
    public suspend fun getAllActiveIncludesInFlight() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 500L)
        expect(store.getAllActive().map { it.id } == listOf("a")) {
            "getAllActive must include IN_FLIGHT items"
        }
    }

    /** Keyless items never deduplicate against each other. */
    public suspend fun nullUniqueKeyNeverConflicts() {
        val store: UploaderStore = createStore()
        val first: Boolean = store.insertKeep(item(id = "a", uniqueKey = null))
        val second: Boolean = store.insertKeep(item(id = "b", uniqueKey = null))
        expect(first && second) { "a null unique key must never conflict" }
        expect(store.getAllActive().map { it.id } == listOf("a", "b")) {
            "both keyless inserts must be stored, in order"
        }
    }

    /** A pending item wins over a KEEP re-enqueue of the same identity. */
    public suspend fun insertKeepIsRefusedByPending() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        val inserted: Boolean = store.insertKeep(item(id = "b", uniqueKey = "u"))
        expect(!inserted) { "insertKeep must return false when a PENDING item holds the key" }
        expect(store.getById("b") == null) { "the refused item must not be stored" }
    }

    /** So does an in-flight one — otherwise the same effect could be delivered twice. */
    public suspend fun insertKeepIsRefusedByInFlight() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val inserted: Boolean = store.insertKeep(item(id = "b", uniqueKey = "u"))
        expect(!inserted) { "insertKeep must return false when an IN_FLIGHT item holds the key" }
    }

    /** A parked item does not: its key would otherwise be dead forever. */
    public suspend fun insertKeepSupersedesParked() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        store.park("a", "gave up")
        val inserted: Boolean = store.insertKeep(item(id = "b", uniqueKey = "u"))
        expect(inserted) { "insertKeep must supersede a PARKED item holding the key" }
        expect(store.getById("a") == null) { "the superseded parked item must be gone" }
        expect(store.getAllActive().map { it.id } == listOf("b")) { "the new item must be active" }
    }

    /** The dedup identity is (type, uniqueKey) — the same key under two types is two items. */
    public suspend fun uniqueKeysAreScopedPerType() {
        val store: UploaderStore = createStore()
        val first: Boolean = store.insertKeep(item(id = "a", type = "one", uniqueKey = "u"))
        val second: Boolean = store.insertKeep(item(id = "b", type = "two", uniqueKey = "u"))
        expect(first && second) { "the same unique key under different types must not conflict" }
    }

    /** A replacement enters at the tail, so a repeatedly replaced key cannot hold its channel head. */
    public suspend fun insertReplaceSupersedesPendingAtTheTail() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        store.insertKeep(item(id = "other", uniqueKey = null))
        store.insertReplace(item(id = "b", uniqueKey = "u"))
        expect(store.getById("a") == null) { "the superseded item must be gone" }
        val actual: List<String> = store.getAllActive().map { it.id }
        expect(actual == listOf("other", "b")) {
            "a replacement must re-enter at the tail of the insertion sequence; got $actual"
        }
    }

    /** Replacing an in-flight item discards its claim and its retry state along with it. */
    public suspend fun insertReplaceSupersedesInFlightAndResetsIt() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u", attempts = 4))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        store.insertReplace(item(id = "b", uniqueKey = "u"))
        expect(store.getById("a") == null) { "the superseded in-flight item must be gone" }
        val replacement: UploaderItem? = store.getById("b")
        expect(replacement?.state == UploaderItemState.PENDING) { "the replacement must be PENDING" }
        expect(replacement?.leaseUntilEpochMillis == 0L) { "the replacement must carry no lease" }
        expect(replacement?.attempts == 0) { "the replacement must start with a clean retry budget" }
    }

    /** And replacing a parked item revives the key. */
    public suspend fun insertReplaceSupersedesParked() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        store.park("a", "gave up")
        store.insertReplace(item(id = "b", uniqueKey = "u"))
        expect(store.getById("a") == null) { "the superseded parked item must be gone" }
        expect(store.getAllActive().map { it.id } == listOf("b")) { "the new item must be active" }
    }

    /**
     * The superseded row comes back from the replacement itself, as it was just before its removal —
     * the engine cancels that item's upload, and a read made beforehand could miss an item enqueued in
     * between.
     */
    public suspend fun insertReplaceReturnsWhatItSuperseded() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u", tag = "t"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val superseded: UploaderItem? = store.insertReplace(item(id = "b", uniqueKey = "u"))
        expect(superseded?.id == "a") { "insertReplace must return the item it superseded; got $superseded" }
        expect(superseded?.state == UploaderItemState.IN_FLIGHT) { "as it was: IN_FLIGHT" }
        expect(superseded?.leaseUntilEpochMillis == 900L) { "as it was: under its lease" }
        expect(superseded?.tag == "t") { "as it was: with its tag" }
    }

    /** Nothing superseded, nothing returned — a keyless append and a fresh key alike. */
    public suspend fun insertReplaceReturnsNullWhenNothingWasSuperseded() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "other", type = "elsewhere", uniqueKey = "u"))
        val keyless: UploaderItem? = store.insertReplace(item(id = "a", uniqueKey = null))
        val freshKey: UploaderItem? = store.insertReplace(item(id = "b", uniqueKey = "u"))
        expect(keyless == null) { "a keyless replacement supersedes nothing; got $keyless" }
        expect(freshKey == null) { "a key held only under another type supersedes nothing; got $freshKey" }
    }

    /** A recorded failure sets all five fields at once, including clearing the lease. */
    public suspend fun recordFailureWritesEveryFieldAndClearsTheLease() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.recordFailure("a", 4, 77L, "e")
        expect(applied) { "recordFailure must report that it wrote" }
        val updated: UploaderItem? = store.getById("a")
        expect(updated?.state == UploaderItemState.PENDING) { "the item must return to PENDING" }
        expect(updated?.attempts == 4) { "attempts must be the absolute value passed in" }
        expect(updated?.nextRunAtEpochMillis == 77L) { "the backoff gate must be stored" }
        expect(updated?.lastError == "e") { "the error must be stored" }
        expect(updated?.leaseUntilEpochMillis == 0L) { "the lease must be cleared" }
    }

    /** The optimistic guard lets the write through when nothing moved. */
    public suspend fun recordFailureAppliesWhenTheLeaseMatches() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.recordFailure("a", 1, 10L, null, expectedLeaseUntilEpochMillis = 900L)
        expect(applied) { "a matching expected lease must let the write through" }
        expect(store.getById("a")?.attempts == 1) { "the write must have applied" }
    }

    /**
     * And rejects it when the item was re-handed in between — the check that keeps a crash-and-
     * recover cycle from producing two live claims on one item.
     */
    public suspend fun recordFailureIsRejectedWhenTheLeaseMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", from = 900L, to = 1_800L) // the drain re-handed it
        val applied: Boolean =
            store.recordFailure("a", 1, 10L, "stale", expectedLeaseUntilEpochMillis = 900L)
        expect(!applied) { "a stale expected lease must reject the write" }
        val untouched: UploaderItem? = store.getById("a")
        expect(untouched?.state == UploaderItemState.IN_FLIGHT) { "the fresh claim must survive" }
        expect(untouched?.leaseUntilEpochMillis == 1_800L) { "the fresh lease must survive" }
        expect(untouched?.attempts == 0) { "a rejected write must not bump attempts" }
        expect(untouched?.lastError == null) { "a rejected write must not store an error" }
    }

    /** A failure that spends the budget parks in the same write, keeping its final count and error. */
    public suspend fun recordFailureCanParkInTheSameWrite() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.recordFailure(
            "a",
            3,
            77L,
            "gave up",
            expectedLeaseUntilEpochMillis = 900L,
            disposition = FailureDisposition.PARK,
        )
        expect(applied) { "recordFailure with PARK must report that it wrote" }
        val parked: UploaderItem? = store.getById("a")
        expect(parked?.state == UploaderItemState.PARKED) { "PARK must leave the item PARKED, never PENDING" }
        expect(parked?.attempts == 3) { "the final attempt count must be stored" }
        expect(parked?.lastError == "gave up") { "the error must be stored" }
        expect(parked?.leaseUntilEpochMillis == 0L) { "the lease must be cleared" }
        expect(store.getAllActive().isEmpty()) { "a parked item is out of rotation" }
    }

    /** And drops in the same write. */
    public suspend fun recordFailureCanDropInTheSameWrite() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        store.insertKeep(item(id = "b"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.recordFailure(
            "a",
            3,
            77L,
            "gave up",
            expectedLeaseUntilEpochMillis = 900L,
            disposition = FailureDisposition.DROP,
        )
        expect(applied) { "recordFailure with DROP must report that it deleted" }
        expect(store.getById("a") == null) { "DROP must delete the item" }
        expect(store.getAllActive().map { it.id } == listOf("b")) { "DROP must delete only that item" }
    }

    /** A give-up obeys the same guard as a retry: a stale one must not touch a fresh claim. */
    public suspend fun recordFailureGivingUpIsRejectedWhenTheLeaseMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", from = 900L, to = 1_800L)
        val parked: Boolean = store.recordFailure("a", 1, 10L, "stale", 900L, FailureDisposition.PARK)
        val dropped: Boolean = store.recordFailure("a", 1, 10L, "stale", 900L, FailureDisposition.DROP)
        expect(!parked && !dropped) { "a stale expected lease must reject a give-up" }
        val untouched: UploaderItem? = store.getById("a")
        expect(untouched?.state == UploaderItemState.IN_FLIGHT) { "the fresh claim must survive" }
        expect(untouched?.leaseUntilEpochMillis == 1_800L) { "the fresh lease must survive" }
    }

    /** An absent row is a no-op, reported as `false`, never an exception. */
    public suspend fun recordFailureOnAnAbsentRowReturnsFalse() {
        val store: UploaderStore = createStore()
        expect(!store.recordFailure("ghost", 1, 0L, null)) {
            "recordFailure on an absent row must return false"
        }
    }

    /** Handing off is not a failure, so the attempt counter must not move. */
    public suspend fun markInFlightLeavesAttemptsAlone() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", attempts = 2))
        val claimed: Boolean = store.markInFlight(
            "a",
            leaseUntilEpochMillis = 4_000L,
            expectedState = UploaderItemState.PENDING,
            expectedLeaseUntilEpochMillis = 0L,
        )
        expect(claimed) { "markInFlight must report a claim it wrote" }
        val updated: UploaderItem? = store.getById("a")
        expect(updated?.state == UploaderItemState.IN_FLIGHT) { "the item must be IN_FLIGHT" }
        expect(updated?.leaseUntilEpochMillis == 4_000L) { "the lease must be stored" }
        expect(updated?.attempts == 2) { "markInFlight must not touch the attempt counter" }
    }

    /** An expired claim can be re-handed: the claim expects IN_FLIGHT under the lease that expired. */
    public suspend fun markInFlightRehandsAnExpiredClaim() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val rehanded: Boolean = store.markInFlight("a", 1_800L, UploaderItemState.IN_FLIGHT, 900L)
        expect(rehanded) { "a re-hand that expects the current state and lease must be written" }
        val updated: UploaderItem? = store.getById("a")
        expect(updated?.state == UploaderItemState.IN_FLIGHT) { "the re-handed item must be IN_FLIGHT" }
        expect(updated?.leaseUntilEpochMillis == 1_800L) { "the re-hand must carry the new lease" }
    }

    /** Two claims made from one read: the second finds the lease moved and must not overwrite it. */
    public suspend fun markInFlightIsRejectedWhenTheLeaseMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", from = 900L, to = 1_800L)
        val stale: Boolean = store.markInFlight("a", 2_700L, UploaderItemState.IN_FLIGHT, 900L)
        expect(!stale) { "a claim expecting a lease that moved must be rejected" }
        expect(store.getById("a")?.leaseUntilEpochMillis == 1_800L) { "the current claim must survive" }
    }

    /**
     * A claim made from a read that predates a park must not revive the item — the check that keeps a
     * known-permanent failure from being uploaded again.
     */
    public suspend fun markInFlightIsRejectedForAParkedItem() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "pending"))
        store.insertKeep(item(id = "expired"))
        claim(store, "expired", leaseUntilEpochMillis = 900L)
        store.park("pending", "permanent")
        store.park("expired", "permanent")
        val revivedPending: Boolean = store.markInFlight("pending", 900L, UploaderItemState.PENDING, 0L)
        val revivedExpired: Boolean = store.markInFlight("expired", 1_800L, UploaderItemState.IN_FLIGHT, 900L)
        expect(!revivedPending && !revivedExpired) { "markInFlight must not claim a PARKED item" }
        expect(store.getById("pending")?.state == UploaderItemState.PARKED) { "it must stay PARKED" }
        expect(store.getById("expired")?.state == UploaderItemState.PARKED) { "it must stay PARKED" }
    }

    /** Nor overwrite a failure that returned the item to PENDING under a backoff gate. */
    public suspend fun markInFlightIsRejectedForAnItemThatFailedSinceTheRead() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        store.recordFailure("a", 1, 5_000L, "503", expectedLeaseUntilEpochMillis = 900L)
        val stale: Boolean = store.markInFlight("a", 1_800L, UploaderItemState.IN_FLIGHT, 900L)
        expect(!stale) { "a claim expecting IN_FLIGHT must be rejected for a PENDING item" }
        val failed: UploaderItem? = store.getById("a")
        expect(failed?.state == UploaderItemState.PENDING) { "the failure's state must survive" }
        expect(failed?.nextRunAtEpochMillis == 5_000L) { "the failure's backoff gate must survive" }
    }

    /** Every id-addressed write tolerates an absent row. */
    public suspend fun markInFlightOnAnAbsentRowIsANoOp() {
        val store: UploaderStore = createStore()
        val claimed: Boolean = store.markInFlight("ghost", 4_000L, UploaderItemState.PENDING, 0L)
        expect(!claimed) { "markInFlight on an absent row must return false" }
        expect(store.getById("ghost") == null) { "markInFlight must not create a row" }
    }

    /** Parking keeps the item and its attempt count, and drops the claim. */
    public suspend fun parkKeepsTheItemAndClearsTheLease() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", attempts = 5))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        store.park("a", "permanent 400")
        val parked: UploaderItem? = store.getById("a")
        expect(parked?.state == UploaderItemState.PARKED) { "the item must be PARKED" }
        expect(parked?.lastError == "permanent 400") { "the reason must be stored" }
        expect(parked?.leaseUntilEpochMillis == 0L) { "parking must clear the lease" }
        expect(parked?.attempts == 5) { "parking must not touch the attempt counter" }
    }

    /** Parking an absent row does nothing. */
    public suspend fun parkOnAnAbsentRowIsANoOp() {
        val store: UploaderStore = createStore()
        store.park("ghost", "whatever")
        expect(store.getById("ghost") == null) { "park must not create a row" }
    }

    /** Deletion is addressed, not broad. */
    public suspend fun deleteByIdRemovesOnlyThatItem() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        store.insertKeep(item(id = "b"))
        store.deleteById("a")
        expect(store.getAllActive().map { it.id } == listOf("b")) {
            "deleteById must remove only the addressed item"
        }
    }

    /** Deleting twice is indistinguishable from deleting once — the duplicate-settle path. */
    public suspend fun deleteByIdIsIdempotent() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        store.insertKeep(item(id = "b"))
        store.deleteById("a")
        store.deleteById("a")
        expect(store.getAllActive().map { it.id } == listOf("b")) {
            "a second deleteById must change nothing"
        }
    }

    /** A tag wipe crosses every state — that is what makes it a privacy guarantee. */
    public suspend fun deleteByTagRemovesEveryStateOfThatTag() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "pending", tag = "session"))
        store.insertKeep(item(id = "inflight", tag = "session"))
        store.insertKeep(item(id = "parked", tag = "session"))
        store.insertKeep(item(id = "other", tag = "keep-me"))
        store.insertKeep(item(id = "untagged", tag = null))
        claim(store, "inflight", leaseUntilEpochMillis = 900L)
        store.park("parked", "x")

        store.deleteByTag("session")

        expect(store.getById("pending") == null) { "a pending tagged item must be wiped" }
        expect(store.getById("inflight") == null) { "an in-flight tagged item must be wiped" }
        expect(store.getById("parked") == null) { "a parked tagged item must be wiped" }
        val remaining: List<String> = store.getAllActive().map { it.id }
        expect(remaining == listOf("other", "untagged")) {
            "other tags and untagged items must survive; got $remaining"
        }
    }

    /** A tag nothing carries wipes nothing. */
    public suspend fun deleteByTagLeavesOtherTagsAlone() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", tag = "one"))
        store.deleteByTag("two")
        expect(store.getAllActive().map { it.id } == listOf("a")) {
            "deleting an unused tag must change nothing"
        }
    }

    /** The wipe hands back every row it removed, in every state and in insertion order, as each was. */
    public suspend fun deleteByTagReturnsWhatItRemoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "pending", tag = "session"))
        store.insertKeep(item(id = "other", tag = "keep-me"))
        store.insertKeep(item(id = "inflight", tag = "session"))
        store.insertKeep(item(id = "parked", tag = "session"))
        claim(store, "inflight", leaseUntilEpochMillis = 900L)
        store.park("parked", "x")

        val removed: List<UploaderItem> = store.deleteByTag("session")

        expect(removed.map { it.id } == listOf("pending", "inflight", "parked")) {
            "deleteByTag must return every removed item in insertion order; got ${removed.map { it.id }}"
        }
        expect(removed.map { it.state } == listOf(UploaderItemState.PENDING, UploaderItemState.IN_FLIGHT, UploaderItemState.PARKED)) {
            "each removed item must come back in the state it was removed in; got ${removed.map { it.state }}"
        }
        expect(removed[1].leaseUntilEpochMillis == 900L) { "the in-flight item must come back with its lease" }
        expect(store.deleteByTag("session").isEmpty()) { "a second wipe of the same tag removes, and returns, nothing" }
    }

    /** The observation flow filters by type and keeps insertion order. */
    public suspend fun observeByTypeFiltersAndOrders() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", type = "one"))
        store.insertKeep(item(id = "b", type = "two"))
        store.insertKeep(item(id = "c", type = "one"))
        val observed: List<String> = store.observeByType("one").first().map { it.id }
        expect(observed == listOf("a", "c")) {
            "observeByType must emit only that type, in order; got $observed"
        }
    }

    /** And includes parked items, which is what a "failed to send" indicator binds to. */
    public suspend fun observeByTypeIncludesParked() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", type = "one"))
        store.park("a", "x")
        expect(store.observeByType("one").first().map { it.id } == listOf("a")) {
            "observeByType must include PARKED items"
        }
    }

    /** An unknown type emits an empty list rather than never emitting. */
    public suspend fun observeByTypeEmitsEmptyForAnUnknownType() {
        val store: UploaderStore = createStore()
        expect(store.observeByType("nobody").first().isEmpty()) {
            "observeByType must emit an empty list for a type with no items"
        }
    }

    /** The reset hatch really resets. */
    public suspend fun clearAllEmptiesTheStore() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", tag = "one"))
        store.insertKeep(item(id = "b", tag = null))
        store.park("b", "x")
        store.clearAll()
        expect(store.getAllActive().isEmpty()) { "clearAll must empty the active set" }
        expect(store.getById("a") == null && store.getById("b") == null) {
            "clearAll must remove items in every state"
        }
    }

    /**
     * Builds an item with defaults, so a check names only the fields it cares about.
     *
     * Public because a store with extra requirements — a foreign key, a non-null column your schema
     * adds — may need to build on it when writing its own checks alongside these.
     */
    public fun item(
        id: String,
        type: String = DEFAULT_TYPE,
        uniqueKey: String? = null,
        orderingKey: String? = null,
        tag: String? = null,
        state: UploaderItemState = UploaderItemState.PENDING,
        attempts: Int = 0,
        nextRunAtEpochMillis: Long = 0L,
        createdAtEpochMillis: Long = 0L,
        leaseUntilEpochMillis: Long = 0L,
    ): UploaderItem = UploaderItem(
        id = id,
        type = type,
        payload = "payload-$id",
        schemaVersion = 1,
        uniqueKey = uniqueKey,
        orderingKey = orderingKey,
        tag = tag,
        state = state,
        attempts = attempts,
        nextRunAtEpochMillis = nextRunAtEpochMillis,
        createdAtEpochMillis = createdAtEpochMillis,
        lastError = null,
        leaseUntilEpochMillis = leaseUntilEpochMillis,
    )

    /** A first hand-off of a pending item, asserted to have been written — the setup most checks need. */
    private suspend fun claim(store: UploaderStore, id: String, leaseUntilEpochMillis: Long) {
        val claimed: Boolean = store.markInFlight(id, leaseUntilEpochMillis, UploaderItemState.PENDING, 0L)
        expect(claimed) { "markInFlight must claim a PENDING item expected as PENDING with no lease" }
    }

    /** A re-hand of an expired claim, the drain's other use of [UploaderStore.markInFlight]. */
    private suspend fun rehand(store: UploaderStore, id: String, from: Long, to: Long) {
        val rehanded: Boolean = store.markInFlight(id, to, UploaderItemState.IN_FLIGHT, from)
        expect(rehanded) { "markInFlight must re-hand an IN_FLIGHT item expected under its current lease" }
    }

    private inline fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw UploaderStoreContractViolation(message())
    }

    private companion object {

        /** Enough inserts that a timestamp-ordered store would visibly shuffle them. */
        const val RAPID_INSERT_COUNT: Int = 20

        const val DEFAULT_TYPE: String = "contract.type"
    }
}

/**
 * Thrown by [UploaderStoreContract] when a store breaks one of [UploaderStore]'s documented invariants.
 *
 * An `AssertionError`, so every test framework reports it as a failed assertion rather than as an
 * unexpected error.
 *
 * @param message which invariant broke, and what was seen instead.
 */
public class UploaderStoreContractViolation(message: String) : AssertionError(message)
