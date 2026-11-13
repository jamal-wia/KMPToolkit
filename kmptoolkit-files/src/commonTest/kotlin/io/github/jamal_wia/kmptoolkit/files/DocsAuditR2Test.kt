@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the stub ops below have their own POSIX path syntax, never the host's

/**
 * Round 2 of the breaker for unit G, step 3 (docs-sentence audit): sentences of docs/kmptoolkit-files/04-api-reference.md
 * that no earlier test pinned (`metadata` "cannot be examined", `freeSpaceBytes` "null when no ancestor exists or the
 * platform cannot tell", `exists` "never throws", the `FileResult` helpers, a failing parent of `move`), each derived
 * from the sentence.
 */
class DocsAuditR2Test {

    /** Ops whose metadata and space answers are scripted; every other operation is a test failure. */
    private open class StubOps(
        val onMetadata: (String, Boolean) -> FileMetadata?,
        val onSpace: (String) -> Long? = { null },
    ) : FileSystemOps {
        override val windowsPaths: Boolean = false
        val spaceAskedAt: MutableList<String> = mutableListOf()
        override fun nowMillis(): Long = 0L
        override fun metadata(path: String, followLinks: Boolean): FileMetadata? = onMetadata(path, followLinks)
        override fun list(directory: String): List<String>? = throw AssertionError("unexpected list")
        override fun createDirectory(path: String): Unit = throw AssertionError("unexpected createDirectory")
        override fun createNew(path: String, protection: FileProtection): WritableFile = throw AssertionError("unexpected createNew")
        override fun openForRead(path: String): ReadableFile = throw AssertionError("unexpected openForRead")
        override fun rename(from: String, to: String): Unit = throw AssertionError("unexpected rename")
        override fun link(existing: String, newPath: String): Unit = throw AssertionError("unexpected link")
        override fun remove(path: String): Unit = throw AssertionError("unexpected remove")
        override fun syncDirectory(directory: String, full: Boolean): Unit = throw AssertionError("unexpected syncDirectory")
        override fun usableSpace(path: String): Long? {
            spaceAskedAt += path
            return onSpace(path)
        }
    }

    private val directory: FileMetadata = FileMetadata(FileKind.Directory, 0L, 0L)

    @Test
    fun `metadata and exists answer null and false for an entry that cannot be examined and never throw`() {
        val failures: List<() -> Nothing> = listOf(
            { throw FileOpException(FileError.AccessDenied("/d/f")) },
            { throw FileOpException(FileError.Io("/d/f")) },
            { throw IllegalStateException("surprise") },
            { throw UnsupportedOperationException("surprise") },
        )
        for (failure: () -> Nothing in failures) {
            val files: FileAccess = fileAccessOver(StubOps(onMetadata = { _: String, _: Boolean -> failure() }))
            assertNull(files.metadata("/d/f", false))
            assertNull(files.metadata("/d/f", true))
            assertFalse(files.exists("/d/f"))
        }
    }

    @Test
    fun `exists is false for a relative empty or NUL path without touching the file system`() {
        val files: FileAccess = fileAccessOver(StubOps(onMetadata = { path: String, _: Boolean -> throw AssertionError("reached $path") }))
        for (path: String in listOf("", "relative", "./x", "a/b", "/d/\u0000x", "\u0000")) {
            assertFalse(files.exists(path), "exists of '${path.replace('\u0000', '0')}'")
            assertNull(files.metadata(path, false))
            assertNull(files.freeSpaceBytes(path))
        }
    }

    @Test
    fun `freeSpaceBytes is measured at the nearest existing ancestor and asks nowhere else`() {
        val ops: StubOps = StubOps(
            onMetadata = { path: String, _: Boolean -> if (path == "/a" || path == "/") directory else null },
            onSpace = { 1234L },
        )
        val files: FileAccess = fileAccessOver(ops)
        assertEquals(1234L, files.freeSpaceBytes("/a/b/c/d"))
        assertEquals(listOf("/a"), ops.spaceAskedAt)
        assertEquals(1234L, files.freeSpaceBytes("/a"))
        assertEquals(listOf("/a", "/a"), ops.spaceAskedAt)
    }

