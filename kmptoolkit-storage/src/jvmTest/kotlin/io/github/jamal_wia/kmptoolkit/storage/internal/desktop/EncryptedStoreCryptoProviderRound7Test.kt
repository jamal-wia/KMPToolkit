package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.security.GeneralSecurityException
import java.security.Provider
import java.security.Security
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume

/**
 * Group sto-desktop, round 7 (breaker finding B4-m2), beyond the breaker's `EncryptedStoreCryptoProviderBreakerR5Test`
 * (first put and cold get): the JDK without AES-GCM or PBKDF2 (a FIPS-configured or restricted provider set) on the paths the
 * breaker did not reach.
 *
 * - `EnvelopeCipher.gcmEncrypt` and `Pbkdf2.derive` throw `IllegalStateException` with the `GeneralSecurityException` as the
 *   cause (the shape `gcmDecrypt` already had), which every store maps to `OperationFailed`;
 * - `changeKey` of an existing store of either app-key kind answers `OperationFailed(CHANGE_KEY)` (never `AccessDenied`: the
 *   key is not wrong), changes nothing on disk, and works once the algorithm is back;
 * - a first put of a passphrase store answers the operation it was, with the entry name.
 *
 * `SunJCE` is removed for the duration of each call and restored at its own position in `finally`; a test skips when another
 * provider still serves the algorithms, so the absence is real. Fake binding only.
 */
class EncryptedStoreCryptoProviderRound7Test {

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

    @Test
    fun `the primitives throw IllegalStateException with the provider failure as the cause`() {
        val key = ByteArray(32) { 3 }
        val encrypt: IllegalStateException = withoutSunJce {
            assertFailsWithIllegalState { EnvelopeCipher.gcmEncrypt(key, ByteArray(12), ByteArray(0), ByteArray(4)) }
        }
        assertIs<GeneralSecurityException>(encrypt.cause, "gcmEncrypt: the provider failure is the cause")
        val derive: IllegalStateException = withoutSunJce {
            assertFailsWithIllegalState { Pbkdf2.derive("pass".toCharArray(), ByteArray(16), Pbkdf2.ITERATIONS_FLOOR, 32) }
        }
        assertIs<GeneralSecurityException>(derive.cause, "Pbkdf2.derive: the provider failure is the cause")
        // Control: with the provider back both work and nothing is left wrong.
        assertEquals(20, EnvelopeCipher.gcmEncrypt(key, ByteArray(12), ByteArray(0), ByteArray(4)).size)
        assertEquals(32, Pbkdf2.derive("pass".toCharArray(), ByteArray(16), 1, 32).size)
    }

    private fun assertFailsWithIllegalState(block: () -> Unit): IllegalStateException {
        try {
            block()
        } catch (error: IllegalStateException) {
            return error
        }
        throw AssertionError("expected an IllegalStateException")
    }

    @Test
    fun `changeKey of an existing passphrase store without PBKDF2 answers OperationFailed and changes nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val old = AppEncryptionKey.Passphrase(provider = { "old horse battery".toCharArray() })
                val store = JvmAppKeyEncryptedKeyValueStorage("rekey-pass", rig.directory, old, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: the store exists")
                val data = File(rig.directory, "${appKeyStoreId("rekey-pass")}.bin")
                val before: ByteArray = data.readBytes()
                assertTrue(before.isNotEmpty(), "control: the store file exists")

                val answer: Result<StorageResult<Unit>> = withoutSunJce {
                    runBlocking { runCatching { store.changeKey(AppEncryptionKey.Passphrase(provider = { "new horse battery".toCharArray() })) } }
                }

                val failure: StorageError = assertIs<StorageResult.Failure>(answer.getOrThrow(), "an exception escaped changeKey: ${answer.exceptionOrNull()}").error
                val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(failure, "a missing PBKDF2 is not a wrong passphrase: $failure")
                assertEquals(StorageOperation.CHANGE_KEY, failed.operation)
                assertContentEquals(before, data.readBytes(), "the store file changed although the re-key failed")
                assertEquals(StorageResult.Success("v"), store.get("k"), "the instance is still bound to its old key")
                assertEquals(StorageResult.Success(Unit), store.changeKey(AppEncryptionKey.Passphrase(provider = { "new horse battery".toCharArray() })), "works once PBKDF2 is back")
            }
        }
    }

    @Test
    fun `changeKey of an existing raw-key store without AES-GCM answers OperationFailed and changes nothing`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val old = AppEncryptionKey.Raw { ByteArray(32) { 1 } }
                val store = JvmAppKeyEncryptedKeyValueStorage("rekey-raw", rig.directory, old, Dispatchers.IO, CrossProcessLock())
                assertEquals(StorageResult.Success(Unit), store.put("k", "v"), "control: the store exists")
                val data = File(rig.directory, "${appKeyStoreId("rekey-raw")}.bin")
                val before: ByteArray = data.readBytes()
                assertTrue(before.isNotEmpty(), "control: the store file exists")

                val answer: Result<StorageResult<Unit>> = withoutSunJce {
                    runBlocking { runCatching { store.changeKey(AppEncryptionKey.Raw { ByteArray(32) { 2 } }) } }
                }

                val failure: StorageError = assertIs<StorageResult.Failure>(answer.getOrThrow(), "an exception escaped changeKey: ${answer.exceptionOrNull()}").error
                assertEquals(StorageOperation.CHANGE_KEY, assertIs<StorageError.OperationFailed>(failure, "$failure").operation)
                assertContentEquals(before, data.readBytes(), "the store file changed although the re-key failed")
                assertEquals(StorageResult.Success("v"), store.get("k"), "the instance is still bound to its old key")
            }
        }
    }

    @Test
    fun `a first put of a passphrase store without PBKDF2 names the operation and the entry`() = runBlocking {
        withTimeout(HANG_BOUND) {
            SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                val store = JvmAppKeyEncryptedKeyValueStorage(
                    "first-pass",
                    rig.directory,
                    AppEncryptionKey.Passphrase(provider = { "correct horse".toCharArray() }),
                    Dispatchers.IO,
                    CrossProcessLock(),
                )

                val answer: Result<StorageResult<Unit>> = withoutSunJce { runBlocking { runCatching { store.put("entry-name", "v") } } }

                val failure: StorageError = assertIs<StorageResult.Failure>(answer.getOrThrow(), "an exception escaped put: ${answer.exceptionOrNull()}").error
                val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(failure, "$failure")
                assertEquals(StorageOperation.PUT, failed.operation)
                assertEquals("entry-name", failed.key)
                assertTrue(failed.cause is IllegalStateException, "the cause is the provider failure: ${failed.cause}")
            }
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
