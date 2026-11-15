package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 4 of group sto-ios (ticket 5.1.0, STO-5): attacks on the round-4 fix of breaker m1 (a probe whose delete
 * by group fails is deleted by its own unique account, `DefaultAccessGroup.deleteOwnProbe`), on every failure path of
 * the probe lifecycle around it.
 *
 * Contract: KDoc of [DefaultAccessGroup] (fail closed with the status of the first call that went wrong; the cleanup's
 * status is ignored and the original failure kept; a probe in another group is never touched; a leftover of this app's
 * default group is swept by the next clear), D-18 (no orphan probe, never the wider delete), plan 4.10 (the error table
 * applies to `clear()` too), docs/kmptoolkit-storage/05-platform-notes.md (the `clear` bullet).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalAtomicApi::class)
class IosDefaultAccessGroupBreakerR4Test {

    private val probeService: String = KeychainQueries.probeService(STORE_SERVICE)

    private fun probe(group: String, account: String): InMemoryKeychainPort.Item = InMemoryKeychainPort.Item(
        service = probeService,
        account = account,
        group = group,
        synchronizable = false,
        accessible = "afterFirstUnlockThisDeviceOnly",
        accessControl = false,
        data = null,
    )

    private fun isGroupProbeDelete(operation: String, q: Map<String, String>): Boolean =
        operation == "delete" && q["service"] == probeService && "group" in q

    private fun isOwnProbeDelete(operation: String, q: Map<String, String>): Boolean =
        operation == "delete" && q["service"] == probeService && "group" !in q && "account" in q

