@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import android.content.Context
import android.system.Os
import androidx.test.core.app.ApplicationProvider
import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.LegacyAndroidFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** The full real-file contract through the public Android factory, on the default SDK of the host tests. */
@RunWith(RobolectricTestRunner::class)
class AndroidDefaultSdkFilesTest : RealFileAccessTests() {
    override fun files(): FileAccess = createFileAccess()
}

/** The same contract through java.nio on the first SDK that has it. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class AndroidSdk26FilesTest : RealFileAccessTests() {
    override fun files(): FileAccess = createFileAccess()
}

/** The factory's ops selection, and the Android app directories. */
@RunWith(RobolectricTestRunner::class)
class AndroidFactoryTest {
    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `the ops follow the availability of java nio file, not the SDK level`() {
        assertTrue(selectAndroidOps(true) is NioFileSystemOps)
        assertTrue(selectAndroidOps(false) is LegacyAndroidFileSystemOps)
    }

    @Test
    fun `the factory accepts a custom temp prefix`() {
        val files: FileAccess = createFileAccess(FileAccessConfig("my-app-"))
        val root: File = java.nio.file.Files.createTempDirectory("kmptoolkit-files-android").toFile()
        try {
            assertTrue(files.writeAtomically(File(root, "f").path, ByteArray(2)).isSuccess)
            assertEquals(listOf("f"), root.list()?.toList())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `app directory roots are the context directories`() {
        val d: AppDirectories = createAppDirectories(context)
        assertEquals(context.filesDir.path, d.rootPath(AppRoot.Files))
        assertEquals(context.noBackupFilesDir.path, d.rootPath(AppRoot.NoBackup))
        assertEquals(context.cacheDir.path, d.rootPath(AppRoot.Cache))
        assertEquals(context.cacheDir.path, d.rootPath(AppRoot.Temporary))
    }

    @Test
    fun `Files with Excluded is Unsupported and creates nothing`() {
        val d: AppDirectories = createAppDirectories(context)
        val name: String = "excluded-files-" + System.nanoTime()
        val result: FileResult<String> = d.directory(AppRoot.Files, name, Backup.Excluded)
        assertTrue(result.errorOrNull() is FileError.Unsupported, "$result")
        assertTrue(!File(context.filesDir, name).exists())
        assertTrue(d.excludeFromBackup(context.filesDir.path).errorOrNull() is FileError.Unsupported)
    }

    @Test
    fun `NoBackup Cache and Temporary directories are created and Excluded is accepted there`() {
        val d: AppDirectories = createAppDirectories(context)
        val suffix: String = System.nanoTime().toString()
        for (root: AppRoot in listOf(AppRoot.NoBackup, AppRoot.Cache, AppRoot.Temporary)) {
            for (backup: Backup in Backup.entries) {
                val path: String = (d.directory(root, "dir-$root-$backup-$suffix", backup) as FileResult.Success).value
                assertTrue(File(path).isDirectory, "$root $backup")
            }
        }
        assertTrue((d.directory(AppRoot.Files, "included-$suffix") as FileResult.Success).value.isNotEmpty())
    }

    @Test
    fun `directory is idempotent from sixteen threads`() = runTest(timeout = 120.seconds) {
        val d: AppDirectories = createAppDirectories(context)
        val name: String = "shared-" + System.nanoTime()
        val results: List<FileResult<String>> = withContext(Dispatchers.Default) {
            withTimeout(100_000) { List(16) { async { d.directory(AppRoot.NoBackup, name) } }.awaitAll() }
        }
        assertEquals(1, results.map { it.getOrNull() }.toSet().size)
        assertTrue(results.all { it.isSuccess })
    }

    @Test
    fun `an invalid directory name throws`() {
        val d: AppDirectories = createAppDirectories(context)
        for (name: String in listOf("", "..", "a/b")) {
            kotlin.test.assertFailsWith<IllegalArgumentException> { d.directory(AppRoot.Files, name) }
        }
    }
}

/**
 * The real factory at Robolectric API 24: the host runtime has java.nio.file, so the NIO ops are selected and a
 * write, read and delete round trip works although `android.system.Os` is a stub there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class AndroidSdk24RoundTripTest {
    @Test
    fun `createFileAccess writes reads and deletes at API 24 on a host runtime`() {
        val files: FileAccess = createFileAccess()
        val root: File = java.nio.file.Files.createTempDirectory("kmptoolkit-files-api24").toFile()
        try {
            val path: String = File(root, "record").path
            assertTrue(files.writeAtomically(path, "v".encodeToByteArray(), WriteOptions(createParentDirectories = true)).isSuccess)
            assertEquals("v", files.readBytes(path, 16).getOrNull()?.decodeToString())
            assertTrue(files.delete(path).isSuccess)
            assertEquals(null, files.metadata(path))
        } finally {
            root.deleteRecursively()
        }
    }
}

/**
 * Canary for the API 24-25 legacy path. `android.system.Os` is a stub under Robolectric (spike of the T1 report): it
 * answers without touching the disk, so [LegacyAndroidFileSystemOps] cannot be exercised by any host test and its
 * contract is an owner item (device check on an API 24 or 25 device). The day Robolectric implements `Os`, this test
 * fails and the shared contract must be run through the legacy ops instead.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24])
class AndroidLegacyOsCanaryTest {
    @Test
    fun `Os lstat of a missing file does not fail under Robolectric so the legacy ops cannot be tested here`() {
        val missing: String = File(System.getProperty("java.io.tmpdir"), "kmptoolkit-files-missing-" + System.nanoTime()).path
        val answered: Boolean = try {
            Os.lstat(missing)
            true
        } catch (_: android.system.ErrnoException) {
            false
        }
        assertTrue(answered, "Os is real now: run the shared contract through LegacyAndroidFileSystemOps")
    }
}
