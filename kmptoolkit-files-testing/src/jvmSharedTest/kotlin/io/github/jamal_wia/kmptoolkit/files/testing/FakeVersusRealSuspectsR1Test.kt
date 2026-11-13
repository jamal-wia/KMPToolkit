package io.github.jamal_wia.kmptoolkit.files.testing

import io.github.jamal_wia.kmptoolkit.files.CancellationCheck
import io.github.jamal_wia.kmptoolkit.files.Durability
import io.github.jamal_wia.kmptoolkit.files.ExistingFile
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileKind
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.WriteOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import io.github.jamal_wia.kmptoolkit.files.getOrNull
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Round 1 of the breaker for unit G: the same scripted scenario runs through [FakeFileAccess] and through the real
 * file system of the host (a fresh temporary directory), and every observable is diffed: the typed result of every
 * call (kind of error, path with the root masked) and the final tree (names, kinds, bytes). The fake is published, so
 * anything it allows that the real file system forbids, or the reverse, misleads consumers.
 */
class FakeVersusRealSuspectsR1Test {

    private class World(
        val files: FileAccess,
        val root: String,
        val symlink: (String, String) -> Unit,
        val hardLink: (String, String) -> Unit,
        val cleanup: () -> Unit,
    )

    private val hostIsWindows: Boolean = File.separatorChar != '/'

    private fun hostIsCaseInsensitive(): Boolean {
        val dir: Path = Files.createTempDirectory("kmptoolkit-case-probe").toRealPath()
        try {
            Files.createFile(dir.resolve("Probe"))
            return Files.exists(dir.resolve("probe"))
        } finally {
            dir.toFile().deleteRecursively()
        }
    }

    private fun realWorld(): World {
        val dir: Path = Files.createTempDirectory("kmptoolkit-diff").toRealPath()
        return World(
            files = createFileAccess(),
            root = dir.toString(),
            symlink = { link: String, target: String -> Files.createSymbolicLink(File(link).toPath(), File(target).toPath()) },
            hardLink = { link: String, existing: String -> Files.createLink(File(link).toPath(), File(existing).toPath()) },
            cleanup = { dir.toFile().deleteRecursively() },
        )
    }

    private fun fakeWorld(caseSensitive: Boolean): World {
        val fake: FakeFileAccess = FakeFileAccess(caseSensitive = caseSensitive)
        fake.createDirectory("/r")
        return World(
            files = fake,
            root = "/r",
            symlink = { link: String, target: String -> fake.createSymbolicLink(link, target) },
            // A hard link pair, made the way the engine can: a no-replace move whose source removal fails.
            hardLink = { link: String, existing: String ->
                fake.failNext(FakeOperation.Remove, io.github.jamal_wia.kmptoolkit.files.FileError.AccessDenied(existing))
                val moved: FileResult<Unit> = fake.move(existing, link, MoveOptions(existing = ExistingFile.Fail))
                check(moved is FileResult.Success) { "could not model a hard link: $moved" }
            },
            cleanup = {},
        )
    }

    private fun render(world: World, value: Any?): String {
        val text: String = when (value) {
            is FileResult.Success<*> -> "OK(" + render(world, value.value) + ")"
            is FileResult.Failure -> {
                val error: io.github.jamal_wia.kmptoolkit.files.FileError = value.error
                // Only the kind and the masked path: the cause and the errno are platform detail.
                error::class.simpleName + "(" + (error.path ?: "-") + ")"
            }
            is io.github.jamal_wia.kmptoolkit.files.FileMetadata -> "meta:" + value.kind + ":" + value.sizeBytes
            is ByteArray -> "bytes:" + value.decodeToString()
            is List<*> -> value.joinToString(prefix = "[", postfix = "]") { render(world, it) }
            null -> "null"
            else -> value.toString()
        }
        return text.replace(world.root, "<R>")
    }

    private fun tree(world: World): List<String> {
        val lines: MutableList<String> = ArrayList()
        fun walk(directory: String, prefix: String) {
            val names: List<String> = world.files.list(directory).getOrNull() ?: return
            for (name: String in names) {
                val path: String = directory + "/" + name
                val meta: io.github.jamal_wia.kmptoolkit.files.FileMetadata? = world.files.metadata(path, false)
                when (meta?.kind) {
                    FileKind.RegularFile -> lines += "$prefix$name F ${world.files.readBytes(path, 1_000_000).getOrNull()?.decodeToString()}"
                    FileKind.Directory -> {
                        lines += "$prefix$name D"
                        walk(path, "$prefix$name/")
                    }
                    FileKind.SymbolicLink -> lines += "$prefix$name L"
                    FileKind.Other -> lines += "$prefix$name O"
                    null -> lines += "$prefix$name ?"
                }
            }
        }
        walk(world.root, "")
        return lines
    }

