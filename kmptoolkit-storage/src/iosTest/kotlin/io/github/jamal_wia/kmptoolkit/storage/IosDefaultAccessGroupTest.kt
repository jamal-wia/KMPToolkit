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
 *
 * The probe is added under an account of its own per call and never looked up (breaker round 1, M2): a lookup would
 * have to name no group and could adopt another process's probe, whose group is that process's default.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class IosDefaultAccessGroupTest {

    private val probeService: String = KeychainQueries.probeService(STORE_SERVICE)

    private fun StorageResult<*>.error(): StorageError = (this as StorageResult.Failure).error

    private fun queries(): KeychainQueries = KeychainQueries(
        service = STORE_SERVICE,
        accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY,
        accessGroup = null,
        synchronizable = false,
        userPresence = null,
    )

    private fun leftoverProbe(group: String, account: String): InMemoryKeychainPort.Item = InMemoryKeychainPort.Item(
        service = probeService,
        account = account,
        group = group,
        synchronizable = false,
        accessible = "afterFirstUnlockThisDeviceOnly",
        accessControl = false,
        data = null,
    )

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
            listOf("addReadingAccessGroup", "delete", "delete", "addReadingAccessGroup", "delete", "delete"),
            keychain.operations(),
            "per clear: one probe add (no lookup), the probe's delete, the store's delete",
        )
    }

    @Test
    fun `a probe left in the app's own group by a killed clear is swept by the next clear and never adopted`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(leftoverProbe("TEAM.default", account = "${KeychainQueries.PROBE_ACCOUNT}.killed"))
        keychain.seed(keychain.item("mine"))
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(1, keychain.calls.count { it.operation == "addReadingAccessGroup" }, "the probe is always added fresh")
        assertTrue(keychain.items.isEmpty(), "the leftover probe is removed with this clear's own: ${keychain.items.map { it.service to it.account }}")
    }

    @Test
    fun `a probe left in a shared group stays there and its group is not taken for ours`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(leftoverProbe("TEAM.shared", account = "${KeychainQueries.PROBE_ACCOUNT}.extension"))
        keychain.seed(keychain.item("mine"))
        keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(
            listOf(probeService to "TEAM.shared", STORE_SERVICE to "TEAM.shared"),
            keychain.items.map { it.service to it.group },
            "only the other group's probe and store item survive",
        )
    }

    @Test
    fun `every clear adds its probe under an account of its own`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        repeat(3) { assertEquals(StorageResult.Success(Unit), store.clear()) }

        val accounts: List<String?> = keychain.calls.filter { it.operation == "addReadingAccessGroup" }.map { it.query["account"] }
        assertEquals(3, accounts.size)
        assertEquals(3, accounts.toSet().size, "the three probes must not share an account: $accounts")
        assertTrue(accounts.all { it != null && it.startsWith(KeychainQueries.PROBE_ACCOUNT + ".") }, "$accounts")
    }

    @Test
    fun `the probe account is the one the injected source gives`() = runTest {
        val keychain = InMemoryKeychainPort()
        val resolved: StorageResult<String> = DefaultAccessGroup(keychain, queries()) { "fixed-account" }.resolve()

        assertEquals(StorageResult.Success("TEAM.default"), resolved)
        assertEquals("fixed-account", keychain.calls.first { it.operation == "addReadingAccessGroup" }.query["account"])
    }

    @Test
    fun `a concurrent clear that adds its own probe in between does not disturb this clear`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "addReadingAccessGroup") {
                // The other clear adds its probe (another account) just before ours lands, in the same default group.
                keychain.seed(leftoverProbe("TEAM.default", account = "${KeychainQueries.PROBE_ACCOUNT}.other"))
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(listOf("addReadingAccessGroup", "delete", "delete"), keychain.operations(), "no retry round exists")
        assertTrue(keychain.items.isEmpty())
    }

    @Test
    fun `a concurrent clear that already swept our probe leaves this clear successful`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        var swept = false
        keychain.script = { operation: String, q: Map<String, String> ->
            if (operation == "delete" && q["service"] == probeService && !swept) {
                // The other clear's sweep of the default group removes our probe before our own delete runs.
                swept = true
                queries().probeRemove("TEAM.default").use { keychain.delete(it) }
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertTrue(keychain.items.isEmpty(), "errSecItemNotFound on the probe's delete is a success: ${keychain.items.map { it.account }}")
    }

    // --- fail closed ----------------------------------------------------------------------------------------

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

    /** The model, except that a successful add answers with [group] instead of the group the item went to. */
    private class AddAnswers(val inner: InMemoryKeychainPort, val group: String?) : KeychainPort by inner {
        override fun addReadingAccessGroup(query: platform.CoreFoundation.CFDictionaryRef): KeychainAccessGroup =
            KeychainAccessGroup(inner.addReadingAccessGroup(query).status, group)
    }

    @Test
    fun `a success that carries no access group string fails closed and removes its own probe`() = runTest {
        // The framework answered success but the attribute dictionary named no group: the store cannot say which group is
        // its own, and the probe it added is in the Keychain: deleted by its unique account, with no group.
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine"))
        model.seed(model.item("theirs", group = "TEAM.shared"))

        val error: StorageError = storeOver(AddAnswers(model, group = null)).clear().error()

        assertEquals(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null), error)
        assertEquals(listOf("mine", "theirs"), model.items.map { it.account }, "the store's items stay, no probe stays")
        val deletes: List<InMemoryKeychainPort.Call> = model.calls.filter { it.operation == "delete" }
        assertEquals(1, deletes.size, "exactly one delete: the cleanup of the probe")
        assertEquals(probeService, deletes.single().query["service"])
        assertTrue(deletes.single().query["account"]?.startsWith(KeychainQueries.PROBE_ACCOUNT + ".") == true, "${deletes.single().query}")
        assertEquals(null, deletes.single().query["group"], "the group is the thing that is not known")
    }

    @Test
    fun `a blank access group string is as unusable as none and removes the probe`() = runTest {
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine"))

        val error: StorageError = storeOver(AddAnswers(model, group = "")).clear().error()

        assertEquals(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null), error)
        assertEquals(listOf("mine"), model.items.map { it.account })
        assertEquals(listOf(probeService), model.calls.filter { it.operation == "delete" }.map { it.query["service"] })
    }

    @Test
    fun `repeated clears without a reported group never accumulate probes`() = runTest {
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine"))
        val store: IosSecureKeyValueStorage = storeOver(AddAnswers(model, group = null))

        repeat(5) { assertTrue(store.clear() is StorageResult.Failure) }

        assertEquals(5, model.calls.count { it.operation == "addReadingAccessGroup" }, "control: five probes were added")
        assertEquals(emptyList(), model.items.filter { it.service == probeService }, "no probe may be left behind")
    }

    @Test
    fun `a failing cleanup of the unreported probe keeps the original failure`() = runTest {
        val model = InMemoryKeychainPort()
        model.seed(model.item("mine"))
        model.script = { operation: String, q: Map<String, String> ->
            if (operation == "delete" && q["service"] == probeService) -25244 else null
        }

        val error: StorageError = storeOver(AddAnswers(model, group = null)).clear().error()

        assertEquals(StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = null), error)
        assertTrue(model.items.any { it.account == "mine" }, "the store's items are never touched")
    }

    @Test
    fun `a probe add that answers duplicate anyway fails closed at once without a retry`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("mine"))
        // A unique account cannot collide, so a duplicate means something is wrong: the clear says so and deletes nothing.
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "addReadingAccessGroup") errSecDuplicateItem else null }

        val error: StorageError = storeOver(keychain).clear().error()

        assertEquals(
            StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = errSecDuplicateItem),
            error,
        )
        assertEquals(listOf("addReadingAccessGroup"), keychain.operations(), "no lookup and no retry loop exist")
        assertEquals(1, keychain.items.size)
    }

    @Test
    fun `many concurrent clears of default-group stores all succeed and leave no probe`() = runTest(timeout = 120.seconds) {
        for (round: Int in 0 until 50) {
            val keychain = InMemoryKeychainPort()
            keychain.seed(keychain.item("mine"))
            keychain.seed(keychain.item("theirs", group = "TEAM.shared"))
            val stores: List<IosSecureKeyValueStorage> = List(CONCURRENT_CLEARS) { storeOver(keychain, dispatcher = Dispatchers.Default) }

            val results: List<StorageResult<Unit>> = stores.map { store -> async { store.clear() } }.awaitAll()

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

    private companion object {
        /** More clears than the old three-round lookup loop could survive. */
        const val CONCURRENT_CLEARS: Int = 6
    }
}
