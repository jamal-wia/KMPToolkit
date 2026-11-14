package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.spi.FailureDisposition
import io.github.jamal_wia.kmptoolkit.uploader.spi.KeepResult
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

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
            ::insertKeepReturnsWhatItSuperseded,
            ::insertKeepReturnsNothingSupersededWhenNoParkedRowHeldTheKey,
            ::uniqueKeysAreScopedPerType,
            ::insertReplaceSupersedesPendingAtTheTail,
            ::insertReplaceSupersedesInFlightAndResetsIt,
            ::insertReplaceSupersedesParked,
            ::insertReplaceReturnsWhatItSuperseded,
            ::insertReplaceReturnsNullWhenNothingWasSuperseded,
            ::recordFailureWritesEveryFieldAndClearsTheLease,
            ::recordFailureAppliesWhenTheClaimMatches,
            ::recordFailureIsRejectedWhenTheClaimMoved,
            ::recordFailureIsRejectedWhenTheStateMoved,
            ::recordFailureAppliesToAPendingItemReadAsPending,
            ::recordFailureCanParkInTheSameWrite,
            ::recordFailureCanDropInTheSameWrite,
            ::recordFailureGivingUpIsRejectedWhenTheClaimMoved,
            ::recordFailureOnAnAbsentRowReturnsFalse,
            ::markInFlightLeavesAttemptsAlone,
            ::markInFlightRehandsAnExpiredClaim,
            ::markInFlightIsRejectedWhenTheClaimMoved,
            ::guardsCompareTheClaimNotTheLease,
            ::markInFlightIsRejectedForAParkedItem,
            ::markInFlightIsRejectedForAnItemThatFailedSinceTheRead,
            ::markInFlightOnAnAbsentRowIsANoOp,
            ::parkKeepsTheItemAndClearsTheLease,
            ::parkIsRejectedWhenTheClaimMoved,
            ::parkIsRejectedWhenTheStateMoved,
            ::parkOnAnAbsentRowIsANoOp,
            ::theClaimAdvancesByOneOnEveryAppliedWriteAndNoOther,
            ::deleteByIdRemovesOnlyThatItem,
            ::deleteByIdIsIdempotent,
            ::deleteByTagRemovesEveryStateOfThatTag,
            ::deleteByTagLeavesOtherTagsAlone,
            ::deleteByTagReturnsWhatItRemoved,
            ::observeByTypeFiltersAndOrders,
            ::observeByTypeIncludesParked,
            ::observeByTypeEmitsEmptyForAnUnknownType,
            ::clearAllEmptiesTheStore,
            ::insertionOrderIgnoresCreationTime,
            ::valuesRoundTripVerbatim,
            ::tagsAreMatchedExactly,
            ::uniqueKeysAreMatchedExactly,
            ::guardedWritesChangeOnlyTheirOwnFields,
            ::attemptsGatesAndLeasesAreStoredAsGiven,
            ::observeByTypeIsLiveAndNeverCompletes,
            ::insertKeepOverAParkedItemEntersAtTheTail,
            ::getByIdMatchesTheWholeId,
            ::aListHandedOutIsASnapshot,
            ::concurrentClaimsOfOneRowHaveExactlyOneWinner,
            ::concurrentKeepsOfOneKeyHaveExactlyOneWinner,
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
        parkNow(store, "b", "parked")
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
        val first: KeepResult = store.insertKeep(item(id = "a", uniqueKey = null))
        val second: KeepResult = store.insertKeep(item(id = "b", uniqueKey = null))
        expect(first is KeepResult.Inserted && second is KeepResult.Inserted) { "a null unique key must never conflict" }
        expect(store.getAllActive().map { it.id } == listOf("a", "b")) {
            "both keyless inserts must be stored, in order"
        }
    }

    /** A pending item wins over a KEEP re-enqueue of the same identity. */
    public suspend fun insertKeepIsRefusedByPending() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        val result: KeepResult = store.insertKeep(item(id = "b", uniqueKey = "u"))
        expect(result == KeepResult.Kept) { "insertKeep must return Kept when a PENDING item holds the key; got $result" }
        expect(store.getById("b") == null) { "the refused item must not be stored" }
    }

    /** So does an in-flight one — otherwise the same effect could be delivered twice. */
    public suspend fun insertKeepIsRefusedByInFlight() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val result: KeepResult = store.insertKeep(item(id = "b", uniqueKey = "u"))
        expect(result == KeepResult.Kept) { "insertKeep must return Kept when an IN_FLIGHT item holds the key; got $result" }
    }

    /** A parked item does not: its key would otherwise be dead forever. */
    public suspend fun insertKeepSupersedesParked() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u"))
        parkNow(store, "a", "gave up")
        val result: KeepResult = store.insertKeep(item(id = "b", uniqueKey = "u"))
        expect(result is KeepResult.Inserted) { "insertKeep must supersede a PARKED item holding the key; got $result" }
        expect(store.getById("a") == null) { "the superseded parked item must be gone" }
        expect(store.getAllActive().map { it.id } == listOf("b")) { "the new item must be active" }
    }

    /**
     * The parked row a KEEP supersedes comes back from the insert itself, as it was just before its removal: the
     * engine withdraws it like any removed row (stops its attempt, cancels its upload), and a read made beforehand
     * could miss an item parked in between.
     *
     * @since 5.0.0
     */
    public suspend fun insertKeepReturnsWhatItSuperseded() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "u", tag = "t"))
        parkNow(store, "a", "gave up")
        val parked: UploaderItem? = store.getById("a")
        val result: KeepResult = store.insertKeep(item(id = "b", uniqueKey = "u"))
        val superseded: UploaderItem? = (result as? KeepResult.Inserted)?.superseded
        expect(superseded?.id == "a") { "insertKeep must return the parked item it superseded; got $result" }
        expect(superseded?.state == UploaderItemState.PARKED) { "as it was: PARKED" }
        expect(superseded?.tag == "t") { "as it was: with its tag" }
        expect(superseded == parked) { "as it was just before its removal; got $superseded, read $parked" }
    }

    /**
     * Nothing superseded, nothing returned: a fresh key, a keyless item and a key held only under another type
     * insert with `null`; a PENDING or IN_FLIGHT holder returns [KeepResult.Kept] (see
     * [insertKeepIsRefusedByPending], [insertKeepIsRefusedByInFlight]).
     *
     * @since 5.0.0
     */
    public suspend fun insertKeepReturnsNothingSupersededWhenNoParkedRowHeldTheKey() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "other", type = "elsewhere", uniqueKey = "u"))
        val fresh: KeepResult = store.insertKeep(item(id = "a", uniqueKey = "u"))
        val keyless: KeepResult = store.insertKeep(item(id = "b", uniqueKey = null))
        expect(fresh == KeepResult.Inserted(null)) { "a fresh key supersedes nothing; got $fresh" }
        expect(keyless == KeepResult.Inserted(null)) { "a keyless item supersedes nothing; got $keyless" }
    }

    /** The dedup identity is (type, uniqueKey) — the same key under two types is two items. */
    public suspend fun uniqueKeysAreScopedPerType() {
        val store: UploaderStore = createStore()
        val first: KeepResult = store.insertKeep(item(id = "a", type = "one", uniqueKey = "u"))
        val second: KeepResult = store.insertKeep(item(id = "b", type = "two", uniqueKey = "u"))
        expect(first is KeepResult.Inserted && second is KeepResult.Inserted) { "the same unique key under different types must not conflict" }
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
        parkNow(store, "a", "gave up")
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

    /** A recorded failure sets all five fields at once, clears the lease and advances the claim. */
    public suspend fun recordFailureWritesEveryFieldAndClearsTheLease() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.recordFailure("a", 4, 77L, "e", UploaderItemState.IN_FLIGHT, expectedClaim = 1L)
        expect(applied) { "recordFailure must report that it wrote" }
        val updated: UploaderItem? = store.getById("a")
        expect(updated?.state == UploaderItemState.PENDING) { "the item must return to PENDING" }
        expect(updated?.attempts == 4) { "attempts must be the absolute value passed in" }
        expect(updated?.nextRunAtEpochMillis == 77L) { "the backoff gate must be stored" }
        expect(updated?.lastError == "e") { "the error must be stored" }
        expect(updated?.leaseUntilEpochMillis == 0L) { "the lease must be cleared" }
        expect(updated?.claim == 2L) { "the claim must advance by one; got ${updated?.claim}" }
    }

    /** The optimistic guard lets the write through when nothing moved. */
    public suspend fun recordFailureAppliesWhenTheClaimMatches() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.recordFailure("a", 1, 10L, null, UploaderItemState.IN_FLIGHT, expectedClaim = 1L)
        expect(applied) { "a matching expected claim must let the write through" }
        expect(store.getById("a")?.attempts == 1) { "the write must have applied" }
    }

    /**
     * And rejects it when the item was re-handed in between — the check that keeps a crash-and-
     * recover cycle from producing two live claims on one item.
     */
    public suspend fun recordFailureIsRejectedWhenTheClaimMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", fromClaim = 1L, to = 1_800L) // the drain re-handed it
        val applied: Boolean =
            store.recordFailure("a", 1, 10L, "stale", UploaderItemState.IN_FLIGHT, expectedClaim = 1L)
        expect(!applied) { "a stale expected claim must reject the write" }
        val untouched: UploaderItem? = store.getById("a")
        expect(untouched?.state == UploaderItemState.IN_FLIGHT) { "the fresh claim must survive" }
        expect(untouched?.leaseUntilEpochMillis == 1_800L) { "the fresh lease must survive" }
        expect(untouched?.attempts == 0) { "a rejected write must not bump attempts" }
        expect(untouched?.lastError == null) { "a rejected write must not store an error" }
    }

    /**
     * The guard covers the state as well as the claim: a failure expecting PENDING must not return a
     * parked item to rotation, even under the claim the parked row now carries.
     */
    public suspend fun recordFailureIsRejectedWhenTheStateMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        parkNow(store, "a", "permanent 400")
        val claimNow: Long = store.getById("a")?.claim ?: -1L
        val retried: Boolean = store.recordFailure("a", 1, 10L, "timeout", UploaderItemState.PENDING, claimNow)
        val dropped: Boolean =
            store.recordFailure("a", 1, 10L, "timeout", UploaderItemState.PENDING, claimNow, FailureDisposition.DROP)
        expect(!retried && !dropped) { "a failure expecting PENDING must be rejected for a PARKED item" }
        val parked: UploaderItem? = store.getById("a")
        expect(parked?.state == UploaderItemState.PARKED) { "the item must stay PARKED" }
        expect(parked?.lastError == "permanent 400") { "the park's reason must survive" }
        expect(parked?.attempts == 0) { "a rejected write must not bump attempts" }
    }

    /** And a failure from a read of a pending item applies when nothing moved — the drain's own path. */
    public suspend fun recordFailureAppliesToAPendingItemReadAsPending() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        val applied: Boolean = store.recordFailure("a", 1, 10L, "timeout", UploaderItemState.PENDING, 0L)
        expect(applied) { "a failure expecting the current state and claim must be written" }
        expect(store.getById("a")?.attempts == 1) { "the write must have applied" }
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
            expectedState = UploaderItemState.IN_FLIGHT,
            expectedClaim = 1L,
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
            expectedState = UploaderItemState.IN_FLIGHT,
            expectedClaim = 1L,
            disposition = FailureDisposition.DROP,
        )
        expect(applied) { "recordFailure with DROP must report that it deleted" }
        expect(store.getById("a") == null) { "DROP must delete the item" }
        expect(store.getAllActive().map { it.id } == listOf("b")) { "DROP must delete only that item" }
    }

    /** A give-up obeys the same guard as a retry: a stale one must not touch a fresh claim. */
    public suspend fun recordFailureGivingUpIsRejectedWhenTheClaimMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", fromClaim = 1L, to = 1_800L)
        val parked: Boolean = store.recordFailure("a", 1, 10L, "stale", UploaderItemState.IN_FLIGHT, 1L, FailureDisposition.PARK)
        val dropped: Boolean = store.recordFailure("a", 1, 10L, "stale", UploaderItemState.IN_FLIGHT, 1L, FailureDisposition.DROP)
        expect(!parked && !dropped) { "a stale expected claim must reject a give-up" }
        val untouched: UploaderItem? = store.getById("a")
        expect(untouched?.state == UploaderItemState.IN_FLIGHT) { "the fresh claim must survive" }
        expect(untouched?.leaseUntilEpochMillis == 1_800L) { "the fresh lease must survive" }
    }

    /** An absent row is a no-op, reported as `false`, never an exception. */
    public suspend fun recordFailureOnAnAbsentRowReturnsFalse() {
        val store: UploaderStore = createStore()
        expect(!store.recordFailure("ghost", 1, 0L, null, UploaderItemState.PENDING, 0L)) {
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
            expectedClaim = 0L,
        )
        expect(claimed) { "markInFlight must report a claim it wrote" }
        val updated: UploaderItem? = store.getById("a")
        expect(updated?.state == UploaderItemState.IN_FLIGHT) { "the item must be IN_FLIGHT" }
        expect(updated?.leaseUntilEpochMillis == 4_000L) { "the lease must be stored" }
        expect(updated?.attempts == 2) { "markInFlight must not touch the attempt counter" }
        expect(updated?.claim == 1L) { "the claim must advance from 0 to 1; got ${updated?.claim}" }
    }

    /** An expired claim can be re-handed: the claim expects IN_FLIGHT under the claim that expired. */
    public suspend fun markInFlightRehandsAnExpiredClaim() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val rehanded: Boolean = store.markInFlight("a", 1_800L, UploaderItemState.IN_FLIGHT, 1L)
        expect(rehanded) { "a re-hand that expects the current state and claim must be written" }
        val updated: UploaderItem? = store.getById("a")
        expect(updated?.state == UploaderItemState.IN_FLIGHT) { "the re-handed item must be IN_FLIGHT" }
        expect(updated?.leaseUntilEpochMillis == 1_800L) { "the re-hand must carry the new lease" }
        expect(updated?.claim == 2L) { "the re-hand must advance the claim; got ${updated?.claim}" }
    }

    /** Two claims made from one read: the second finds the claim moved and must not overwrite it. */
    public suspend fun markInFlightIsRejectedWhenTheClaimMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", fromClaim = 1L, to = 1_800L)
        val stale: Boolean = store.markInFlight("a", 2_700L, UploaderItemState.IN_FLIGHT, 1L)
        expect(!stale) { "a claim expecting a claim that moved must be rejected" }
        expect(store.getById("a")?.leaseUntilEpochMillis == 1_800L) { "the current claim must survive" }
    }

    /**
     * Two claims can carry the same lease — written in the same millisecond, or across a wall clock
     * that stepped back — so a guard on the lease would take a report from the first for one from the
     * second. The guards compare the claim.
     */
    public suspend fun guardsCompareTheClaimNotTheLease() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", fromClaim = 1L, to = 900L) // a second claim, under the very same lease
        val failed: Boolean = store.recordFailure("a", 1, 10L, "stale", UploaderItemState.IN_FLIGHT, 1L)
        val parked: Boolean = store.park("a", "stale", UploaderItemState.IN_FLIGHT, 1L)
        val reclaimed: Boolean = store.markInFlight("a", 1_800L, UploaderItemState.IN_FLIGHT, 1L)
        expect(!failed && !parked && !reclaimed) {
            "a write expecting the first claim must be rejected under the second, though the lease is the same"
        }
        val untouched: UploaderItem? = store.getById("a")
        expect(untouched?.state == UploaderItemState.IN_FLIGHT && untouched.claim == 2L) {
            "the second claim must survive; got $untouched"
        }
        expect(untouched?.attempts == 0 && untouched.lastError == null) { "no rejected write may have landed" }
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
        parkNow(store, "pending", "permanent")
        parkNow(store, "expired", "permanent")
        val revivedPending: Boolean = store.markInFlight("pending", 900L, UploaderItemState.PENDING, 0L)
        val revivedExpired: Boolean = store.markInFlight("expired", 1_800L, UploaderItemState.IN_FLIGHT, 1L)
        expect(!revivedPending && !revivedExpired) { "markInFlight must not claim a PARKED item" }
        expect(store.getById("pending")?.state == UploaderItemState.PARKED) { "it must stay PARKED" }
        expect(store.getById("expired")?.state == UploaderItemState.PARKED) { "it must stay PARKED" }
    }

    /** Nor overwrite a failure that returned the item to PENDING under a backoff gate. */
    public suspend fun markInFlightIsRejectedForAnItemThatFailedSinceTheRead() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        store.recordFailure("a", 1, 5_000L, "503", UploaderItemState.IN_FLIGHT, expectedClaim = 1L)
        val stale: Boolean = store.markInFlight("a", 1_800L, UploaderItemState.IN_FLIGHT, 1L)
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

    /** Parking keeps the item and its attempt count, and drops the lease. */
    public suspend fun parkKeepsTheItemAndClearsTheLease() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", attempts = 5))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        val applied: Boolean = store.park("a", "permanent 400", UploaderItemState.IN_FLIGHT, 1L)
        expect(applied) { "park must report that it wrote" }
        val parked: UploaderItem? = store.getById("a")
        expect(parked?.state == UploaderItemState.PARKED) { "the item must be PARKED" }
        expect(parked?.lastError == "permanent 400") { "the reason must be stored" }
        expect(parked?.leaseUntilEpochMillis == 0L) { "parking must clear the lease" }
        expect(parked?.attempts == 5) { "parking must not touch the attempt counter" }
        expect(parked?.claim == 2L) { "parking must advance the claim; got ${parked?.claim}" }
    }

    /**
     * A park is a verdict on the attempt the caller read: after a re-hand it must not take the item from
     * the executor now holding it.
     */
    public suspend fun parkIsRejectedWhenTheClaimMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        rehand(store, "a", fromClaim = 1L, to = 1_800L)
        val parked: Boolean = store.park("a", "stale", UploaderItemState.IN_FLIGHT, 1L)
        expect(!parked) { "a park expecting a claim that moved must be rejected" }
        val untouched: UploaderItem? = store.getById("a")
        expect(untouched?.state == UploaderItemState.IN_FLIGHT) { "the fresh claim must survive" }
        expect(untouched?.leaseUntilEpochMillis == 1_800L) { "the fresh lease must survive" }
        expect(untouched?.lastError == null) { "a rejected park must not store its reason" }
    }

    /** And when the state moved under the same claim — the guard is the pair. */
    public suspend fun parkIsRejectedWhenTheStateMoved() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        claim(store, "a", leaseUntilEpochMillis = 900L)
        store.recordFailure("a", 1, 5_000L, "503", UploaderItemState.IN_FLIGHT, expectedClaim = 1L)
        val claimNow: Long = store.getById("a")?.claim ?: -1L
        val parked: Boolean = store.park("a", "stale", UploaderItemState.IN_FLIGHT, claimNow)
        expect(!parked) { "a park expecting IN_FLIGHT must be rejected for a PENDING item" }
        expect(store.getById("a")?.state == UploaderItemState.PENDING) { "the failure's state must survive" }
    }

    /** Parking an absent row does nothing. */
    public suspend fun parkOnAnAbsentRowIsANoOp() {
        val store: UploaderStore = createStore()
        val parked: Boolean = store.park("ghost", "whatever", UploaderItemState.PENDING, 0L)
        expect(!parked) { "park on an absent row must return false" }
        expect(store.getById("ghost") == null) { "park must not create a row" }
    }

    /**
     * The claim is a counter the engine derives the claims it hands out from: `0` on insert, one more
     * on every guarded write that applies, unchanged by one that is rejected. A store that skips an
     * advance would let a superseded executor's report pass for the current one.
     */
    public suspend fun theClaimAdvancesByOneOnEveryAppliedWriteAndNoOther() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        val claims: MutableList<Long?> = mutableListOf(store.getById("a")?.claim)
        store.markInFlight("a", 900L, UploaderItemState.PENDING, 0L)
        claims += store.getById("a")?.claim
        store.markInFlight("a", 900L, UploaderItemState.PENDING, 0L) // rejected: the row moved
        claims += store.getById("a")?.claim
        store.recordFailure("a", 1, 10L, "503", UploaderItemState.IN_FLIGHT, 1L)
        claims += store.getById("a")?.claim
        store.recordFailure("a", 2, 10L, "503", UploaderItemState.IN_FLIGHT, 1L) // rejected
        claims += store.getById("a")?.claim
        store.markInFlight("a", 1_800L, UploaderItemState.PENDING, 2L)
        claims += store.getById("a")?.claim
        store.park("a", "400", UploaderItemState.IN_FLIGHT, 3L)
        claims += store.getById("a")?.claim
        store.park("a", "400", UploaderItemState.IN_FLIGHT, 3L) // rejected
        claims += store.getById("a")?.claim
        expect(claims == listOf<Long?>(0L, 1L, 1L, 2L, 2L, 3L, 4L, 4L)) {
            "the claim must start at 0 and advance by exactly one per applied write; got $claims"
        }
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
        parkNow(store, "parked", "x")

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
        parkNow(store, "parked", "x")

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

    /**
     * The observation flow filters by type and keeps insertion order. The type is compared exactly: a prefix, a
     * longer name containing it, the same letters in another case and a name matching it as a wildcard pattern (`_`
     * or `%`, as SQL `LIKE` reads them) are other types.
     */
    public suspend fun observeByTypeFiltersAndOrders() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", type = "one"))
        store.insertKeep(item(id = "b", type = "two"))
        store.insertKeep(item(id = "c", type = "one"))
        val observed: List<String> = store.observeByType("one").first().map { it.id }
        expect(observed == listOf("a", "c")) {
            "observeByType must emit only that type, in order; got $observed"
        }

        val lookalikes: List<Pair<String, String>> = listOf(
            "d" to "ones", "e" to "One", "f" to "o_e", "g" to "on", "h" to "xone", "i" to "o%e", "j" to "ONES",
        )
        lookalikes.forEach { (id, type) -> store.insertKeep(item(id = id, type = type)) }
        val expected: List<Pair<String, List<String>>> = listOf(
            "one" to listOf("a", "c"),
            "on" to listOf("g"),
            "ones" to listOf("d"),
            "One" to listOf("e"),
            "o_e" to listOf("f"),
            "o%e" to listOf("i"),
            "o%" to emptyList(),
            "_ne" to emptyList(),
            "%" to emptyList(),
            "ne" to emptyList(),
        )
        for ((type, ids) in expected) {
            val seen: List<String> = store.observeByType(type).first().map { it.id }
            expect(seen == ids) {
                "observeByType('$type') must match the type exactly (no prefix, substring, case folding or " +
                    "wildcard); expected $ids, got $seen"
            }
        }
    }

    /** And includes parked items, which is what a "failed to send" indicator binds to. */
    public suspend fun observeByTypeIncludesParked() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", type = "one"))
        parkNow(store, "a", "x")
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
        parkNow(store, "b", "x")
        store.clearAll()
        expect(store.getAllActive().isEmpty()) { "clearAll must empty the active set" }
        expect(store.getById("a") == null && store.getById("b") == null) {
            "clearAll must remove items in every state"
        }
    }

    /**
     * Insertion order is not creation order: items inserted with falling creation times still come back
     * in the order they were inserted, as they would after a wall clock stepped backwards.
     *
     * @since 4.0.0
     */
    public suspend fun insertionOrderIgnoresCreationTime() {
        val store: UploaderStore = createStore()
        val createdAt: List<Long> = listOf(100L, 50L, 75L, 0L, 60L)
        createdAt.forEachIndexed { index, time ->
            store.insertKeep(item(id = "c$index", createdAtEpochMillis = time))
        }
        val expected: List<String> = createdAt.indices.map { "c$it" }
        val actual: List<String> = store.getAllActive().map { it.id }
        expect(actual == expected) { "getAllActive must be in insertion order, not creation time order; got $actual" }
    }

    /**
     * Payloads come back byte for byte: no trimming, case folding, normalization or truncation, from both
     * [UploaderStore.getById] and [UploaderStore.getAllActive].
     *
     * @since 4.0.0
     */
    public suspend fun valuesRoundTripVerbatim() {
        val store: UploaderStore = createStore()
        val payloads: List<String> = listOf(
            "  lead and trail \r\n\t",
            "MiXeD CaSe",
            "e\u0301 vs \u00E9",
            "\uD83D\uDE00 emoji",
            "line1\nline2\r\nline3",
            "x".repeat(LARGE_PAYLOAD_LENGTH),
            "'; DROP TABLE kmptoolkit_uploader_item; --",
            "",
        )
        payloads.forEachIndexed { index, payload ->
            store.insertKeep(item(id = "p$index", tag = "Tag $index", uniqueKey = "Key $index").copy(payload = payload))
            val stored: UploaderItem? = store.getById("p$index")
            expect(stored?.payload == payload) { "payload #$index changed on the way through" }
            expect(stored != null && stored.tag == "Tag $index" && stored.uniqueKey == "Key $index") {
                "tag and unique key #$index must be stored as written (case and spaces included)"
            }
        }
        expect(store.getAllActive().map { it.payload } == payloads) { "getAllActive changed a payload" }
    }

    /**
     * [UploaderStore.deleteByTag] matches a tag exactly: not by case, prefix, suffix or wildcard (`%`, `_`),
     * so one account's wipe never removes another account's rows. The empty tag is a tag of its own.
     *
     * @since 4.0.0
     */
    public suspend fun tagsAreMatchedExactly() {
        val store: UploaderStore = createStore()
        val tags: List<Pair<String, String?>> = listOf(
            "t1" to "acct", "t2" to "acct2", "t3" to "Acct", "t4" to "acct%", "t5" to "acct_", "t6" to "",
            "t7" to null, "t8" to "acc", "t9" to " acct", "t10" to "a\\b", "t11" to "a\\\\b",
        )
        tags.forEach { (id, tag) -> store.insertKeep(item(id = id, tag = tag)) }

        fun idsOf(removed: List<UploaderItem>): List<String> = removed.map { it.id }
        expect(idsOf(store.deleteByTag("acct")) == listOf("t1")) { "tag 'acct' must match only itself" }
        expect(idsOf(store.deleteByTag("")) == listOf("t6")) { "the empty tag must match only the empty tag" }
        expect(idsOf(store.deleteByTag("acct%")) == listOf("t4")) { "a percent sign in a tag is not a wildcard" }
        expect(idsOf(store.deleteByTag("acct_")) == listOf("t5")) { "an underscore in a tag is not a wildcard" }
        expect(idsOf(store.deleteByTag("a\\b")) == listOf("t10")) { "a backslash in a tag is not an escape" }
        val left: List<String> = store.getAllActive().map { it.id }
        expect(left == listOf("t2", "t3", "t7", "t8", "t9", "t11")) { "every other tag must survive; left $left" }
    }

    /**
     * The dedup identity compares the unique key exactly: keys differing in case, spacing or a wildcard
     * character are different keys, an identical key conflicts.
     *
     * @since 4.0.0
     */
    public suspend fun uniqueKeysAreMatchedExactly() {
        val store: UploaderStore = createStore()
        val keys: List<String> = listOf("K", "k", "K ", "K%", "K_", "e\u0301", "\u00E9", "")
        keys.forEachIndexed { index, key ->
            expect(store.insertKeep(item(id = "k$index", uniqueKey = key)) is KeepResult.Inserted) { "unique key '$key' was taken for another key" }
        }
        expect(store.insertKeep(item(id = "dup", uniqueKey = "K")) == KeepResult.Kept) { "an identical unique key must conflict" }
        expect(store.getAllActive().size == keys.size) { "a conflicting insertKeep must not add a row" }
    }

    /**
     * Every guarded write changes the fields it documents and nothing else: whole rows are compared after
     * [UploaderStore.markInFlight], [UploaderStore.recordFailure] and [UploaderStore.park], so a store that
     * clears a tag, a key or the creation time, or loses the last error across a hand-off, is caught.
     *
     * @since 4.0.0
     */
    public suspend fun guardedWritesChangeOnlyTheirOwnFields() {
        val store: UploaderStore = createStore()
        val original: UploaderItem = item(
            id = "a", uniqueKey = "U", orderingKey = "O", tag = "T", createdAtEpochMillis = 77L,
        ).copy(schemaVersion = 3)
        store.insertKeep(original)

        claim(store, "a", 500L)
        expectRow(store, "markInFlight changed more than state, lease and claim",
            original.copy(state = UploaderItemState.IN_FLIGHT, leaseUntilEpochMillis = 500L, claim = 1L))
        val failed: Boolean = store.recordFailure("a", 4, 99L, "err", UploaderItemState.IN_FLIGHT, 1L, FailureDisposition.RETRY)
        expect(failed) { "recordFailure must apply under the claim the row holds" }
        val afterFailure: UploaderItem = original.copy(attempts = 4, nextRunAtEpochMillis = 99L, lastError = "err", claim = 2L)
        expectRow(store, "recordFailure changed more than state, attempts, gate, error, lease and claim", afterFailure)
        rehandFromPending(store, "a", fromClaim = 2L, lease = 600L)
        expectRow(store, "a second markInFlight must keep the attempts, the gate and the last error",
            afterFailure.copy(state = UploaderItemState.IN_FLIGHT, leaseUntilEpochMillis = 600L, claim = 3L))
        val parked: Boolean = store.park("a", "why", UploaderItemState.IN_FLIGHT, 3L)
        expect(parked) { "park must apply under the claim the row holds" }
        expectRow(store, "park changed more than state, error, lease and claim",
            afterFailure.copy(state = UploaderItemState.PARKED, lastError = "why", claim = 4L))
    }

    /**
     * Attempts, backoff gate and lease are stored as the absolute values passed in, never added to or
     * compared with the old ones: a gate or a lease may legitimately move earlier.
     *
     * @since 4.0.0
     */
    public suspend fun attemptsGatesAndLeasesAreStoredAsGiven() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", attempts = 3, nextRunAtEpochMillis = 5_000L))
        val failed: Boolean = store.recordFailure("a", 1, 100L, "e", UploaderItemState.PENDING, 0L, FailureDisposition.RETRY)
        expect(failed) { "recordFailure must apply to a PENDING row under claim 0" }
        val stored: UploaderItem? = store.getById("a")
        expect(stored?.attempts == 1) { "attempts are the absolute value passed in; got ${stored?.attempts}" }
        expect(stored?.nextRunAtEpochMillis == 100L) {
            "the gate is the value passed in, even an earlier one; got ${stored?.nextRunAtEpochMillis}"
        }
        rehandFromPending(store, "a", fromClaim = 1L, lease = 10L)
        rehand(store, "a", fromClaim = 2L, to = 5L)
        expect(store.getById("a")?.leaseUntilEpochMillis == 5L) { "the lease is the value passed in, even an earlier one" }
    }

    /**
     * [UploaderStore.observeByType] is live: it emits again after every change (a new row, a hand-off, a
     * park, items in every state) and never completes.
     *
     * @since 4.0.0
     */
    public suspend fun observeByTypeIsLiveAndNeverCompletes() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "o1", type = "observed"))
        coroutineScope {
            val emissions: Channel<List<UploaderItem>> = Channel(Channel.UNLIMITED)
            val collector: Job = launch(Dispatchers.Default) {
                store.observeByType("observed").collect { emissions.trySend(it) }
                emissions.close() // the flow completed, which a live observation must not
            }
            try {
                withContext(Dispatchers.Default) {
                    awaitEmission(emissions, "the first emission") { rows -> rows.map { it.id } == listOf("o1") }
                    store.insertKeep(item(id = "o2", type = "observed"))
                    awaitEmission(emissions, "a row inserted after the first emission") { rows ->
                        rows.map { it.id } == listOf("o1", "o2")
                    }
                    claim(store, "o1", 1L)
                    awaitEmission(emissions, "an IN_FLIGHT item (observeByType includes every state)") { rows ->
                        rows.map { it.state } == listOf(UploaderItemState.IN_FLIGHT, UploaderItemState.PENDING)
                    }
                    parkNow(store, "o2", "x")
                    awaitEmission(emissions, "a PARKED item") { rows ->
                        rows.map { it.state } == listOf(UploaderItemState.IN_FLIGHT, UploaderItemState.PARKED)
                    }
                }
            } finally {
                collector.cancel()
            }
        }
    }

    /**
     * [UploaderStore.insertKeep] over a PARKED item with the same identity deletes the parked row and
     * inserts the new one at the tail: it is not revived in place at the old position.
     *
     * @since 4.0.0
     */
    public suspend fun insertKeepOverAParkedItemEntersAtTheTail() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a", uniqueKey = "k"))
        store.insertKeep(item(id = "b"))
        parkNow(store, "a", "x")
        expect(store.insertKeep(item(id = "c", uniqueKey = "k")) is KeepResult.Inserted) { "insertKeep must replace a parked holder of the key" }
        val ids: List<String> = store.getAllActive().map { it.id }
        expect(ids == listOf("b", "c")) { "the replacement must enter at the tail; got $ids" }
        expect(store.getById("a") == null) { "the parked row must be gone after it was replaced" }
    }

    /**
     * [UploaderStore.getById] matches the whole id: not a prefix, another case, or a wildcard pattern.
     *
     * @since 4.0.0
     */
    public suspend fun getByIdMatchesTheWholeId() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "item-1"))
        store.insertKeep(item(id = "item-10"))
        store.deleteById("item-1")
        expect(store.getById("item-1") == null) { "getById matched another id by prefix" }
        expect(store.getById("item") == null) { "getById matched a prefix of an id" }
        expect(store.getById("ITEM-10") == null) { "getById must be case-sensitive" }
        expect(store.getById("item-1%") == null) { "a percent sign in an id is not a wildcard" }
        expect(store.getById("item-10")?.id == "item-10") { "getById must find the exact id" }
    }

    /**
     * A list already returned by [UploaderStore.getAllActive] does not change when the store does.
     *
     * @since 4.0.0
     */
    public suspend fun aListHandedOutIsASnapshot() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        val before: List<UploaderItem> = store.getAllActive()
        store.insertKeep(item(id = "b"))
        store.deleteById("a")
        expect(before.map { it.id } == listOf("a")) { "a list already handed out changed afterwards" }
    }

    /**
     * Concurrent [UploaderStore.markInFlight] calls on one row under the same expected claim: exactly one
     * applies. This is the compare-and-set the engine's claim protocol stands on.
     *
     * @since 4.0.0
     */
    public suspend fun concurrentClaimsOfOneRowHaveExactlyOneWinner() {
        val store: UploaderStore = createStore()
        store.insertKeep(item(id = "a"))
        val winners: Int = withContext(Dispatchers.Default) {
            (1..CONCURRENT_CALLS).map { who ->
                async { store.markInFlight("a", 1_000L + who, UploaderItemState.PENDING, 0L) }
            }.awaitAll().count { it }
        }
        expect(winners == 1) { "$winners concurrent claims of one row applied; exactly one must" }
        expect(store.getById("a")?.claim == 1L) { "the claim must have advanced exactly once" }
    }

    /**
     * Concurrent [UploaderStore.insertKeep] calls for one dedup identity: exactly one inserts.
     *
     * @since 4.0.0
     */
    public suspend fun concurrentKeepsOfOneKeyHaveExactlyOneWinner() {
        val store: UploaderStore = createStore()
        val winners: Int = withContext(Dispatchers.Default) {
            (1..CONCURRENT_CALLS).map { who ->
                async { store.insertKeep(item(id = "k$who", uniqueKey = "same")) }
            }.awaitAll().count { it is KeepResult.Inserted }
        }
        expect(winners == 1) { "$winners concurrent insertKeep calls for one key inserted; exactly one must" }
        expect(store.getAllActive().size == 1) { "one key must hold exactly one row" }
    }

    /** Waits for an emission that satisfies [matches], failing the contract if the flow ends or stalls. */
    private suspend fun awaitEmission(
        emissions: Channel<List<UploaderItem>>,
        what: String,
        matches: (List<UploaderItem>) -> Boolean,
    ) {
        val found: Boolean? = withTimeoutOrNull(OBSERVE_TIMEOUT_SECONDS.seconds) {
            var result: Boolean? = null
            while (result == null) {
                val next: ChannelResult<List<UploaderItem>> = emissions.receiveCatching()
                result = when {
                    next.isClosed -> false
                    matches(next.getOrThrow()) -> true
                    else -> null
                }
            }
            result
        }
        when (found) {
            true -> Unit
            false -> throw UploaderStoreContractViolation("observeByType completed while waiting for $what; it must never complete")
            null -> throw UploaderStoreContractViolation("observeByType did not emit $what within $OBSERVE_TIMEOUT_SECONDS s")
        }
    }

    private suspend fun expectRow(store: UploaderStore, message: String, expected: UploaderItem) {
        val actual: UploaderItem? = store.getById(expected.id)
        expect(actual == expected) { "$message; expected $expected, got $actual" }
    }

    private suspend fun rehandFromPending(store: UploaderStore, id: String, fromClaim: Long, lease: Long) {
        val applied: Boolean = store.markInFlight(id, lease, UploaderItemState.PENDING, fromClaim)
        expect(applied) { "markInFlight must claim a PENDING item expected under its current claim" }
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

    /**
     * A first hand-off of a freshly inserted item, asserted to have been written — the setup most checks
     * need. It leaves the item under claim `1`.
     */
    private suspend fun claim(store: UploaderStore, id: String, leaseUntilEpochMillis: Long) {
        val claimed: Boolean = store.markInFlight(id, leaseUntilEpochMillis, UploaderItemState.PENDING, 0L)
        expect(claimed) { "markInFlight must claim a PENDING item expected as PENDING under claim 0" }
    }

    /** A re-hand of an expired claim, the drain's other use of [UploaderStore.markInFlight]. */
    private suspend fun rehand(store: UploaderStore, id: String, fromClaim: Long, to: Long) {
        val rehanded: Boolean = store.markInFlight(id, to, UploaderItemState.IN_FLIGHT, fromClaim)
        expect(rehanded) { "markInFlight must re-hand an IN_FLIGHT item expected under its current claim" }
    }

    /** Parks [id] as it is right now, asserted to have been written — setup for checks about parked rows. */
    private suspend fun parkNow(store: UploaderStore, id: String, reason: String) {
        val current: UploaderItem = store.getById(id) ?: throw UploaderStoreContractViolation("no row '$id' to park")
        val parked: Boolean = store.park(id, reason, current.state, current.claim)
        expect(parked) { "park must apply to a row expected in its current state and claim" }
    }

    private inline fun expect(condition: Boolean, message: () -> String) {
        if (!condition) throw UploaderStoreContractViolation(message())
    }

    private companion object {

        /** Enough inserts that a timestamp-ordered store would visibly shuffle them. */
        const val RAPID_INSERT_COUNT: Int = 20

        const val DEFAULT_TYPE: String = "contract.type"

        /** Large enough to catch a store that truncates, small enough for a store with a row limit. */
        const val LARGE_PAYLOAD_LENGTH: Int = 300_000

        const val CONCURRENT_CALLS: Int = 8

        /** Real time an observation may take to show a change; a correct store takes milliseconds. */
        const val OBSERVE_TIMEOUT_SECONDS: Int = 5
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
