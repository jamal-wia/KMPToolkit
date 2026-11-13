@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Unit G, stage 2 (final boss), round 1: attacks that earlier rounds did not make, on real files of every engine
 * (java.nio on the JVM and the Android host, POSIX on the iOS simulator), each a sentence of FileAccess's KDoc.
 *
 * - A temporary name taken by somebody else between the engine's choice and its exclusive create: the SPI promises
 *   O_CREAT|O_EXCL, so the engine must never write into the foreign file (kills the r6 survivors N03 and I05, which
 *   r6 had argued unobservable).
 * - Paths below a regular file are absent: delete, deleteRecursively, readBytes, treeSize, move and metadata must
 *   treat them as absent on every engine (kills the iOS survivor I02, ENOTDIR from stat).
 * - Concurrent actors (FaultOps.onBefore) at the steps no earlier race test covered: a directory appearing at the
 *   destination of a replacing move, a file growing past the read limit between the size check and the read.
 */
class BossOneR1Test {
    private lateinit var root: String
    private lateinit var ops: FaultOps
    private lateinit var engine: FileAccess
    private val plain: FileAccess = createFileAccess()

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        ops = FaultOps(TestEnv.realOps())
        engine = fileAccessOver(ops)
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private fun p(relative: String): String = TestPaths.join(root, relative)

    private fun put(relative: String, text: String): String {
        val path: String = p(relative)
        assertTrue(plain.writeAtomically(path, text.encodeToByteArray(), WriteOptions(createParentDirectories = true)).isSuccess)
        return path
    }

    private fun text(path: String): String? = plain.readBytes(path, 1L shl 20).getOrNull()?.decodeToString()

    private fun names(relative: String = ""): List<String> =
        plain.list(if (relative.isEmpty()) root else p(relative)).getOrNull().orEmpty()

    /** Runs [action] once, just before the first [op] on a path for which [match] holds (a concurrent actor). */
    private fun before(op: Op, match: (String) -> Boolean = { true }, action: (String) -> Unit) {
        var done: Boolean = false
        ops.onBefore = { seen: Op, path: String ->
            if (!done && seen == op && match(path)) {
                done = true
                action(path)
            }
        }
    }

    private fun failureOf(result: FileResult<*>): FileError =
        (result as? FileResult.Failure)?.error ?: error("expected a failure, got $result")

    @Test
    fun `a temporary name created by somebody else first is never written into and the write still succeeds`() {
        val target: String = put("d/target", "old")
        var planted: String? = null
        before(Op.CreateNew) { tempPath: String ->
            // Another writer (another process with the same prefix) wins the exclusive create of this very name.
            assertTrue(plain.writeAtomically(tempPath, "foreign".encodeToByteArray()).isSuccess)
            planted = tempPath
        }
        assertEquals(FileResult.Success(Unit), engine.writeAtomically(target, "new".encodeToByteArray()))
        val foreign: String = planted ?: error("the engine never created a temporary file")
        assertEquals("new", text(target))
        assertEquals("foreign", text(foreign), "the engine wrote into or renamed a temporary file it did not create")
        assertEquals(2, ops.log.count { it.op == Op.CreateNew }, "the clash must be retried with a new name")
        assertEquals(listOf(TestPaths.fileName(foreign), "target").sorted(), names("d"))
    }

    @Test
    fun `a temporary name taken first is never moved into place by a no replace write either`() {
        val target: String = p("d/target")
        put("d/other", "x")
        var planted: String? = null
        before(Op.CreateNew) { tempPath: String ->
            assertTrue(plain.writeAtomically(tempPath, "foreign".encodeToByteArray()).isSuccess)
            planted = tempPath
        }
        val options: WriteOptions = WriteOptions(existing = ExistingFile.Fail)
        assertEquals(FileResult.Success(Unit), engine.writeAtomically(target, "new".encodeToByteArray(), options))
        assertEquals("new", text(target))
        assertEquals("foreign", text(planted ?: error("no temporary file was created")))
    }

    @Test
    fun `a path below a regular file is absent to delete deleteRecursively readBytes treeSize metadata and move`() {
        put("f", "file")
        val below: String = p("f/x")
        val deeper: String = p("f/x/y")
        assertEquals(FileResult.Success(Unit), engine.delete(below))
        assertEquals(FileResult.Success(Unit), engine.delete(deeper))
        assertEquals(FileResult.Success(Unit), engine.deleteRecursively(below))
        assertEquals(FileResult.Success(Unit), engine.deleteRecursively(deeper))
        assertEquals(FileResult.Failure(FileError.NotFound(below)), engine.readBytes(below, 100))
        assertEquals(FileResult.Success(0L), engine.treeSize(below))
        assertNull(engine.metadata(below))
        assertNull(engine.metadata(below, followLinks = true))
        assertEquals(FileResult.Failure(FileError.NotFound(below)), engine.move(below, p("g")))
        assertEquals(FileResult.Failure(FileError.NotADirectory(p("f"))), engine.list(p("f")))
        assertEquals("file", text(p("f")))
        assertEquals(listOf("f"), names())
    }

    @Test
    fun `a directory that appears at the destination of a replacing move is IsADirectory naming the destination`() {
        val from: String = put("a", "source")
        val to: String = p("b")
        before(Op.Rename, { it == from }) {
            assertTrue(plain.createDirectories(to).isSuccess)
        }
        assertEquals(FileResult.Failure(FileError.IsADirectory(to)), engine.move(from, to))
        assertEquals("source", text(from))
        assertEquals(FileKind.Directory, plain.metadata(to)?.kind)
    }

