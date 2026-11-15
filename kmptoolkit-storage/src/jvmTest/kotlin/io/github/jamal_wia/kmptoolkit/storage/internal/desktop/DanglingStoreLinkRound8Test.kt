package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.storage.AppEncryptionKey
import io.github.jamal_wia.kmptoolkit.storage.DesktopSecureStorageOptions
import io.github.jamal_wia.kmptoolkit.storage.EncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.JvmAppKeyEncryptedKeyValueStorage
import io.github.jamal_wia.kmptoolkit.storage.StorageError
import io.github.jamal_wia.kmptoolkit.storage.StorageOperation
import io.github.jamal_wia.kmptoolkit.storage.StorageResult
import io.github.jamal_wia.kmptoolkit.storage.appKeyStoreId
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.SecureRandom as JcaSecureRandom
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 8 (breaker finding B5-m1), beyond `DesktopSecureStoreBreakerR6B5Test`: the store file of an
 * encrypted store is a symbolic link whose target does not exist (a file moved or deleted behind a link the user made).
 *
 * A read of it is "no file" (`Success(null)`), but nothing can be created at its name, which is a permanent fault of the path:
 * a `put` and an `ensureKey` answer `OperationFailed` at once, with `IOException` as the cause, BEFORE any key material is made,
 * any native call is made, or the app's key provider is asked. Before this round they went round four times (a key drawn and, on
 * Linux without UI or on Windows, a native call each time) and answered `Unavailable`, the answer that means "retrying later can
 * succeed". The link and the missing target are never touched; the same store works once the link is gone.
 */
class DanglingStoreLinkRound8Test {

    /** Counts draws; the parent generator is never used (`Random(SEED)`). */
    private class CountingRandom : JcaSecureRandom() {
        private val source: Random = Random(SEED)

        @Volatile
        var draws: Int = 0

        @Synchronized
        override fun nextBytes(bytes: ByteArray) {
            draws += 1
            source.nextBytes(bytes)
        }
    }

    private fun operationFailedWithIo(label: String, result: StorageResult<*>, operation: StorageOperation) {
        val error: StorageError = assertIs<StorageResult.Failure>(result, "$label: $result").error
        val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: a dangling link never heals by retrying, so it is not a retry-later answer: $error")
        assertEquals(operation, failed.operation, label)
        assertIs<IOException>(failed.cause, "$label: the cause names the path fault")
    }

    @Test
    fun `an OS-key store over a dangling link refuses put and ensureKey before any key or native call and works once the link is gone`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    SecureTestRig(os).use { rig: SecureTestRig ->
                        val target = File(rig.directory, "moved-away.bin")
                        Files.createSymbolicLink(rig.dataFile().toPath(), target.toPath())
                        val random = CountingRandom()
                        val store: EncryptedKeyValueStorage = rig.store(options = DesktopSecureStorageOptions(allowUserInteraction = false), cipher = EnvelopeCipher(random))

                        operationFailedWithIo("$os put", store.put("a", "1"), StorageOperation.PUT)
                        operationFailedWithIo("$os ensureKey", store.ensureKey(), StorageOperation.ENSURE_KEY)

                        assertEquals(0, random.draws, "$os: no key material was made for a create that cannot succeed (seed=$SEED)")
                        assertEquals(emptyList(), rig.fake.callNames, "$os: no native call was made")
                        assertFalse(target.exists(), "$os: the target of the link was created")
                        assertEquals(target.toPath(), Files.readSymbolicLink(rig.dataFile().toPath()), "$os: the link was replaced")
                        // The pinned reads of a store with no file: nothing to read, remove or clear, and nothing is changed.
                        assertEquals(StorageResult.Success(null), store.get("a"), "$os: get")
                        assertEquals(StorageResult.Success(Unit), store.remove("a"), "$os: remove")
                        assertEquals(StorageResult.Success(Unit), store.clear(), "$os: clear")
                        assertTrue(Files.isSymbolicLink(rig.dataFile().toPath()), "$os: a read or clear removed the link")

                        // Control: the fault was the link. With it gone the same instance creates the store.
                        Files.delete(rig.dataFile().toPath())
                        assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "$os: control, the same store works without the link")
                        assertEquals(StorageResult.Success("1"), store.get("a"), "$os: control, the value reads back")
                    }
                }
            }
        }
    }

    @Test
    fun `an app-key store over a dangling link refuses put and ensureKey without asking the key provider`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                SecureTestRig(DesktopOs.MACOS).use { rig: SecureTestRig ->
                    val asked = AtomicInteger()
                    val name = "r8-dangling"
                    val file = File(rig.directory, "${appKeyStoreId(name)}.bin")
                    val target = File(rig.directory, "moved-away.bin")
                    Files.createSymbolicLink(file.toPath(), target.toPath())
                    val store = JvmAppKeyEncryptedKeyValueStorage(
                        name, rig.directory, AppEncryptionKey.Raw { asked.incrementAndGet(); ByteArray(32) { 6 } }, Dispatchers.IO, CrossProcessLock(),
                    )

                    operationFailedWithIo("put", store.put("a", "1"), StorageOperation.PUT)
                    operationFailedWithIo("ensureKey", store.ensureKey(), StorageOperation.ENSURE_KEY)

                    assertEquals(0, asked.get(), "the key provider was asked for a store that cannot be created")
                    assertFalse(target.exists(), "the target of the link was created")
                    assertEquals(target.toPath(), Files.readSymbolicLink(file.toPath()), "the link was replaced")
                    assertEquals(StorageResult.Success(null), store.get("a"), "get of a store with no file")
                    Files.delete(file.toPath())
                    assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "control: the same store works without the link")
                    assertEquals(1, asked.get(), "control: the provider is asked once a create can happen")
                }
            }
        }
    }

    private companion object {
        const val SEED: Int = 818
        val HANG_BOUND = 120.seconds
    }
}
