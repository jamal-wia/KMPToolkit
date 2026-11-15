package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import platform.Security.errSecDuplicateItem
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 2 of group sto-ios (ticket 5.1.0, STO-5): attacks on the round-2 write loop of
 * [IosSecureKeyValueStorage.put] ("update, then add on a miss", at most [MAX_WRITE_ATTEMPTS] rounds; fix of breaker M1).
 *
 * Contract (docs/kmptoolkit-storage/04-api-reference.md `put`; 05-platform-notes.md "bounded put retry"; KDoc of
 * `write` and [MAX_WRITE_ATTEMPTS]): a put that answers Success has its value stored (readable at once); racing
 * writers, removers and clears never make it fail unless it lost every one of its rounds, in which case it answers
 * `OperationFailed(PUT, key, errSecDuplicateItem)` after exactly [MAX_WRITE_ATTEMPTS] rounds and claims nothing; a
 * user-presence put opens one session, sends the session with every update and none with the add, and releases it.
 *
 * The adversary runs inside the model's script, so it acts at the start of each of the put's own Keychain calls, from a
 * seeded schedule: every interleaving it produces is reproducible from `seed=` in the message.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalAtomicApi::class)
class IosSecureStoreWriteLoopBreakerR2Test {

    private fun queriesOf(options: IosSecureStorageOptions): KeychainQueries = KeychainQueries(
        service = STORE_SERVICE,
        accessibility = options.accessibility,
        accessGroup = options.accessGroup,
        synchronizable = options.synchronizable,
        userPresence = null,
    )

    private class Outcome(val problems: List<String>, val adversaryActions: Int, val lostRounds: Int, val exhausted: Int)

