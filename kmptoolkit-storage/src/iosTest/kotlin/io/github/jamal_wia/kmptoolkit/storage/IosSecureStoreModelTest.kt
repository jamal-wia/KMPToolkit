package io.github.jamal_wia.kmptoolkit.storage

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSData
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.dataUsingEncoding
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

internal const val STORE_SERVICE: String = "app.model.kmptoolkit.securestorage"

internal fun TestScope.storeOver(
    keychain: KeychainPort,
    options: IosSecureStorageOptions = IosSecureStorageOptions(),
    sessions: AuthenticationSessions = CountingAuthenticationSessions(),
    dispatcher: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    service: String = STORE_SERVICE,
): IosSecureKeyValueStorage = IosSecureKeyValueStorage(service, options, dispatcher, keychain, sessions)

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun utf8(value: String): NSData = NSString.create(string = value).dataUsingEncoding(NSUTF8StringEncoding)!!

internal fun InMemoryKeychainPort.item(
    account: String,
    value: String = "v",
    group: String = defaultGroup,
    synchronizable: Boolean = false,
    accessible: String? = "afterFirstUnlockThisDeviceOnly",
    service: String = STORE_SERVICE,
): InMemoryKeychainPort.Item = InMemoryKeychainPort.Item(
    service = service,
    account = account,
    group = group,
    synchronizable = synchronizable,
    accessible = accessible,
    accessControl = false,
    data = utf8(value),
)

