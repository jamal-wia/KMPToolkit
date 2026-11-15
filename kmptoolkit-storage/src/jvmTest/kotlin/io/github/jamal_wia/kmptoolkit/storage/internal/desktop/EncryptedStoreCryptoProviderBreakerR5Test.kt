package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.security.Provider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * Breaker round 4 of phase B of group `sto-desktop` (review/sto-desktop-B-breaker-4), against task/sto-desktop 9dadc3e4:
 * the JCA as a failure source. Nothing the encrypted stores need from the JDK's crypto layer is guaranteed: a FIPS-configured
 * JDK removes `SunJCE` (and its PBKDF2 and AES-GCM), and a provider can refuse a key or a spec. The contract of every store
 * call is a typed `StorageResult` (EncryptedFileGuard: "Nothing throws out of locked"; the guard maps "the AES-GCM provider
 * failed" to `OperationFailed`), so a missing or failing algorithm must answer a typed failure with no half state, and the
 * store must work again once the algorithm is back.
 *
 * The attack removes `SunJCE` for the duration of each call (restored at its own position in `finally`; skipped when another
 * provider still serves AES-GCM, so the absence is real). Fake binding only.
 */
class EncryptedStoreCryptoProviderBreakerR5Test {

    private fun <T> withoutSunJce(block: () -> T): T {
        val sunJce: Provider = Security.getProvider("SunJCE") ?: error("control: this JDK has SunJCE")
        val position: Int = Security.getProviders().indexOf(sunJce) + 1
        Security.removeProvider("SunJCE")
        try {
            val gcmGone: Boolean = runCatching { Cipher.getInstance("AES/GCM/NoPadding") }.isFailure
            val pbkdf2Gone: Boolean = runCatching { SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256") }.isFailure
            Assume.assumeTrue("another provider still serves AES-GCM or PBKDF2 here", gcmGone && pbkdf2Gone)
            return block()
        } finally {
            Security.insertProviderAt(sunJce, position)
        }
    }

    /** The call's outcome, with a throwable caught so the test can say WHAT escaped. */
    private suspend fun attempt(call: suspend () -> StorageResult<*>): Result<StorageResult<*>> = runCatching { call() }

    private fun typedFailure(label: String, outcome: Result<StorageResult<*>>): StorageError {
        assertTrue(outcome.isSuccess, "$label: an exception escaped the store call instead of a typed failure: ${outcome.exceptionOrNull()}")
        return assertIs<StorageResult.Failure>(outcome.getOrThrow(), "$label: ${outcome.getOrThrow()}").error
    }

    /**
     * A first put with no AES-GCM in the JDK: the OS-key store seals its key-check, the raw-key app store wraps its data key
     * (both under the locks, through `EnvelopeCipher.gcmEncrypt`, which does NOT wrap `GeneralSecurityException` the way
     * `gcmDecrypt` does). Red on: `NoSuchAlgorithmException` thrown out of `put`. Also: no store file and no keychain item is
     * left, and the same instance creates the store once the algorithm is back.
     */
    @Test
    fun `a first put without AES-GCM answers a typed failure and leaves nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val raw = JvmAppKeyEncryptedKeyValueStorage("crypto", rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 1 } }, Dispatchers.IO, CrossProcessLock())
                val stores: List<Triple<String, EncryptedKeyValueStorage, File>> = listOf(
                    Triple("OS-key", rig.store(), rig.dataFile()),
                    Triple("raw app-key", raw, File(rig.directory, "${appKeyStoreId("crypto")}.bin")),
                )
                // Every store is tried before anything is asserted, so the report names each one that throws.
                val escaped: List<String> = stores.mapNotNull { (label: String, store: EncryptedKeyValueStorage, _) ->
                    withoutSunJce { runBlocking { attempt { store.put("k", "v") } } }.exceptionOrNull()?.let { "$label: $it" }
                }
                assertEquals(emptyList(), escaped, "an exception escaped a store call instead of a typed failure")
                for ((label: String, store: EncryptedKeyValueStorage, data: File) in stores) {
                    typedFailure(label, withoutSunJce { runBlocking { attempt { store.put("k", "v") } } })
                    assertFalse(data.exists(), "$label: a store file was left")
                    assertEquals(emptyMap(), rig.fake.macItems.toMap(), "$label: a keychain item was left")
                    assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "$label: the same instance works once AES-GCM is back")
                    rig.fake.macItems.clear()
                }
            }
        }
    }

    /**
     * A passphrase store with no PBKDF2 in the JDK: the key-encryption key is derived OFF the lock (`AppKeyStoreEngine.derive`,
     * which catches only `IllegalArgumentException`), so `NoSuchAlgorithmException` from `SecretKeyFactory.getInstance` is
     * outside every mapping the guard has. Red on: the exception thrown out of `put` / `get`. A provider failure is not a wrong
     * passphrase, so the answer must not be `AccessDenied` either.
     */
    @Test
    fun `a passphrase store without PBKDF2 answers a typed failure that does not call the passphrase wrong`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val key = AppEncryptionKey.Passphrase(provider = { "correct horse battery".toCharArray() })
                val store = JvmAppKeyEncryptedKeyValueStorage("pbkdf2", rig.directory, key, Dispatchers.IO, CrossProcessLock())
                val fresh: StorageError = typedFailure("first put", withoutSunJce { runBlocking { attempt { store.put("k", "v") } } })
                assertFalse(fresh is StorageError.AccessDenied, "first put: a missing algorithm reported as a wrong passphrase: $fresh")
                assertFalse(File(rig.directory, "${appKeyStoreId("pbkdf2")}.bin").exists(), "first put: a store file was left")

                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: works with the algorithm")
                val other = JvmAppKeyEncryptedKeyValueStorage("pbkdf2", rig.directory, key, Dispatchers.IO, CrossProcessLock())
                val cold: StorageError = typedFailure("cold get", withoutSunJce { runBlocking { attempt { other.get("k") } } })
                assertFalse(cold is StorageError.AccessDenied, "cold get: a missing algorithm reported as a wrong passphrase: $cold")
                assertEquals(StorageResult.Success("v"), other.get("k"), "the same instance reads once PBKDF2 is back")
            }
        }
    }

    /**
     * Control (expected green): AES-GCM decryption is wrapped (`gcmDecrypt` turns a provider failure into
     * `IllegalStateException`, mapped to `OperationFailed`). A warm and a cold OS-key store without AES-GCM answer
     * `OperationFailed`, never "key lost": no quarantine, the file byte-identical, no keychain change.
     */
    @Test
    fun `an existing store without AES-GCM answers OperationFailed and is never read as key lost`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                assertEquals(StorageResult.Success(Unit), rig.store().put("a", "1"), "control")
                val before: ByteArray = rig.dataFile().readBytes()
                val items: Map<Pair<String, String>, List<Byte>> = rig.fake.macItems.mapValues { it.value.toList() }
                for ((label: String, store: EncryptedKeyValueStorage) in listOf("warm" to rig.store(), "cold" to rig.store(cache = OsKeyCache()))) {
                    assertIs<StorageError.OperationFailed>(typedFailure("$label get", withoutSunJce { runBlocking { attempt { store.get("a") } } }), label)
                    assertIs<StorageError.OperationFailed>(typedFailure("$label put", withoutSunJce { runBlocking { attempt { store.put("b", "2") } } }), label)
                    assertContentEquals(before, rig.dataFile().readBytes(), "$label: the user's file changed")
                    assertEquals(emptyList(), rig.fileNames().filter { ".undecryptable-" in it }, "$label: moved to quarantine")
                    assertEquals(items, rig.fake.macItems.mapValues { it.value.toList() }, "$label: the keychain changed")
                }
                assertEquals(StorageResult.Success("1"), rig.store(cache = OsKeyCache()).get("a"), "the data is intact")
            }
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
