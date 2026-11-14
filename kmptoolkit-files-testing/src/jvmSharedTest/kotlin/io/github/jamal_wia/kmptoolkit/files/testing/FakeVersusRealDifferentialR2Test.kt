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
 * Round 2 of the breaker (new scenarios) for unit G: the same scripted scenario runs through [FakeFileAccess] and through the real
 * file system of the host (a fresh temporary directory), and every observable is diffed: the typed result of every
 * call (kind of error, path with the root masked) and the final tree (names, kinds, bytes). The fake is published, so
 * anything it allows that the real file system forbids, or the reverse, misleads consumers.
 */
class FakeVersusRealDifferentialR2Test {

    private class World(
        val files: FileAccess,
        val root: String,
        val symlink: (String, String) -> Unit,
        val hardLink: (String, String) -> Unit,
        val cleanup: () -> Unit,
    )

    // path-hygiene-ignore: PH1 the host separator is read only to detect a Windows host, where this POSIX-spelling differential adapts or skips; it is never joined into a path
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
                // path-hygiene-ignore: PH4 the differential feeds one deliberate POSIX '/' spelling to both the fake and the real file system, so the spelling itself is under test; the Windows JDK accepts '/'
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

    // path-hygiene-ignore: PH4 the differential feeds one deliberate POSIX '/' spelling to both the fake and the real file system, so the spelling itself is under test; the Windows JDK accepts '/'
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
    fun `relative symbolic link targets resolve against the directory of the link`() = assertSameObservables { w, out ->
        mkdir(w, "d/sub")
        put(w, "d/x", "ex")
        put(w, "y", "why")
        w.symlink(p(w, "d/rel"), "x")
        w.symlink(p(w, "d/sub/up"), "../x")
        w.symlink(p(w, "d/sub/up2"), "../sub/../x")
        w.symlink(p(w, "d/chain"), "rel")
        w.symlink(p(w, "d/later"), "not-yet")
        w.symlink(p(w, "d/dirlink"), "sub")
        for (name: String in listOf("d/rel", "d/sub/up", "d/sub/up2", "d/chain", "d/later")) {
            out("read $name", w.files.readBytes(p(w, name), 100))
            out("metadata $name", w.files.metadata(p(w, name), true))
        }
        out("list through relative dir link", w.files.list(p(w, "d/dirlink")))
        out("write through relative dir link", w.files.writeAtomically(p(w, "d/dirlink/new"), "n".encodeToByteArray()))
        out("create through dangling relative link target", w.files.writeAtomically(p(w, "d/later"), "now".encodeToByteArray()))
        out("later after", w.files.readBytes(p(w, "d/later"), 100))
        out("treeSize", w.files.treeSize(p(w, "d")))
        out("deleteRecursively", w.files.deleteRecursively(p(w, "d")))
    }

    @Test
    fun `a symbolic link replaced by move and by write keeps its target`() = assertSameObservables { w, out ->
        put(w, "target", "t")
        put(w, "src", "s")
        w.symlink(p(w, "lnk"), p(w, "target"))
        out("move onto link", w.files.move(p(w, "src"), p(w, "lnk")))
        out("target intact", w.files.readBytes(p(w, "target"), 100))
        out("lnk now", w.files.metadata(p(w, "lnk"), false))
        w.symlink(p(w, "lnk2"), p(w, "target"))
        out("fail move onto link", w.files.move(p(w, "lnk"), p(w, "lnk2"), MoveOptions(existing = ExistingFile.Fail)))
        out("sweep keeps young link", w.files.sweep(w.root, 3_600_000L) { true })
    }
}
