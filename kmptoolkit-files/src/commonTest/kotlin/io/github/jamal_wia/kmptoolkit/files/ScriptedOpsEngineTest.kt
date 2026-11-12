@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import io.github.jamal_wia.kmptoolkit.files.internal.FileEngine
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the ops below are a scripted fake with its own POSIX or Windows path syntax, never the host's

/** A scripted file system: entries by path, every other operation is a test failure. */
private class ScriptedOps(override val windowsPaths: Boolean = false, var now: Long = 0L) : FileSystemOps {
    val entries: MutableMap<String, FileMetadata> = mutableMapOf()
    val removed: MutableList<String> = mutableListOf()
    val touched: MutableList<String> = mutableListOf()
    val created: MutableList<String> = mutableListOf()
    var onList: ((String) -> List<String>?)? = null
    var onRemove: ((String) -> Unit)? = null
    var onOpen: ((String) -> ReadableFile)? = null

    override fun nowMillis(): Long = now

    override fun metadata(path: String, followLinks: Boolean): FileMetadata? {
        touched += path
        return entries[path]
    }

    override fun list(directory: String): List<String>? {
        touched += directory
        onList?.let { return it(directory) }
        val prefix: String = directory + "/"
        return entries.keys.filter { it.startsWith(prefix) && !it.substring(prefix.length).contains('/') }
            .map { it.substring(prefix.length) }
    }

    override fun createDirectory(path: String) = throw AssertionError("unexpected createDirectory $path")

    override fun createNew(path: String, protection: FileProtection): WritableFile {
        created += path
        throw FileOpException(FileError.AccessDenied(path))
    }

    override fun openForRead(path: String): ReadableFile =
        onOpen?.invoke(path) ?: throw AssertionError("unexpected openForRead $path")

    override fun rename(from: String, to: String) = throw AssertionError("unexpected rename $from")

    override fun link(existing: String, newPath: String) = throw AssertionError("unexpected link $newPath")

    override fun remove(path: String) {
        onRemove?.invoke(path)
        removed += path
        entries.remove(path)
    }

    override fun syncDirectory(directory: String, full: Boolean) = throw AssertionError("unexpected syncDirectory")

    override fun usableSpace(path: String): Long? = null
}

/** Rules that need no real file system: the engine over scripted ops. */
class ScriptedOpsEngineTest {

    private fun file(modified: Long?): FileMetadata = FileMetadata(FileKind.RegularFile, 1L, modified)

