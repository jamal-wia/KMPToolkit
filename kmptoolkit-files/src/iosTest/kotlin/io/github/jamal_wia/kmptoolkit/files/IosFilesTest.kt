@file:OptIn(ExperimentalForeignApi::class)

package io.github.jamal_wia.kmptoolkit.files

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileProtectionCompleteUntilFirstUserAuthentication
import platform.Foundation.NSFileProtectionKey
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import platform.Foundation.NSUUID
import platform.Foundation.NSUserDomainMask
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** R-12 and R-13 on the iOS simulator: directories, the backup flag, the protection class. */
class IosFilesTest {
    private val created: MutableList<String> = mutableListOf()

    @AfterTest
    fun cleanUp() {
        for (path: String in created) NSFileManager.defaultManager.removeItemAtPath(path, null)
    }

    private fun unique(): String = "kmptoolkit-files-test-" + NSUUID().UUIDString

    private fun searchPath(directory: ULong): String =
        NSSearchPathForDirectoriesInDomains(directory, NSUserDomainMask, true).first() as String

    private fun excluded(path: String): Boolean {
        val values: Map<Any?, *> = NSURL.fileURLWithPath(path).resourceValuesForKeys(listOf(NSURLIsExcludedFromBackupKey), null)
            ?: return false
        return (values[NSURLIsExcludedFromBackupKey] as? NSNumber)?.boolValue ?: false
    }

    @Test
    fun `roots match the table`() {
        val d: AppDirectories = createAppDirectories()
        assertEquals(searchPath(NSApplicationSupportDirectory), d.rootPath(AppRoot.Files))
        assertEquals(searchPath(NSApplicationSupportDirectory), d.rootPath(AppRoot.NoBackup))
        assertEquals(searchPath(NSCachesDirectory), d.rootPath(AppRoot.Cache))
        assertEquals(NSTemporaryDirectory().trimEnd('/'), d.rootPath(AppRoot.Temporary))
    }

    @Test
    fun `Excluded marks the directory and the flag survives an atomic write inside it`() {
        val d: AppDirectories = createAppDirectories()
        val name: String = unique()
        val path: String = (d.directory(AppRoot.Files, name, Backup.Excluded) as FileResult.Success).value
        created += path
        assertTrue(excluded(path), "the directory is excluded")
        val files: FileAccess = createFileAccess()
        assertTrue(files.writeAtomically(Paths.join(path, "record"), ByteArray(4), WriteOptions(durability = Durability.SyncedWithDirectory)).isSuccess)
        assertTrue(files.writeAtomically(Paths.join(path, "record"), ByteArray(5)).isSuccess)
        assertTrue(excluded(path), "the directory is still excluded after files were replaced inside")
    }

    @Test
    fun `Included leaves the directory in the backup and NoBackup excludes it`() {
        val d: AppDirectories = createAppDirectories()
        val included: String = (d.directory(AppRoot.Files, unique()) as FileResult.Success).value
        val noBackup: String = (d.directory(AppRoot.NoBackup, unique()) as FileResult.Success).value
        created += included
        created += noBackup
        assertTrue(!excluded(included))
        assertTrue(excluded(noBackup))
    }

    @Test
    fun `the exclusion is re-applied on every call`() {
        val d: AppDirectories = createAppDirectories()
        val name: String = unique()
        val path: String = (d.directory(AppRoot.Files, name, Backup.Excluded) as FileResult.Success).value
        created += path
        NSURL.fileURLWithPath(path).setResourceValue(false, forKey = NSURLIsExcludedFromBackupKey, error = null)
        assertTrue(!excluded(path))
        assertTrue(d.directory(AppRoot.Files, name, Backup.Excluded).isSuccess)
        assertTrue(excluded(path))
    }

    @Test
    fun `excludeFromBackup marks an existing entry and reports NotFound for an absent one`() {
        val d: AppDirectories = createAppDirectories()
        val path: String = (d.directory(AppRoot.Files, unique()) as FileResult.Success).value
        created += path
        assertTrue(d.excludeFromBackup(path).isSuccess)
        assertTrue(excluded(path))
        assertTrue(d.excludeFromBackup(Paths.join(path, "absent")).errorOrNull() is FileError.NotFound)
    }

    @Test
    fun `directory is idempotent from sixteen threads and rejects invalid names`() = runTest(timeout = 120.seconds) {
        val d: AppDirectories = createAppDirectories()
        val name: String = unique()
        val results: List<FileResult<String>> = withContext(Dispatchers.Default) {
            withTimeout(100_000) { List(16) { async { d.directory(AppRoot.Cache, name, Backup.Excluded) } }.awaitAll() }
        }
        created += results.first().getOrNull() ?: ""
        assertEquals(1, results.map { it.getOrNull() }.toSet().size)
        assertTrue(results.all { it.isSuccess })
        for (invalid: String in listOf("", "..", "a/b")) assertFailsWith<IllegalArgumentException> { d.directory(AppRoot.Files, invalid) }
    }

    @Test
    fun `a requested protection class is reported by the file where the platform reports it`() {
        val files: FileAccess = createFileAccess()
        val dir: String = NSTemporaryDirectory().trimEnd('/') + "/" + unique()
        created += dir
        assertTrue(files.createDirectories(dir).isSuccess)
        val path: String = Paths.join(dir, "protected")
        val options: WriteOptions = WriteOptions(protection = FileProtection.CompleteUntilFirstUserAuthentication)
        assertTrue(files.writeAtomically(path, ByteArray(3), options).isSuccess)
        val attributes: Map<Any?, *>? = NSFileManager.defaultManager.attributesOfItemAtPath(path, null)
        val reported: Any? = attributes?.get(NSFileProtectionKey)
        // The simulator may report nothing at all; when it reports a class, it must be the requested one.
        if (reported != null) assertEquals<Any?>(NSFileProtectionCompleteUntilFirstUserAuthentication, reported)
    }
}
