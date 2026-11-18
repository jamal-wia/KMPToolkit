package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.CoreFoundation.CFDictionaryRef
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 2 of group sto-ios (ticket 5.1.0, STO-5): attacks on the round-2 resolution of the default access group
 * (unique probe account, no lookup; fix of breaker M2) that `clear()` of an `accessGroup = null` store runs first.
 *
 * Contract (D-18; KDoc of [DefaultAccessGroup] and `IosSecureKeyValueStorage.clear`; docs/kmptoolkit-storage/
 * 05-platform-notes.md): `clear()` deletes the store's items in exactly the app's default group, fails closed when the
 * group cannot be resolved, and "nothing is left behind silently": the probe is removed before `resolve()` returns,
 * on every path, and a probe of another group is never touched.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDefaultAccessGroupBreakerR2Test {

    private val probeService: String = KeychainQueries.probeService(STORE_SERVICE)

    /**
     * The model, except that a successful `SecItemAdd` answers without a group in its attributes: the one success path of
     * [DefaultAccessGroup.resolve] that does not reach the probe's delete. The item IS added (the add succeeded).
     */
    private class AddAnswersNoGroup(val inner: InMemoryKeychainPort) : KeychainPort by inner {
        override fun addReadingAccessGroup(query: CFDictionaryRef): KeychainAccessGroup =
            KeychainAccessGroup(inner.addReadingAccessGroup(query).status, group = null)
    }

    @Test
    fun `an add that succeeds without reporting its group fails closed and leaves no probe behind`() = runTest {
        // DefaultAccessGroup.resolve(): `val group = added.group ?: return unreadableGroup()` returns before the
        // probe's delete. The probe was added (status success), so every such clear() files one more item under the
        // probe service, forever: the Keychain grows by one item per call and nothing ever sweeps it (the sweep needs
        // the group this path does not know). The probe has an account of its own, so it can be deleted by that account
        // without naming a group, touching nothing else.
        val inner = InMemoryKeychainPort()
        inner.seed(inner.item("a", group = "TEAM.default"))
        inner.seed(inner.item("b", group = "TEAM.shared"))
        val store: IosSecureKeyValueStorage = storeOver(AddAnswersNoGroup(inner))

        val results: List<StorageResult<Unit>> = (1..CLEARS).map { store.clear() }

        assertEquals(
            CLEARS,
            inner.calls.count { it.operation == "addReadingAccessGroup" && it.query["service"] == probeService },
            "control: every clear added a probe",
        )
        assertTrue(results.all { it is StorageResult.Failure }, "fail closed: a clear with no known group fails: $results")
        assertEquals(2, inner.items.count { it.service == STORE_SERVICE }, "fail closed: no store item deleted")
        val probes: List<InMemoryKeychainPort.Item> = inner.items.filter { it.service == probeService }
        assertTrue(
            probes.isEmpty(),
            "$CLEARS failed clears left ${probes.size} probe items behind (one per call, never swept): " +
                "${probes.map { it.group to it.account }}; calls: ${inner.operations()}",
        )
    }

    @Test
    fun `default-group clears racing an explicit-group writer and a shared-group writer and an extension's probes never touch the shared group`() =
        runTest(timeout = 120.seconds) {
            // Real threads, invariants only. Four default-group stores clear in a loop (each resolution adds and sweeps
            // a probe in TEAM.default); a store with accessGroup = TEAM.default writes items for them to delete; a
            // same-name store in TEAM.shared writes and reads back its own keys; an "extension" leaves probes of its
            // own in TEAM.shared (killed mid-clear). Default-group writes through `accessGroup = null` are left out on
            // purpose: they would reach TEAM.shared through the known limit M3/Q6, which is not under test here.
            for (seed: Int in SEEDS) {
                val keychain = InMemoryKeychainPort()
                val sharedOptions = IosSecureStorageOptions(accessGroup = "TEAM.shared")
                val defaultGroupOptions = IosSecureStorageOptions(accessGroup = "TEAM.default")
                val problems: List<String> = withContext(Dispatchers.Default) {
                    coroutineScope {
                        val clearers = (0 until 4).map { worker ->
                            async {
                                val store: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)
                                (0 until 40).mapNotNull { step ->
                                    val result: StorageResult<Unit> = store.clear()
                                    if (result is StorageResult.Success) null else "seed=$seed clearer=$worker step=$step: $result"
                                }
                            }
                        }
                        val defaultWriter = async {
                            val rng = Random(seed)
                            val store: IosSecureKeyValueStorage =
                                storeOver(keychain, options = defaultGroupOptions, dispatcher = Dispatchers.Default)
                            (0 until 80).mapNotNull { step ->
                                val result: StorageResult<Unit> = store.put("d${rng.nextInt(4)}", "d$step")
                                if (result is StorageResult.Success) null else "seed=$seed defaultWriter step=$step: $result"
                            }
                        }
                        val sharedWriter = async {
                            val rng = Random(seed + 1)
                            val store: IosSecureKeyValueStorage =
                                storeOver(keychain, options = sharedOptions, dispatcher = Dispatchers.Default)
                            (0 until 80).mapNotNull { step ->
                                val key = "s${rng.nextInt(3)}"
                                val put: StorageResult<Unit> = store.put(key, "s$step")
                                val read: StorageResult<String?> = store.get(key)
                                if (put == StorageResult.Success(Unit) && read == StorageResult.Success("s$step")) {
                                    null
                                } else {
                                    "seed=$seed sharedWriter step=$step key=$key: put=$put read=$read (a default-group clear reached TEAM.shared?)"
                                }
                            }
                        }
                        val extension = async {
                            (0 until FOREIGN_PROBES).forEach { index ->
                                keychain.seed(
                                    InMemoryKeychainPort.Item(
                                        service = probeService,
                                        account = "${KeychainQueries.PROBE_ACCOUNT}.extension-$seed-$index",
                                        group = "TEAM.shared",
                                        synchronizable = false,
                                        accessible = "afterFirstUnlockThisDeviceOnly",
                                        accessControl = false,
                                        data = null,
                                    ),
                                )
                            }
                            emptyList<String>()
                        }
                        (clearers + defaultWriter + sharedWriter + extension).awaitAll().flatten()
                    }
                }
                assertTrue(problems.isEmpty(), problems.take(10).joinToString("\n"))
                val probes: List<InMemoryKeychainPort.Item> = keychain.items.filter { it.service == probeService }
                assertEquals(
                    FOREIGN_PROBES,
                    probes.count { it.group == "TEAM.shared" },
                    "seed=$seed: the extension's probes in TEAM.shared must be untouched: ${probes.map { it.group to it.account }}",
                )
                assertTrue(
                    probes.none { it.group == "TEAM.default" },
                    "seed=$seed: a probe was left in the default group: ${probes.map { it.group to it.account }}",
                )
                assertTrue(
                    keychain.items.count { it.service == STORE_SERVICE && it.group == "TEAM.shared" } in 1..3,
                    "seed=$seed: the shared store's keys survive every default-group clear",
                )
            }
        }

    private companion object {
        const val CLEARS: Int = 5
        const val FOREIGN_PROBES: Int = 12
        val SEEDS: List<Int> = listOf(4242, 90210, 31337)
    }
}
