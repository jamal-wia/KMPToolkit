package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Breaker round 1 of group sto-ios (ticket 5.1.0, STO-5): the I-2b rule (D-18) applied to the single-key operations.
 *
 * Contract: a store with `accessGroup = null` lives in the app's default access group, and a same-named store that
 * names a shared group is a different store (05-platform-notes.md, "clear deletes by service, in one access group":
 * the 6.0.0 `clear()` "never touches a same-named store that lives in a shared group"; KDoc of
 * `createSecureKeyValueStorage`). A query that names no group matches every group the app belongs to (the premise of
 * I-2b, modelled by [InMemoryKeychainPort]), so a default-group store's `put`, `get` and `remove` reach the shared
 * store's item exactly as the old `clear()` did. The executor named this weak spot in its report (residual risks)
 * for `get`/`remove`; `put` is worse: `SecItemUpdate` updates every match, so it overwrites the other store's value.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class IosSecureStoreGroupScopeBreakerR1Test {

    private val shared: IosSecureStorageOptions = IosSecureStorageOptions(accessGroup = "TEAM.shared")

    @Test
    fun `a put of a default-group store does not overwrite a same-name store's item in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        val sharedStore: IosSecureKeyValueStorage = storeOver(keychain, shared)
        val defaultStore: IosSecureKeyValueStorage = storeOver(keychain)
        assertEquals(StorageResult.Success(Unit), sharedStore.put("k", "shared-value"))

        assertEquals(StorageResult.Success(Unit), defaultStore.put("k", "default-value"))

        assertEquals(
            StorageResult.Success("shared-value"),
            sharedStore.get("k"),
            "the default-group store's put overwrote the shared-group store's item; items: " +
                "${keychain.items.map { it.group to it.account }}",
        )
        assertEquals(StorageResult.Success("default-value"), defaultStore.get("k"))
    }

    @Test
    fun `a remove of a default-group store does not delete a same-name store's item in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("k", "shared-value", group = "TEAM.shared"))
        val sharedStore: IosSecureKeyValueStorage = storeOver(keychain, shared)
        val defaultStore: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), defaultStore.remove("k"))

        assertEquals(
            StorageResult.Success("shared-value"),
            sharedStore.get("k"),
            "the default-group store's remove deleted the shared-group store's item",
        )
    }

    @Test
    fun `a get of a default-group store does not read a same-name store's item in a shared group`() = runTest {
        val keychain = InMemoryKeychainPort(defaultGroup = "TEAM.default")
        keychain.seed(keychain.item("k", "shared-value", group = "TEAM.shared"))
        val defaultStore: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(
            StorageResult.Success(null),
            defaultStore.get("k"),
            "the default-group store read the shared-group store's value",
        )
    }
}