    @Test
    fun `sweep keeps an entry exactly olderThan old and deletes one a millisecond older`() {
        val ops: ScriptedOps = ScriptedOps(now = 1_000_000L)
        ops.entries["/d"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.entries["/d/exact"] = file(1_000_000L - 1000)
        ops.entries["/d/older"] = file(1_000_000L - 1001)
        ops.entries["/d/younger"] = file(1_000_000L - 999)
        ops.entries["/d/future"] = file(1_000_000L + 1)
        ops.entries["/d/unknown"] = file(null)
        ops.entries["/d/dir"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.entries["/d/link"] = FileMetadata(FileKind.SymbolicLink, 0L, 0L)
        ops.entries["/d/other"] = FileMetadata(FileKind.Other, 0L, 0L)
        val result: FileResult<Int> = FileEngine(ops, FileAccessConfig()).sweep("/d", 1000L) { true }
        assertEquals(2, result.getOrNull())
        assertEquals(setOf("/d/older", "/d/link"), ops.removed.toSet())
    }

    @Test
    fun `sweep age arithmetic saturates instead of overflowing`() {
        val ops: ScriptedOps = ScriptedOps(now = Long.MAX_VALUE)
        ops.entries["/d"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.entries["/d/ancient"] = file(-5L)
        assertEquals(1, FileEngine(ops, FileAccessConfig()).sweep("/d", 10L) { true }.getOrNull(), "MAX - (-5) saturates to MAX")
        val early: ScriptedOps = ScriptedOps(now = Long.MIN_VALUE + 1)
        early.entries["/d"] = FileMetadata(FileKind.Directory, 0L, 0L)
        early.entries["/d/far-future"] = file(Long.MAX_VALUE)
        assertEquals(0, FileEngine(early, FileAccessConfig()).sweep("/d", 0L) { true }.getOrNull(), "MIN - MAX saturates to MIN")
    }

    @Test
    fun `sweep only asks match for names and never calls it for a cancelled entry`() {
        val ops: ScriptedOps = ScriptedOps(now = 10L)
        ops.entries["/d"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.entries["/d/a"] = file(0L)
        ops.entries["/d/b"] = file(0L)
        val asked: MutableList<String> = mutableListOf()
        val result: FileResult<Int> = FileEngine(ops, FileAccessConfig()).sweep("/d", 0L, { asked.isNotEmpty() }) { asked += it; false }
        assertEquals(FileError.Cancelled, result.errorOrNull())
        assertEquals(1, asked.size)
    }

    @Test
    fun `windows syntax paths with reserved names or trailing dots never reach the file system`() {
        val ops: ScriptedOps = ScriptedOps(windowsPaths = true)
        val engine: FileAccess = FileEngine(ops, FileAccessConfig())
        for (path: String in listOf("C:\\d\\CON", "C:\\d\\nul.txt", "C:\\d\\a.", "C:\\d\\b ", "C:\\d\\CON .txt", "C:\\d\\com1 .log", "C:\\COM1\\x", "C:/d/LPT3.log", "\\\\srv\\share\\aux")) {
            val result: FileResult<Unit> = engine.writeAtomically(path, ByteArray(1))
            assertEquals(FileError.InvalidPath(path), result.errorOrNull(), path)
            assertEquals(null, engine.metadata(path), path)
        }
        assertTrue(ops.touched.isEmpty() && ops.created.isEmpty(), "the file system was touched: ${ops.touched}")
    }

    @Test
    fun `windows syntax accepts dot segments drive roots and shares and rejects drive-relative paths`() {
        val ops: ScriptedOps = ScriptedOps(windowsPaths = true)
        val engine: FileAccess = FileEngine(ops, FileAccessConfig())
        for (path: String in listOf("C:\\d\\ok.txt", "C:/d/./ok.txt", "C:\\d\\..\\ok.txt", "\\\\srv\\share\\ok.txt")) {
            assertTrue(engine.writeAtomically(path, ByteArray(1)).errorOrNull() is FileError.AccessDenied, path)
        }
        assertEquals(4, ops.created.size)
        for (path: String in listOf("C:ok.txt", "\\ok.txt", "ok.txt", "")) {
            assertEquals(FileError.InvalidPath(path), engine.writeAtomically(path, ByteArray(1)).errorOrNull(), "'$path'")
        }
        assertEquals(4, ops.created.size)
    }

    @Test
    fun `the engine joins with the separator of its ops not of the host`() {
        val windowsOps: ScriptedOps = ScriptedOps(windowsPaths = true)
        FileEngine(windowsOps, FileAccessConfig()).writeAtomically("C:\\data\\f", ByteArray(1))
        val windowsTemp: String = windowsOps.created.single()
        assertTrue(windowsTemp.startsWith("C:\\data\\" + FileAccessConfig.DEFAULT_TEMP_PREFIX), windowsTemp)
        assertTrue(FileNames.isTempName(windowsTemp.substring("C:\\data\\".length)), windowsTemp)
        val posixOps: ScriptedOps = ScriptedOps()
        FileEngine(posixOps, FileAccessConfig("tmp-")).writeAtomically("/data/f", ByteArray(1), WriteOptions(tempPrefix = "own-"))
        val posixTemp: String = posixOps.created.single()
        assertTrue(posixTemp.startsWith("/data/own-"), posixTemp)
        assertTrue(FileNames.isTempName(posixTemp.substring("/data/".length), "own-"), posixTemp)
    }

    private fun engine(ops: ScriptedOps): FileAccess = FileEngine(ops, FileAccessConfig())

    @Test
    fun `a device pipe or socket is Unsupported and is never opened`() {
        val ops: ScriptedOps = ScriptedOps()
        ops.entries["/d/fifo"] = FileMetadata(FileKind.Other, 0L, 0L)
        assertEquals(FileError.Unsupported("/d/fifo"), engine(ops).readBytes("/d/fifo", 10).errorOrNull())
    }

    @Test
    fun `a huge reported size is TooLarge without opening or allocating`() {
        val ops: ScriptedOps = ScriptedOps()
        ops.entries["/d/f"] = FileMetadata(FileKind.RegularFile, Long.MAX_VALUE, 0L)
        val error: FileError.TooLarge = engine(ops).readBytes("/d/f", 10).errorOrNull() as FileError.TooLarge
        assertEquals(Long.MAX_VALUE, error.sizeBytes)
        assertEquals(10L, error.maxBytes)
    }

    @Test
    fun `a file that grew after the size was read is TooLarge and reading stops at max plus one byte`() {
        val ops: ScriptedOps = ScriptedOps()
        ops.entries["/d/f"] = FileMetadata(FileKind.RegularFile, 1L, 0L)
        var delivered: Int = 0
        ops.onOpen = {
            object : ReadableFile {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    delivered += length
                    return length // an endless file
                }

                override fun close() = Unit
            }
        }
        val error: FileError.TooLarge = engine(ops).readBytes("/d/f", 10).errorOrNull() as FileError.TooLarge
        assertTrue(error.sizeBytes > 10L, "$error")
        assertEquals(10L, error.maxBytes)
        assertTrue(delivered <= 64, "reading went on far past max + 1: $delivered bytes")
    }

    @Test
    fun `treeSize counts a vanished entry and an unreadable directory as zero`() {
        val ops: ScriptedOps = ScriptedOps()
        ops.entries["/t"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.entries["/t/file"] = file(0L)
        ops.entries["/t/locked"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.onList = { directory ->
            when (directory) {
                "/t" -> listOf("file", "gone", "locked")
                else -> throw FileOpException(FileError.AccessDenied(directory))
            }
        }
        assertEquals(1L, engine(ops).treeSize("/t").getOrNull())
    }

    @Test
    fun `sweep neither counts nor reports an entry whose removal answers NotFound`() {
        val ops: ScriptedOps = ScriptedOps(now = 100L)
        ops.entries["/d"] = FileMetadata(FileKind.Directory, 0L, 0L)
        ops.entries["/d/a"] = file(0L)
        ops.entries["/d/b"] = file(0L)
        ops.onRemove = { path -> if (path == "/d/a") throw FileOpException(FileError.NotFound(path)) }
        assertEquals(1, engine(ops).sweep("/d", 10L) { true }.getOrNull())
    }

    @Test
    fun `the config prefix names the temp file when the options name none`() {
        val ops: ScriptedOps = ScriptedOps()
        FileEngine(ops, FileAccessConfig("cfg-")).writeAtomically("/data/f", ByteArray(1))
        assertTrue(ops.created.single().startsWith("/data/cfg-"), ops.created.single())
    }

    @Test
    fun `a non-FileOpException from a misbehaving ops becomes Io and never escapes`() {
        val ops: FileSystemOps = object : FileSystemOps by ScriptedOps() {
            override fun metadata(path: String, followLinks: Boolean): FileMetadata? = throw IllegalStateException("boom")

            override fun list(directory: String): List<String>? = throw IllegalStateException("boom")
        }
        val result: FileResult<List<String>> = FileEngine(ops, FileAccessConfig()).list("/d")
        assertTrue(result.errorOrNull() is FileError.Io, "$result")
        assertEquals(null, FileEngine(ops, FileAccessConfig()).metadata("/d"))
    }
}