    private fun run(world: World, script: (World, (String, Any?) -> Unit) -> Unit): List<String> {
        val log: MutableList<String> = ArrayList()
        script(world) { label: String, value: Any? -> log += "$label -> ${render(world, value)}" }
        log += "TREE " + tree(world).joinToString(" | ")
        return log
    }

    private fun assertSameObservables(script: (World, (String, Any?) -> Unit) -> Unit) {
        if (hostIsWindows) return // the fake is POSIX on every host; the scenarios build POSIX paths
        val insensitive: Boolean = hostIsCaseInsensitive()
        val real: World = realWorld()
        val fake: World = fakeWorld(caseSensitive = !insensitive)
        try {
            val realLog: List<String> = run(real, script)
            val fakeLog: List<String> = run(fake, script)
            assertEquals(realLog.joinToString("\n"), fakeLog.joinToString("\n"), "the fake and the real file system disagree")
        } finally {
            real.cleanup()
        }
    }

    private fun p(world: World, relative: String): String = world.root + "/" + relative

    private fun put(world: World, relative: String, text: String) {
        val written: FileResult<Unit> = world.files.writeAtomically(
            p(world, relative),
            text.encodeToByteArray(),
            WriteOptions(durability = Durability.Buffered, createParentDirectories = true),
        )
        check(written is FileResult.Success) { "setup write of $relative failed: $written" }
    }

    private fun mkdir(world: World, relative: String) {
        check(world.files.createDirectories(p(world, relative)) is FileResult.Success)
    }

    @Test
    fun `a dot segment after a regular file is ENOTDIR for every query and operation`() = assertSameObservables { w, out ->
        put(w, "f", "payload")
        out("metadata f/.", w.files.metadata(p(w, "f/."), false))
        out("metadata f/./", w.files.metadata(p(w, "f/./"), false))
        out("readBytes f/.", w.files.readBytes(p(w, "f/."), 100))
        out("list f/.", w.files.list(p(w, "f/.")))
        out("delete f/.", w.files.delete(p(w, "f/.")))
        out("treeSize f/.", w.files.treeSize(p(w, "f/.")))
        out("createDirectories f/.", w.files.createDirectories(p(w, "f/.")))
        out("write f/.", w.files.writeAtomically(p(w, "f/."), "x".encodeToByteArray()))
    }

    @Test
    fun `a dot segment after an empty directory is not removable as the directory itself`() = assertSameObservables { w, out ->
        mkdir(w, "d")
        out("delete d/.", w.files.delete(p(w, "d/.")))
        out("deleteRecursively d/.", w.files.deleteRecursively(p(w, "d/.")))
        out("metadata d/.", w.files.metadata(p(w, "d/."), false))
        out("exists d", w.files.metadata(p(w, "d"), false))
    }

    @Test
    fun `moves of a directory onto a file and under a file`() = assertSameObservables { w, out ->
        mkdir(w, "d/sub")
        put(w, "f", "file")
        mkdir(w, "empty")
        mkdir(w, "full")
        put(w, "full/x", "x")
        out("dir into itself", w.files.move(p(w, "d"), p(w, "d/sub/d")))
        out("dir onto file", w.files.move(p(w, "d"), p(w, "f")))
        out("file onto dir", w.files.move(p(w, "f"), p(w, "empty")))
        out("dir onto empty dir", w.files.move(p(w, "d"), p(w, "empty")))
        out("dir onto full dir", w.files.move(p(w, "d"), p(w, "full")))
        out("same path replace", w.files.move(p(w, "f"), p(w, "f")))
        out("same path fail", w.files.move(p(w, "f"), p(w, "f"), MoveOptions(existing = ExistingFile.Fail)))
        out("same dir path", w.files.move(p(w, "d"), p(w, "d")))
        out("under a file", w.files.move(p(w, "f"), p(w, "f/g")))
        out("create parents under a file", w.files.move(p(w, "full/x"), p(w, "f/g/h"), MoveOptions(createParentDirectories = true)))
        out("into a missing parent", w.files.move(p(w, "full/x"), p(w, "nope/g")))
        out("missing source", w.files.move(p(w, "absent"), p(w, "g")))
    }

    @Test
    fun `lookups of a name over 255 bytes are NameTooLong on real systems`() = assertSameObservables { w, out ->
        val ascii256: String = "a".repeat(256)
        out("read 256", w.files.readBytes(p(w, ascii256), 10))
        out("delete 256", w.files.delete(p(w, ascii256)))
        out("deleteRecursively 256", w.files.deleteRecursively(p(w, ascii256)))
        out("list 256", w.files.list(p(w, ascii256)))
    }
}