    @Test
    fun `freeSpaceBytes is null when no ancestor exists when the platform cannot tell and when it fails`() {
        val nothingExists: StubOps = StubOps(onMetadata = { _: String, _: Boolean -> null }, onSpace = { 99L })
        assertNull(fileAccessOver(nothingExists).freeSpaceBytes("/a/b"))
        assertTrue(nothingExists.spaceAskedAt.isEmpty(), "asked ${nothingExists.spaceAskedAt}")
        val cannotTell: StubOps = StubOps(onMetadata = { _: String, _: Boolean -> directory }, onSpace = { null })
        assertNull(fileAccessOver(cannotTell).freeSpaceBytes("/a/b"))
        val failing: StubOps = StubOps(onMetadata = { _: String, _: Boolean -> directory }, onSpace = { throw IllegalStateException("no statfs") })
        assertNull(fileAccessOver(failing).freeSpaceBytes("/a/b"))
        val examinationFails: StubOps = StubOps(
            onMetadata = { path: String, _: Boolean -> if (path == "/a/b") throw FileOpException(FileError.AccessDenied(path)) else directory },
            onSpace = { 7L },
        )
        val walked: FileAccess = fileAccessOver(examinationFails)
        assertEquals(7L, walked.freeSpaceBytes("/a/b"), "an ancestor that cannot be examined is skipped like an absent one")
        assertEquals(listOf("/a"), examinationFails.spaceAskedAt)
    }

    @Test
    fun `the FileResult helpers return the value or the error and isSuccess tells them apart`() {
        val success: FileResult<Int> = FileResult.Success(5)
        val failure: FileResult<Int> = FileResult.Failure(FileError.Cancelled)
        assertEquals(5, success.getOrNull())
        assertNull(success.errorOrNull())
        assertTrue(success.isSuccess)
        assertNull(failure.getOrNull())
        assertEquals(FileError.Cancelled, failure.errorOrNull())
        assertFalse(failure.isSuccess)
        assertEquals(null, FileResult.Success<Int?>(null).getOrNull())
        assertTrue(FileResult.Success<Int?>(null).isSuccess)
        assertEquals(FileError.Cancelled.path, null)
    }

    @Test
    fun `CancellationCheck Never is never cancelled`() {
        repeat(3) { assertFalse(CancellationCheck.Never.isCancelled()) }
    }

    @Test
    fun `a move that creates its parent and fails names the level of the parent as createDirectories does`() {
        val root: String = TestEnv.newRoot()
        try {
            val files: FileAccess = fileAccessOver(TestEnv.realOps())
            val file: String = TestPaths.join(root, "f")
            assertTrue(files.writeAtomically(file, "x".encodeToByteArray()).isSuccess)
            val source: String = TestPaths.join(root, "source")
            assertTrue(files.writeAtomically(source, "s".encodeToByteArray()).isSuccess)
            val target: String = TestPaths.join(TestPaths.join(TestPaths.join(root, "f"), "sub"), "target")
            val parent: String = TestPaths.join(TestPaths.join(root, "f"), "sub")
            val moved: FileResult<Unit> = files.move(source, target, MoveOptions(createParentDirectories = true))
            val created: FileResult<Unit> = files.createDirectories(parent)
            assertTrue(moved is FileResult.Failure && created is FileResult.Failure, "moved=$moved created=$created")
            assertEquals(created.errorOrNull(), moved.errorOrNull())
            assertEquals(FileError.NotADirectory(file), moved.errorOrNull())
            assertEquals("s", files.readBytes(source, 10).getOrNull()?.decodeToString(), "a failed move changes nothing")
        } finally {
            TestEnv.deleteTree(root)
        }
    }

    @Test
    fun `deleteRecursively reports the first error in time when several entries cannot be removed`() {
        val root: String = TestEnv.newRoot()
        try {
            val real: FileSystemOps = TestEnv.realOps()
            val order: MutableList<String> = mutableListOf()
            val ops: FileSystemOps = object : FileSystemOps by real {
                override fun remove(path: String) {
                    if (path.endsWith("a") || path.endsWith("b")) {
                        order += path
                        throw FileOpException(if (path.endsWith("a")) FileError.AccessDenied(path) else FileError.NoSpace(path))
                    }
                    real.remove(path)
                }
            }
            val files: FileAccess = fileAccessOver(ops)
            val tree: String = TestPaths.join(root, "tree")
            assertTrue(files.createDirectories(tree).isSuccess)
            assertTrue(files.writeAtomically(TestPaths.join(tree, "a"), ByteArray(1)).isSuccess)
            assertTrue(files.writeAtomically(TestPaths.join(tree, "b"), ByteArray(1)).isSuccess)
            assertTrue(files.writeAtomically(TestPaths.join(tree, "c"), ByteArray(1)).isSuccess)
            val result: FileResult<Unit> = files.deleteRecursively(tree)
            assertEquals(2, order.size, "both undeletable entries were tried: $order")
            val expected: FileError = if (order.first().endsWith("a")) FileError.AccessDenied(order.first()) else FileError.NoSpace(order.first())
            assertEquals(FileResult.Failure(expected), result, "the error reported is the first one that happened")
            assertEquals(null, files.metadata(TestPaths.join(tree, "c"), false), "the entries that could be removed were removed")
        } finally {
            TestEnv.deleteTree(root)
        }
    }

