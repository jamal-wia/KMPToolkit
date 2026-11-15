@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T10, engine half: what the engine asks of the ops for `ownerOnly`, on every platform. It asks for owner-only access
 * exactly for the entries it creates for the call (the temp file, the missing directories, the temp directory) and
 * never for an existing one; a call that does not ask for it never reaches the owner-only entry points. The permission
 * results themselves are in the platform tests.
 */
class OwnerOnlyEngineT10Test {
    private lateinit var root: String

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    /** Records every directory and file creation, with the owner-only flag the engine passed. */
    private class RecordingOps(private val real: FileSystemOps) : FileSystemOps by real {
        val directories: MutableList<Pair<String, Boolean>> = mutableListOf()
        val files: MutableList<Pair<String, Boolean>> = mutableListOf()

        override fun createDirectory(path: String) {
            directories += path to false
            real.createDirectory(path)
        }

        override fun createDirectory(path: String, ownerOnly: Boolean) {
            directories += path to ownerOnly
            real.createDirectory(path, ownerOnly)
        }

        override fun createNew(path: String, protection: FileProtection): WritableFile {
            files += path to false
            return real.createNew(path, protection)
        }

        override fun createNew(path: String, protection: FileProtection, ownerOnly: Boolean): WritableFile {
            files += path to ownerOnly
            return real.createNew(path, protection, ownerOnly)
        }
    }

    private fun p(logical: String): String = TestPaths.join(root, logical)

    @Test
    fun `WriteOptions default is not owner only`() {
        assertFalse(WriteOptions().ownerOnly)
        assertTrue(WriteOptions(ownerOnly = true).ownerOnly)
    }

    @Test
    fun `a write with ownerOnly creates its temp file owner only`() {
        val ops: RecordingOps = RecordingOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        assertTrue(files.writeAtomically(p("f"), byteArrayOf(1, 2, 3), WriteOptions(ownerOnly = true)).isSuccess)
        assertEquals(1, ops.files.size)
        assertTrue(ops.files.single().second, "the temp file of an ownerOnly write must be created owner-only")
        assertEquals(listOf<Byte>(1, 2, 3), files.readBytes(p("f"), 16).getOrNull()?.toList())
    }

    @Test
    fun `a write without ownerOnly never asks for owner only access`() {
        val ops: RecordingOps = RecordingOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        assertTrue(files.writeAtomically(p("d/f"), byteArrayOf(1), WriteOptions(createParentDirectories = true)).isSuccess)
        assertTrue(ops.files.none { it.second }, "files: ${ops.files}")
        assertTrue(ops.directories.none { it.second }, "directories: ${ops.directories}")
    }

    @Test
    fun `a write with ownerOnly and createParentDirectories creates the missing parents and the temp directory owner only`() {
        val ops: RecordingOps = RecordingOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        val options: WriteOptions = WriteOptions(
            createParentDirectories = true,
            tempDirectory = p("tmpdir"),
            ownerOnly = true,
        )
        assertTrue(files.writeAtomically(p("a/b/f"), byteArrayOf(9), options).isSuccess)
        assertEquals(setOf(p("a"), p("a/b"), p("tmpdir")), ops.directories.map { it.first }.toSet())
        assertTrue(ops.directories.all { it.second }, "every created directory must be owner-only: ${ops.directories}")
        assertTrue(ops.files.single().second)
    }

    @Test
    fun `createDirectories with ownerOnly asks for owner only access for the missing levels only`() {
        val ops: RecordingOps = RecordingOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        assertTrue(files.createDirectories(p("existing")).isSuccess)
        ops.directories.clear()
        assertTrue(files.createDirectories(p("existing/x/y"), ownerOnly = true).isSuccess)
        assertEquals(listOf(p("existing/x") to true, p("existing/x/y") to true), ops.directories)
    }

    @Test
    fun `createDirectories of an existing directory with ownerOnly creates nothing and succeeds`() {
        val ops: RecordingOps = RecordingOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        assertTrue(files.createDirectories(p("d")).isSuccess)
        ops.directories.clear()
        assertTrue(files.createDirectories(p("d"), ownerOnly = true).isSuccess)
        assertTrue(ops.directories.isEmpty(), "an existing directory is never touched: ${ops.directories}")
    }

    @Test
    fun `createDirectories without ownerOnly never asks for it`() {
        val ops: RecordingOps = RecordingOps(TestEnv.realOps())
        val files: FileAccess = fileAccessOver(ops)
        assertTrue(files.createDirectories(p("a/b")).isSuccess)
        assertTrue(ops.directories.none { it.second }, "directories: ${ops.directories}")
    }
}
