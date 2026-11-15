package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import java.io.File
import java.security.Security
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 11 (breaker finding B8-M1): under a provider that attaches a cause to its real tag failures (the PKCS#11
 * shape) a cached key that does not open the key-check of a file WITH entries is a provider failure, and a stale key (another process
 * replaced the OS item) looks the same. `OsKeyStoreEngine.resolve` tells them apart without a native call while the provider fails:
 * it asks the item for its key only when the provider still seals and opens with the cached key (a pure round trip), and replaces the
 * cache only by a fetched key that opens THIS file. The breaker's `OsKeyStoreProviderBreakerR9B8Test` covers the stale key; here the
 * other branches of the same decision. The stand-in provider is the breaker's [B8TokenGcmProvider], inserted first and removed in `finally`.
 *
 * - **A transient token error on a warm store with entries** costs ONE fetch and the call succeeds (the item still holds the cached key).
 * - **A fetched key that does not open the file** (the item holds an unrelated key) leaves the failure and the cache as they were.
 * - **An item that is gone while a wrong key is cached** leaves the failure and the cache as they were (the cached key may be the only copy).
 * - **A key-check that no key opens** (tampered file, the cached key equals the item) answers `OperationFailed`, moves nothing, and the
 *   documented cost is one fetch per failing call.
 */
class OsKeyStaleCacheRound11Test {

    private inline fun <T> withTokenProvider(block: () -> T): T {
        B8TokenGcmSpi.causeOnRealTagFailure = true
        B8TokenGcmSpi.failNextDecrypts.set(0)
        check(Security.insertProviderAt(B8TokenGcmProvider(), 1) == 1) { "control: the stand-in provider is first" }
        try {
            return block()
        } finally {
            Security.removeProvider(B8TokenGcmProvider.NAME)
            B8TokenGcmSpi.failNextDecrypts.set(0)
            B8TokenGcmSpi.causeOnRealTagFailure = true
        }
    }

    private fun fetchCall(os: DesktopOs): String = when (os) {
        DesktopOs.MACOS -> "copyGenericPassword"
        DesktopOs.LINUX -> "lookup"
        DesktopOs.WINDOWS -> "unprotect"
    }

    private fun createCall(os: DesktopOs): String = when (os) {
        DesktopOs.MACOS -> "addGenericPassword"
        DesktopOs.LINUX -> "store"
        DesktopOs.WINDOWS -> "protect"
    }

    private fun quarantined(directory: File): List<String> = directory.list().orEmpty().filter { ".undecryptable-" in it }

    private fun wrongKey(): ByteArray = ByteArray(SecureFileFormat.DATA_KEY_BYTES) { 9 }

    private fun assertOperationFailed(answer: StorageResult<*>, operation: StorageOperation, label: String) {
        val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
        assertEquals(operation, assertIs<StorageError.OperationFailed>(error, "$label: $error").operation, "$label: operation")
    }

