package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryRef
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 3 of group sto-ios (ticket 5.1.0, STO-5): attacks on the round-3 fix of breaker M1 (the probe whose add
 * reports no group is deleted by its own unique account, `KeychainQueries.probeRemoveOwn`) and on the one failing path
 * of a successful probe add that still keeps its probe.
 *
 * Contract: KDoc of [DefaultAccessGroup] ("so no failing path of a successful add leaves an item behind"; "a probe in
 * another group is none of this call's business and is never touched"), D-18 ("no orphan probe"), and
 * docs/kmptoolkit-storage/05-platform-notes.md ("a failed `clear()` never leaves a probe behind"; the store's items are
 * never deleted when the group is not resolved).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalAtomicApi::class)
class IosDefaultAccessGroupBreakerR3Test {

    private val probeService: String = KeychainQueries.probeService(STORE_SERVICE)

    private fun queries(): KeychainQueries = KeychainQueries(
        service = STORE_SERVICE,
        accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
        accessGroup = null,
        synchronizable = false,
        userPresence = null,
    )

    private fun probe(group: String, account: String, synchronizable: Boolean = false): InMemoryKeychainPort.Item =
        InMemoryKeychainPort.Item(
            service = probeService,
            account = account,
            group = group,
            synchronizable = synchronizable,
            accessible = "afterFirstUnlockThisDeviceOnly",
            accessControl = false,
            data = null,
        )

    /** The model, except that a successful probe add answers with [group] instead of the group the item went to. */
    private class AddAnswers(val inner: InMemoryKeychainPort, val group: String?) : KeychainPort by inner {
        override fun addReadingAccessGroup(query: CFDictionaryRef): KeychainAccessGroup =
            KeychainAccessGroup(inner.addReadingAccessGroup(query).status, group)
    }

    // --- m1: the group-addressed probe delete fails ---------------------------------------------------------------

