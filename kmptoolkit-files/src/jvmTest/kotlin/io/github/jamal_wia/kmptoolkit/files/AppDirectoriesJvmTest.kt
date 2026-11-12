package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/** R-12 on the desktop JVM: roots follow the given directories, Excluded is a no-op, names are validated. */
class AppDirectoriesJvmTest {
    private lateinit var base: File

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("kmptoolkit-appdirs").toFile()
    }

    @AfterTest
    fun tearDown() {
        base.deleteRecursively()
    }

    private fun dirs(): AppDirectories =
        createAppDirectories(
            filesDirectory = File(base, "files").path,
            cacheDirectory = File(base, "cache").path,
            temporaryDirectory = File(base, "tmp").path,
        )

    @Test
    fun `roots match the table`() {
        val d: AppDirectories = dirs()
        assertEquals(File(base, "files").path, d.rootPath(AppRoot.Files))
        assertEquals(File(base, "files").path, d.rootPath(AppRoot.NoBackup))
        assertEquals(File(base, "cache").path, d.rootPath(AppRoot.Cache))
        assertEquals(File(base, "tmp").path, d.rootPath(AppRoot.Temporary))
        assertTrue(!File(base, "files").exists(), "rootPath does not create anything")
    }

    @Test
    fun `the defaults are the files directory for the cache and the system temp directory`() {
        val d: AppDirectories = createAppDirectories(File(base, "only").path)
        assertEquals(File(base, "only").path, d.rootPath(AppRoot.Cache))
        assertEquals(System.getProperty("java.io.tmpdir"), d.rootPath(AppRoot.Temporary))
    }

    @Test
    fun `directory creates the root and the directory and returns its path`() {
        val d: AppDirectories = dirs()
        val path: String = (d.directory(AppRoot.Files, "data") as FileResult.Success).value
        assertEquals(File(File(base, "files"), "data").path, path)
        assertTrue(File(path).isDirectory)
        for (backup: Backup in Backup.entries) for (root: AppRoot in AppRoot.entries) {
            assertTrue(d.directory(root, "x-$backup-$root", backup).isSuccess, "$root $backup")
        }
        assertTrue(d.excludeFromBackup(path).isSuccess)
    }

    @Test
    fun `directory is idempotent from sixteen threads`() = runTest(timeout = 120.seconds) {
        val d: AppDirectories = dirs()
        val results: List<FileResult<String>> = withContext(Dispatchers.Default) {
            withTimeout(100_000) { List(16) { async { d.directory(AppRoot.Cache, "shared") } }.awaitAll() }
        }
        assertEquals(1, results.map { it.getOrNull() }.toSet().size)
        assertTrue(results.all { it.isSuccess })
    }

    @Test
    fun `an invalid name throws and creates nothing`() {
        val d: AppDirectories = dirs()
        for (name: String in listOf("", ".", "..", "a/b", "a\u0000b")) {
            assertFailsWith<IllegalArgumentException>("'$name'") { d.directory(AppRoot.Files, name) }
        }
        assertTrue(!File(base, "files").exists())
    }

    @Test
    fun `a relative directory is rejected`() {
        assertFailsWith<IllegalArgumentException> { createAppDirectories("relative") }
        assertFailsWith<IllegalArgumentException> { createAppDirectories(File(base, "f").path, cacheDirectory = "rel") }
        assertFailsWith<IllegalArgumentException> { createAppDirectories(File(base, "f").path, temporaryDirectory = "") }
    }
}