    @Test
    fun `a transient token error on a warm store with entries costs one fetch and the call succeeds`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in DesktopOs.entries) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val store: EncryptedKeyValueStorage = rig.store()
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control, the store has an entry")
                    val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
                    val cachedBefore: ByteArray = checkNotNull(rig.cache.get(identity)) { "control: the key is cached" }.copyOf()
                    val fetches: Int = rig.fake.count(fetchCall(os))
                    val creates: Int = rig.fake.count(createCall(os))
                    val before: ByteArray = rig.dataFile().readBytes()
                    withTokenProvider {
                        val injected: Int = B8TokenGcmSpi.injected.get()
                        B8TokenGcmSpi.failNextDecrypts.set(1)
                        assertEquals(StorageResult.Success("v"), store.get("k"), "$os: get after one transient token error")
                        assertEquals(injected + 1, B8TokenGcmSpi.injected.get(), "$os: control, the token error was served")
                    }
                    assertEquals(fetches + 1, rig.fake.count(fetchCall(os)), "$os: a transient error on a warm store costs exactly one fetch")
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: nothing may be created")
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: the file changed")
                    assertContentEquals(cachedBefore, checkNotNull(rig.cache.get(identity)), "$os: the cached key changed")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined")
                }
            }
        }
        Unit
    }

    @Test
    fun `a wrong cached key whose fetched item key does not open the file leaves the failure and the cache as they were`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store has an entry")
                    val unrelated = ByteArray(SecureFileFormat.DATA_KEY_BYTES) { index: Int -> (index * 13 + 5).toByte() }
                    rig.fake.macItems.replaceAll { _, _ -> unrelated.copyOf() }
                    rig.fake.secretItems.replaceAll { _, item: FakeNativeKeyStoreBinding.SecretItem ->
                        FakeNativeKeyStoreBinding.SecretItem(item.label, java.util.Base64.getEncoder().encode(unrelated))
                    }
                    assertEquals(1, rig.fake.macItems.size + rig.fake.secretItems.size, "$os: control, one item, now holding an unrelated key")
                    val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
                    val cache = OsKeyCache()
                    cache.put(identity, wrongKey())
                    val store: EncryptedKeyValueStorage = rig.store(cache = cache)
                    val fetches: Int = rig.fake.count(fetchCall(os))
                    val creates: Int = rig.fake.count(createCall(os))
                    val before: ByteArray = rig.dataFile().readBytes()
                    withTokenProvider {
                        assertOperationFailed(store.get("k"), StorageOperation.GET, "$os get")
                        assertOperationFailed(store.put("k2", "v2"), StorageOperation.PUT, "$os put")
                    }
                    assertEquals(fetches + 2, rig.fake.count(fetchCall(os)), "$os: control, each failing call asked the item for its key once")
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: nothing may be created")
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: the file changed")
                    assertContentEquals(wrongKey(), checkNotNull(cache.get(identity)), "$os: the cache was replaced by a key that does not open the file")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined")
                }
            }
        }
        Unit
    }

    @Test
    fun `a wrong cached key with no item left to fetch leaves the failure and the cache as they were`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store has an entry")
                    rig.fake.macItems.clear()
                    rig.fake.secretItems.clear()
                    val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
                    val cache = OsKeyCache()
                    cache.put(identity, wrongKey())
                    val store: EncryptedKeyValueStorage = rig.store(cache = cache)
                    val creates: Int = rig.fake.count(createCall(os))
                    val before: ByteArray = rig.dataFile().readBytes()
                    withTokenProvider {
                        assertOperationFailed(store.get("k"), StorageOperation.GET, "$os get")
                        assertOperationFailed(store.put("k2", "v2"), StorageOperation.PUT, "$os put")
                    }
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: a key must not be created for a file with entries")
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: the file changed")
                    assertContentEquals(wrongKey(), checkNotNull(cache.get(identity)), "$os: the cache was dropped")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined")
                }
            }
        }
        Unit
    }

    @Test
    fun `a key-check that the right key does not open answers OperationFailed, moves nothing and costs one fetch per call`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in DesktopOs.entries) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val store: EncryptedKeyValueStorage = rig.store()
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control, the store has an entry")
                    val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
                    val cachedBefore: ByteArray = checkNotNull(rig.cache.get(identity)) { "control: the key is cached" }.copyOf()
                    val parsed: ParsedStoreFile = (StoreClassifier.classify(rig.dataFile().readBytes(), StoreKind.entries.toSet()) as FileState.Parsed).file
                    val bytes: ByteArray = rig.dataFile().readBytes()
                    val at: Int = (0..bytes.size - parsed.keyCheck.size).first { start: Int ->
                        parsed.keyCheck.indices.all { offset: Int -> bytes[start + offset] == parsed.keyCheck[offset] }
                    }
                    bytes[at] = (bytes[at].toInt() xor 0x01).toByte()
                    rig.dataFile().writeBytes(bytes)
                    val fetches: Int = rig.fake.count(fetchCall(os))
                    val creates: Int = rig.fake.count(createCall(os))
                    withTokenProvider {
                        assertOperationFailed(store.get("k"), StorageOperation.GET, "$os get")
                        assertOperationFailed(store.get("k"), StorageOperation.GET, "$os get again")
                    }
                    assertEquals(fetches + 2, rig.fake.count(fetchCall(os)), "$os: documented cost, one fetch per failing call")
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: nothing may be created")
                    assertContentEquals(bytes, rig.dataFile().readBytes(), "$os: the file changed")
                    assertContentEquals(cachedBefore, checkNotNull(rig.cache.get(identity)), "$os: the cached key changed")
                    assertNotNull(rig.cache.get(identity), "$os: the cache entry is kept")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: nothing quarantined")
                }
            }
        }
        Unit
    }

    @Test
    fun `a wrong cached key that a transient error twice kept from a clean verdict never quarantines a file with entries`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    assertEquals(StorageResult.Success(Unit), rig.store().put("k", "v"), "$os: control, the store has an entry")
                    val unrelated = ByteArray(SecureFileFormat.DATA_KEY_BYTES) { index: Int -> (index * 13 + 5).toByte() }
                    rig.fake.macItems.replaceAll { _, _ -> unrelated.copyOf() }
                    rig.fake.secretItems.replaceAll { _, item: FakeNativeKeyStoreBinding.SecretItem ->
                        FakeNativeKeyStoreBinding.SecretItem(item.label, java.util.Base64.getEncoder().encode(unrelated))
                    }
                    val identity: StoreIdentity = StoreIdentity.of(SecureTestRig.NAME, rig.directory)
                    val cache = OsKeyCache()
                    cache.put(identity, wrongKey())
                    val store: EncryptedKeyValueStorage = rig.store(cache = cache)
                    val creates: Int = rig.fake.count(createCall(os))
                    val before: ByteArray = rig.dataFile().readBytes()
                    // SunJCE-shaped tag failures (a wrong key is a clean `false`), plus one transient token error on the cached key's check in
                    // each round: the first round asks the item for its key, the second round's cached check fails again and meets the fetched
                    // key, which cleanly does not open the file. Nothing in that history says the CACHED key is wrong, so nothing may be
                    // dropped, no "key lost" may be concluded and a put must not move the file aside.
                    rig.fake.before = { name: String -> if (name == fetchCall(os)) B8TokenGcmSpi.failNextDecrypts.set(1) }
                    withTokenProvider {
                        B8TokenGcmSpi.causeOnRealTagFailure = false
                        B8TokenGcmSpi.failNextDecrypts.set(1)
                        val injected: Int = B8TokenGcmSpi.injected.get()
                        assertOperationFailed(store.get("k"), StorageOperation.GET, "$os get")
                        assertEquals(injected + 2, B8TokenGcmSpi.injected.get(), "$os: control, the transient error was served in both rounds")
                        B8TokenGcmSpi.failNextDecrypts.set(1)
                        assertOperationFailed(store.put("k2", "v2"), StorageOperation.PUT, "$os put")
                    }
                    assertEquals(creates, rig.fake.count(createCall(os)), "$os: nothing may be created")
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: the file changed")
                    assertContentEquals(wrongKey(), checkNotNull(cache.get(identity)), "$os: the cache was replaced or dropped")
                    assertEquals(emptyList(), quarantined(rig.directory), "$os: a file with entries was moved aside")
                }
            }
        }
        Unit
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
