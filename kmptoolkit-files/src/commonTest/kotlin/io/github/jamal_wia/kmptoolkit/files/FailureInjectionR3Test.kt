@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Round 3 of the breaker for unit G (consumer surface, failure injection). 04-api-reference.md: a failure is a typed
 * [FileError], no function throws except for the stated IllegalArgumentException, nothing is left behind (no temporary
 * file, no open handle). Every scenario changes the file system between the engine's check and its use, or makes one
 * low-level operation fail, on the platform's real ops through [FaultOps]; the same class runs on the JVM, Android
 * host and iOS.
 */
class FailureInjectionR3Test {
    private lateinit var root: String
    private lateinit var ops: FaultOps
    private lateinit var engine: FileAccess
    private lateinit var plain: FileAccess
    private var descriptorsBefore: Long? = null

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
        ops = FaultOps(TestEnv.realOps())
        engine = fileAccessOver(ops)
        plain = fileAccessOver(TestEnv.realOps())
        descriptorsBefore = TestEnv.openDescriptorCount()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
        val before: Long? = descriptorsBefore
        val after: Long? = TestEnv.openDescriptorCount()
        if (before != null && after != null) assertTrue(after - before <= 3, "descriptors went from $before to $after")
    }

    private fun p(relative: String): String = TestPaths.join(root, relative)

    private fun put(relative: String, text: String = relative): String {
        val path: String = p(relative)
        assertTrue(plain.writeAtomically(path, text.encodeToByteArray(), WriteOptions(createParentDirectories = true)).isSuccess)
        return path
    }

    private fun text(path: String): String? = plain.readBytes(path, 1L shl 20).getOrNull()?.decodeToString()

    private fun tempsUnder(directory: String): List<String> =
        (plain.list(directory).getOrNull() ?: emptyList()).filter { FileNames.isTempName(it) }

    /** Runs [action] once, just before the first [op] on a path for which [match] holds (a concurrent actor). */
    private fun before(op: Op, match: (String) -> Boolean = { true }, action: () -> Unit) {
        var done: Boolean = false
        ops.onBefore = { seen: Op, path: String ->
            if (!done && seen == op && match(path)) {
                done = true
                action()
            }
        }
    }

    private fun replaceByFile(path: String, text: String = "now a file") {
        assertTrue(plain.deleteRecursively(path).isSuccess)
        assertTrue(plain.writeAtomically(path, text.encodeToByteArray()).isSuccess)
    }

    private fun failureOf(result: FileResult<*>): FileError =
        assertNotNull(result.errorOrNull(), "expected a failure but got $result")

    // ---- a file or directory changes between the check and the use ------------------------------------------

    @Test
    fun `readBytes of a file that vanishes between the size check and the open is NotFound`() {
        val file: String = put("f")
        before(Op.OpenForRead) { assertTrue(plain.delete(file).isSuccess) }
        assertEquals(FileError.NotFound(file), failureOf(engine.readBytes(file, 1024)))
    }

    @Test
    fun `readBytes of a file replaced by a directory between the check and the open is typed IsADirectory`() {
        val file: String = put("f")
        before(Op.OpenForRead) {
            assertTrue(plain.delete(file).isSuccess)
            assertTrue(plain.createDirectories(file).isSuccess)
        }
        assertEquals(FileError.IsADirectory(file), failureOf(engine.readBytes(file, 1024)))
    }

    @Test
    fun `writeAtomically whose directory vanishes before the rename is NotFound and leaves nothing`() {
        val target: String = put("d/f", "old")
        before(Op.Rename) { assertTrue(plain.deleteRecursively(p("d")).isSuccess) }
        assertEquals(FileError.NotFound(target), failureOf(engine.writeAtomically(target, "new".encodeToByteArray())))
        assertEquals(emptyList(), tempsUnder(root))
    }

    @Test
    fun `writeAtomically whose parent becomes a file before the temp file is created is NotADirectory`() {
        val target: String = put("d/f", "old")
        before(Op.CreateNew) { replaceByFile(p("d")) }
        val error: FileError = failureOf(engine.writeAtomically(target, "new".encodeToByteArray()))
        assertTrue(error is FileError.NotADirectory, "was $error")
        assertEquals("now a file", text(p("d")))
    }

    @Test
    fun `writeAtomically whose target becomes a directory before the rename is IsADirectory and leaves nothing`() {
        val target: String = put("f", "old")
        before(Op.Rename) {
            assertTrue(plain.delete(target).isSuccess)
            assertTrue(plain.createDirectories(TestPaths.join(target, "child")).isSuccess)
        }
        val error: FileError = failureOf(engine.writeAtomically(target, "new".encodeToByteArray()))
        assertTrue(error is FileError.IsADirectory || error is FileError.DirectoryNotEmpty, "was $error")
        assertEquals(FileKind.Directory, plain.metadata(target)?.kind)
        assertEquals(emptyList(), tempsUnder(root))
    }

    @Test
    fun `move whose source vanishes before the rename is NotFound naming the source`() {
        val from: String = put("a")
        before(Op.Rename) { assertTrue(plain.delete(from).isSuccess) }
        assertEquals(FileError.NotFound(from), failureOf(engine.move(from, p("b"))))
        assertEquals(null, plain.metadata(p("b")))
    }

    @Test
    fun `move with Fail whose destination appears before the link is AlreadyExists naming the destination`() {
        val from: String = put("a", "from")
        val to: String = p("b")
        before(Op.Link) { put("b", "winner") }
        assertEquals(FileError.AlreadyExists(to), failureOf(engine.move(from, to, MoveOptions(existing = ExistingFile.Fail))))
        assertEquals("winner", text(to))
        assertEquals("from", text(from))
    }

    @Test
    fun `move whose destination parent becomes a file before the rename is NotADirectory naming the destination`() {
        val from: String = put("a")
        put("d/x")
        val to: String = p("d/b")
        before(Op.Rename) { replaceByFile(p("d")) }
        assertEquals(FileError.NotADirectory(to), failureOf(engine.move(from, to)))
        assertEquals("a", text(from))
    }

    @Test
    fun `delete of a file that vanishes before the remove is Success`() {
        val file: String = put("f")
        before(Op.Remove) { assertTrue(plain.delete(file).isSuccess) }
        assertTrue(engine.delete(file).isSuccess)
    }

    @Test
    fun `deleteRecursively of a subdirectory replaced by a file during the walk is the typed NotADirectory and removes the siblings`() {
        put("t/a")
        put("t/sub/b")
        put("t/z")
        before(Op.List, { it == p("t/sub") }) { replaceByFile(p("t/sub")) }
        val error: FileError = failureOf(engine.deleteRecursively(p("t")))
        assertEquals(FileError.NotADirectory(p("t/sub")), error)
        assertEquals(null, plain.metadata(p("t/a")))
        assertEquals(null, plain.metadata(p("t/z")))
    }

    @Test
    fun `deleteRecursively reports an entry it could not examine as the first error and removes the rest`() {
        put("t/a")
        put("t/b")
        put("t/z")
        ops.failNext(Op.Metadata, FileError.AccessDenied(p("t/b")), count = 1000) { it == p("t/b") }
        val error: FileError = failureOf(engine.deleteRecursively(p("t")))
        assertEquals(FileError.AccessDenied(p("t/b")), error)
        assertEquals(null, plain.metadata(p("t/a")))
        assertEquals(null, plain.metadata(p("t/z")))
        assertEquals(FileKind.RegularFile, plain.metadata(p("t/b"))?.kind)
    }

    @Test
    fun `deleteRecursively of a tree that vanishes entirely during the walk is Success`() {
        put("t/a")
        put("t/sub/b")
        before(Op.List, { it == p("t/sub") }) { assertTrue(plain.deleteRecursively(p("t")).isSuccess) }
        assertTrue(engine.deleteRecursively(p("t")).isSuccess)
        assertEquals(null, plain.metadata(p("t")))
    }

    @Test
    fun `treeSize of a subdirectory replaced by a file during the walk counts that entry as 0`() {
        put("t/a", "12345")
        put("t/sub/b", "1234567")
        before(Op.List, { it == p("t/sub") }) { replaceByFile(p("t/sub"), "xxxxxxxxxxxx") }
        assertEquals(FileResult.Success(5L), engine.treeSize(p("t")))
    }

    @Test
    fun `list of a directory that vanishes is empty and of one replaced by a file is NotADirectory`() {
        put("d/a")
        before(Op.List) { assertTrue(plain.deleteRecursively(p("d")).isSuccess) }
        assertEquals(FileResult.Success(emptyList<String>()), engine.list(p("d")))
        put("e/a")
        before(Op.List) { replaceByFile(p("e")) }
        assertEquals(FileError.NotADirectory(p("e")), failureOf(engine.list(p("e"))))
    }

    @Test
    fun `sweep of a directory replaced by a file is NotADirectory and an entry that vanishes at the remove is not counted`() {
        val a: String = put("s/a")
        val b: String = put("s/b")
        assertTrue(TestEnv.setLastModified(a, 1_000L))
        assertTrue(TestEnv.setLastModified(b, 1_000L))
        before(Op.Remove, { it == a }) { assertTrue(plain.delete(a).isSuccess) }
        assertEquals(FileResult.Success(1), engine.sweep(p("s"), 1_000L, CancellationCheck.Never) { true })
        before(Op.List) { replaceByFile(p("s")) }
        assertEquals(FileError.NotADirectory(p("s")), failureOf(engine.sweep(p("s"), 0L, CancellationCheck.Never) { true }))
    }

    @Test
    fun `createDirectories whose missing level appears as a file is NotADirectory and as a directory is Success`() {
        val target: String = p("x/y")
        before(Op.CreateDirectory, { it == p("x") }) { put("x", "a file") }
        assertEquals(FileError.NotADirectory(p("x")), failureOf(engine.createDirectories(target)))
        assertTrue(plain.delete(p("x")).isSuccess)
        before(Op.CreateDirectory, { it == p("x") }) { assertTrue(plain.createDirectories(p("x")).isSuccess) }
        assertTrue(engine.createDirectories(target).isSuccess)
        assertEquals(FileKind.Directory, plain.metadata(target)?.kind)
    }

    // ---- every function against every low-level failure --------------------------------------------------------

    private fun errorsFor(path: String): List<FileError> = listOf(
        FileError.NoSpace(path),
        FileError.AccessDenied(path),
        FileError.Io(path),
        FileError.NameTooLong(path),
        FileError.CrossDevice(path),
        FileError.Unsupported(path),
    )

    private fun reseed() {
        TestEnv.deleteTree(root)
        root = TestEnv.newRoot()
        put("dir/a")
        put("dir/b")
        put("dir/sub/c")
        put("file", "content")
        assertTrue(TestEnv.setLastModified(p("dir/a"), 1_000L))
    }

    @Test
    fun `no function throws or leaves a temp file whatever low-level operation fails with whatever typed error`() {
        val problems: MutableList<String> = mutableListOf()
        for (op: Op in Op.entries) {
            for (error: FileError in errorsFor(root)) {
                val calls: List<Pair<String, (FileAccess) -> Any?>> = listOf(
                    "metadata" to { f: FileAccess -> f.metadata(p("file")) },
                    "exists" to { f: FileAccess -> f.exists(p("file")) },
                    "freeSpaceBytes" to { f: FileAccess -> f.freeSpaceBytes(p("file")) },
                    "list" to { f: FileAccess -> f.list(p("dir")) },
                    "createDirectories" to { f: FileAccess -> f.createDirectories(p("n/m")) },
                    "createDirectories durable" to { f: FileAccess -> f.createDirectories(p("n2/m"), durable = true) },
                    "readBytes" to { f: FileAccess -> f.readBytes(p("file"), 1024) },
                    "write Buffered" to { f: FileAccess -> f.writeAtomically(p("file"), byteArrayOf(1), WriteOptions(durability = Durability.Buffered)) },
                    "write Synced" to { f: FileAccess -> f.writeAtomically(p("file"), byteArrayOf(1), WriteOptions(durability = Durability.Synced)) },
                    "write SyncedWithDirectory" to { f: FileAccess ->
                        f.writeAtomically(p("w/new"), byteArrayOf(1), WriteOptions(durability = Durability.SyncedWithDirectory, createParentDirectories = true))
                    },
                    "write Fail" to { f: FileAccess -> f.writeAtomically(p("fresh"), byteArrayOf(1), WriteOptions(existing = ExistingFile.Fail)) },
                    "move" to { f: FileAccess -> f.move(p("file"), p("moved")) },
                    "move Fail" to { f: FileAccess -> f.move(p("dir/b"), p("moved2"), MoveOptions(existing = ExistingFile.Fail)) },
                    "delete" to { f: FileAccess -> f.delete(p("dir/b")) },
                    "deleteRecursively" to { f: FileAccess -> f.deleteRecursively(p("dir")) },
                    "treeSize" to { f: FileAccess -> f.treeSize(p("dir")) },
                    "sweep" to { f: FileAccess -> f.sweep(p("dir"), 0L, CancellationCheck.Never) { true } },
                )
                for ((name: String, call: (FileAccess) -> Any?) in calls) {
                    reseed()
                    ops = FaultOps(TestEnv.realOps())
                    // A persistent Close failure would keep FaultOps from ever reaching the real close (a real failing close(2)
                    // still frees the descriptor), so Close fails once: the engine's second, quiet close then runs.
                    ops.failNext(op, error, count = if (op == Op.Close) 1 else 1000)
                    val files: FileAccess = fileAccessOver(ops)
                    val label: String = "$name with $op failing as ${error::class.simpleName}"
                    val fdBefore: Long? = TestEnv.openDescriptorCount()
                    val result: Any? = try {
                        call(files)
                    } catch (e: Throwable) {
                        problems += "$label threw $e"
                        continue
                    }
                    val fdAfter: Long? = TestEnv.openDescriptorCount()
                    if (fdBefore != null && fdAfter != null && fdAfter > fdBefore) problems += "$label leaked descriptors $fdBefore to $fdAfter"
                    if (result is FileResult<*> && result is FileResult.Failure && result.error == FileError.Cancelled) {
                        problems += "$label answered Cancelled without a cancellation"
                    }
                    // G13 (documentation gap, see the round 3 report): a no-replace write whose link succeeded and whose
                    // temp name cannot be removed is a Success that leaves the temp name, swept later by its name.
                    if (name == "write Fail" && op == Op.Remove) continue
                    for (directory: String in listOf(root, p("dir"), p("w"))) {
                        val left: List<String> = tempsUnder(directory)
                        if (left.isNotEmpty()) problems += "$label left $left in $directory"
                    }
                }
            }
        }
        if (problems.isNotEmpty()) fail("${problems.size} problems, first 20:\n" + problems.take(20).joinToString("\n"))
    }
}
