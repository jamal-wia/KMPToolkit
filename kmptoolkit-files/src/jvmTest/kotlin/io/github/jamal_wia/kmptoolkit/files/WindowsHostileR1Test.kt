package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Round 1 of the breaker for unit G: hostile Windows spellings through the public API. Does nothing on a POSIX host. */
class WindowsHostileR1Test {
    private val windows: Boolean = File.separatorChar == '\\'
    private lateinit var root: String
    private val fs: FileAccess = createFileAccess()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kmptoolkit-winr1").toRealPath().toString()
    }

    @AfterTest
    fun tearDown() {
        File(root).deleteRecursively()
    }

    private fun p(name: String): String = root + "\\" + name

    @Test
    fun `alternate data streams and device names are InvalidPath for every function`() {
        if (!windows) return
        val bad: List<String> = listOf("f.txt:stream", "f.txt::\$DATA", "CON", "con.txt", "COM1", "lpt9", "COM¹", "aux .log", "nul ", "name.", "name ")
        for (name: String in bad) {
            for (path: String in listOf(p(name), p("d\\$name\\x"))) {
                assertTrue(fs.writeAtomically(path, ByteArray(1)).errorOrNull() is FileError.InvalidPath, "write $path")
                assertTrue(fs.createDirectories(path).errorOrNull() is FileError.InvalidPath, "mkdir $path")
                assertTrue(fs.readBytes(path, 10).errorOrNull() is FileError.InvalidPath, "read $path")
                assertTrue(fs.delete(path).errorOrNull() is FileError.InvalidPath, "delete $path")
                assertTrue(fs.list(path).errorOrNull() is FileError.InvalidPath, "list $path")
                assertNull(fs.metadata(path), "metadata $path")
            }
        }
    }

    @Test
    fun `drive relative and rooted without drive paths are InvalidPath`() {
        if (!windows) return
        for (path: String in listOf("C:rel", "\\rooted", "rel\\x", "\\\\server")) {
            assertTrue(fs.writeAtomically(path, ByteArray(1)).errorOrNull() is FileError.InvalidPath, path)
        }
    }

    @Test
    fun `a path far over 260 characters works plain and with the extended length prefix`() {
        if (!windows) return
        val deep: String = (0 until 40).joinToString("\\") { "directory$it" }
        val dir: String = p(deep)
        assertTrue(dir.length > 400)
        assertTrue(fs.createDirectories(dir).isSuccess)
        assertTrue(fs.writeAtomically("$dir\\f", "x".encodeToByteArray()).isSuccess)
        assertEquals("x", fs.readBytes("\\\\?\\$dir\\f", 10).getOrNull()?.decodeToString())
        assertEquals(listOf("f"), fs.list("\\\\?\\$dir").getOrNull())
        assertEquals(1L, fs.treeSize(p("directory0")).getOrNull())
        assertTrue(fs.deleteRecursively(p("directory0")).isSuccess)
        assertNull(fs.metadata(p("directory0")))
    }

    @Test
    fun `case insensitive spellings are one file and a case only move renames the spelling`() {
        if (!windows) return
        assertTrue(fs.writeAtomically(p("File.TXT"), "a".encodeToByteArray()).isSuccess)
        assertEquals("a", fs.readBytes(p("file.txt"), 10).getOrNull()?.decodeToString())
        assertTrue(fs.move(p("File.TXT"), p("file.txt")).isSuccess)
        assertEquals(listOf("file.txt"), fs.list(root).getOrNull())
        assertTrue(fs.move(p("file.txt"), p("FILE.txt"), MoveOptions(existing = ExistingFile.Fail)).errorOrNull() is FileError.AlreadyExists)
    }
}