    /**
     * [PUTS] puts of key "k" through a store with [options]; before each of the put's own update/add calls the adversary
     * (seeded) adds a foreign item, removes the item, clears the whole store through a second store (probe included), or
     * does nothing.
     */
    private suspend fun kotlinx.coroutines.test.TestScope.attack(
        seed: Int,
        options: IosSecureStorageOptions,
        sessions: CountingAuthenticationSessions,
    ): Outcome {
        val keychain = InMemoryKeychainPort()
        val rng = Random(seed)
        val plain: KeychainQueries = queriesOf(options)
        val adversaryStore = IosSecureKeyValueStorage(
            STORE_SERVICE,
            IosSecureStorageOptions(accessibility = options.accessibility),
            Dispatchers.Unconfined,
            keychain,
            CountingAuthenticationSessions(),
        )
        var acting = false
        var actions = 0
        keychain.script = { operation: String, q: Map<String, String> ->
            if (!acting && q["service"] == STORE_SERVICE && q["account"] == "k" && (operation == "update" || operation == "add")) {
                acting = true
                try {
                    when (rng.nextInt(4)) {
                        0 -> if (keychain.items.none { it.service == STORE_SERVICE && it.account == "k" }) {
                            keychain.seed(keychain.item("k", "theirs"))
                            actions++
                        }
                        1 -> {
                            plain.item("k", authentication = null).use { keychain.delete(it) }
                            actions++
                        }
                        2 -> {
                            runBlocking { adversaryStore.clear() }
                            actions++
                        }
                        else -> Unit
                    }
                } finally {
                    acting = false
                }
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain, options = options, sessions = sessions)
        val problems: MutableList<String> = mutableListOf()
        var lost = 0
        var exhausted = 0
        for (index: Int in 0 until PUTS) {
            val before: Int = keychain.calls.size
            val value = "ours-$index"
            val result: StorageResult<Unit> = store.put("k", value)
            val own: List<InMemoryKeychainPort.Call> = keychain.calls.drop(before).filter {
                it.query["service"] == STORE_SERVICE && it.query["account"] == "k" && (it.operation == "update" || it.operation == "add")
            }
            if (own.size > 2) lost++
            val label = "seed=$seed put=$index"
            if (own.size > 2 * MAX_WRITE_ATTEMPTS) problems += "$label: ${own.size} update/add calls, above the bound"
            if (options.userPresence != null) {
                own.filter { it.operation == "update" && "context" !in it.query }
                    .forEach { problems += "$label: an update of a user-presence put without its session: $it" }
                own.filter { it.operation == "add" && "context" in it.query }
                    .forEach { problems += "$label: an add carries the session (a new item needs none): $it" }
            }
            val read: StorageResult<String?> = store.get("k")
            when (result) {
                is StorageResult.Success ->
                    if (read != StorageResult.Success(value)) problems += "$label: Success but the item reads $read; calls: $own"

                is StorageResult.Failure -> {
                    exhausted++
                    val expected = StorageError.OperationFailed(StorageOperation.PUT, key = "k", platformCode = errSecDuplicateItem)
                    if (result.error != expected) problems += "$label: a failure other than the give-up: ${result.error}; calls: $own"
                    if (own.size != 2 * MAX_WRITE_ATTEMPTS) problems += "$label: gave up after ${own.size} calls, not every round lost"
                    if (read == StorageResult.Success(value)) problems += "$label: answered a failure for a value that is stored"
                }
            }
        }
        keychain.script = null
        val probes: List<InMemoryKeychainPort.Item> = keychain.items.filter { it.service == KeychainQueries.probeService(STORE_SERVICE) }
        if (probes.isNotEmpty()) problems += "seed=$seed: probes left: ${probes.map { it.group to it.account }}"
        if (keychain.items.count { it.service == STORE_SERVICE && it.account == "k" } > 1) {
            problems += "seed=$seed: more than one item for the key"
        }
        return Outcome(problems, actions, lost, exhausted)
    }

    @Test
    fun `seeded writers removers and clears racing every put round never break the put contract`() = runTest {
        var lostRounds = 0
        for (seed: Int in SEEDS) {
            val outcome: Outcome = attack(seed, IosSecureStorageOptions(), CountingAuthenticationSessions())
            assertTrue(outcome.problems.isEmpty(), outcome.problems.take(10).joinToString("\n"))
            assertTrue(outcome.adversaryActions > 0, "control seed=$seed: the adversary acted")
            lostRounds += outcome.lostRounds
        }
        assertTrue(lostRounds > 0, "control: some put lost a round, so the loop was exercised (seeds=$SEEDS)")
    }

    @Test
    fun `seeded races against a user-presence put keep its session on every update and none on the add and release it`() = runTest {
        val presence = IosSecureStorageOptions(userPresence = IosUserPresence(IosUserPresencePolicy.USER_PRESENCE, "Unlock"))
        for (seed: Int in SEEDS) {
            val sessions = CountingAuthenticationSessions()
            val outcome: Outcome = attack(seed, presence, sessions)
            assertTrue(outcome.problems.isEmpty(), outcome.problems.take(10).joinToString("\n"))
            // One session per put and one per get of the check, never more however many rounds a put took.
            assertEquals(2 * PUTS, sessions.opened, "seed=$seed: one session per operation, not per round")
            assertEquals(sessions.opened, sessions.invalidated, "seed=$seed: every session released")
        }
    }

    @Test
    fun `user-presence overwrites waiting on sheets at the same time all proceed`() = runTest(timeout = 120.seconds) {
        // Each overwrite's update blocks until ALL of them are inside the Keychain at once (the update of an existing
        // user-presence item is the call that may show a sheet). A lock or a single thread around the write loop would
        // keep the last one out; the wait is bounded so a hang becomes a failure, not a stuck thread.
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k", "old"))
        val sessions = CountingAuthenticationSessions()
        val arrivals = AtomicInt(0)
        val allInside = CompletableDeferred<Unit>()
        val timedOut = AtomicInt(0)
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "update") {
                if (arrivals.incrementAndFetch() == PUTS_AT_ONCE) allInside.complete(Unit)
                val opened: Unit? = runBlocking { kotlinx.coroutines.withTimeoutOrNull(60.seconds) { allInside.await() } }
                if (opened == null) timedOut.incrementAndFetch()
            }
            null
        }
        val presence = IosSecureStorageOptions(userPresence = IosUserPresence(IosUserPresencePolicy.BIOMETRY_ANY, "Unlock"))
        val store: IosSecureKeyValueStorage = storeOver(keychain, presence, sessions, dispatcher = Dispatchers.IO)

        val writes: List<Deferred<StorageResult<Unit>>> = List(PUTS_AT_ONCE) { index -> async { store.put("k", "v$index") } }
        val results: List<StorageResult<Unit>> = writes.awaitAll()

        assertEquals(0, timedOut.load(), "overwrites were serialized: only ${arrivals.load()} of $PUTS_AT_ONCE got inside")
        assertEquals(List(PUTS_AT_ONCE) { StorageResult.Success(Unit) }, results)
        assertEquals(PUTS_AT_ONCE, sessions.opened)
        assertEquals(PUTS_AT_ONCE, sessions.invalidated, "every session released")
    }

    private companion object {
        const val PUTS: Int = 120
        const val PUTS_AT_ONCE: Int = 5
        val SEEDS: List<Int> = listOf(7, 97, 2026, 65537, 777777)
    }
}
