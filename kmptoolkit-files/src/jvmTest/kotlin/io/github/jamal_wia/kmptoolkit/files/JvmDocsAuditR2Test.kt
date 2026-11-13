package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 2 of the breaker for unit G, step 3 (docs-sentence audit): `AppDirectories` sentences of 04-api-reference.md
 * for the desktop JVM that AppDirectoriesJvmTest does not pin.
 */
class JvmDocsAuditR2Test {
    private lateinit var base: File

    @BeforeTest
    fun setUp() {
        base = Files.createTempDirectory("kmptoolkit-appdirs-r2").toFile()
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
    fun `rootPath does not create the root`() {
        val appDirectories: AppDirectories = dirs()
        for (root: AppRoot in AppRoot.entries) appDirectories.rootPath(root)
        assertEquals(emptyList(), base.list()?.toList())
    }

    @Test
    fun `Excluded is a no-op on every root and excludeFromBackup is Success even for an absent path`() {
        val appDirectories: AppDirectories = dirs()
        for (root: AppRoot in AppRoot.entries) {
            val made: FileResult<String> = appDirectories.directory(root, "excluded", Backup.Excluded)
            assertTrue(made is FileResult.Success, "$root: $made")
            assertTrue(File(made.value).isDirectory)
            assertEquals(File(appDirectories.rootPath(root), "excluded").path, File(made.value).path)
        }
        assertEquals(FileResult.Success(Unit), appDirectories.excludeFromBackup(File(base, "nothing-here").path))
        assertEquals(FileResult.Success(Unit), appDirectories.excludeFromBackup(File(base, "files").path))
        assertEquals(false, File(base, "nothing-here").exists())
    }

    @Test
    fun `directory over a regular file is NotADirectory and a plain name is the only accepted name`() {
        val appDirectories: AppDirectories = dirs()
        val root: String = appDirectories.rootPath(AppRoot.Files)
        assertTrue(appDirectories.directory(AppRoot.Files, "first") is FileResult.Success)
        assertTrue(File(root, "blocker").createNewFile())
        val blocked: FileResult<String> = appDirectories.directory(AppRoot.Files, "blocker")
        assertTrue(blocked is FileResult.Failure && blocked.error is FileError.NotADirectory, "was $blocked")
        for (bad: String in listOf("", ".", "..", "a/b", "nul\u0000", "x".repeat(300))) {
            var threw: Boolean = false
            try {
                appDirectories.directory(AppRoot.Files, bad)
            } catch (_: IllegalArgumentException) {
                threw = true
            }
            assertTrue(threw, "'${bad.take(10)}' must throw IllegalArgumentException")
        }
    }
}