    @Test
    fun `a clear whose probe delete by group fails still leaves no probe behind and keeps its failure`() = runTest {
        // DefaultAccessGroup.resolve(): `if (removed != errSecSuccess && removed != errSecItemNotFound) return failure(removed)`
        // returns with the probe still in the Keychain. Its unique account is known at that point, so the same cleanup the
        // null-group path does (delete by own account, no group) is available and would leave nothing behind. The KDoc
        // promises "no failing path of a successful add leaves an item behind"; the notes "a failed clear() never leaves
        // a probe behind". Only the group-addressed sweep fails here (a transient status); a delete by account succeeds.
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine", group = "TEAM.default"))
        keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
        keychain.script = { operation: String, q: Map<String, String> ->
            if (operation == "delete" && q["service"] == probeService && "group" in q) TRANSIENT else null
        }

        val result: StorageResult<Unit> = storeOver(keychain).clear()

        assertTrue(
            keychain.calls.any { it.operation == "addReadingAccessGroup" && it.query["service"] == probeService },
            "control: the probe was added",
        )
        assertTrue(
            keychain.calls.any { it.operation == "delete" && it.query["service"] == probeService && "group" in it.query },
            "control: the group-addressed probe delete was attempted and failed",
        )
        assertEquals(
            StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT)),
            result,
            "fail closed with the status of the failing delete",
        )
        assertEquals(2, keychain.items.count { it.service == STORE_SERVICE }, "fail closed: no store item deleted")
        val probes: List<InMemoryKeychainPort.Item> = keychain.items.filter { it.service == probeService }
        assertTrue(
            probes.isEmpty(),
            "a failed clear left its probe behind although its own account was known: " +
                "${probes.map { it.group to it.account }}; calls: ${keychain.operations()}",
        )
    }

    // --- the M1 fix: cleanup by own account -------------------------------------------------------------------------

    @Test
    fun `the cleanup of an unreported probe deletes only that probe and nothing that merely resembles it`() = runTest {
        // probeRemoveOwn names no group, so in a real Keychain it matches every group the app can reach; only the account
        // (and the probe service, and local-only) keep it on this call's probe. Seeded neighbours: another process's
        // probe in the shared group, a probe a killed clear left in the default group (another account, not ours to
        // know), a store item whose key happens to equal our probe account, and a synchronizable twin of our probe.
        val model = InMemoryKeychainPort()
        val foreign: InMemoryKeychainPort.Item = probe("TEAM.shared", "${KeychainQueries.PROBE_ACCOUNT}.extension")
        val leftover: InMemoryKeychainPort.Item = probe("TEAM.default", "${KeychainQueries.PROBE_ACCOUNT}.killed")
        val sameKey: InMemoryKeychainPort.Item = model.item(OWN_ACCOUNT, group = "TEAM.default")
        val syncTwin: InMemoryKeychainPort.Item = probe("TEAM.default", OWN_ACCOUNT, synchronizable = true)
        listOf(foreign, leftover, sameKey, syncTwin).forEach { model.seed(it) }

        val resolved: StorageResult<String> = DefaultAccessGroup(AddAnswers(model, group = null), queries()) { OWN_ACCOUNT }.resolve()

        assertEquals(
            StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null)),
            resolved,
        )
        assertTrue(
            model.calls.any { it.operation == "addReadingAccessGroup" && it.query["account"] == OWN_ACCOUNT },
            "control: our probe was added",
        )
        val remaining: List<Triple<String, String, Boolean>> = model.items.map { Triple(it.service, it.account, it.synchronizable) }
        val expected: List<Triple<String, String, Boolean>> =
            listOf(foreign, leftover, sameKey, syncTwin).map { Triple(it.service, it.account, it.synchronizable) }
        assertEquals(expected, remaining, "exactly our local probe must be gone; calls: ${model.calls}")
    }

    @Test
    fun `an unreported probe already swept by a concurrent clear keeps this clear failed and touches no store item`() = runTest {
        // Our probe landed in the default group; another clear of the app resolves that group and sweeps it before our
        // cleanup runs. The cleanup then answers errSecItemNotFound: still the original failure, never a success (the
        // group is still unknown to this call) and never a store delete.
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine", group = "TEAM.default"))
        model.seed(model.item("theirs", group = "TEAM.shared"))
        var swept = false
        model.script = { operation: String, q: Map<String, String> ->
            if (operation == "delete" && q["service"] == probeService && "account" in q && !swept) {
                swept = true
                queries().probeRemove("TEAM.default").use { model.delete(it) }
            }
            null
        }

        val result: StorageResult<Unit> = storeOver(AddAnswers(model, group = null)).clear()

        assertTrue(swept, "control: the concurrent sweep ran before our cleanup")
        assertEquals(
            StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null)),
            result,
        )
        assertEquals(2, model.items.count { it.service == STORE_SERVICE }, "no store item deleted by a clear with no group")
        assertTrue(model.items.none { it.service == probeService }, "${model.items.map { it.account }}")
        assertTrue(
            model.calls.none { it.operation == "delete" && it.query["service"] == STORE_SERVICE },
            "a clear that never learned its group sent a store delete: ${model.calls}",
        )
    }

    @Test
    fun `a clear cancelled while its unreported probe add is blocked still removes that probe`() = runTest(timeout = 120.seconds) {
        // Round 1 attacked cancellation on the path that knows its group; this is the new path. The add, then the cleanup,
        // must run as one blocking block: a cancellation observed between them would strand the probe in a group nobody
        // learned, which no later sweep is guaranteed to reach.
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine"))
        val addReached = CompletableDeferred<Unit>()
        val releaseAdd = CompletableDeferred<Unit>()
        model.script = { operation: String, _: Map<String, String> ->
            if (operation == "addReadingAccessGroup" && !addReached.isCompleted) {
                addReached.complete(Unit)
                runBlocking { releaseAdd.await() }
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(AddAnswers(model, group = null), dispatcher = Dispatchers.Default)

        val clearing: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.clear() }
        addReached.await()
        assertTrue(clearing.isActive, "control: the clear is inside its probe add")
        clearing.cancel()
        releaseAdd.complete(Unit)
        clearing.join()

        assertTrue(
            model.calls.any { it.operation == "addReadingAccessGroup" && it.query["service"] == probeService },
            "control: the probe add really happened",
        )
        assertTrue(model.items.none { it.service == probeService }, "a cancelled clear orphaned its unreported probe: ${model.calls}")
        assertEquals(listOf("mine"), model.items.map { it.account }, "and deleted no store item")
    }

    // --- seeded storm over a flaky resolution -----------------------------------------------------------------------

    /**
     * The model with a seeded, misbehaving probe: each probe add (numbered in call order) fails, answers no group, answers
     * a blank group or answers truly; each delete of the probe service fails with a transient status or goes through.
     * Decisions are a pure function of (seed, index), so the totals per kind are fixed by the seed; which clear meets
     * which decision depends on the thread schedule, so only invariants are asserted.
     */
    private class FlakyProbe(val inner: InMemoryKeychainPort, val seed: Int, val probeService: String) : KeychainPort by inner {
        private val adds = AtomicInt(0)
        private val deletes = AtomicInt(0)
        val addFailures = AtomicInt(0)
        val unreported = AtomicInt(0)
        val deleteFailures = AtomicInt(0)

        private fun roll(kind: Int, index: Int): Int = Random(seed * 7919 + kind * 1_000_003 + index).nextInt(100)

        override fun addReadingAccessGroup(query: CFDictionaryRef): KeychainAccessGroup {
            val r: Int = roll(1, adds.incrementAndFetch())
            if (r < 10) {
                addFailures.incrementAndFetch()
                return KeychainAccessGroup(ERR_SEC_INTERACTION_NOT_ALLOWED, null)
            }
            val real: KeychainAccessGroup = inner.addReadingAccessGroup(query)
            return when {
                r < 22 -> KeychainAccessGroup(real.status, null).also { unreported.incrementAndFetch() }
                r < 30 -> KeychainAccessGroup(real.status, " ").also { unreported.incrementAndFetch() }
                else -> real
            }
        }

        override fun delete(query: CFDictionaryRef): Int {
            if (describe(query)["service"] == probeService && roll(2, deletes.incrementAndFetch()) < 15) {
                deleteFailures.incrementAndFetch()
                return TRANSIENT
            }
            return inner.delete(query)
        }
    }

    @Test
    fun `seeded flaky resolutions under concurrent clears never delete outside the default group and end clean`() =
        runTest(timeout = 180.seconds) {
            for (seed: Int in SEEDS) {
                val model = InMemoryKeychainPort()
                val flaky = FlakyProbe(model, seed, probeService)
                val allowed: Set<StorageError> = setOf(
                    StorageError.Unavailable(),
                    StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null),
                    StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT),
                )
                val problems: List<String> = withContext(Dispatchers.Default) {
                    coroutineScope {
                        val clearers = (0 until CLEARERS).map { worker ->
                            async {
                                val store: IosSecureKeyValueStorage = storeOver(flaky, dispatcher = Dispatchers.Default)
                                (0 until CLEARS_EACH).mapNotNull { step ->
                                    when (val result: StorageResult<Unit> = store.clear()) {
                                        is StorageResult.Success -> null
                                        is StorageResult.Failure ->
                                            if (result.error in allowed) null else "seed=$seed clearer=$worker step=$step: unexpected $result"
                                    }
                                }
                            }
                        }
                        val sharedWriter = async {
                            val rng = Random(seed + 1)
                            val store: IosSecureKeyValueStorage = storeOver(
                                flaky,
                                options = IosSecureStorageOptions(accessGroup = "TEAM.shared"),
                                dispatcher = Dispatchers.Default,
                            )
                            (0 until 60).mapNotNull { step ->
                                val key = "s${rng.nextInt(3)}"
                                val put: StorageResult<Unit> = store.put(key, "s$step")
                                val read: StorageResult<String?> = store.get(key)
                                if (put == StorageResult.Success(Unit) && read == StorageResult.Success("s$step")) {
                                    null
                                } else {
                                    "seed=$seed sharedWriter step=$step key=$key: put=$put read=$read (a clear reached TEAM.shared?)"
                                }
                            }
                        }
                        val defaultWriter = async {
                            val rng = Random(seed + 2)
                            val store: IosSecureKeyValueStorage = storeOver(
                                flaky,
                                options = IosSecureStorageOptions(accessGroup = "TEAM.default"),
                                dispatcher = Dispatchers.Default,
                            )
                            (0 until 60).mapNotNull { step ->
                                val result: StorageResult<Unit> = store.put("d${rng.nextInt(4)}", "d$step")
                                if (result is StorageResult.Success) null else "seed=$seed defaultWriter step=$step: $result"
                            }
                        }
                        val extension = async {
                            (0 until FOREIGN_PROBES).forEach { index ->
                                model.seed(probe("TEAM.shared", "${KeychainQueries.PROBE_ACCOUNT}.extension-$seed-$index"))
                            }
                            emptyList<String>()
                        }
                        (clearers + sharedWriter + defaultWriter + extension).awaitAll().flatten()
                    }
                }
                assertTrue(problems.isEmpty(), problems.take(10).joinToString("\n"))
                assertTrue(
                    flaky.addFailures.load() > 0 && flaky.unreported.load() > 0 && flaky.deleteFailures.load() > 0,
                    "seed=$seed control: every misbehaviour happened (addFailures=${flaky.addFailures.load()} " +
                        "unreported=${flaky.unreported.load()} deleteFailures=${flaky.deleteFailures.load()})",
                )
                val stranded: List<InMemoryKeychainPort.Item> =
                    model.items.filter { it.service == probeService && it.group == "TEAM.default" }
                assertTrue(
                    stranded.size <= flaky.deleteFailures.load(),
                    "seed=$seed: ${stranded.size} probes stranded in the default group, more than the " +
                        "${flaky.deleteFailures.load()} probe deletes that failed: ${stranded.map { it.account }}",
                )
                assertEquals(
                    FOREIGN_PROBES,
                    model.items.count { it.service == probeService && it.group == "TEAM.shared" },
                    "seed=$seed: another process's probes in TEAM.shared must be untouched",
                )
                assertTrue(
                    model.items.count { it.service == STORE_SERVICE && it.group == "TEAM.shared" } in 1..3,
                    "seed=$seed: the shared store's keys survive every default-group clear",
                )

                // Once the Keychain behaves, one clear sweeps every leftover of the default group.
                assertEquals(StorageResult.Success(Unit), storeOver(model).clear(), "seed=$seed: a clean clear after the storm")
                assertTrue(
                    model.items.none { it.group == "TEAM.default" },
                    "seed=$seed: the default group is not empty after a clean clear: " +
                        "${model.items.filter { it.group == "TEAM.default" }.map { it.service to it.account }}",
                )
            }
        }

    private companion object {
        /** A status no mapping translates (`errSecInvalidOwnerEdit`); stands for a transient securityd failure. */
        const val TRANSIENT: Int = -25244
        const val OWN_ACCOUNT: String = "${KeychainQueries.PROBE_ACCOUNT}.mine"
        const val CLEARERS: Int = 4
        const val CLEARS_EACH: Int = 30
        const val FOREIGN_PROBES: Int = 8
        val SEEDS: List<Int> = listOf(1103, 52711, 8675309)
    }
}
