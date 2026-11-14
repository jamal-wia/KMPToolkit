package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.AppDirectories
import io.github.jamal_wia.kmptoolkit.files.AppRoot
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createAppDirectories
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import io.github.jamal_wia.kmptoolkit.files.isSuccess
import org.junit.Assume
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the fake file system is POSIX on every host; the test below is skipped where the host's paths are not

/** F-22: a directory handed out by [AppDirectories] is durable, so a first SyncedWithDirectory record survives power loss. */
class AppDirectoriesDurabilityTest {
    private val posixHost: Boolean = !System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

    @Test
    fun `F-22 a record written into an AppDirectories directory survives a power loss and a second lookup syncs nothing`() {
        Assume.assumeTrue("the fake's POSIX paths are not absolute on a Windows host", posixHost)
        val fake: FakeFileAccess = FakeFileAccess()
        val directories: AppDirectories = createAppDirectories("/files", cacheDirectory = "/files", temporaryDirectory = "/tmp", files = fake)
        val receipts: String = directories.directory(AppRoot.Files, "receipts").getOrNull() ?: error("directory failed")
        assertEquals("/files/receipts", receipts)
        val syncsBefore: Int = fake.operations.count { it.operation == FakeOperation.SyncDirectory }
        assertTrue(syncsBefore > 0, "creating the directory synced its new levels")
        assertTrue(fake.writeAtomically("$receipts/r1", "receipt".encodeToByteArray(), WriteOptions(durability = Durability.SyncedWithDirectory)).isSuccess)
        val again: String? = directories.directory(AppRoot.Files, "receipts").getOrNull()
        assertEquals(receipts, again)
        assertEquals(
            syncsBefore + 1,
            fake.operations.count { it.operation == FakeOperation.SyncDirectory },
            "only the write's own sync: the second directory() call syncs nothing",
        )
        fake.crashAfter(FakeOperation.Metadata)
        runCatching { fake.metadata("/") }
        val restarted: FakeFileAccess = fake.restartAfterCrash(CrashKind.PowerLoss, revertUnsyncedDirectoryEntries = true)
        assertEquals("receipt", restarted.readBytes("$receipts/r1", 1024).getOrNull()?.decodeToString())
    }
}
