@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Process death between the steps of writeAtomically and move, and failures injected into single steps, observed on
 * real files: [FaultOps] wraps the platform's own ops, so what survives is what a real crash would leave.
 */
class CrashAndFaultTest {
    private lateinit var root: String
    private lateinit var ops: FaultOps
    private lateinit var engine: FileAccess
    private lateinit var plain: FileAccess

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        ops = FaultOps(TestEnv.realOps())
        engine = fileAccessOver(ops)
        plain = fileAccessOver(TestEnv.realOps())
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private fun p(relative: String): String = TestPaths.join(root, relative)

    private fun seed(relative: String, text: String): String {
        val path: String = p(relative)
        assertTrue(plain.writeAtomically(path, text.encodeToByteArray(), WriteOptions(createParentDirectories = true)).isSuccess)
        return path
    }

    private fun text(path: String): String? = plain.readBytes(path, 1L shl 24).getOrNull()?.decodeToString()

    private fun temps(directory: String): List<String> =
        assertNotNull(plain.list(directory).getOrNull()).filter { FileNames.isTempName(it) }

    /** A fresh fault layer, for tests that try several injections in a row on one root. */
    private fun freshEngine() {
        ops = FaultOps(TestEnv.realOps())
        engine = fileAccessOver(ops)
    }

    private fun crashDuringWrite(op: Op, options: WriteOptions = WriteOptions(), relative: String = "f") {
        val target: String = seed(relative, "old")
        ops.crashAfter(op)
        assertFailsWith<SimulatedKill> { engine.writeAtomically(target, "new".encodeToByteArray(), options) }
        assertTrue(ops.hasCrashed)
    }

    @Test
    fun `a crash after CreateNew Write Sync or Close leaves the old content and exactly one temp file`() {
        for (op: Op in listOf(Op.CreateNew, Op.Write, Op.Sync, Op.Close)) {
            freshEngine()
            crashDuringWrite(op, relative = "${op.name}/f")
            assertEquals("old", text(p("${op.name}/f")), "after $op")
            assertEquals(1, temps(p(op.name)).size, "after $op: exactly one temp")
        }
    }

    @Test
    fun `a crash after Rename leaves the new content and no temp file`() {
        crashDuringWrite(Op.Rename)
        assertEquals("new", text(p("f")))
        assertEquals(emptyList(), temps(root))
    }

    @Test
    fun `a crash after the directory sync leaves the new content`() {
        crashDuringWrite(Op.SyncDirectory, WriteOptions(durability = Durability.SyncedWithDirectory))
        assertEquals("new", text(p("f")))
        assertEquals(emptyList(), temps(root))
    }