    @Test
    fun `isWindowsReserved follows the documented list on every host`() {
        val reserved: List<String> = listOf(
            "CON", "con", "Prn", "aux", "NUL", "nul.txt", "NUL.tar.gz", "COM0", "com1", "COM9", "LPT0", "lpt9",
            "COM\u00B9", "COM\u00B2", "COM\u00B3", "LPT\u00B9", "LPT\u00B2", "lpt\u00B3.log", "CON .txt", "NUL ", "AUX   .x",
        )
        for (name: String in reserved) assertTrue(FileNames.isWindowsReserved(name), "'$name' is reserved")
        val ordinary: List<String> = listOf(
            "", "COM10", "COMX", "COM", "LPT", "LPT10", "CONN", "CONSOLE", "NULL", "AUX1", "PRN1", " CON", "xCON", ".NUL",
            "COM\u2074", "CO", "C.ON", "CON\u00A0",
        )
        for (name: String in ordinary) assertFalse(FileNames.isWindowsReserved(name), "'$name' is not reserved")
    }

    @Test
    fun `SyncedWithDirectory asks for the full flush of the file and Synced and Buffered do not`() {
        val root: String = TestEnv.newRoot()
        try {
            val real: FileSystemOps = TestEnv.realOps()
            val syncs: MutableList<Boolean> = mutableListOf()
            val ops: FileSystemOps = object : FileSystemOps by real {
                override fun createNew(path: String, protection: FileProtection): WritableFile {
                    val inner: WritableFile = real.createNew(path, protection)
                    return object : WritableFile by inner {
                        override fun sync(full: Boolean) {
                            syncs += full
                            inner.sync(full)
                        }
                    }
                }
            }
            val files: FileAccess = fileAccessOver(ops)
            val path: String = TestPaths.join(root, "f")
            assertTrue(files.writeAtomically(path, ByteArray(1), WriteOptions(durability = Durability.SyncedWithDirectory)).isSuccess)
            assertEquals(listOf(true), syncs)
            syncs.clear()
            assertTrue(files.writeAtomically(path, ByteArray(1), WriteOptions(durability = Durability.Synced)).isSuccess)
            assertEquals(listOf(false), syncs)
            syncs.clear()
            assertTrue(files.writeAtomically(path, ByteArray(1), WriteOptions(durability = Durability.Buffered)).isSuccess)
            assertEquals(emptyList(), syncs)
        } finally {
            TestEnv.deleteTree(root)
        }
    }

    @Test
    fun `treeSize saturates at Long MAX_VALUE instead of overflowing`() {
        val big: FileMetadata = FileMetadata(FileKind.RegularFile, Long.MAX_VALUE - 1, 0L)
        val ops: StubOps = object : StubOps(onMetadata = { path: String, _: Boolean -> if (path == "/d") directory else big }) {
            override fun list(directory: String): List<String>? = listOf("a", "b", "c")
        }
        assertEquals(FileResult.Success(Long.MAX_VALUE), fileAccessOver(ops).treeSize("/d"))
    }

    @Test
    fun `freeSpaceBytes treats a dangling link as absent and measures its nearest existing ancestor`() {
        val root: String = TestEnv.newRoot()
        try {
            val real: FileSystemOps = TestEnv.realOps()
            val asked: MutableList<String> = mutableListOf()
            val ops: FileSystemOps = object : FileSystemOps by real {
                override fun usableSpace(path: String): Long? {
                    asked += path
                    return real.usableSpace(path)
                }
            }
            val files: FileAccess = fileAccessOver(ops)
            val dangling: String = TestPaths.join(root, "dangling")
            if (!TestEnv.createSymbolicLink(dangling, TestPaths.join(root, "nowhere"))) return
            files.freeSpaceBytes(TestPaths.join(dangling, "x"))
            assertEquals(listOf(root), asked, "the dangling link does not exist, so the root is measured")
        } finally {
            TestEnv.deleteTree(root)
        }
    }

    @Test
    fun `a temp directory that cannot be created is reported like createDirectories reports it`() {
        val root: String = TestEnv.newRoot()
        try {
            val files: FileAccess = fileAccessOver(TestEnv.realOps())
            val blocker: String = TestPaths.join(root, "blocker")
            assertTrue(files.writeAtomically(blocker, ByteArray(1)).isSuccess)
            val tempDirectory: String = TestPaths.join(TestPaths.join(blocker, "sub"), "tmp")
            val expected: FileResult<Unit> = files.createDirectories(tempDirectory)
            assertTrue(expected is FileResult.Failure)
            val target: String = TestPaths.join(root, "target")
            val written: FileResult<Unit> = files.writeAtomically(
                target,
                ByteArray(1),
                WriteOptions(createParentDirectories = true, tempDirectory = tempDirectory),
            )
            assertEquals(FileResult.Failure(expected.error), written)
            assertEquals(null, files.metadata(target, false))
        } finally {
            TestEnv.deleteTree(root)
        }
    }
}
