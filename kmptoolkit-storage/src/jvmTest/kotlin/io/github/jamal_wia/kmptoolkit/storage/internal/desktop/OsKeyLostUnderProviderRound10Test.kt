package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.security.Provider
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 10 (breaker finding B7-M1; lead ruling): under a provider that attaches a cause to its real tag failures
 * (the PKCS#11 shape) a key-check that fails with a PROVIDER failure is, for an OS-key store whose file has ZERO entries, read as
 * "this key does not open it", exactly like a not-found item ("a zero-entry file has nothing to lose"). `clear()` keeps the header
 * and the key-check, so without the rule every later round would fail on them again and `clear()` would be no way out. For a file
 * WITH entries nothing changes: `OperationFailed`, nothing moved, nothing quarantined (the environment never deletes data).
 *
 * The breaker's `EncryptedStoreCryptoProviderBreakerR8B7Test` covers `clear()` then `put`; here the other paths of the same rule:
 * a CACHED wrong key (the cache branch of `resolve`), `ensureKey` first, a file with entries (the narrowness), and `put` and
 * `ensureKey` (not only `get`) on a lost key with entries. The stand-in provider is the breaker's, inserted first and removed in `finally`.
 */
class OsKeyLostUnderProviderRound10Test {

    private inline fun <T> withProvider(provider: Provider, block: () -> T): T {
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    private fun replaceItemKeys(rig: SecureTestRig) {
        val other = ByteArray(SecureFileFormat.DATA_KEY_BYTES) { index: Int -> (index * 13 + 5).toByte() }
        rig.fake.macItems.replaceAll { _, _ -> other.copyOf() }
        rig.fake.secretItems.replaceAll { _, item: FakeNativeKeyStoreBinding.SecretItem ->
            FakeNativeKeyStoreBinding.SecretItem(item.label, java.util.Base64.getEncoder().encode(other))
        }
        assertEquals(1, rig.fake.macItems.size + rig.fake.secretItems.size, "control: one item, now holding another key")
    }

    private fun quarantined(directory: File): List<String> = directory.list().orEmpty().filter { ".undecryptable-" in it }

    private fun assertOperationFailed(answer: StorageResult<*>, operation: StorageOperation, label: String) {
        val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
        assertEquals(operation, assertIs<StorageError.OperationFailed>(error, "$label: $error").operation, "$label: operation")
    }

    @Test
    fun `a cached key that does not open an empty file is dropped and the real key is fetched under a failing provider`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store exists")
                    assertEquals(StorageResult.Success(Unit), rig.store().clear(), "$os: control, now a file with zero entries")
                    val cache = OsKeyCache()
                    cache.put(StoreIdentity.of(SecureTestRig.NAME, rig.directory), ByteArray(SecureFileFormat.DATA_KEY_BYTES) { 9 })
                    val store: EncryptedKeyValueStorage = rig.store(cache = cache)
                    withProvider(B7CausedTagGcmProvider()) {
                        val tagFailures: Int = B7CausedTagGcmSpi.causedTagFailures.get()
                        assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "$os: put")
                        assertTrue(B7CausedTagGcmSpi.causedTagFailures.get() > tagFailures, "$os: control, the wrong cached key failed the key-check with a cause")
                        assertEquals(StorageResult.Success("v2"), store.get("k2"), "$os: the real key was fetched and used")
                    }
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined")
                }
            }
        }
        Unit
    }

    @Test
    fun `ensureKey on an emptied store whose item holds another key creates a working store under a failing provider`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store exists")
                    replaceItemKeys(rig)
                    val store: EncryptedKeyValueStorage = rig.store(cache = OsKeyCache())
                    withProvider(B7CausedTagGcmProvider()) {
                        assertEquals(StorageResult.Success(Unit), store.clear(), "$os: clear")
                        assertEquals(StorageResult.Success(Unit), store.ensureKey(), "$os: ensureKey is the first call after clear")
                        assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "$os: put")
                        assertEquals(StorageResult.Success("v2"), rig.store(cache = OsKeyCache()).get("k2"), "$os: a cold instance reads it")
                    }
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined (the file was empty: nothing to lose)")
                }
            }
        }
        Unit
    }

    @Test
    fun `a lost key with entries stays OperationFailed for get put and ensureKey under a failing provider and nothing moves`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store exists")
                    replaceItemKeys(rig)
                    val before: ByteArray = rig.dataFile().readBytes()
                    val itemsBefore: Map<Pair<String, String>, ByteArray> = rig.fake.macItems.mapValues { it.value.copyOf() }
                    val cached = OsKeyCache().also { it.put(StoreIdentity.of(SecureTestRig.NAME, rig.directory), ByteArray(SecureFileFormat.DATA_KEY_BYTES) { 9 }) }
                    withProvider(B7CausedTagGcmProvider()) {
                        for ((label: String, cache: OsKeyCache) in listOf("cold" to OsKeyCache(), "wrong cached key" to cached)) {
                            val store: EncryptedKeyValueStorage = rig.store(cache = cache)
                            assertOperationFailed(store.get("k"), StorageOperation.GET, "$os $label get")
                            assertOperationFailed(store.put("k2", "v2"), StorageOperation.PUT, "$os $label put")
                            assertOperationFailed(store.ensureKey(), StorageOperation.ENSURE_KEY, "$os $label ensureKey")
                        }
                    }
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: the file is byte-identical")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined")
                    assertEquals(1, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: the item was neither replaced nor removed")
                    if (os == DesktopOs.MACOS) {
                        assertEquals(itemsBefore.keys, rig.fake.macItems.keys, "$os: the same item")
                        itemsBefore.forEach { (key: Pair<String, String>, value: ByteArray) -> assertContentEquals(value, rig.fake.macItems.getValue(key), "$os: item bytes") }
                    }
                }
            }
        }
        Unit
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