    @Test
    fun `a non-empty directory that appears at the destination of a replacing move is kept whole`() {
        val from: String = put("a", "source")
        val to: String = p("b")
        before(Op.Rename, { it == from }) {
            put("b/inside", "kept")
        }
        val error: FileError = failureOf(engine.move(from, to))
        assertTrue(
            error is FileError.IsADirectory || error is FileError.DirectoryNotEmpty,
            "a directory at to must be IsADirectory (or the platform's DirectoryNotEmpty), was $error",
        )
        assertEquals(to, error.path)
        assertEquals("source", text(from))
        assertEquals("kept", text(p("b/inside")))
    }

    @Test
    fun `a file that grows past the limit between the size check and the read is TooLarge and never returned in part`() {
        val target: String = put("big", "12345")
        before(Op.OpenForRead, { it == target }) {
            assertTrue(plain.writeAtomically(target, ByteArray(4096) { 7 }).isSuccess)
        }
        val result: FileResult<ByteArray> = engine.readBytes(target, 10)
        val error: FileError = failureOf(result)
        assertTrue(error is FileError.TooLarge, "expected TooLarge, was $error")
        assertEquals(target, error.path)
        assertEquals(10L, error.maxBytes)
    }

    @Test
    fun `a file that grows to exactly the limit between the size check and the read is returned whole`() {
        val target: String = put("edge", "123")
        val grown: ByteArray = ByteArray(10) { (it + 1).toByte() }
        before(Op.OpenForRead, { it == target }) {
            assertTrue(plain.writeAtomically(target, grown).isSuccess)
        }
        val read: ByteArray = engine.readBytes(target, 10).getOrNull() ?: error("expected the whole grown content")
        assertTrue(grown.contentEquals(read), "read ${read.size} bytes, expected the whole 10")
    }

    @Test
    fun `a file that shrinks to empty between the size check and the read is returned as the empty new content`() {
        val target: String = put("shrink", "a fairly long old content")
        before(Op.OpenForRead, { it == target }) {
            assertTrue(plain.writeAtomically(target, ByteArray(0)).isSuccess)
        }
        val read: ByteArray = engine.readBytes(target, 100).getOrNull() ?: error("expected Success")
        assertEquals(0, read.size)
    }
    @Test
    fun `a no replace move across volumes leaves no temporary file and only the destination holds the bytes`() {
        val from: String = put("src/a", "payload")
        val to: String = p("dst/b")
        assertTrue(plain.createDirectories(p("dst")).isSuccess)
        // The first link (from -> to) answers like a link across volumes; the copy's own link then works.
        ops.failNext(Op.Link, FileError.CrossDevice(to), match = { it == to })
        val options: MoveOptions = MoveOptions(existing = ExistingFile.Fail)
        assertEquals(FileResult.Success(Unit), engine.move(from, to, options))
        assertEquals("payload", text(to))
        assertNull(plain.metadata(from), "the source must be removed once the destination holds the bytes")
        assertEquals(listOf("b"), names("dst"), "a temporary name (a second hard link of the copy) was left behind")
        assertEquals(2, ops.log.count { it.op == Op.Link }, "expected the refused link and the copy's link")
    }

    @Test
    fun `a replacing move across volumes over an existing file leaves no temporary file`() {
        val from: String = put("src/a", "payload")
        val to: String = put("dst/b", "old")
        ops.failNext(Op.Rename, FileError.CrossDevice(from), match = { it == from })
        assertEquals(FileResult.Success(Unit), engine.move(from, to))
        assertEquals("payload", text(to))
        assertNull(plain.metadata(from))
        assertEquals(listOf("b"), names("dst"))
    }
    @Test
    fun `freeSpaceBytes at a dangling symbolic link is measured at the nearest existing ancestor`() {
        val atRoot: Long = plain.freeSpaceBytes(root) ?: TestEnv.skip("the platform cannot tell the free space here")
        if (atRoot < (1L shl 30)) TestEnv.skip("less than 1 GiB free: too little to compare two readings")
        val dangling: String = p("dangling")
        if (!TestEnv.createSymbolicLink(dangling, p("missing/target"))) TestEnv.skip("no symbolic links here")
        assertNull(plain.metadata(dangling, followLinks = true), "the link must dangle")
        // A dangling link does not exist in the sense of exists(): its space is the space of its directory.
        val atLink: Long = engine.freeSpaceBytes(dangling) ?: error("freeSpaceBytes of a dangling link was null")
        val below: Long = engine.freeSpaceBytes(p("dangling/x")) ?: error("freeSpaceBytes below a dangling link was null")
        assertTrue(kotlin.math.abs(atLink - atRoot) < atRoot / 10, "at the link $atLink, at its directory $atRoot")
        assertTrue(kotlin.math.abs(below - atRoot) < atRoot / 10, "below the link $below, at its directory $atRoot")
    }
    @Test
    fun `deleteRecursively whose root is recreated before its final check is not Success`() {
        val target: String = p("tree")
        put("tree/a", "x")
        var metadataOfRoot: Int = 0
        ops.onBefore = { op: Op, path: String ->
            // The walk examines the root first and checks it again at the end: a concurrent creator acts in between.
            if (op == Op.Metadata && path == target && ++metadataOfRoot == 2) {
                assertTrue(plain.createDirectories(target).isSuccess)
            }
        }
        val result: FileResult<Unit> = engine.deleteRecursively(target)
        assertEquals(2, metadataOfRoot, "the walk must look at the root exactly twice")
        assertEquals(FileKind.Directory, plain.metadata(target)?.kind)
        assertTrue(result is FileResult.Failure, "Success means nothing is left at the path, yet a directory is there: $result")
    }
}