/**
 * What [IosSecureKeyValueStorage] asks of the Keychain, against [InMemoryKeychainPort]: which calls each
 * operation makes, what they say, and what the store answers to every status of the plan's error table (4.10). The
 * model carries the rules the store depends on, so this runs in a test binary that has no Keychain; what only a
 * device decides is a case of the `storage-ios-harness` module.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class, ExperimentalCoroutinesApi::class)
class IosSecureStoreModelTest {

    private fun StorageResult<*>.error(): StorageError = (this as StorageResult.Failure).error

    // --- round trips ----------------------------------------------------------------------------------------

    @Test
    fun `a value round-trips and an absent key reads as null`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(null), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.put("k", "v1"))
        assertEquals(StorageResult.Success("v1"), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.put("k", "v2"))
        assertEquals(StorageResult.Success("v2"), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.remove("k"))
        assertEquals(StorageResult.Success(null), store.get("k"))
        assertEquals(StorageResult.Success(Unit), store.remove("k"), "remove of an absent key succeeds")
    }

    @Test
    fun `an empty value is stored as a value and is not absent`() = runTest {
        val store: IosSecureKeyValueStorage = storeOver(InMemoryKeychainPort())

        assertEquals(StorageResult.Success(Unit), store.put("k", ""))
        assertEquals(StorageResult.Success(""), store.get("k"))
    }

    @Test
    fun `a value with every kind of character survives`() = runTest {
        val store: IosSecureKeyValueStorage = storeOver(InMemoryKeychainPort())
        val value = "line1\nline2 é 😀 \u0000 end"

        assertEquals(StorageResult.Success(Unit), store.put("k", value))
        assertEquals(StorageResult.Success(value), store.get("k"))
    }

    // --- I-1: accessibility on update -----------------------------------------------------------------------

    @Test
    fun `an overwrite moves an existing item to the configured accessibility I-1`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k", "old", accessible = "whenUnlocked"))
        val store: IosSecureKeyValueStorage = storeOver(
            keychain,
            IosSecureStorageOptions(accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK_THIS_DEVICE_ONLY),
        )

        assertEquals(StorageResult.Success(Unit), store.put("k", "new"))

        assertEquals(listOf("update"), keychain.operations(), "an existing item is updated, never re-added")
        assertEquals("afterFirstUnlockThisDeviceOnly", keychain.calls.single().attributes!!["accessible"])
        assertEquals("afterFirstUnlockThisDeviceOnly", keychain.items.single().accessible)
        assertEquals(StorageResult.Success("new"), store.get("k"))
    }

    @Test
    fun `a first write adds the item with the configured accessibility after the update misses`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(
            keychain,
            IosSecureStorageOptions(accessibility = KeychainAccessibility.WHEN_UNLOCKED),
        )

        assertEquals(StorageResult.Success(Unit), store.put("k", "v"))

        assertEquals(listOf("update", "add"), keychain.operations())
        assertEquals("whenUnlocked", keychain.calls.last().query["accessible"])
        assertEquals("whenUnlocked", keychain.items.single().accessible)
    }

    // --- the errSecDuplicateItem retry ----------------------------------------------------------------------

    @Test
    fun `an add that loses a race to another writer retries as an update and succeeds`() = runTest {
        val keychain = InMemoryKeychainPort()
        // The other writer lands between our update (miss) and our add.
        keychain.script = { operation: String, _: Map<String, String> ->
            if (operation == "add" && keychain.items.isEmpty()) {
                keychain.seed(keychain.item("k", "theirs"))
            }
            null
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.put("k", "ours"))

        assertEquals(listOf("update", "add", "update"), keychain.operations())
        assertEquals(StorageResult.Success("ours"), store.get("k"))
        assertEquals(1, keychain.items.size)
    }

    @Test
    fun `an update that still fails after a duplicate is reported with its status`() = runTest {
        val keychain = InMemoryKeychainPort()
        var updates = 0
        keychain.script = { operation: String, _: Map<String, String> ->
            when (operation) {
                "update" -> if (++updates == 1) errSecItemNotFound else ERR_SEC_INTERACTION_NOT_ALLOWED
                "add" -> platform.Security.errSecDuplicateItem
                else -> null
            }
        }
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageError.Unavailable(), store.put("k", "v").error())
        assertEquals(2, updates)
    }

    // --- the error table (4.10), through the store ----------------------------------------------------------

    @Test
    fun `every status of the table maps as the plan says on get`() = runTest {
        val table: List<Pair<Int, StorageError>> = listOf(
            ERR_SEC_INTERACTION_NOT_ALLOWED to StorageError.Unavailable(),
            ERR_SEC_NOT_AVAILABLE to StorageError.Unavailable(),
            ERR_SEC_USER_CANCELED to StorageError.AccessDenied(-128),
            ERR_SEC_AUTH_FAILED to StorageError.AccessDenied(-25293),
            ERR_SEC_MISSING_ENTITLEMENT to StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT),
            ERR_SEC_DECODE to StorageError.Undecryptable("k"),
            -25301 to StorageError.OperationFailed(StorageOperation.GET, key = "k", platformCode = -25301),
        )
        for ((status: Int, expected: StorageError) in table) {
            val keychain = InMemoryKeychainPort()
            keychain.script = { operation: String, _: Map<String, String> -> if (operation == "readData") status else null }

            assertEquals(expected, storeOver(keychain).get("k").error(), "status $status")
        }
    }

    @Test
    fun `an ungranted access group is Unsupported MISSING_ENTITLEMENT on every operation and not OperationFailed`() = runTest {
        val keychain = InMemoryKeychainPort(grantedGroups = setOf("TEAM.default"))
        val store: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(accessGroup = "TEAM.ungranted"))
        val unsupported = StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT)

        assertEquals(unsupported, store.get("k").error())
        assertEquals(unsupported, store.put("k", "v").error())
        assertEquals(unsupported, store.remove("k").error())
        assertEquals(unsupported, store.clear().error())
        assertTrue(keychain.items.isEmpty())
    }

    @Test
    fun `a write that fails with an unknown status keeps the raw code`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "update") -25244 else null }

        assertEquals(
            StorageError.OperationFailed(StorageOperation.PUT, key = "k", platformCode = -25244),
            storeOver(keychain).put("k", "v").error(),
        )
    }

    @Test
    fun `an item whose bytes are not UTF-8 is Undecryptable for its key`() = runTest {
        val keychain = InMemoryKeychainPort()
        val invalid: NSData = ByteArray(2) { 0xFF.toByte() }.usePinned { NSData.create(bytes = it.addressOf(0), length = 2UL) }
        keychain.seed(
            InMemoryKeychainPort.Item(STORE_SERVICE, "k", keychain.defaultGroup, false, "afterFirstUnlock", false, invalid),
        )

        assertEquals(StorageError.Undecryptable("k"), storeOver(keychain).get("k").error())
    }

    @Test
    fun `an unpaired surrogate is rejected before any Keychain call`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        val error: StorageError = store.put("k", "a\uD800b").error()

        assertTrue(error is StorageError.InvalidValue && error.key == "k", "answered $error")
        assertTrue(keychain.calls.isEmpty())
    }

    // --- exact synchronizable on read and write -------------------------------------------------------------

    @Test
    fun `a local store and a synchronizable store never see each other's items`() = runTest {
        val keychain = InMemoryKeychainPort()
        val local: IosSecureKeyValueStorage = storeOver(keychain)
        val synced: IosSecureKeyValueStorage = storeOver(
            keychain,
            IosSecureStorageOptions(accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK, synchronizable = true),
        )

        assertEquals(StorageResult.Success(Unit), local.put("k", "device"))
        assertEquals(StorageResult.Success(null), synced.get("k"), "the synchronizable store must not read a local item")
        assertEquals(StorageResult.Success(Unit), synced.put("k", "cloud"))

        assertEquals(StorageResult.Success("device"), local.get("k"))
        assertEquals(StorageResult.Success("cloud"), synced.get("k"))
        assertEquals(2, keychain.items.size)
        assertEquals(setOf(false, true), keychain.items.map { it.synchronizable }.toSet())
    }

    @Test
    fun `remove deletes only the store's own kind of item`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("k", "local", synchronizable = false))
        keychain.seed(keychain.item("k", "cloud", synchronizable = true, accessible = "afterFirstUnlock"))
        val synced: IosSecureKeyValueStorage = storeOver(
            keychain,
            IosSecureStorageOptions(accessibility = KeychainAccessibility.AFTER_FIRST_UNLOCK, synchronizable = true),
        )

        assertEquals(StorageResult.Success(Unit), synced.remove("k"))

        assertEquals(listOf(false), keychain.items.map { it.synchronizable })
    }

    // --- clear() with a configured group (I-2) --------------------------------------------------------------

    @Test
    fun `clear with a configured group removes local and synchronizable items of that group and nothing else`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.seed(keychain.item("a", group = "TEAM.shared"))
        keychain.seed(keychain.item("b", group = "TEAM.shared", synchronizable = true, accessible = "afterFirstUnlock"))
        keychain.seed(keychain.item("c", group = "TEAM.default"))
        keychain.seed(keychain.item("d", group = "TEAM.shared", service = "another.service"))
        val store: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(accessGroup = "TEAM.shared"))

        assertEquals(StorageResult.Success(Unit), store.clear())

        assertEquals(setOf("c", "d"), keychain.items.map { it.account }.toSet())
        assertEquals(listOf("delete"), keychain.operations(), "a configured group needs no probe")
        assertEquals("TEAM.shared", keychain.calls.single().query["group"])
        assertEquals("any", keychain.calls.single().query["synchronizable"])
    }

    @Test
    fun `clear of an empty store succeeds`() = runTest {
        val store: IosSecureKeyValueStorage = storeOver(InMemoryKeychainPort(), IosSecureStorageOptions(accessGroup = "TEAM.shared"))

        assertEquals(StorageResult.Success(Unit), store.clear())
    }

    @Test
    fun `a clear that fails reports CLEAR with no key and its raw code`() = runTest {
        val keychain = InMemoryKeychainPort()
        keychain.script = { operation: String, _: Map<String, String> -> if (operation == "delete") -25244 else null }
        val store: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(accessGroup = "TEAM.shared"))

        assertEquals(
            StorageError.OperationFailed(StorageOperation.CLEAR, key = null, platformCode = -25244),
            store.clear().error(),
        )
    }

    // --- ensureKey ------------------------------------------------------------------------------------------

    @Test
    fun `ensureKey succeeds with no items and with items and and changes nothing`() = runTest {
        val keychain = InMemoryKeychainPort()
        val store: IosSecureKeyValueStorage = storeOver(keychain)

        assertEquals(StorageResult.Success(Unit), store.ensureKey())
        keychain.seed(keychain.item("k"))
        keychain.seed(keychain.item("s", synchronizable = true, accessible = "afterFirstUnlock"))
        val before: List<InMemoryKeychainPort.Item> = keychain.items
        assertEquals(StorageResult.Success(Unit), store.ensureKey())

        assertEquals(listOf("matches", "matches"), keychain.operations(), "ensureKey must only look")
        assertEquals(before, keychain.items)
        assertEquals("fail", keychain.calls.last().query["authUI"])
        assertEquals("any", keychain.calls.last().query["synchronizable"])
    }

    @Test
    fun `ensureKey failures report ENSURE_KEY with no key`() = runTest {
        val table: List<Pair<Int, StorageError>> = listOf(
            ERR_SEC_NOT_AVAILABLE to StorageError.Unavailable(),
            ERR_SEC_INTERACTION_NOT_ALLOWED to StorageError.Unavailable(),
            ERR_SEC_MISSING_ENTITLEMENT to StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT),
            ERR_SEC_DECODE to StorageError.Undecryptable(null),
            -25244 to StorageError.OperationFailed(StorageOperation.ENSURE_KEY, key = null, platformCode = -25244),
        )
        for ((status: Int, expected: StorageError) in table) {
            val keychain = InMemoryKeychainPort()
            keychain.script = { operation: String, _: Map<String, String> -> if (operation == "matches") status else null }

            assertEquals(expected, storeOver(keychain).ensureKey().error(), "status $status")
        }
    }

    @Test
    fun `ensureKey of a store in an ungranted group is MISSING_ENTITLEMENT`() = runTest {
        val keychain = InMemoryKeychainPort(grantedGroups = setOf("TEAM.default"))
        val store: IosSecureKeyValueStorage = storeOver(keychain, IosSecureStorageOptions(accessGroup = "TEAM.ungranted"))

        assertEquals(StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT), store.ensureKey().error())
    }

    // --- the plain mapping function -------------------------------------------------------------------------

    @Test
    fun `the status mapping is total and keeps the entry key only where there is one`() {
        assertEquals(StorageError.Undecryptable(null), ERR_SEC_DECODE.toStorageError(StorageOperation.CLEAR, key = null))
        assertEquals(StorageError.Undecryptable("k"), ERR_SEC_DECODE.toStorageError(StorageOperation.GET, "k"))
        assertEquals(StorageError.AccessDenied(-128), ERR_SEC_USER_CANCELED.toStorageError(StorageOperation.GET, "k"))
        assertEquals(StorageError.AccessDenied(-25293), ERR_SEC_AUTH_FAILED.toStorageError(StorageOperation.PUT, "k"))
        assertEquals(
            StorageError.Unsupported(UnsupportedReason.MISSING_ENTITLEMENT),
            ERR_SEC_MISSING_ENTITLEMENT.toStorageError(StorageOperation.REMOVE, "k"),
        )
        assertNull((ERR_SEC_PARAM.toStorageError(StorageOperation.GET, "k") as StorageError.OperationFailed).cause)
    }

    @Test
    fun `the model itself agrees with the framework constants it stands in for`() {
        assertEquals(0, errSecSuccess)
        assertEquals(-25300, errSecItemNotFound)
    }
}