    @Test
    fun `every failing status of the delete by group keeps its table mapping and still leaves no probe`() = runTest {
        // Plan 4.10 maps a status the same way on every operation; the m1 cleanup must neither replace the status with its
        // own nor skip the cleanup for a status that happens to be translated (an early return per category would).
        val cases: List<Pair<Int, StorageError>> = listOf(
            ERR_SEC_MISSING_ENTITLEMENT to StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT),
            ERR_SEC_INTERACTION_NOT_ALLOWED to StorageError.Unavailable(),
            ERR_SEC_NOT_AVAILABLE to StorageError.Unavailable(),
            ERR_SEC_DECODE to StorageError.Undecryptable(null),
            ERR_SEC_USER_CANCELED to StorageError.AccessDenied(platformCode = ERR_SEC_USER_CANCELED),
            ERR_SEC_AUTH_FAILED to StorageError.AccessDenied(platformCode = ERR_SEC_AUTH_FAILED),
            TRANSIENT to StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT),
        )
        for ((status: Int, expected: StorageError) in cases) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("mine", group = "TEAM.default"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            keychain.script = { operation: String, q: Map<String, String> ->
                if (isGroupProbeDelete(operation, q)) status else null
            }

            val result: StorageResult<Unit> = storeOver(keychain).clear()

            assertTrue(
                keychain.calls.any { it.operation == "addReadingAccessGroup" && it.query["service"] == probeService },
                "status=$status control: the probe was added",
            )
            assertEquals(StorageResult.Failure(expected), result, "status=$status: the group delete's status, mapped by 4.10")
            assertTrue(
                keychain.calls.any { isOwnProbeDelete(it.operation, it.query) },
                "status=$status: no cleanup by own account was sent: ${keychain.calls}",
            )
            assertTrue(
                keychain.items.none { it.service == probeService },
                "status=$status: a probe stayed: ${keychain.items.filter { it.service == probeService }.map { it.account }}",
            )
            assertEquals(
                listOf("mine", "theirs"),
                keychain.items.map { it.account },
                "status=$status: fail closed, no store item deleted",
            )
            assertTrue(
                keychain.calls.none { it.operation == "delete" && it.query["service"] == STORE_SERVICE },
                "status=$status: a store delete was sent although the group was not trusted: ${keychain.calls}",
            )
        }
    }

    @Test
    fun `the own-account cleanup leaves older leftovers and a foreign probe alone and the next clean clear sweeps only the default group`() =
        runTest {
            // Leftovers of killed clears sit in the default group under other accounts; an extension's probe sits in
            // TEAM.shared. The failing group delete swept nothing; the cleanup by own account may remove only our probe.
            // A later healthy clear sweeps the default group's leftovers and must still not reach TEAM.shared.
            val keychain = InMemoryKeychainPort()
            val killedA: InMemoryKeychainPort.Item = probe("TEAM.default", "${KeychainQueries.PROBE_ACCOUNT}.killed-a")
            val killedB: InMemoryKeychainPort.Item = probe("TEAM.default", "${KeychainQueries.PROBE_ACCOUNT}.killed-b")
            val foreign: InMemoryKeychainPort.Item = probe("TEAM.shared", "${KeychainQueries.PROBE_ACCOUNT}.extension")
            listOf(killedA, killedB, foreign).forEach { keychain.seed(it) }
            keychain.seed(keychain.item("mine", group = "TEAM.default"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            keychain.script = { operation: String, q: Map<String, String> ->
                if (isGroupProbeDelete(operation, q)) TRANSIENT else null
            }

            val failed: StorageResult<Unit> = storeOver(keychain).clear()

            assertEquals(
                StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT)),
                failed,
            )
            assertEquals(
                setOf(killedA.account, killedB.account, foreign.account),
                keychain.items.filter { it.service == probeService }.map { it.account }.toSet(),
                "exactly our probe must be gone, the leftovers and the foreign probe untouched by the account-only cleanup",
            )

            keychain.script = null
            val healthy: StorageResult<Unit> = storeOver(keychain).clear()

            assertEquals(StorageResult.Success(Unit), healthy)
            assertEquals(
                listOf(foreign.account, "theirs"),
                keychain.items.map { it.account },
                "the healthy clear sweeps the default group's leftovers and the store, and nothing of TEAM.shared",
            )
        }

    @Test
    fun `a double failure leaves at most its own probe in the default group and the original status and the next clear sweeps it`() =
        runTest {
            // Both the group delete and the cleanup fail: nothing more can be tried. The bound the executor names (one
            // probe per double failure, swept by the next successful clear) and the kept status are what a caller can rely
            // on; the store and another group's probe stay untouched.
            val keychain = InMemoryKeychainPort()
            val foreign: InMemoryKeychainPort.Item = probe("TEAM.shared", "${KeychainQueries.PROBE_ACCOUNT}.extension")
            keychain.seed(foreign)
            keychain.seed(keychain.item("mine", group = "TEAM.default"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            keychain.script = { operation: String, q: Map<String, String> ->
                when {
                    isGroupProbeDelete(operation, q) -> TRANSIENT
                    isOwnProbeDelete(operation, q) -> CLEANUP_FAILURE
                    else -> null
                }
            }

            val failed: StorageResult<Unit> = storeOver(keychain).clear()

            assertEquals(
                StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT)),
                failed,
                "the group delete's status, not the cleanup's",
            )
            val stranded: List<InMemoryKeychainPort.Item> =
                keychain.items.filter { it.service == probeService && it.group == "TEAM.default" }
            assertEquals(1, stranded.size, "control: exactly the one probe of this double failure stays: ${stranded.map { it.account }}")
            assertTrue(stranded.single().account.startsWith(KeychainQueries.PROBE_ACCOUNT + "."), stranded.single().account)
            assertEquals(listOf(foreign.account, "mine", "theirs"), keychain.items.filter { it !in stranded }.map { it.account })

            keychain.script = null
            assertEquals(StorageResult.Success(Unit), storeOver(keychain).clear())
            assertEquals(
                listOf(foreign.account, "theirs"),
                keychain.items.map { it.account },
                "the next clean clear sweeps the stranded probe and the store, never TEAM.shared",
            )
        }

    @Test
    fun `the cleanup of a synchronizable or user-presence store names a local probe and opens no session`() = runTest {
        // The probe is always local and never gated: a store option leaking into the cleanup (synchronizable = true, an
        // access control, an authentication context) would make the cleanup miss the probe or show a sheet.
        val variants: List<Pair<String, IosSecureStorageOptions>> = listOf(
            "synchronizable" to IosSecureStorageOptions(accessibility = KeychainAccessibility.WHEN_UNLOCKED, synchronizable = true),
            "userPresence" to IosSecureStorageOptions(
                userPresence = IosUserPresence(IosUserPresencePolicy.BIOMETRY_CURRENT_SET, prompt = "p"),
            ),
        )
        for ((name: String, options: IosSecureStorageOptions) in variants) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("mine", group = "TEAM.default", synchronizable = options.synchronizable))
            keychain.script = { operation: String, q: Map<String, String> ->
                if (isGroupProbeDelete(operation, q)) TRANSIENT else null
            }
            val sessions = CountingAuthenticationSessions()

            val result: StorageResult<Unit> = storeOver(keychain, options = options, sessions = sessions).clear()

            assertEquals(
                StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT)),
                result,
                name,
            )
            val cleanup: InMemoryKeychainPort.Call = keychain.calls.single { isOwnProbeDelete(it.operation, it.query) }
            assertEquals("false", cleanup.query["synchronizable"], "$name: ${cleanup.query}")
            assertTrue("context" !in cleanup.query && "accessControl" !in cleanup.query, "$name: ${cleanup.query}")
            assertTrue(keychain.items.none { it.service == probeService }, "$name: a probe stayed")
            assertEquals(listOf("mine"), keychain.items.map { it.account }, "$name: no store item deleted")
            assertEquals(0, sessions.opened, "$name: clear opened an authentication session")
        }
    }

    @Test
    fun `a clear cancelled while its own-account cleanup is blocked still finishes the cleanup and deletes no store item`() =
        runTest(timeout = 120.seconds) {
            // The round-4 cleanup is a third blocking call after the add and the group delete; a suspension point slipped
            // in before it (a yield, a withContext hop) would let a cancellation strand the probe on exactly this path.
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("mine", group = "TEAM.default"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            val cleanupReached = CompletableDeferred<Unit>()
            val releaseCleanup = CompletableDeferred<Unit>()
            keychain.script = { operation: String, q: Map<String, String> ->
                when {
                    isGroupProbeDelete(operation, q) -> TRANSIENT
                    isOwnProbeDelete(operation, q) && !cleanupReached.isCompleted -> {
                        cleanupReached.complete(Unit)
                        runBlocking { releaseCleanup.await() }
                        null
                    }
                    else -> null
                }
            }
            val store: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)

            val clearing: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.clear() }
            cleanupReached.await()
            assertTrue(clearing.isActive, "control: the clear is inside its cleanup")
            clearing.cancel()
            releaseCleanup.complete(Unit)
            clearing.join()

            assertTrue(clearing.isCancelled, "control: the caller was cancelled")
            assertTrue(keychain.items.none { it.service == probeService }, "a cancelled clear stranded its probe: ${keychain.calls}")
            assertEquals(listOf("mine", "theirs"), keychain.items.map { it.account }, "and deleted no store item")
            assertTrue(
                keychain.calls.none { it.operation == "delete" && it.query["service"] == STORE_SERVICE },
                "a store delete followed a failed resolution: ${keychain.calls}",
            )
        }

    @Test
    fun `a clear whose group delete fails while a concurrent clear succeeds reports its failure and the store is cleared exactly once`() =
        runTest(timeout = 120.seconds) {
            // A's group delete is held, then fails. Meanwhile B resolves the same default group, sweeps both probes (its own
            // and A's) and clears the store. A's cleanup then finds nothing (ignored): A keeps its failure and sends no store
            // delete; B's success stands; no probe remains; TEAM.shared is untouched.
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("mine", group = "TEAM.default"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            val groupDeletes = AtomicInt(0)
            val aHeld = CompletableDeferred<Unit>()
            val releaseA = CompletableDeferred<Unit>()
            keychain.script = { operation: String, q: Map<String, String> ->
                if (isGroupProbeDelete(operation, q) && groupDeletes.incrementAndFetch() == 1) {
                    aHeld.complete(Unit)
                    runBlocking { releaseA.await() }
                    TRANSIENT
                } else {
                    null
                }
            }
            val storeA: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)

            val a: Deferred<StorageResult<Unit>> = async(Dispatchers.Default) { storeA.clear() }
            aHeld.await()
            assertEquals(1, keychain.items.count { it.service == probeService }, "control: A's probe is in the Keychain")
            val b: StorageResult<Unit> = storeOver(keychain).clear()
            releaseA.complete(Unit)
            val resultA: StorageResult<Unit> = a.await()

            assertEquals(StorageResult.Success(Unit), b, "B resolved and cleared")
            assertEquals(
                StorageResult.Failure(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = TRANSIENT)),
                resultA,
                "A keeps the failure of its group delete although its cleanup found nothing",
            )
            assertTrue(keychain.items.none { it.service == probeService }, "${keychain.items.map { it.account }}")
            assertEquals(listOf("theirs"), keychain.items.map { it.account }, "the default store cleared, TEAM.shared untouched")
            val storeDeletes: List<InMemoryKeychainPort.Call> =
                keychain.calls.filter { it.operation == "delete" && it.query["service"] == STORE_SERVICE }
            assertEquals(1, storeDeletes.size, "only B deletes the store: $storeDeletes")
            assertEquals("TEAM.default", storeDeletes.single().query["group"])
        }

    private companion object {
        /** A status no mapping translates (`errSecInvalidOwnerEdit`); stands for a transient securityd failure. */
        const val TRANSIENT: Int = -25244

        /** A different untranslated status for the cleanup (`errSecNoSuchAttr`), so the two cannot be confused. */
        const val CLEANUP_FAILURE: Int = -25303
    }
}
