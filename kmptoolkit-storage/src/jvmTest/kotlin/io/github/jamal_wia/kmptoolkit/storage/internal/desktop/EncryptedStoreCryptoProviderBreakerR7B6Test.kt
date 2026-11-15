package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.SecureStorageProtection
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.security.Security
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Breaker round 6 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-6), against task/sto-desktop 29627aaf: the
 * typed crypto failures of round 8 (A6-m1, D-P12) as they reach the stores, at the call sites the earlier rounds did not
 * cover, and the rule "no state change on a provider failure".
 *
 * D-P12 / 05 "A JDK without AES-GCM or PBKDF2": a provider that fails (a `ProviderException` out of the operation, a random
 * source that cannot be read) makes every call answer `OperationFailed(operation, key)`, and writes, creates and moves nothing.
 * Here also: the KEY CACHES are state. A provider failure must not drop a verified cached key (the next call would ask the OS
 * key store again, which can mean a keychain prompt, or ask the app's provider and run PBKDF2 again), and must not bind an
 * app-key instance to a key it never re-keyed to. Every test has a control that the same call works once the provider is gone,
 * and counts the native fetches or provider calls that the recovery needed (zero: the cache survived).
 *
 * The failing provider (`A6FailingProvider`, AES-GCM and PBKDF2 engines that throw `ProviderException`) is inserted first for
 * the failing calls and removed in `finally`. Fake binding only.
 */
class EncryptedStoreCryptoProviderBreakerR7B6Test {

    private fun <T> withFailingProvider(block: suspend () -> T): T {
        val provider = A6FailingProvider()
        check(Security.insertProviderAt(provider, 1) == 1) { "control: the failing provider is first" }
        try {
            return runBlocking { block() }
        } finally {
            Security.removeProvider(provider.name)
        }
    }

    private fun assertOperationFailed(label: String, outcome: Result<StorageResult<*>>, operation: StorageOperation, key: String?) {
        assertTrue(outcome.isSuccess, "$label: an exception escaped the store call: ${outcome.exceptionOrNull()}")
        val error: StorageError = assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
        val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: a provider failure is neither a wrong nor a lost key: $error")
        assertEquals(operation, failed.operation, "$label: operation")
        assertEquals(key, failed.key, "$label: key")
        assertNotNull(failed.cause, "$label: the provider failure is the cause")
    }

    /** The native call that fetches the key of an existing OS-key store, per OS. */
    private fun fetchCall(os: DesktopOs): String = when (os) {
        DesktopOs.MACOS -> "copyGenericPassword"
        DesktopOs.LINUX -> "lookup"
        DesktopOs.WINDOWS -> "unprotect"
    }

    /**
     * A warm OS-key store (its verified key is in the process cache) meets a failing AES-GCM provider: `get`, `put`, `remove`
     * and `ensureKey` answer `OperationFailed(op, key)`, the file is byte-identical, and once the provider is gone the same
     * instance reads WITHOUT fetching the key from the OS again (a dropped cache entry would mean a new native fetch, a keychain
     * prompt with dialogs allowed). Red on: an exception, another answer, a changed file, a native fetch after the recovery.
     */
    @Test
    fun `a provider failure on a warm OS-key store changes neither the file nor the key cache`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val store: EncryptedKeyValueStorage = rig.store()
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control, the store exists")
                    assertEquals(StorageResult.Success(Unit), store.put("r", "to-remove"), "$os: control, a second entry")
                    assertEquals(1, rig.cache.size, "$os: control, the key is cached")
                    val before: ByteArray = rig.dataFile().readBytes()
                    val fetchesBefore: Int = rig.fake.count(fetchCall(os))
                    val answers: List<Triple<StorageOperation, String?, Result<StorageResult<*>>>> = withFailingProvider {
                        listOf(
                            Triple(StorageOperation.GET, "k", runCatching { store.get("k") }),
                            Triple(StorageOperation.PUT, "k2", runCatching { store.put("k2", "v2") }),
                            Triple(StorageOperation.REMOVE, "r", runCatching { store.remove("r") }),
                            Triple(StorageOperation.ENSURE_KEY, null, runCatching { store.ensureKey() }),
                        )
                    }
                    for ((operation: StorageOperation, key: String?, outcome: Result<StorageResult<*>>) in answers) {
                        assertOperationFailed("$os $operation", outcome, operation, key)
                    }
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: a failed call changed the store file")
                    assertEquals(1, rig.cache.size, "$os: the provider failure dropped the cached key")
                    assertEquals(StorageResult.Success("v"), store.get("k"), "$os: control, the store reads once the provider is gone")
                    assertEquals(StorageResult.Success("to-remove"), store.get("r"), "$os: the failed remove removed")
                    assertEquals(fetchesBefore, rig.fake.count(fetchCall(os)), "$os: the recovery fetched the key from the OS again (the cache was dropped)")
                }
            }
        }
        Unit
    }

    /**
     * A warm app-key store (raw and passphrase; its data key is cached in the instance) meets a failing AES-GCM provider: every
     * operation, `clear()` and `changeKey` included, answers `OperationFailed(op, key)`; the file is byte-identical, `clear()`
     * cleared nothing, `changeKey` neither asked the NEW key's provider nor rebound the instance (its protection is unchanged),
     * and once the provider is gone the same instance reads without asking its provider again (no new PBKDF2 run). Red on: an
     * exception, `AccessDenied`, a changed file, a rebound instance, a provider call after the recovery.
     */
    @Test
    fun `a provider failure on a warm app-key store changes neither the file nor the binding nor the cached key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (kind: String in listOf("raw", "passphrase")) {
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val providerCalls = AtomicInteger()
                    val newKeyCalls = AtomicInteger()
                    val key: AppEncryptionKey = if (kind == "raw") {
                        AppEncryptionKey.Raw { providerCalls.incrementAndGet(); ByteArray(32) { 8 } }
                    } else {
                        AppEncryptionKey.Passphrase(provider = { providerCalls.incrementAndGet(); "correct horse battery".toCharArray() })
                    }
                    val newKey = AppEncryptionKey.Raw { newKeyCalls.incrementAndGet(); ByteArray(32) { 4 } }
                    val name = "b6-warm-$kind"
                    val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, key, Dispatchers.IO, CrossProcessLock())
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$kind: control, the store exists")
                    assertEquals(StorageResult.Success(Unit), store.put("r", "to-remove"), "$kind: control, a second entry")
                    val protection: SecureStorageProtection = store.protection
                    val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                    val before: ByteArray = file.readBytes()
                    val callsBefore: Int = providerCalls.get()
                    val answers: List<Triple<StorageOperation, String?, Result<StorageResult<*>>>> = withFailingProvider {
                        listOf(
                            Triple(StorageOperation.GET, "k", runCatching { store.get("k") }),
                            Triple(StorageOperation.PUT, "k2", runCatching { store.put("k2", "v2") }),
                            Triple(StorageOperation.REMOVE, "r", runCatching { store.remove("r") }),
                            Triple(StorageOperation.CLEAR, null, runCatching { store.clear() }),
                            Triple(StorageOperation.ENSURE_KEY, null, runCatching { store.ensureKey() }),
                            Triple(StorageOperation.CHANGE_KEY, null, runCatching { store.changeKey(newKey) }),
                        )
                    }
                    for ((operation: StorageOperation, entry: String?, outcome: Result<StorageResult<*>>) in answers) {
                        assertOperationFailed("$kind $operation", outcome, operation, entry)
                    }
                    assertContentEquals(before, file.readBytes(), "$kind: a failed call changed the store file")
                    assertEquals(0, newKeyCalls.get(), "$kind: changeKey asked the new key's provider although the current key could not be checked")
                    assertEquals(protection, store.protection, "$kind: a failed changeKey rebound the instance")
                    assertEquals(StorageResult.Success("v"), store.get("k"), "$kind: control, the store reads once the provider is gone")
                    assertEquals(StorageResult.Success("to-remove"), store.get("r"), "$kind: the failed remove or clear removed")
                    assertEquals(callsBefore, providerCalls.get(), "$kind: the recovery asked the provider again (the cached data key was dropped)")
                }
            }
        }
        Unit
    }

    /**
     * `changeKey` from a raw key to a passphrase whose new salt cannot be drawn (the random source fails after the current key
     * verified and the new provider answered): `OperationFailed(CHANGE_KEY, null)`, the file byte-identical, the instance still
     * bound to the raw key (`protection` = APP_PROVIDED_KEY) and reading; a second `changeKey` with a working source re-keys,
     * and a new instance with the passphrase reads the value. Red on: an exception, a rebound instance after the failure, a
     * changed file, a failing retry.
     */
    @Test
    fun `a changeKey whose new salt cannot be drawn leaves the instance on its old key and can be repeated`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val random = B6RecordingRandom(SEED)
                val name = "b6-salt"
                val raw = AppEncryptionKey.Raw { ByteArray(32) { 3 } }
                val passphraseCalls = AtomicInteger()
                val passphrase = AppEncryptionKey.Passphrase(provider = { passphraseCalls.incrementAndGet(); "a new passphrase".toCharArray() })
                val store = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, raw, Dispatchers.IO, CrossProcessLock(), cipher = EnvelopeCipher(random))
                assertEquals(StorageResult.Success(Unit), store.put("a", "v-a"), "control: the store exists")
                val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                val before: ByteArray = file.readBytes()
                random.failFrom = random.draws.get() + 1
                assertOperationFailed("changeKey", runCatching { store.changeKey(passphrase) }, StorageOperation.CHANGE_KEY, null)
                assertEquals(1, passphraseCalls.get(), "control: the new provider answered before the salt draw failed")
                assertContentEquals(before, file.readBytes(), "the failed re-key changed the file")
                assertEquals(SecureStorageProtection.APP_PROVIDED_KEY, store.protection, "the failed re-key rebound the instance")
                assertEquals(emptyList(), random.liveKeys(), "a data key was left unwiped (seed=$SEED)")
                random.failFrom = Int.MAX_VALUE
                assertEquals(StorageResult.Success("v-a"), store.get("a"), "the instance still reads with its old key")
                assertEquals(StorageResult.Success(Unit), store.changeKey(passphrase), "the re-key can be repeated")
                assertEquals(SecureStorageProtection.PASSPHRASE, store.protection, "control: now bound to the passphrase")
                val reader = JvmAppKeyEncryptedKeyValueStorage(name, rig.directory, passphrase, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success("v-a"), reader.get("a"), "a new instance with the passphrase reads the value")
            }
        }
        Unit
    }

    /**
     * A put on an existing, warm store whose value nonce cannot be drawn (the random source fails at the seal): `OperationFailed`,
     * the file byte-identical, the cache kept, and the next put works. OS-key (macOS, Windows) and raw app-key stores.
     */
    @Test
    fun `a put whose value nonce cannot be drawn writes nothing and keeps the cache`() = runBlocking {
        withTimeout(HANG_BOUND) {
            for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.WINDOWS)) {
                SecureTestRig(os).use { rig: SecureTestRig ->
                    val random = B6RecordingRandom(SEED)
                    val store: EncryptedKeyValueStorage = rig.store(cipher = EnvelopeCipher(random))
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$os: control")
                    val before: ByteArray = rig.dataFile().readBytes()
                    val fetches: Int = rig.fake.count(fetchCall(os))
                    random.failFrom = random.draws.get() + 1
                    assertOperationFailed("$os put", runCatching { store.put("k2", "v2") }, StorageOperation.PUT, "k2")
                    assertContentEquals(before, rig.dataFile().readBytes(), "$os: the failed put changed the file")
                    random.failFrom = Int.MAX_VALUE
                    assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "$os: the next put works")
                    assertEquals(fetches, rig.fake.count(fetchCall(os)), "$os: the cache was dropped by the failed put")
                    assertEquals(StorageResult.Success("v"), store.get("k"), "$os: the first value is intact")
                }
            }
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val random = B6RecordingRandom(SEED)
                val calls = AtomicInteger()
                val name = "b6-nonce"
                val store = JvmAppKeyEncryptedKeyValueStorage(
                    name, rig.directory, AppEncryptionKey.Raw { calls.incrementAndGet(); ByteArray(32) { 1 } }, Dispatchers.IO, CrossProcessLock(),
                    cipher = EnvelopeCipher(random),
                )
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "raw: control")
                val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                val before: ByteArray = file.readBytes()
                val callsBefore: Int = calls.get()
                random.failFrom = random.draws.get() + 1
                assertOperationFailed("raw put", runCatching { store.put("k2", "v2") }, StorageOperation.PUT, "k2")
                assertContentEquals(before, file.readBytes(), "raw: the failed put changed the file")
                random.failFrom = Int.MAX_VALUE
                assertEquals(StorageResult.Success(Unit), store.put("k2", "v2"), "raw: the next put works")
                assertEquals(callsBefore, calls.get(), "raw: the cached data key was dropped by the failed put")
            }
        }
        Unit
    }

    private companion object {
        const val SEED: Int = 616
        val HANG_BOUND = 120.seconds
    }
}
