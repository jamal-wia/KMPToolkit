@file:OptIn(ExperimentalForeignApi::class)

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.posix.link
import platform.posix.symlink

/**
 * Round 2 of the breaker for unit G, step 4 (iOS differential): the scenarios of the JVM class
 * FakeVersusRealDifferentialR1Test run through [FakeFileAccess] and through the real POSIX implementation on the
 * simulator (a fresh temporary directory), and every observable is diffed. Unlike the JDK, iOS passes a trailing
 * separator to the kernel, so a few scenarios here are a differential of the platforms themselves.
 */
class IosTrailingSlashDifferentialSuspectR2Test {

    private class World(
        val files: FileAccess,
        val root: String,
        val symlink: (String, String) -> Unit,
        val hardLink: (String, String) -> Unit,
        val cleanup: () -> Unit,
    )

    private fun realWorld(): World {
        val root: String = NSTemporaryDirectory().trimEnd('/') + "/kmptoolkit-diff-r2-" + NSUUID().UUIDString
        check(NSFileManager.defaultManager.createDirectoryAtPath(root, true, null, null)) { "no test root" }
        return World(
            files = createFileAccess(),
            root = root,
            symlink = { l: String, target: String -> check(symlink(target, l) == 0) { "symlink failed" } },
            hardLink = { l: String, existing: String -> check(link(existing, l) == 0) { "link failed" } },
            cleanup = { NSFileManager.defaultManager.removeItemAtPath(root, null) },
        )
    }

    private fun fakeWorld(caseSensitive: Boolean): World {
        val fake: FakeFileAccess = FakeFileAccess(caseSensitive = caseSensitive)
        fake.createDirectory("/r")
        return World(
            files = fake,
            root = "/r",
            symlink = { l: String, target: String -> fake.createSymbolicLink(l, target) },
            hardLink = { l: String, existing: String ->
                fake.failNext(FakeOperation.Remove, io.github.jamal_wia.kmptoolkit.files.FileError.AccessDenied(existing))
                val moved: FileResult<Unit> = fake.move(existing, l, MoveOptions(existing = ExistingFile.Fail))
                check(moved is FileResult.Success) { "could not model a hard link: $moved" }
            },
            cleanup = {},
        )
    }

    /** Whether the volume of the temporary directory folds case (APFS by default does). */
    private fun hostIsCaseInsensitive(): Boolean {
        val world: World = realWorld()
        try {
            check(world.files.writeAtomically(world.root + "/Probe", ByteArray(1)) is FileResult.Success)
            return world.files.metadata(world.root + "/probe", false) != null
        } finally {
            world.cleanup()
        }
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
        val insensitive: Boolean = hostIsCaseInsensitive()
        val real: World = realWorld()
        val fake: World = fakeWorld(caseSensitive = !insensitive)
        try {
            val realLog: List<String> = run(real, script)
            val fakeLog: List<String> = run(fake, script)
            assertEquals(realLog.joinToString("\n"), fakeLog.joinToString("\n"), "the fake and the real iOS file system disagree")
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
    fun `repeated and trailing separators on files and directories`() = assertSameObservables { w, out ->
        put(w, "f", "payload")
        mkdir(w, "d")
        out("metadata //f", w.files.metadata(w.root + "//f", false))
        out("read f///", w.files.readBytes(p(w, "f///"), 100))
        out("list d//", w.files.list(p(w, "d//")))
        out("write d/", w.files.writeAtomically(p(w, "d/"), "x".encodeToByteArray()))
        out("write newfile/", w.files.writeAtomically(p(w, "newfile/"), "x".encodeToByteArray()))
        out("delete f/", w.files.delete(p(w, "f/")))
    }

    @Test
    fun `deleting and reading through links and loops`() = assertSameObservables { w, out ->
        mkdir(w, "dir")
        put(w, "dir/in", "in")
        put(w, "f", "file")
        w.symlink(p(w, "toDir"), p(w, "dir"))
        w.symlink(p(w, "toFile"), p(w, "f"))
        w.symlink(p(w, "loopA"), p(w, "loopB"))
        w.symlink(p(w, "loopB"), p(w, "loopA"))
        out("delete toDir/", w.files.delete(p(w, "toDir/")))
        out("dir survived", w.files.readBytes(p(w, "dir/in"), 100))
        w.symlink(p(w, "toDir"), p(w, "dir"))
        out("deleteRecursively toDir/", w.files.deleteRecursively(p(w, "toDir/")))
        out("dir survived again", w.files.readBytes(p(w, "dir/in"), 100))
        out("list toFile", w.files.list(p(w, "toFile")))
        out("read loop", w.files.readBytes(p(w, "loopA"), 100).let { r: FileResult<ByteArray> -> if (r is FileResult.Failure) "FAIL:" + r.error::class.simpleName else "ok" })
        out("metadata loop nofollow", w.files.metadata(p(w, "loopA"), false)?.kind)
        out("metadata loop follow", w.files.metadata(p(w, "loopA"), true))
        out("treeSize loop", w.files.treeSize(p(w, "loopA")))
        out("delete loop", w.files.delete(p(w, "loopA")))
        out("write through loop", w.files.writeAtomically(p(w, "loopB"), "x".encodeToByteArray()).let { r: FileResult<Unit> -> r::class.simpleName })
    }
}
