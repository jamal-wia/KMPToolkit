package io.github.jamal_wia.kmptoolkit.storage.internal.desktop

import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Group sto-desktop, round 9 (breaker finding B6-m1), beyond `DesktopSecureStoreBreakerR7B6Test`: the dangling-link check asks the
 * store's OWN configured name too. The store path is a link to an EXISTING file elsewhere (followed: the round's real path is the
 * target) and the target is deleted after the round resolved that real path and before it reads the file. At the moment the
 * round decides the store path is a link whose target does not exist, so a `put` and an `ensureKey` answer `OperationFailed`
 * (never create the target at the far end of the link, never replace the link), on every OS-key store and on the app-key store.
 * The breaker's test pins macOS and `put`; this pins Linux, Windows, `ensureKey` and the raw app-key store's `put`, and that the same
 * instance works once the link is gone. A target that is NOT deleted is untouched: the store still works through the link.
 */
class ConfiguredLinkRound9Test {

    /**
     * Runs [beforeRead] once, just before the [fireAtRead]-th read of the store file (after the path was resolved under the locks).
     * An app-key store asks the app for its key between the first read and the create, so its create round is the second one.
     */
    private class HookedFiles(private val delegate: FileAccess, private val storeName: String, private val fireAtRead: Int = 1) : FileAccess by delegate {
        @Volatile
        var beforeRead: (() -> Unit)? = null
        private var reads: Int = 0

        override fun readBytes(path: String, maxBytes: Long): FileResult<ByteArray> {
            if (File(path).name == storeName) {
                reads += 1
                if (reads == fireAtRead) {
                    beforeRead?.let { hook: () -> Unit ->
                        beforeRead = null
                        hook()
                    }
                }
            }
            return delegate.readBytes(path, maxBytes)
        }
    }

    private fun assertRefused(label: String, answer: StorageResult<*>, operation: StorageOperation) {
        val error: StorageError = assertIs<StorageResult.Failure>(answer, "$label: $answer").error
        val failed: StorageError.OperationFailed = assertIs<StorageError.OperationFailed>(error, "$label: $error")
        assertEquals(operation, failed.operation, label)
        assertIs<IOException>(failed.cause, "$label: the cause names the path fault")
    }

    private fun linkToExistingTarget(link: File, elsewhere: File, targetName: String): File {
        val target = File(elsewhere, targetName).apply { writeText("a file that is about to be deleted") }
        Files.createSymbolicLink(link.toPath(), target.toPath())
        return target
    }

    @Test
    fun `an OS-key store whose link target vanishes after the path was resolved refuses put and ensureKey on every OS`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                for (os: DesktopOs in listOf(DesktopOs.MACOS, DesktopOs.LINUX, DesktopOs.WINDOWS)) {
                    for (operation: StorageOperation in listOf(StorageOperation.PUT, StorageOperation.ENSURE_KEY)) {
                        SecureTestRig(os).use { rig: SecureTestRig ->
                            val label = "$os $operation"
                            val elsewhere: File = File(rig.directory, "elsewhere").apply { mkdirs() }
                            val target: File = linkToExistingTarget(rig.dataFile(), elsewhere, "target.bin")
                            val hooked = HookedFiles(createFileAccess(), target.name)
                            hooked.beforeRead = { Files.delete(target.toPath()) }
                            val store: EncryptedKeyValueStorage = rig.store(options = DesktopSecureStorageOptions(allowUserInteraction = false), files = hooked)
                            val answer: StorageResult<*> = if (operation == StorageOperation.PUT) store.put("a", "1") else store.ensureKey()
                            assertNull(hooked.beforeRead, "$label: control, the target was deleted inside the round")
                            assertRefused(label, answer, operation)
                            assertFalse(target.exists(), "$label: the store created its file at the far end of the link")
                            assertTrue(Files.isSymbolicLink(rig.dataFile().toPath()), "$label: the link was replaced")
                            assertEquals(0, rig.fake.macItems.size + rig.fake.secretItems.size, "$label: a key item was made")
                            Files.delete(rig.dataFile().toPath())
                            assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "$label: the same instance works once the link is gone")
                            assertEquals(StorageResult.Success("1"), store.get("a"), "$label: and reads back")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `an app-key store whose link target vanishes after the path was resolved refuses put`() {
        TestHost.assumePosixFileSemantics("symbolic links")
        runBlocking {
            withTimeout(HANG_BOUND) {
                SecureTestRig().use { rig: SecureTestRig ->
                    val label = "app-key PUT"
                    val file = File(rig.directory, "${appKeyStoreId("link")}.bin")
                    val elsewhere: File = File(rig.directory, "elsewhere").apply { mkdirs() }
                    val target: File = linkToExistingTarget(file, elsewhere, "target.bin")
                    // The app-key store reads the unusable target (round 1), asks the app for its key, and creates in round 2: the
                    // target vanishes just before that second read. (An ensureKey over an unusable file is Undecryptable, not a create.)
                    val hooked = HookedFiles(createFileAccess(), target.name, fireAtRead = 2)
                    hooked.beforeRead = { Files.delete(target.toPath()) }
                    val store: EncryptedKeyValueStorage = JvmAppKeyEncryptedKeyValueStorage(
                        "link", rig.directory, AppEncryptionKey.Raw { ByteArray(32) { 3 } }, Dispatchers.IO, CrossProcessLock(), files = hooked,
                    )
                    val answer: StorageResult<*> = store.put("a", "1")
                    assertNull(hooked.beforeRead, "$label: control, the target was deleted inside the round")
                    assertRefused(label, answer, StorageOperation.PUT)
                    assertFalse(target.exists(), "$label: the store created its file at the far end of the link")
                    assertTrue(Files.isSymbolicLink(file.toPath()), "$label: the link was replaced")
                    Files.delete(file.toPath())
                    assertEquals(StorageResult.Success(Unit), store.put("a", "1"), "$label: the same instance works once the link is gone")
                }
            }
        }
    }

    private companion object {
        val HANG_BOUND = 120.seconds
    }
}