    @Test
    fun `a crash between link and remove in Fail mode leaves the new content and the temp file`() {
        val target: String = p("fresh")
        ops.crashAfter(Op.Link)
        assertFailsWith<SimulatedKill> {
            engine.writeAtomically(target, "new".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail))
        }
        assertEquals("new", text(target))
        assertEquals(1, temps(root).size)
        val again: FileResult<Unit> = plain.writeAtomically(target, "x".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail))
        assertTrue(again.errorOrNull() is FileError.AlreadyExists)
        assertEquals("new", text(target))
    }

    @Test
    fun `the operations of one Synced write are CreateNew Write Sync Close Rename in order`() {
        val target: String = p("f")
        assertTrue(engine.writeAtomically(target, ByteArray(3), WriteOptions(createParentDirectories = false)).isSuccess)
        val mutating: List<Op> = ops.log.map { it.op }.filter { it in setOf(Op.CreateNew, Op.Write, Op.Sync, Op.Close, Op.Rename, Op.SyncDirectory) }
        assertEquals(listOf(Op.CreateNew, Op.Write, Op.Sync, Op.Close, Op.Rename), mutating)
    }

    @Test
    fun `SyncedWithDirectory syncs the file fully and then the directory after the rename`() {
        assertTrue(engine.writeAtomically(p("f"), ByteArray(3), WriteOptions(durability = Durability.SyncedWithDirectory)).isSuccess)
        val mutating: List<Op> = ops.log.map { it.op }.filter { it in setOf(Op.Sync, Op.Rename, Op.SyncDirectory) }
        assertEquals(listOf(Op.Sync, Op.Rename, Op.SyncDirectory), mutating)
    }

    @Test
    fun `Buffered never syncs`() {
        assertTrue(engine.writeAtomically(p("f"), ByteArray(3), WriteOptions(durability = Durability.Buffered)).isSuccess)
        assertTrue(ops.log.none { it.op == Op.Sync || it.op == Op.SyncDirectory })
    }

    @Test
    fun `NoSpace from Write or Rename fails the call removes the temp and keeps the target`() {
        for (op: Op in listOf(Op.Write, Op.Rename, Op.Sync, Op.Close)) {
            freshEngine()
            val target: String = seed("${op.name}/f", "old")
            ops.failNext(op, FileError.NoSpace(target))
            val result: FileResult<Unit> = engine.writeAtomically(target, "new".encodeToByteArray())
            assertEquals(FileError.NoSpace(target), result.errorOrNull(), "$op")
            assertEquals("old", text(target), "$op")
            assertEquals(emptyList(), temps(p(op.name)), "$op")
        }
    }

    @Test
    fun `a failed directory sync is reported as Io although the target already holds the new bytes`() {
        val target: String = seed("f", "old")
        ops.failNext(Op.SyncDirectory, FileError.Io(root, 5))
        val result: FileResult<Unit> = engine.writeAtomically(target, "new".encodeToByteArray(), WriteOptions(durability = Durability.SyncedWithDirectory))
        assertTrue(result.errorOrNull() is FileError.Io, "$result")
        assertEquals("new", text(target))
        assertEquals(emptyList(), temps(root))
    }

    @Test
    fun `a name clash on the temp file is retried and gives up after sixteen attempts`() {
        val target: String = p("f")
        ops.failNext(Op.CreateNew, FileError.AlreadyExists(target), count = 3)
        assertTrue(engine.writeAtomically(target, ByteArray(1)).isSuccess)
        ops.failNext(Op.CreateNew, FileError.AlreadyExists(target), count = 17)
        val result: FileResult<Unit> = engine.writeAtomically(p("g"), ByteArray(1))
        assertTrue(result.errorOrNull() is FileError.Io, "$result")
        assertNull(plain.metadata(p("g")))
        // 3 clashes and the success of the first write, then exactly 16 attempts of the second.
        assertEquals(4 + 16, ops.log.count { it.op == Op.CreateNew })
    }

    @Test
    fun `the temp file goes to tempDirectory with the custom prefix`() {
        plain.createDirectories(p("tmp"))
        val target: String = p("out/f")
        plain.createDirectories(p("out"))
        ops.crashAfter(Op.Close)
        assertFailsWith<SimulatedKill> {
            engine.writeAtomically(target, ByteArray(2), WriteOptions(tempDirectory = p("tmp"), tempPrefix = "mine-"))
        }
        val inTemp: List<String> = assertNotNull(plain.list(p("tmp")).getOrNull())
        assertEquals(1, inTemp.size)
        assertTrue(FileNames.isTempName(inTemp.single(), "mine-"), inTemp.single())
        assertEquals(emptyList(), assertNotNull(plain.list(p("out")).getOrNull()))
    }

    @Test
    fun `a temp directory on another volume is CrossDevice and leaves nothing behind`() {
        plain.createDirectories(p("tmp"))
        val target: String = seed("f", "old")
        ops.failNext(Op.Rename, FileError.CrossDevice(target))
        val result: FileResult<Unit> = engine.writeAtomically(target, ByteArray(1), WriteOptions(tempDirectory = p("tmp")))
        assertTrue(result.errorOrNull() is FileError.CrossDevice)
        assertEquals("old", text(target))
        assertEquals(emptyList(), assertNotNull(plain.list(p("tmp")).getOrNull()))
    }

    @Test
    fun `without hard links Fail is Unsupported and the target and directory are untouched`() {
        val target: String = p("f")
        ops.failNext(Op.Link, FileError.Unsupported(target))
        val result: FileResult<Unit> = engine.writeAtomically(target, ByteArray(1), WriteOptions(existing = ExistingFile.Fail))
        assertTrue(result.errorOrNull() is FileError.Unsupported)
        assertEquals(emptyList(), assertNotNull(plain.list(root).getOrNull()))
        seed("a", "A")
        ops.failNext(Op.Link, FileError.Unsupported(p("b")))
        assertTrue(engine.move(p("a"), p("b"), MoveOptions(existing = ExistingFile.Fail)).errorOrNull() is FileError.Unsupported)
        assertEquals("A", text(p("a")))
    }

    // ---- move across volumes ----------------------------------------------------------------------

    private fun crossDevice(from: String, to: String) {
        ops.failNext(Op.Rename, FileError.CrossDevice(from), match = { it == from })
        ops.failNext(Op.Link, FileError.CrossDevice(to), match = { it == to })
    }

    @Test
    fun `a move across volumes copies syncs renames and then removes the source`() {
        val from: String = seed("a", "payload")
        crossDevice(from, p("b"))
        assertTrue(engine.move(from, p("b")).isSuccess)
        assertNull(plain.metadata(from))
        assertEquals("payload", text(p("b")))
        assertEquals(emptyList(), temps(root))
        assertTrue(ops.log.any { it.op == Op.Sync })
    }

    @Test
    fun `a move across volumes with Fail links the copy and refuses an existing target`() {
        val from: String = seed("a", "A")
        seed("b", "B")
        crossDevice(from, p("b"))
        val result: FileResult<Unit> = engine.move(from, p("b"), MoveOptions(existing = ExistingFile.Fail))
        assertTrue(result.errorOrNull() is FileError.AlreadyExists, "$result")
        assertEquals("A", text(from))
        assertEquals("B", text(p("b")))
        assertEquals(emptyList(), temps(root))
    }

    @Test
    fun `a crash after the copy was renamed into place leaves the bytes at both names`() {
        val from: String = seed("a", "payload")
        crossDevice(from, p("b"))
        // The refused rename is not a successful operation: the first successful Rename is the copy's.
        ops.crashAfter(Op.Rename)
        assertFailsWith<SimulatedKill> { engine.move(from, p("b")) }
        assertEquals("payload", text(from))
        assertEquals("payload", text(p("b")))
    }

    @Test
    fun `a move across volumes is CrossDevice when acrossVolumes is off and changes nothing`() {
        val from: String = seed("a", "A")
        crossDevice(from, p("b"))
        val result: FileResult<Unit> = engine.move(from, p("b"), MoveOptions(acrossVolumes = false))
        assertTrue(result.errorOrNull() is FileError.CrossDevice)
        assertEquals("A", text(from))
        assertNull(plain.metadata(p("b")))
    }

    @Test
    fun `a directory moved across volumes is CrossDevice`() {
        plain.createDirectories(p("d"))
        crossDevice(p("d"), p("e"))
        assertTrue(engine.move(p("d"), p("e")).errorOrNull() is FileError.CrossDevice)
        assertEquals(FileKind.Directory, plain.metadata(p("d"))?.kind)
    }

    @Test
    fun `a copy cancelled after two chunks removes its temp and leaves source and destination as they were`() {
        val big: ByteArray = ByteArray(1 shl 20) { it.toByte() }
        val from: String = p("big")
        assertTrue(plain.writeAtomically(from, big, WriteOptions(durability = Durability.Buffered)).isSuccess)
        crossDevice(from, p("copy"))
        var checks: Int = 0
        val result: FileResult<Unit> = engine.move(from, p("copy")) { ++checks > 2 }
        assertEquals(FileError.Cancelled, result.errorOrNull())
        assertContentEquals(big, plain.readBytes(from, 2L shl 20).getOrNull())
        assertNull(plain.metadata(p("copy")))
        assertEquals(emptyList(), temps(root))
    }

    @Test
    fun `a failing source removal after the copy does not fail the move`() {
        val from: String = seed("a", "A")
        crossDevice(from, p("b"))
        ops.failNext(Op.Remove, FileError.AccessDenied(from), match = { it == from })
        assertTrue(engine.move(from, p("b")).isSuccess)
        assertEquals("A", text(p("b")))
        assertEquals("A", text(from))
    }

    @Test
    fun `move with Fail whose source removal fails after the link is still a Success with both names present`() {
        val from: String = seed("a", "A")
        ops.failNext(Op.Remove, FileError.AccessDenied(from), match = { it == from })
        assertTrue(engine.move(from, p("b"), MoveOptions(existing = ExistingFile.Fail)).isSuccess)
        assertEquals("A", text(from))
        assertEquals("A", text(p("b")))
    }

    // ---- delete ----------------------------------------------------------------------------------

    @Test
    fun `deleteRecursively keeps going past an entry it cannot remove and reports its error`() {
        for (name: String in listOf("a", "b", "c")) seed("t/$name", name)
        val stuck: String = p("t/b")
        ops.failNext(Op.Remove, FileError.AccessDenied(stuck), count = 5, match = { it == stuck })
        val result: FileResult<Unit> = engine.deleteRecursively(p("t"))
        assertEquals(FileError.AccessDenied(stuck), result.errorOrNull())
        assertEquals(listOf("b"), assertNotNull(plain.list(p("t")).getOrNull()))
    }

    @Test
    fun `entries that vanish during the walk count as removed`() {
        for (name: String in listOf("a", "b", "c")) seed("t/$name", name)
        // A concurrent deleter removes two siblings after the walk has listed them.
        var done: Boolean = false
        ops.onBefore = { op: Op, path: String ->
            if (!done && op == Op.Metadata && path == p("t/b")) {
                done = true
                plain.delete(p("t/a"))
                plain.delete(p("t/c"))
                plain.delete(p("t/b"))
            }
        }
        assertTrue(engine.deleteRecursively(p("t")).isSuccess)
        assertNull(plain.metadata(p("t")))
    }

    @Test
    fun `a directory that is refilled during the delete is DirectoryNotEmpty not Success`() {
        seed("t/a", "a")
        // The remove of "a" answers NotFound although the file is there: nothing was removed, nothing failed loudly.
        ops.failNext(Op.Remove, FileError.NotFound(p("t/a")), match = { it == p("t/a") })
        val result: FileResult<Unit> = engine.deleteRecursively(p("t"))
        assertEquals(FileError.DirectoryNotEmpty(p("t")), result.errorOrNull())
        assertEquals(FileKind.RegularFile, plain.metadata(p("t/a"))?.kind)
    }

    @Test
    fun `delete treats NotFound from the remove as success`() {
        val file: String = seed("f", "x")
        ops.failNext(Op.Remove, FileError.NotFound(file))
        assertTrue(engine.delete(file).isSuccess)
    }
}
