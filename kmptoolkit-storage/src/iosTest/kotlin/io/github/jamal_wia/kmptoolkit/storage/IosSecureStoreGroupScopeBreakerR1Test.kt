package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5), rewritten after the owner's decision for 6.0.0 to pin the
 * documented known limit instead of expecting the old, stronger behaviour.
 *
 * The documented behaviour (`IosSecureStorageOptions.accessGroup`, `docs/kmptoolkit-storage/05-platform-notes.md`, "Known
 * limit: with `accessGroup = null`, `get`, `put` and `remove` address every access group the app can reach"): only
 * `clear()` is pinned to the default access group. With `accessGroup = null` the single-key operations send a query that
 * names no group, which the Keychain matches against every group the app belongs to (the premise modelled by
 * [InMemoryKeychainPort]), so a same-named store in a shared group can be read, deleted or overwritten by a default-group
 * store. Narrowing them would hide items created under an earlier default group, which is a data loss, so the 5.0.0
 * semantics stay. The advice that goes with it is also pinned: an explicit `accessGroup` or a distinct store name keeps the
 * stores apart.
 *
 * Each pinning test fails if the behaviour changes (for example, if `get`, `put` or `remove` start to name the default
 * group): the documentation, the KDoc and the CHANGELOG known issue must then change with it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStoreGroupScopeBreakerR1Test {

    private val shared: IosSecureStorageOptions = IosSecureStorageOptions(accessGroup = "TEAM.shared")

    @Test
    fun `documented limit - a put of a default-group store overwrites a same-name store's item in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        val sharedStore: IosSecureKeyValueStorage = storeOver(keychain, shared)
        val defaultStore: IosSecureKeyValueStorage = storeOver(keychain)
        assertEquals(StorageResult.Success(Unit), sharedStore.put("k", "shared-value"))
        assertEquals(listOf("TEAM.shared" to "k"), keychain.items.map { it.group to it.account }, "control: the shared store's item")

        assertEquals(StorageResult.Success(Unit), defaultStore.put("k", "default-value"))

        assertEquals(
            StorageResult.Success("default-value"),
            sharedStore.get("k"),
            "documented limit: the update of a default-group store's put matches the shared group's item and overwrites it",
        )
        assertEquals(
            listOf("TEAM.shared" to "k"),
            keychain.items.map { it.group to it.account },
            "documented limit: the put updated the shared item and added nothing to the default group",
        )
        assertEquals(StorageResult.Success("default-value"), defaultStore.get("k"))
    }

    @Test
    fun `documented limit - a remove of a default-group store deletes a same-name store's item in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("k", "shared-value", group = "TEAM.shared"))
        val sharedStore: IosSecureKeyValueStorage = storeOver(keychain, shared)
        val defaultStore: IosSecureKeyValueStorage = storeOver(keychain)
        assertEquals(StorageResult.Success("shared-value"), sharedStore.get("k"), "control: the shared store's item is there")

        assertEquals(StorageResult.Success(Unit), defaultStore.remove("k"))

        assertEquals(
            StorageResult.Success(null),
            sharedStore.get("k"),
            "documented limit: the delete of a default-group store's remove matches the shared group's item and deletes it",
        )
        assertEquals(listOf<Pair<String, String>>(), keychain.items.map { it.group to it.account })
    }

    @Test
    fun `documented limit - a get of a default-group store reads a same-name store's item in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("k", "shared-value", group = "TEAM.shared"))
        val defaultStore: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(
            StorageResult.Success("shared-value"),
            defaultStore.get("k"),
            "documented limit: the read of a default-group store names no group and finds the shared group's item",
        )
    }

    @Test
    fun `documented advice - an explicit accessGroup keeps a store apart from a same-name store in another group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("k", "shared-value", group = "TEAM.shared"))
        val sharedStore: IosSecureKeyValueStorage = storeOver(keychain, shared)
        val pinnedStore: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(accessGroup = "TEAM.default"))

        assertEquals(StorageResult.Success(null), pinnedStore.get("k"), "an explicit group does not read the shared item")
        assertEquals(StorageResult.Success(Unit), pinnedStore.remove("k"))
        assertEquals(StorageResult.Success("shared-value"), sharedStore.get("k"), "an explicit group does not delete the shared item")
        assertEquals(StorageResult.Success(Unit), pinnedStore.put("k", "default-value"))

        assertEquals(StorageResult.Success("shared-value"), sharedStore.get("k"), "an explicit group does not overwrite the shared item")
        assertEquals(StorageResult.Success("default-value"), pinnedStore.get("k"))
        assertEquals(
            setOf("TEAM.shared" to "k", "TEAM.default" to "k"),
            keychain.items.map { it.group to it.account }.toSet(),
            "each store has its own item in its own group",
        )
    }

    @Test
    fun `documented advice - a distinct store name keeps a default-group store apart from a store in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("k", "shared-value", group = "TEAM.shared"))
        val sharedStore: IosSecureKeyValueStorage = storeOver(keychain, shared)
        val otherStore: IosSecureKeyValueStorage = storeOver(keychain, service = "$STORE_SERVICE.other")

        assertEquals(StorageResult.Success(null), otherStore.get("k"), "a distinct name does not read the shared item")
        assertEquals(StorageResult.Success(Unit), otherStore.remove("k"))
        assertEquals(StorageResult.Success(Unit), otherStore.put("k", "other-value"))

        assertEquals(StorageResult.Success("shared-value"), sharedStore.get("k"), "a distinct name leaves the shared item alone")
        assertEquals(StorageResult.Success("other-value"), otherStore.get("k"))
    }
}
