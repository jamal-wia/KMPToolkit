package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Round 4 of the breaker for unit G: `AppDirectories.directory(name)` of the desktop JVM against hostile names (containment, entry point 2). */
class AppDirectoriesContainmentR4Test {
    private lateinit var top: File
    private lateinit var outside: File

    @BeforeTest
    fun setUp() {
        top = Files.createTempDirectory("kmptoolkit-appdirs-r4").toRealPath().toFile()
        outside = File(top, "outside").also { it.mkdirs() }
        File(outside, "sentinel").writeText("OUT")
    }

    @AfterTest
    fun tearDown() {
        top.deleteRecursively()
    }

    @Test
    fun `AppDirectories directory refuses every name that is not plain and creates only inside its root`() {
        val filesRoot: File = File(top, "files")
        val directories: AppDirectories = createAppDirectories(filesRoot.path, File(top, "cache").path, File(top, "tmp").path)
        val names: List<String> = listOf(
            "..", ".", "", "a/b", "../x", "/abs", "a/../..", "x\u0000y", " ", "a\\b", "...", "é", "é", "-rf", "~", "\$HOME", "*", "\n",
            "x".repeat(255), "x".repeat(256), "\uD83D", "😀", "CON", "name.", "a:b", "a/", "/", "./a", "a/.",
        )
        val before: List<String> = top.list()!!.sorted()
        for (name: String in names) {
            val expectPlain: Boolean = FileNames.isPlainName(name)
            if (!expectPlain) {
                assertFailsWith<IllegalArgumentException>("name '${name.take(20)}'") { directories.directory(AppRoot.Files, name) }
                continue
            }
            val made: FileResult<String> = directories.directory(AppRoot.Files, name)
            val path: String = assertNotNull(made.getOrNull(), "name '${name.take(20)}' -> $made")
            assertEquals(filesRoot.path, File(path).parent, "name '${name.take(20)}' created outside its root: $path")
            assertTrue(File(path).isDirectory, "name '${name.take(20)}'")
            // Idempotent: the same name again is the same path.
            assertEquals(path, directories.directory(AppRoot.Files, name).getOrNull())
        }
        val after: List<String> = top.list()!!.sorted()
        assertEquals((before + "files").sorted(), after, "an invalid name left an entry beside the root")
        assertEquals(listOf("sentinel"), outside.list()!!.sorted(), "the outside directory gained an entry")
        assertEquals("OUT", File(outside, "sentinel").readText())
    }
}
