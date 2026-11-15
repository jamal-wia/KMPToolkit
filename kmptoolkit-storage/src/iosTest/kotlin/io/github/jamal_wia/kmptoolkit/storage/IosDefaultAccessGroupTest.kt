package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import platform.Security.errSecDuplicateItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * `clear()` of a store with `accessGroup = null` (I-2b, decision D-18): it must resolve the app's default access
 * group and delete in that group only, so a same-named store in a shared group keeps its items; it must leave no
 * probe item behind; and when the group cannot be resolved it must fail closed, deleting nothing.
 *
 * Against [InMemoryKeychainPort], which models "a delete that names no group matches every group", so a regression
 * to the wider delete shows up as a surviving-item assertion failing, not as a changed query string only.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class IosDefaultAccessGroupTest {

    private val probeService: String = KeychainQueries.probeService(STORE_SERVICE)

    private fun StorageResult<*>.error(): StorageError = (this as StorageResult.Failure).error

    @Test
    fun `clear of a default-group store leaves a same-name store in a shared group untouched I-2b`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine", group = "TEAM.default"))
        keychain.seed(keychain.item("mine-synced", group = "TEAM.default", synchronizable = true, accessible = "afterFirstUnlock"))
        keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(listOf("theirs"), keychain.items.map { it.account }, "only the shared group's item may survive")
        assertEquals("TEAM.shared", keychain.items.single().group)
    }

    @Test
    fun `clear names the resolved group in its delete and sends no delete without one`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        val deletes: List<InMemoryKeychainPort.Call> = keychain.calls.filter { it.operation == "delete" }
        assertEquals(2, deletes.size, "the probe's delete and the store's delete")
        assertEquals(probeService, deletes[0].query["service"])
        assertEquals("TEAM.default", deletes[0].query["group"])
        assertEquals(STORE_SERVICE, deletes[1].query["service"])
        assertEquals("TEAM.default", deletes[1].query["group"])
        assertEquals("any", deletes[1].query["synchronizable"])
        assertTrue(deletes.all { "group" in it.query }, "no delete may omit the access group: $deletes")
    }

    @Test
    fun `the resolution leaves no probe item behind and no item of the store appears`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())
        assertEquals(StorageResult.Success(Unit), store.clear())

        assertTrue(keychain.items.isEmpty(), "left over: ${keychain.items.map { it.service to it.account }}")
        assertEquals(
            listOf("readAccessGroup", "addReadingAccessGroup", "delete", "delete"),
            keychain.operations().take(4),
        )
    }

    @Test
    fun `a probe that a killed process left behind is used and removed rather than added again`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(
            InMemoryKeychainPort.Item(probeService, KeychainQueries.PROBE_ACCOUNT, "TEAM.default", false, "afterFirstUnlockThisDeviceOnly", false, null),
        )
        keychain.seed(keychain.item("mine"))
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertTrue("addReadingAccessGroup" !in keychain.operations(), "an existing probe must not be added again")
        assertTrue(keychain.items.isEmpty(), "the leftover probe is removed with the clear")
    }

    @Test
    fun `a second clear that races the first one's probe still resolves`() = runTest {
        val keychain = InMemoryKeychainPort()
        // The other clear() adds the probe between our lookup and our add: our add then sees a duplicate.
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "addReadingAccessGroup" && keychain.items.isEmpty()) {
                keychain.seed(
                    InMemoryKeychainPort.Item(probeService, KeychainQueries.PROBE_ACCOUNT, "TEAM.default", false, "afterFirstUnlockThisDeviceOnly", false, null),
                )
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(
            listOf("readAccessGroup", "addReadingAccessGroup", "readAccessGroup", "delete", "delete"),
            keychain.operations(),
        )
        assertTrue(keychain.items.isEmpty())
    }

    // --- fail closed ----------------------------------------------------------------------------------------

    @Test
    fun `when the lookup fails the clear fails and nothing is deleted`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "readAccessGroup") ERR_SEC_INTERACTION_NOT_ALLOWED else null }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageError.Unavailable(), store.clear().error())

        assertEquals(2, keychain.items.size, "fail closed: nothing may be deleted")
        assertTrue("delete" !in keychain.operations())
    }

    @Test
    fun `when the probe cannot be added the clear fails with the mapped status and nothing is deleted`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "addReadingAccessGroup") ERR_SEC_MISSING_ENTITLEMENT else null }

        val error: StorageError = storeOver(keychain).clear().error()

        assertEquals(StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT), error)
        assertEquals(1, keychain.items.size)
        assertTrue("delete" !in keychain.operations())
    }

    @Test
    fun `a probe that cannot be removed fails the clear before the store is touched`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        keychain.script = { operation: String, q: Map<String, String> ->
            if (operation == "delete" && q["service"] == probeService) -25244 else null
        }

        val error: StorageError = storeOver(keychain).clear().error()

        assertEquals(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = -25244), error)
        assertTrue(keychain.items.any { it.account == "mine" }, "the store's own items must not be deleted when the probe stays")
    }

    @Test
    fun `a success that carries no access group string fails closed`() = runTest {
        // The framework answered success but the attribute dictionary named no group: the store cannot say which group is its own.
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine"))
        val withoutGroup = object : KeychainPort by model {
            override fun addReadingAccessGroup(query: platform.CoreFoundation.CFDictionaryRef): KeychainAccessGroup =
                KeychainAccessGroup(platform.Security.errSecSuccess, null)
        }

        val error: StorageError = storeOver(withoutGroup).clear().error()

        assertEquals(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null), error)
        assertEquals(listOf("mine"), model.items.map { it.account })
        assertTrue("delete" !in model.operations())
    }

    @Test
    fun `a probe that keeps answering duplicate is retried a bounded number of times and then fails closed`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        // The add always loses (the other writer's probe is gone again by our next lookup): never an endless loop.
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "addReadingAccessGroup") errSecDuplicateItem else null }

        val error: StorageError = storeOver(keychain).clear().error()

        assertEquals(
            StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = errSecDuplicateItem),
            error,
        )
        assertEquals(List(3) { listOf("readAccessGroup", "addReadingAccessGroup") }.flatten(), keychain.operations())
        assertEquals(1, keychain.items.size)
    }

    @Test
    fun `two concurrent clears of default-group stores both succeed and leave no probe`() = runTest(timeout = 120.seconds) {
        for (round: Int in 0 until 50) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("mine"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            val first: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)
            val second: IosSecureKeyValueStorage = storeOver(keychain, dispatcher = Dispatchers.Default)

            val results: List<StorageResult<Unit>> = listOf(async { first.clear() }, async { second.clear() }).awaitAll()

            assertTrue(results.all { it == StorageResult.Success(Unit) }, "round $round answered $results")
            assertEquals(listOf("theirs"), keychain.items.map { it.account }, "round $round")
        }
    }

    @Test
    fun `a configured group is never resolved or probed`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(accessGroup = "TEAM.shared"))

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(listOf("delete"), keychain.operations())
    }
}
