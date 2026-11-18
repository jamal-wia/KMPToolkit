package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 6 (phase B breaker round 3, O8): a first put whose entry cannot fit even the smallest file of its
 * kind is refused with `InvalidValue` BEFORE any key material is asked for (a Linux preflight, a provider call, PBKDF2), so
 * the answer does not depend on what the key store would have said. The exact header is still measured afterwards (a DPAPI
 * header has a length of its own); `EncryptedStoreSizeBreakerR4B3Test` covers that path.
 */
class EncryptedStoreSizeRound6Test {

    private val directory: File = Files.createTempDirectory("kmptoolkit-size6").toFile()
    private val name = "size6"

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private class CountingKey {
        val calls: AtomicInteger = AtomicInteger()
        val raw: AppEncryptionKey.Raw = AppEncryptionKey.Raw {
            calls.incrementAndGet()
            ByteArray(32) { it.toByte() }
        }
        val passphrase: AppEncryptionKey.Passphrase = AppEncryptionKey.Passphrase(
            provider = {
                calls.incrementAndGet()
                "a passphrase for the size test".toCharArray()
            },
        )
    }

    private fun appStore(key: AppEncryptionKey): JvmAppKeyEncryptedKeyValueStorage =
        JvmAppKeyEncryptedKeyValueStorage(name, directory, key, Dispatchers.IO, CrossProcessLock(), maxStoreBytes = LIMIT)

    private fun failure(result: StorageResult<*>): StorageError = assertIs<StorageResult.Failure>(result, "result: $result").error

    @Test
    fun `a raw-key first put that cannot fit the smallest file never asks the provider`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val key = CountingKey()
            val store: JvmAppKeyEncryptedKeyValueStorage = appStore(key.raw)

            // Entry of 4 035 bytes: fits alone (limit 4 096), not with the 100-byte header of a raw-key file.
            val refused: StorageError = failure(store.put("k", "v".repeat(4_000)))

            assertEquals("k", assertIs<StorageError.InvalidValue>(refused).key)
            assertEquals(0, key.calls.get(), "the provider was asked for a key for a value that could never be stored")
            assertTrue(!File(directory, "${appKeyStoreId(name)}.bin").exists(), "a file was created")
            assertEquals(StorageResult.Success(Unit), store.put("k", "fits"), "control: the same store takes a value that fits")
            assertEquals(1, key.calls.get(), "control: and then asks the provider once")
        }
    }

    @Test
    fun `a passphrase first put that cannot fit the smallest file never derives a key`() = runBlocking {
        withTimeout(HANG_BOUND) {
            val key = CountingKey()
            val store: JvmAppKeyEncryptedKeyValueStorage = appStore(key.passphrase)

            // 4 035 + the 120-byte header of a passphrase file; no 600 000-iteration derivation may run for it.
            val refused: StorageError = failure(store.put("k", "v".repeat(4_000)))

            assertIs<StorageError.InvalidValue>(refused)
            assertEquals(0, key.calls.get(), "the passphrase provider was asked for a value that could never be stored")
        }
    }

    @Test
    fun `an OS-key first put that cannot fit answers InvalidValue even where the key store would have refused it`() = runBlocking {
        withTimeout(HANG_BOUND) {
            // Linux without a session bus: a first put that fits is refused by the preflight; one that cannot fit must say InvalidValue.
            SecureTestRig(os = DesktopOs.LINUX).use { rig: SecureTestRig ->
                rig.hasSessionBus = false
                val store = rig.store(maxStoreBytes = LIMIT)

                val tooBig: StorageError = failure(store.put("k", "v".repeat(4_020)))
                val control: StorageError = failure(store.put("k", "fits"))

                assertIs<StorageError.InvalidValue>(tooBig, "the key store's own refusal answered for a value that could never be stored")
                assertTrue(control !is StorageError.InvalidValue, "control: without a session the same store refuses a value that fits with something else: $control")
            }
        }
    }

    @Test
    fun `the smallest file of each kind is exactly what an empty file of that kind encodes to`() {
        val fingerprint = ByteArray(SecureFileFormat.FINGERPRINT_BYTES)
        val wrapped = ByteArray(SecureFileFormat.WRAPPED_KEY_BYTES)
        val keyCheck = ByteArray(SecureFileFormat.KEY_CHECK_BYTES)
        val salt = ByteArray(SecureFileFormat.SALT_BYTES)
        val headers: List<HeaderBody> = listOf(
            HeaderBody.OsItem(fingerprint, SecureFileFormat.OS_TAG_MACOS),
            HeaderBody.Dpapi(ByteArray(1)),
            HeaderBody.Passphrase(salt, 600_000, wrapped),
            HeaderBody.RawKey(wrapped),
        )
        for (header: HeaderBody in headers) {
            val encoded: Int = SecureFileFormat.encode(header, keyCheck, emptyList()).size
            assertEquals(encoded.toLong(), SecureFileFormat.minimumFileBytes(header.kind), "kind ${header.kind}: the minimum disagrees with the encoder")
            assertEquals(encoded.toLong(), SecureFileFormat.encodedSize(header, emptyList()), "kind ${header.kind}: encodedSize disagrees")
        }
        assertEquals(StoreKind.entries.toSet(), headers.map { it.kind }.toSet(), "control: every kind was checked")
    }

    private companion object {
        const val LIMIT: Long = 4_096
        val HANG_BOUND = 60.seconds
    }
}
