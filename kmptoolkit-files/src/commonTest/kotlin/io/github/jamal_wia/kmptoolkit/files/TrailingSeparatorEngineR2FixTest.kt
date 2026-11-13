@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.FileOpException
import io.github.jamal_wia.kmptoolkit.files.spi.FileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.files.spi.WritableFile
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the scripted ops below have a syntax of their own, never the host's

/**
 * G12 (round 2 of unit G): the engine drops trailing separators before any platform call, on every platform, so no
 * ops ever sees one and an error names the path without it (docs 04, "Paths are passed to the platform as given, with
 * one exception"). Scripted ops record every path argument; they run on any host.
 */
class TrailingSeparatorEngineR2FixTest {

    /** Ops over a map of existing entries; records every path it is handed, in any role. */
    private class RecordingOps(override val windowsPaths: Boolean, val entries: Map<String, FileMetadata>) : FileSystemOps {
        val seen: MutableList<String> = mutableListOf()
        private fun note(vararg paths: String) { seen += paths }

        override fun nowMillis(): Long = 1_000_000L
        override fun metadata(path: String, followLinks: Boolean): FileMetadata? { note(path); return entries[path] }
        override fun list(directory: String): List<String>? { note(directory); return if (entries[directory]?.kind == FileKind.Directory) listOf("child") else null }
        override fun createDirectory(path: String) { note(path) }
        override fun createNew(path: String, protection: FileProtection): WritableFile {
            note(path)
            return object : WritableFile {
                override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
                override fun sync(full: Boolean) = Unit
                override fun close() = Unit
            }
        }
        override fun openForRead(path: String): ReadableFile {
            note(path)
            return object : ReadableFile {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int = -1
                override fun close() = Unit
            }
        }
        override fun rename(from: String, to: String) { note(from, to) }
        override fun link(existing: String, newPath: String) { note(existing, newPath) }
        override fun remove(path: String) { note(path) }
        override fun syncDirectory(directory: String, full: Boolean) { note(directory) }
        override fun usableSpace(path: String): Long? { note(path); return 7L }
    }

    private val file: FileMetadata = FileMetadata(FileKind.RegularFile, 1L, 0L)
    private val directory: FileMetadata = FileMetadata(FileKind.Directory, 0L, 0L)

    private fun isRoot(path: String): Boolean = path == "/" || (path.length == 3 && path[1] == ':')

    private fun assertNoTrailingSeparator(ops: RecordingOps, label: String) {
        for (path: String in ops.seen) {
            assertTrue(isRoot(path) || !(path.endsWith("/") || path.endsWith("\\")), "$label: ops saw '$path'")
        }
    }

    private class Syntax(val windows: Boolean, val target: String, val spellings: List<String>, val tempDirectory: String, val sibling: String)

    private val syntaxes: List<Syntax> = listOf(
        Syntax(false, "/a/b", listOf("/a/b/", "/a/b///"), "/t/", "/a/c/"),
        Syntax(true, "C:\\a\\b", listOf("C:\\a\\b\\", "C:/a/b//"), "C:\\t\\", "C:\\a\\c\\"),
    )

    private fun runAll(syntax: Syntax, present: Boolean) {
        for (spelling: String in syntax.spellings) {
            val entries: Map<String, FileMetadata> = if (present) mapOf(syntax.target to file) else emptyMap()
            val parent: String = syntax.target.substringBeforeLast(if (syntax.windows) '\\' else '/')
            val tree: Map<String, FileMetadata> = entries + mapOf(parent to directory)
            val calls: List<Pair<String, (FileAccess) -> Unit>> = listOf(
                "metadata" to { f: FileAccess -> f.metadata(spelling, false); f.metadata(spelling, true) },
                "exists" to { f: FileAccess -> f.exists(spelling) },
                "list" to { f: FileAccess -> f.list(spelling) },
                "freeSpaceBytes" to { f: FileAccess -> f.freeSpaceBytes(spelling) },
                "createDirectories" to { f: FileAccess -> f.createDirectories(spelling, true); f.createDirectories(spelling, false) },
                "readBytes" to { f: FileAccess -> f.readBytes(spelling, 1024L) },
                "writeAtomically" to { f: FileAccess ->
                    f.writeAtomically(spelling, byteArrayOf(1), WriteOptions(tempDirectory = syntax.tempDirectory))
                    f.writeAtomically(spelling, byteArrayOf(1), WriteOptions(existing = ExistingFile.Fail, durability = Durability.SyncedWithDirectory))
                },
                "move" to { f: FileAccess ->
                    f.move(spelling, syntax.sibling)
                    f.move(spelling, syntax.sibling, MoveOptions(existing = ExistingFile.Fail))
                },
                "delete" to { f: FileAccess -> f.delete(spelling) },
                "deleteRecursively" to { f: FileAccess -> f.deleteRecursively(spelling) },
                "treeSize" to { f: FileAccess -> f.treeSize(spelling) },
                "sweep" to { f: FileAccess -> f.sweep(spelling, 0L) { true } },
            )
            for ((name: String, call: (FileAccess) -> Unit) in calls) {
                val ops: RecordingOps = RecordingOps(syntax.windows, tree)
                call(fileAccessOver(ops))
                assertNoTrailingSeparator(ops, "$name('$spelling', present=$present)")
            }
        }
    }

    @Test
    fun `no ops ever sees a trailing separator for an absent entry on either syntax`() {
        for (syntax: Syntax in syntaxes) runAll(syntax, present = false)
    }

    @Test
    fun `no ops ever sees a trailing separator for an existing entry on either syntax`() {
        for (syntax: Syntax in syntaxes) runAll(syntax, present = true)
    }

    @Test
    fun `an error names the path without its trailing separators`() {
        val ops: RecordingOps = RecordingOps(false, mapOf("/a" to directory))
        val files: FileAccess = fileAccessOver(ops)
        assertEquals(FileResult.Failure(FileError.NotFound("/a/b")), files.readBytes("/a/b/", 1024L))
        assertEquals(FileResult.Failure(FileError.NotFound("/a/b")), files.move("/a/b/", "/a/c/"))
        assertEquals(FileResult.Failure(FileError.NotFound("/a/b")), files.move("/a/b///", "/a/c"))
    }

    @Test
    fun `a trailing separator of an invalid path is still named exactly as given`() {
        val files: FileAccess = fileAccessOver(RecordingOps(false, emptyMap()))
        assertEquals(FileResult.Failure(FileError.InvalidPath("rel/b/")), files.readBytes("rel/b/", 1024L))
        assertEquals(FileResult.Failure(FileError.InvalidPath("rel/")), files.list("rel/"))
    }

    @Test
    fun `a root keeps its own separator`() {
        val ops: RecordingOps = RecordingOps(false, mapOf("/" to directory))
        fileAccessOver(ops).list("//")
        assertEquals(listOf("/"), ops.seen)
    }

    @Test
    fun `a failing temp directory is named without its trailing separator`() {
        val ops: FileSystemOps = object : FileSystemOps by RecordingOps(false, mapOf("/a" to directory)) {
            override fun createNew(path: String, protection: FileProtection): WritableFile =
                throw FileOpException(FileError.NotFound(path))
        }
        val result: FileResult<Unit> = fileAccessOver(ops).writeAtomically("/a/b", byteArrayOf(1), WriteOptions(tempDirectory = "/t//"))
        assertEquals(FileResult.Failure(FileError.NotFound("/t")), result)
    }
}
