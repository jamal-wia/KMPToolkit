@file:OptIn(ToolkitInternalApi::class, ExperimentalAtomicApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The contract of [FileAccess] on a real file system (R-1..R-13). The body is shared; every platform runs it through a
 * concrete subclass, and the Android one also under Robolectric through the public factory.
 */
abstract class RealFileAccessTests {
    protected open fun files(): FileAccess = fileAccessOver(TestEnv.realOps())

    private lateinit var rootPath: String
    protected lateinit var fs: FileAccess
    protected val root: String get() = rootPath

    @BeforeTest
    fun setUpRoot() {
        rootPath = TestEnv.newRoot()
        fs = files()
    }

    @AfterTest
    fun tearDownRoot() {
        TestEnv.deleteTree(rootPath)
    }

    // ---- helpers ----------------------------------------------------------------------------------

    protected fun p(relative: String): String = TestPaths.join(root, relative)

    private fun bytesOf(text: String): ByteArray = text.encodeToByteArray()

    private fun put(relative: String, content: ByteArray = bytesOf(relative)): String {
        val path: String = p(relative)
        assertTrue(fs.writeAtomically(path, content, WriteOptions(durability = Durability.Buffered, createParentDirectories = true)).isSuccess)
        return path
    }

    private fun assertOk(result: FileResult<*>, message: String = "") {
        assertTrue(result.isSuccess, "$message expected success but was $result")
    }

    private inline fun <reified E : FileError> failure(result: FileResult<*>): E {
        val error: FileError? = result.errorOrNull()
        assertTrue(error is E, "expected ${E::class.simpleName} but was $result")
        return error
    }

    private fun names(directory: String): List<String> = assertNotNull(fs.list(directory).getOrNull(), "list $directory")

    private fun leftovers(directory: String, prefix: String = FileAccessConfig.DEFAULT_TEMP_PREFIX): List<String> =
        names(directory).filter { FileNames.isTempName(it, prefix) }

    private fun text(path: String): String = assertNotNull(fs.readBytes(path, 1L shl 20).getOrNull(), "read $path").decodeToString()

    private fun realTime(seconds: Int = 120, body: suspend CoroutineScope.() -> Unit): TestResult =
        runTest(timeout = seconds.seconds) {
            withContext(Dispatchers.Default) { withTimeout(seconds * 1000L - 5000L) { body() } }
        }

    private fun symlinkOrSkip(link: String, target: String) {
        if (!TestEnv.createSymbolicLink(link, target)) TestEnv.skip("the host cannot create symbolic links")
    }

    private val windowsHost: Boolean get() = TestHost.pathSyntax == PathSyntax.WINDOWS

    // ---- metadata, exists, list, createDirectories ------------------------------------------------

    @Test
    fun `metadata reports kind size and a modification time`() {
        val file: String = put("f", ByteArray(3))
        fs.createDirectories(p("d"))
        val meta: FileMetadata = assertNotNull(fs.metadata(file))
        assertEquals(FileKind.RegularFile, meta.kind)
        assertEquals(3L, meta.sizeBytes)
        assertNotNull(meta.lastModifiedMillis)
        val dir: FileMetadata = assertNotNull(fs.metadata(p("d")))
        assertEquals(FileKind.Directory, dir.kind)
        assertEquals(0L, dir.sizeBytes)
        assertNull(fs.metadata(p("missing")))
    }

    @Test
    fun `metadata of an invalid path is null`() {
        for (path: String in listOf("", "relative/x", "x", "a\u0000b")) {
            assertNull(fs.metadata(path), "'$path'")
            assertFalse(fs.exists(path), "'$path'")
            assertNull(fs.freeSpaceBytes(path), "'$path'")
        }
    }

    @Test
    fun `a symbolic link is reported as a link unless followed and a dangling link is null when followed`() {
        val target: String = put("target", ByteArray(5))
        symlinkOrSkip(p("link"), target)
        symlinkOrSkip(p("dangling"), p("nowhere"))
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("link"))?.kind)
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("link"), followLinks = false)?.kind)
        val followed: FileMetadata = assertNotNull(fs.metadata(p("link"), followLinks = true))
        assertEquals(FileKind.RegularFile, followed.kind)
        assertEquals(5L, followed.sizeBytes)
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("dangling"))?.kind)
        assertNull(fs.metadata(p("dangling"), followLinks = true))
        assertTrue(fs.exists(p("link")))
        assertFalse(fs.exists(p("dangling")), "exists follows links")
    }

    @Test
    fun `list is sorted without dot entries and an absent directory is empty`() {
        for (name: String in listOf("b", "a", "C", "a1", "_x")) put("d/$name")
        assertEquals(listOf("C", "_x", "a", "a1", "b").sorted(), names(p("d")))
        assertEquals(emptyList(), names(p("absent")))
        fs.createDirectories(p("empty"))
        assertEquals(emptyList(), names(p("empty")))
    }

    @Test
    fun `list of a file is NotADirectory and of an invalid path is InvalidPath`() {
        val file: String = put("f")
        assertEquals(FileError.NotADirectory(file), failure<FileError.NotADirectory>(fs.list(file)))
        failure<FileError.InvalidPath>(fs.list("relative"))
    }

    @Test
    fun `createDirectories creates every level and is idempotent`() {
        val deep: String = p("a/b/c")
        assertOk(fs.createDirectories(deep))
        assertEquals(FileKind.Directory, fs.metadata(deep)?.kind)
        assertOk(fs.createDirectories(deep))
        assertOk(fs.createDirectories(root))
    }

    @Test
    fun `createDirectories over a file or below a file is NotADirectory`() {
        val file: String = put("f")
        failure<FileError.NotADirectory>(fs.createDirectories(file))
        failure<FileError.NotADirectory>(fs.createDirectories(TestPaths.join(file, "sub/deeper")))
        assertNull(fs.metadata(TestPaths.join(file, "sub")))
    }

    @Test
    fun `concurrent creators of one directory all succeed`() = realTime {
        repeat(20) { round ->
            val target: String = p("race$round/x/y/z")
            val results: List<FileResult<Unit>> = List(8) { async { fs.createDirectories(target) } }.awaitAll()
            for (result: FileResult<Unit> in results) assertOk(result, "round $round")
            assertEquals(FileKind.Directory, fs.metadata(target)?.kind)
        }
    }

    // ---- readBytes --------------------------------------------------------------------------------

    @Test
    fun `readBytes returns the whole content of a file`() {
        val path: String = put("f", byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), fs.readBytes(path, 3).getOrNull())
        assertContentEquals(byteArrayOf(1, 2, 3), fs.readBytes(path, 1000).getOrNull())
        put("empty", ByteArray(0))
        assertContentEquals(ByteArray(0), fs.readBytes(p("empty"), 0).getOrNull())
    }

    @Test
    fun `readBytes of an absent file is NotFound and of a directory IsADirectory`() {
        assertEquals(FileError.NotFound(p("absent")), failure<FileError.NotFound>(fs.readBytes(p("absent"), 10)))
        fs.createDirectories(p("d"))
        assertEquals(FileError.IsADirectory(p("d")), failure<FileError.IsADirectory>(fs.readBytes(p("d"), 10)))
        failure<FileError.InvalidPath>(fs.readBytes("relative", 10))
    }

    @Test
    fun `a file larger than maxBytes is TooLarge and is never returned in part`() {
        val path: String = put("f", ByteArray(100) { 7 })
        val tooLarge: FileError.TooLarge = failure(fs.readBytes(path, 99))
        assertEquals(100L, tooLarge.sizeBytes)
        assertEquals(99L, tooLarge.maxBytes)
        assertEquals(path, tooLarge.path)
        failure<FileError.TooLarge>(fs.readBytes(path, 0))
        assertEquals(100, fs.readBytes(path, 100).getOrNull()?.size)
    }

    @Test
    fun `readBytes rejects a negative or oversized maxBytes`() {
        val path: String = put("f")
        assertFailsWith<IllegalArgumentException> { fs.readBytes(path, -1) }
        assertFailsWith<IllegalArgumentException> { fs.readBytes(path, FileAccess.MAX_READ_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { fs.readBytes("relative", Long.MAX_VALUE) }
        assertEquals(Int.MAX_VALUE - 16L, FileAccess.MAX_READ_BYTES)
        assertEquals(1, fs.readBytes(path, FileAccess.MAX_READ_BYTES).getOrNull()?.size)
    }

    // ---- writeAtomically --------------------------------------------------------------------------

    @Test
    fun `writeAtomically replaces the content and leaves no temporary file`() {
        val path: String = p("f")
        assertOk(fs.writeAtomically(path, bytesOf("one")))
        assertOk(fs.writeAtomically(path, bytesOf("two")))
        assertEquals("two", text(path))
        assertEquals(listOf("f"), names(root))
    }

    @Test
    fun `writeAtomically writes an empty array and a 16 MiB payload`() {
        val path: String = p("f")
        assertOk(fs.writeAtomically(path, ByteArray(0)))
        assertEquals(0L, fs.metadata(path)?.sizeBytes)
        val big: ByteArray = ByteArray(16 shl 20) { (it * 31 + (it ushr 8)).toByte() }
        assertOk(fs.writeAtomically(path, big, WriteOptions(durability = Durability.Buffered)))
        assertContentEquals(big, fs.readBytes(path, big.size.toLong()).getOrNull())
        failure<FileError.TooLarge>(fs.readBytes(path, big.size - 1L))
    }

    @Test
    fun `every durability writes the same content`() {
        for (durability: Durability in Durability.entries) {
            val path: String = p("f-$durability")
            assertOk(fs.writeAtomically(path, bytesOf(durability.name), WriteOptions(durability = durability)), "$durability")
            assertEquals(durability.name, text(path))
        }
        assertTrue(leftovers(root).isEmpty())
    }

    @Test
    fun `writeAtomically needs the parent unless asked to create it`() {
        val path: String = p("missing/f")
        assertEquals(p("missing"), failure<FileError.NotFound>(fs.writeAtomically(path, ByteArray(1))).path, "names the directory, never a temp file")
        assertNull(fs.metadata(p("missing")))
        assertOk(fs.writeAtomically(path, ByteArray(1), WriteOptions(createParentDirectories = true)))
        assertEquals(1L, fs.metadata(path)?.sizeBytes)
    }

    @Test
    fun `writeAtomically over a directory is IsADirectory and changes nothing`() {
        fs.createDirectories(p("d"))
        put("d/inner")
        failure<FileError.IsADirectory>(fs.writeAtomically(p("d"), ByteArray(1)))
        assertEquals(listOf("inner"), names(p("d")))
        assertEquals(listOf("d"), names(root))
    }

    @Test
    fun `ExistingFile Fail on an existing target is AlreadyExists with the target and the directory untouched`() {
        val path: String = put("f", bytesOf("keep"))
        val error: FileError.AlreadyExists = failure(fs.writeAtomically(path, bytesOf("new"), WriteOptions(existing = ExistingFile.Fail)))
        assertEquals(path, error.path)
        assertEquals("keep", text(path))
        assertEquals(listOf("f"), names(root))
        assertOk(fs.writeAtomically(p("fresh"), bytesOf("x"), WriteOptions(existing = ExistingFile.Fail)))
        assertEquals("x", text(p("fresh")))
        assertEquals(listOf("f", "fresh"), names(root))
    }

    @Test
    fun `ExistingFile Fail leaves an existing directory and a dangling link alone`() {
        fs.createDirectories(p("d"))
        failure<FileError.IsADirectory>(fs.writeAtomically(p("d"), ByteArray(1), WriteOptions(existing = ExistingFile.Fail)))
        symlinkOrSkip(p("dangling"), p("nowhere"))
        failure<FileError.AlreadyExists>(fs.writeAtomically(p("dangling"), ByteArray(1), WriteOptions(existing = ExistingFile.Fail)))
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("dangling"))?.kind)
    }

    @Test
    fun `a symbolic link at the path is replaced by a regular file and its target is untouched`() {
        val target: String = put("target", bytesOf("target-bytes"))
        symlinkOrSkip(p("link"), target)
        assertOk(fs.writeAtomically(p("link"), bytesOf("through-link")))
        assertEquals(FileKind.RegularFile, fs.metadata(p("link"))?.kind)
        assertEquals("through-link", text(p("link")))
        assertEquals("target-bytes", text(target))
    }

    @Test
    fun `a temp directory on the same volume receives the temporary file and ends clean`() {
        fs.createDirectories(p("tmp"))
        val path: String = p("out/f")
        assertOk(fs.writeAtomically(path, bytesOf("v"), WriteOptions(createParentDirectories = true, tempDirectory = p("tmp"))))
        assertEquals("v", text(path))
        assertEquals(emptyList(), names(p("tmp")))
        assertEquals(listOf("f"), names(p("out")))
        assertOk(fs.writeAtomically(p("out/g"), bytesOf("w"), WriteOptions(tempDirectory = p("tmp-created"), createParentDirectories = true)))
        assertEquals(emptyList(), names(p("tmp-created")))
    }

    @Test
    fun `an invalid temp directory is InvalidPath`() {
        failure<FileError.InvalidPath>(fs.writeAtomically(p("f"), ByteArray(1), WriteOptions(tempDirectory = "relative")))
        assertNull(fs.metadata(p("f")))
    }

    @Test
    fun `writeAtomically rejects relative empty and NUL paths`() {
        for (path: String in listOf("", "f", "dir/f", "a\u0000b")) {
            assertEquals(FileError.InvalidPath(path), failure<FileError.InvalidPath>(fs.writeAtomically(path, ByteArray(1))))
        }
    }

    @Test
    fun `a name over 255 bytes is NameTooLong and leaves no temporary file`() {
        val error: FileError.NameTooLong = failure(fs.writeAtomically(p("n".repeat(256)), ByteArray(1)))
        assertEquals(p("n".repeat(256)), error.path, "the caller's path, not the temp file")
        assertEquals(emptyList(), names(root))
    }

    @Test
    fun `a 200 character CJK name is accepted on Windows and APFS and NameTooLong on a Linux host where names count UTF-8 bytes`() {
        val name: String = "\u4e2d".repeat(200) // 200 UTF-16 units, 600 UTF-8 bytes
        val result: FileResult<Unit> = fs.writeAtomically(p(name), bytesOf("cjk"))
        if (windowsHost) {
            assertOk(result)
            assertEquals("cjk", text(p(name)))
        } else if (TestEnv.isLinuxHost) {
            // ext4, XFS, Btrfs, tmpfs and F2FS all enforce 255 UTF-8 bytes: pin the byte rule end to end.
            assertEquals(p(name), failure<FileError.NameTooLong>(result).path)
            assertEquals(emptyList(), names(root))
        } else if (result.isSuccess) {
            // APFS (macOS, iOS) counts 255 characters, not bytes, so it accepts this name; ext4 and F2FS do not.
            assertEquals("cjk", text(p(name)))
        } else {
            assertEquals(p(name), failure<FileError.NameTooLong>(result).path)
            assertEquals(emptyList(), names(root))
        }
    }

    @Test
    fun `a write into an unwritable directory is AccessDenied and leaves nothing behind`() {
        fs.createDirectories(p("locked"))
        val restore: AutoCloseable = TestEnv.makeUnwritable(p("locked")) ?: TestEnv.skip("the host does not enforce directory permissions here")
        try {
            assertEquals(p("locked"), failure<FileError.AccessDenied>(fs.writeAtomically(p("locked/f"), ByteArray(1))).path)
        } finally {
            restore.close()
        }
        assertEquals(emptyList(), names(p("locked")))
    }

    @Test
    fun `no temporary file is left after a thousand writes and temp names match isTempName`() {
        val path: String = p("f")
        repeat(1000) { assertOk(fs.writeAtomically(path, bytesOf("v$it"), WriteOptions(durability = Durability.Buffered))) }
        repeat(20) { assertOk(fs.writeAtomically(path, bytesOf("s$it"))) }
        assertEquals(listOf("f"), names(root))
        assertEquals("s19", text(path))
    }

    @Test
    fun `concurrent writers of one path and readers see only whole payloads`() = realTime {
        val target: String = p("shared")
        val size: Int = 4096
        val writtenOnce: AtomicInt = AtomicInt(0)
        val writersLeft: AtomicInt = AtomicInt(8)
        val writers: List<Deferred<Unit>> = List(8) { writer ->
            async {
                val payload: ByteArray = ByteArray(size) { (writer + 1).toByte() }
                try {
                    repeat(200) {
                        assertOk(fs.writeAtomically(target, payload, WriteOptions(durability = Durability.Buffered)), "writer $writer")
                        writtenOnce.store(1)
                    }
                } finally {
                    // A failing writer must still release the readers, or they would spin forever.
                    writersLeft.addAndFetch(-1)
                }
            }
        }
        val readers: List<Deferred<Int>> = List(4) {
            async {
                var reads: Int = 0
                while (writersLeft.load() > 0) {
                    val wasWritten: Boolean = writtenOnce.load() == 1
                    val result: FileResult<ByteArray> = fs.readBytes(target, 1L shl 20)
                    when (result) {
                        is FileResult.Success -> {
                            assertEquals(size, result.value.size, "a torn read")
                            val first: Byte = result.value[0]
                            assertTrue(first in 1..8, "foreign content")
                            assertTrue(result.value.all { it == first }, "a mixed payload")
                        }
                        is FileResult.Failure -> {
                            assertTrue(result.error is FileError.NotFound && !wasWritten, "unexpected $result")
                        }
                    }
                    reads++
                    // A suspension point, so cancellation and the timeout can stop a reader.
                    yield()
                }
                reads
            }
        }
        writers.awaitAll()
        readers.awaitAll()
        assertEquals(listOf("shared"), names(root))
    }

    // ---- move -------------------------------------------------------------------------------------

    @Test
    fun `move renames a file within a volume and replaces a file at the destination`() {
        val from: String = put("a", bytesOf("A"))
        assertOk(fs.move(from, p("b")))
        assertNull(fs.metadata(from))
        assertEquals("A", text(p("b")))
        put("c", bytesOf("C"))
        assertOk(fs.move(p("c"), p("b")))
        assertEquals("C", text(p("b")))
        assertEquals(listOf("b"), names(root))
    }

    @Test
    fun `move with Fail refuses an existing destination and keeps both`() {
        put("a", bytesOf("A"))
        put("b", bytesOf("B"))
        val error: FileError.AlreadyExists = failure(fs.move(p("a"), p("b"), MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(p("b"), error.path)
        assertEquals("A", text(p("a")))
        assertEquals("B", text(p("b")))
        assertOk(fs.move(p("a"), p("c"), MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(listOf("b", "c"), names(root))
        assertEquals("A", text(p("c")))
    }

    @Test
    fun `move of a missing source is NotFound and of a directory onto a directory is IsADirectory`() {
        assertEquals(FileError.NotFound(p("nope")), failure<FileError.NotFound>(fs.move(p("nope"), p("x"))))
        put("f")
        fs.createDirectories(p("d"))
        assertEquals(FileError.IsADirectory(p("d")), failure<FileError.IsADirectory>(fs.move(p("f"), p("d"))))
        fs.createDirectories(p("e"))
        failure<FileError.IsADirectory>(fs.move(p("e"), p("d")))
        assertEquals(FileKind.RegularFile, fs.metadata(p("f"))?.kind)
    }

    @Test
    fun `move creates the destination parent on request and needs it otherwise`() {
        put("f", bytesOf("F"))
        failure<FileError.NotFound>(fs.move(p("f"), p("new/dir/f")))
        assertEquals("F", text(p("f")))
        assertOk(fs.move(p("f"), p("new/dir/f"), MoveOptions(createParentDirectories = true)))
        assertEquals("F", text(p("new/dir/f")))
    }

    @Test
    fun `move moves a directory and a link as themselves`() {
        put("src/inner", bytesOf("I"))
        assertOk(fs.move(p("src"), p("dst")))
        assertEquals("I", text(p("dst/inner")))
        val target: String = put("target", bytesOf("T"))
        symlinkOrSkip(p("link"), target)
        assertOk(fs.move(p("link"), p("link2")))
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("link2"))?.kind)
        assertEquals("T", text(target))
    }

    @Test
    fun `move of a symbolic link with ExistingFile Fail is Unsupported and changes nothing`() {
        val target: String = put("target", bytesOf("T"))
        symlinkOrSkip(p("link"), target)
        symlinkOrSkip(p("dangling"), p("nowhere"))
        failure<FileError.Unsupported>(fs.move(p("link"), p("new"), MoveOptions(existing = ExistingFile.Fail)))
        failure<FileError.Unsupported>(fs.move(p("dangling"), p("new2"), MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("link"))?.kind)
        assertEquals(FileKind.SymbolicLink, fs.metadata(p("dangling"))?.kind)
        assertNull(fs.metadata(p("new")))
        assertNull(fs.metadata(p("new2")))
        assertEquals("T", text(target))
    }

    @Test
    fun `move of a directory with ExistingFile Fail is Unsupported and changes nothing`() {
        fs.createDirectories(p("d"))
        failure<FileError.Unsupported>(fs.move(p("d"), p("e"), MoveOptions(existing = ExistingFile.Fail)))
        assertEquals(listOf("d"), names(root))
    }

    @Test
    fun `two racing moves with Fail to one target have exactly one winner`() = realTime {
        repeat(40) { round ->
            val target: String = p("target$round")
            put("one$round", bytesOf("one"))
            put("two$round", bytesOf("two"))
            val first: Deferred<FileResult<Unit>> = async { fs.move(p("one$round"), target, MoveOptions(existing = ExistingFile.Fail)) }
            val second: Deferred<FileResult<Unit>> = async { fs.move(p("two$round"), target, MoveOptions(existing = ExistingFile.Fail)) }
            val a: FileResult<Unit> = first.await()
            val b: FileResult<Unit> = second.await()
            assertEquals(1, listOf(a, b).count { it.isSuccess }, "round $round: $a / $b")
            val loser: FileResult<Unit> = if (a.isSuccess) b else a
            assertTrue(loser.errorOrNull() is FileError.AlreadyExists, "round $round: $loser")
            assertEquals(if (a.isSuccess) "one" else "two", text(target), "the winner's bytes are in place")
        }
    }

    // ---- delete, deleteRecursively, treeSize ------------------------------------------------------

    @Test
    fun `delete removes a file an empty directory and a link but never a link target`() {
        val file: String = put("f")
        assertOk(fs.delete(file))
        assertNull(fs.metadata(file))
        fs.createDirectories(p("d"))
        assertOk(fs.delete(p("d")))
        val target: String = put("target", bytesOf("T"))
        symlinkOrSkip(p("link"), target)
        assertOk(fs.delete(p("link")))
        assertNull(fs.metadata(p("link")))
        assertEquals("T", text(target))
    }

    @Test
    fun `delete of an absent path is Success and of a non-empty directory DirectoryNotEmpty`() {
        assertOk(fs.delete(p("absent")))
        put("d/inner")
        assertEquals(FileError.DirectoryNotEmpty(p("d")), failure<FileError.DirectoryNotEmpty>(fs.delete(p("d"))))
        assertEquals(listOf("inner"), names(p("d")))
        failure<FileError.InvalidPath>(fs.delete("relative"))
    }

    private fun buildTree(base: String, directories: Int, filesPerDirectory: Int) {
        for (d: Int in 0 until directories) {
            for (f: Int in 0 until filesPerDirectory) put("$base-tree/d$d/f$f", ByteArray(f + 1))
        }
    }

    @Test
    fun `deleteRecursively removes a 2000 entry tree without following a link to the outside`() {
        put("outside/keep", bytesOf("keep"))
        buildTree("x", 40, 50)
        symlinkOrSkip(p("x-tree/d0/escape"), p("outside"))
        assertOk(fs.deleteRecursively(p("x-tree")))
        assertNull(fs.metadata(p("x-tree")))
        assertEquals("keep", text(p("outside/keep")))
    }

    @Test
    fun `deleteRecursively of a root that is a link to a directory removes only the link`() {
        put("real/inner", bytesOf("I"))
        symlinkOrSkip(p("rootlink"), p("real"))
        assertOk(fs.deleteRecursively(p("rootlink")))
        assertNull(fs.metadata(p("rootlink")))
        assertEquals("I", text(p("real/inner")))
    }

    @Test
    fun `deleteRecursively of an absent path is Success and of a plain file removes it`() {
        assertOk(fs.deleteRecursively(p("absent")))
        val file: String = put("f")
        assertOk(fs.deleteRecursively(file))
        assertNull(fs.metadata(file))
        failure<FileError.InvalidPath>(fs.deleteRecursively("relative"))
    }

    @Test
    fun `two threads deleting one tree both succeed`() = realTime {
        repeat(6) { round ->
            buildTree("r$round", 10, 20)
            val tree: String = p("r$round-tree")
            val results: List<FileResult<Unit>> = List(2) { async { fs.deleteRecursively(tree) } }.awaitAll()
            for (result: FileResult<Unit> in results) assertOk(result, "round $round")
            assertNull(fs.metadata(tree))
        }
    }

    @Test
    fun `a very deep tree is deleted without a stack overflow`() {
        var current: String = p("deep")
        var depth: Int = 0
        assertOk(fs.createDirectories(current))
        while (depth < 2000) {
            val next: String = TestPaths.join(current, "d")
            if (fs.createDirectories(next).errorOrNull() != null) break
            current = next
            depth++
        }
        assertTrue(depth >= 100, "the host allows at least 100 levels, got $depth")
        put("deep/top")
        assertOk(fs.deleteRecursively(p("deep")))
        assertNull(fs.metadata(p("deep")))
    }

    @Test
    fun `deleteRecursively stops with Cancelled and a rerun finishes the job`() {
        buildTree("c", 5, 10)
        var checks: Int = 0
        val result: FileResult<Unit> = fs.deleteRecursively(p("c-tree")) { ++checks > 8 }
        assertEquals(FileError.Cancelled, result.errorOrNull())
        assertNotNull(fs.metadata(p("c-tree")), "the rest stays in place")
        assertOk(fs.deleteRecursively(p("c-tree")))
        assertNull(fs.metadata(p("c-tree")))
    }

    @Test
    fun `treeSize sums regular files and counts an absent path as zero`() {
        put("t/a", ByteArray(10))
        put("t/sub/b", ByteArray(5))
        put("t/sub/deeper/c", ByteArray(1))
        assertEquals(16L, fs.treeSize(p("t")).getOrNull())
        assertEquals(10L, fs.treeSize(p("t/a")).getOrNull())
        assertEquals(0L, fs.treeSize(p("absent")).getOrNull())
        failure<FileError.InvalidPath>(fs.treeSize("relative"))
    }

    @Test
    fun `treeSize terminates on a link cycle and counts links as zero`() {
        put("t/f", ByteArray(10))
        put("outside/big", ByteArray(1000))
        symlinkOrSkip(p("t/loop"), p("t"))
        symlinkOrSkip(p("t/out"), p("outside"))
        symlinkOrSkip(p("t/file-link"), p("outside/big"))
        assertEquals(10L, fs.treeSize(p("t")).getOrNull())
    }

    @Test
    fun `treeSize stops with Cancelled`() {
        put("t/a", ByteArray(1))
        assertEquals(FileError.Cancelled, fs.treeSize(p("t")) { true }.errorOrNull())
    }

    // ---- sweep ------------------------------------------------------------------------------------

    @Test
    fun `sweep deletes matching old files and keeps the rest`() {
        val realNow: Long = nowMillis()
        put("d/old.tmp"); put("d/old.keep"); put("d/new.tmp"); put("d/future.tmp"); fs.createDirectories(p("d/dir.tmp"))
        assertTrue(TestEnv.setLastModified(p("d/old.tmp"), realNow - 60_000))
        assertTrue(TestEnv.setLastModified(p("d/old.keep"), realNow - 60_000))
        assertTrue(TestEnv.setLastModified(p("d/new.tmp"), realNow - 1_000))
        assertTrue(TestEnv.setLastModified(p("d/future.tmp"), realNow + 3_600_000))
        assertTrue(TestEnv.setLastModified(p("d/dir.tmp"), realNow - 60_000))
        assertEquals(1, fs.sweep(p("d"), 30_000) { it.endsWith(".tmp") }.getOrNull())
        assertEquals(listOf("dir.tmp", "future.tmp", "new.tmp", "old.keep"), names(p("d")))
    }

    private fun nowMillis(): Long = TestEnv.realOps().nowMillis()

    @Test
    fun `sweep of an absent directory is zero and of a file NotADirectory`() {
        assertEquals(0, fs.sweep(p("absent"), 0) { true }.getOrNull())
        val file: String = put("f")
        failure<FileError.NotADirectory>(fs.sweep(file, 0) { true })
        failure<FileError.InvalidPath>(fs.sweep("relative", 0) { true })
    }

    @Test
    fun `sweep rejects a negative age and lets the match exception propagate`() {
        put("d/a")
        assertFailsWith<IllegalArgumentException> { fs.sweep(p("d"), -1) { true } }
        assertFailsWith<IllegalStateException> { fs.sweep(p("d"), 0) { error("from match") } }
        assertEquals(listOf("a"), names(p("d")))
    }

    @Test
    fun `sweep stops with Cancelled before touching an entry`() {
        put("d/a")
        assertTrue(TestEnv.setLastModified(p("d/a"), nowMillis() - 60_000))
        assertEquals(FileError.Cancelled, fs.sweep(p("d"), 0, { true }) { true }.errorOrNull())
        assertEquals(listOf("a"), names(p("d")))
    }

    // ---- free space, resources --------------------------------------------------------------------

    @Test
    fun `freeSpaceBytes of a missing nested path is that of its nearest existing ancestor`() {
        val existing: Long = assertNotNull(fs.freeSpaceBytes(root))
        assertTrue(existing > 0L)
        val nested: Long = assertNotNull(fs.freeSpaceBytes(p("not/yet/created")))
        assertTrue(kotlin.math.abs(existing - nested) < (256L shl 20), "same volume: $existing vs $nested")
        assertNull(fs.metadata(p("not")))
    }

    @Test
    fun `five thousand write read delete cycles succeed and do not leak descriptors`() {
        val before: Long? = TestEnv.openDescriptorCount()
        val path: String = p("cycle")
        repeat(5000) {
            assertOk(fs.writeAtomically(path, bytesOf("cycle-$it"), WriteOptions(durability = Durability.Buffered)))
            assertEquals("cycle-$it", text(path))
            assertOk(fs.delete(path))
        }
        val after: Long? = TestEnv.openDescriptorCount()
        if (before != null && after != null) assertTrue(after - before <= 5, "descriptors went from $before to $after")
    }

    @Test
    fun `windows reserved names and trailing dots are InvalidPath on a Windows host`() {
        // iOS tests cannot be skipped; the same rules run on every host in ScriptedOpsEngineTest.
        if (!windowsHost) return
        for (name: String in listOf("CON", "nul.txt", "a.", "b ", "CON .txt")) {
            assertTrue(fs.writeAtomically(p(name), ByteArray(1)).errorOrNull() is FileError.InvalidPath, name)
            assertNull(fs.metadata(p(name)), name)
        }
    }

    @Test
    fun `every function reports a relative empty or NUL path as InvalidPath`() {
        for (path: String in listOf("", "rel", "a\u0000b")) {
            assertTrue(fs.list(path).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.createDirectories(path).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.readBytes(path, 1).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.move(path, p("x")).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.move(p("x"), path).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.delete(path).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.deleteRecursively(path).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.treeSize(path).errorOrNull() is FileError.InvalidPath)
            assertTrue(fs.sweep(path, 0) { true }.errorOrNull() is FileError.InvalidPath)
        }
    }

    @Test
    fun `a write with a 232 byte ASCII temp prefix succeeds and leaves no temporary file`() {
        val prefix: String = "t".repeat(232)
        assertTrue(fs.writeAtomically(p("rec"), bytesOf("v"), WriteOptions(createParentDirectories = true, tempPrefix = prefix)).isSuccess)
        assertContentEquals(bytesOf("v"), fs.readBytes(p("rec"), 16).getOrNull())
        assertEquals(listOf("rec"), fs.list(root).getOrNull())
    }

    @Test
    fun `a write with a 232 byte multi-byte temp prefix succeeds and leaves no temporary file`() {
        val prefix: String = "\u00e9".repeat(116)
        assertEquals(232, prefix.encodeToByteArray().size)
        assertTrue(fs.writeAtomically(p("rec"), bytesOf("v"), WriteOptions(createParentDirectories = true, tempPrefix = prefix)).isSuccess)
        assertContentEquals(bytesOf("v"), fs.readBytes(p("rec"), 16).getOrNull())
        assertEquals(listOf("rec"), fs.list(root).getOrNull())
    }

    @Test
    fun `isTempName accepts a 232 byte prefix with a 19 digit temp name`() {
        val prefix: String = "t".repeat(232)
        val name: String = prefix + "1234567890123456789.tmp"
        assertEquals(255, name.encodeToByteArray().size)
        assertTrue(FileNames.isTempName(name, prefix))
    }
}
