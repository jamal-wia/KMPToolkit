package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import platform.Security.errSecDuplicateItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5): attacks on the I-2b resolution of the app's default access
 * group ([DefaultAccessGroup], decision D-18) that `clear()` of an `accessGroup = null` store runs first.
 *
 * Contract (D-18; KDoc of `IosSecureKeyValueStorage.clear`; docs/kmptoolkit-storage/05-platform-notes.md "clear deletes
 * by service, in one access group"): `clear()` deletes the store's items in exactly the app's default group, never in
 * a shared group; a failure to resolve fails closed (nothing deleted); no probe item is ever orphaned by a cancelled
 * call.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosDefaultAccessGroupBreakerR1Test {

    private val probeService: String = KeychainQueries.probeService(STORE_SERVICE)

    private fun probeIn(group: String): InMemoryKeychainPort.Item = InMemoryKeychainPort.Item(
        service = probeService,
        account = KeychainQueries.PROBE_ACCOUNT,
        group = group,
        synchronizable = false,
        accessible = "afterFirstUnlockThisDeviceOnly",
        accessControl = false,
        data = null,
    )

    @Test
    fun `a probe left in a shared group by another process is not taken for this app's default group`() = runTest {
        // An app extension that shares TEAM.shared with the app and lists it FIRST in its keychain-access-groups has
        // TEAM.shared as its default group. It ran clear() of the same store name and was killed between the probe's
        // add and its delete (extensions are killed routinely). Its probe now sits in TEAM.shared. The app's own
        // default group is TEAM.default. The app's clear() must still clear TEAM.default and leave TEAM.shared alone:
        // a group learned from a probe that this process did not add is not this process's default group.
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("mine", "app-value", group = "TEAM.default"))
        keychain.seed(keychain.item("theirs", "shared-value", group = "TEAM.shared"))
        keychain.seed(probeIn("TEAM.shared"))
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        val result: StorageResult<Unit> = store.clear()

        val storeItems: List<InMemoryKeychainPort.Item> = keychain.items.filter { it.service == STORE_SERVICE }
        assertTrue(
            storeItems.any { it.group == "TEAM.shared" && it.account == "theirs" },
            "clear() of a default-group store deleted the same-name store's item in the shared group ($result); " +
                "remaining: ${storeItems.map { it.group to it.account }}; calls: ${keychain.calls}",
        )
        assertEquals(StorageResult.Success(Unit), result)
        assertTrue(
            storeItems.none { it.group == "TEAM.default" },
            "clear() must remove the store's items in the app's own default group; remaining: " +
                "${storeItems.map { it.group to it.account }}",
        )
    }

    @Test
    fun `a clear cancelled while its probe add is blocked leaves no probe behind`() = runTest(timeout = 120.seconds) {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("a"))
        val addReached = CompletableDeferred<Unit>()
        val releaseAdd = CompletableDeferred<Unit>()
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "addReadingAccessGroup" && !addReached.isCompleted) {
                addReached.complete(Unit)
                runBlocking { releaseAdd.await() }
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)

        val clearing: Job = launch(start = CoroutineStart.UNDISPATCHED) { store.clear() }
        addReached.await()
        assertTrue(clearing.isActive, "control: the clear is between its probe add and the probe delete")
        clearing.cancel()
        releaseAdd.complete(Unit)
        clearing.join()

        assertTrue(
            keychain.calls.any { it.operation == "addReadingAccessGroup" && it.query["service"] == probeService },
            "control: the probe add really happened",
        )
        assertTrue(
            keychain.items.none { it.service == probeService },
            "a cancelled clear orphaned its probe: ${keychain.items.map { it.service to it.group }}",
        )
    }

    @Test
    fun `every failing status at every step of the resolution fails closed and deletes nothing of the store`() = runTest {
        val statuses: List<Int> = listOf(
            ERR_SEC_INTERACTION_NOT_ALLOWED,
            ERR_SEC_NOT_AVAILABLE,
            ERR_SEC_MISSING_ENTITLEMENT,
            ERR_SEC_USER_CANCELED,
            ERR_SEC_AUTH_FAILED,
            ERR_SEC_DECODE,
            ERR_SEC_PARAM,
            errSecDuplicateItem,
            -25244, // errSecInvalidOwnerEdit
            -4, // errSecUnimplemented
            1, // a positive status no table knows
            Int.MIN_VALUE,
        )
        val steps: List<String> = listOf("readAccessGroup", "addReadingAccessGroup", "delete")
        val problems: MutableList<String> = mutableListOf()
        for (step: String in steps) {
            for (status: Int in statuses) {
                val keychain = InMemoryKeychainPort()
                keychain.seed(keychain.item("a", group = "TEAM.default"))
                keychain.seed(keychain.item("b", group = "TEAM.shared"))
                keychain.script = { operation: String, q: Map<String, String> ->
                    if (operation == step && q["service"] == probeService) status else null
                }
                val store: IosSecureKeyValueStorage = storeOver(keychain)

                val result: StorageResult<Unit> = store.clear()

                val label = "step=$step status=$status"
                if (result !is StorageResult.Failure) problems += "$label: answered $result"
                if (keychain.calls.any { it.operation == "delete" && it.query["service"] == STORE_SERVICE }) {
                    problems += "$label: a delete of the store's items was sent"
                }
                if (keychain.items.count { it.service == STORE_SERVICE } != 2) {
                    problems += "$label: store items changed: ${keychain.items.map { it.group to it.account }}"
                }
            }
        }
        assertTrue(problems.isEmpty(), problems.joinToString("\n"))
    }
}
