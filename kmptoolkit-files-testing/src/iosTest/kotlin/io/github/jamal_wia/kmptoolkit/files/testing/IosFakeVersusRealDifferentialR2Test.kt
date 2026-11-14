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
class IosFakeVersusRealDifferentialR2Test {

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
    fun `dotdot segments after a file or through links resolve physically`() = assertSameObservables { w, out ->
        put(w, "f", "payload")
        mkdir(w, "a/b")
        put(w, "a/b/inner", "in")
        w.symlink(p(w, "lnk"), p(w, "a/b"))
        out("read f/../f", w.files.readBytes(p(w, "f/../f"), 100))
        out("write f/../x", w.files.writeAtomically(p(w, "f/../x"), "x".encodeToByteArray()))
        out("write lnk/../x", w.files.writeAtomically(p(w, "lnk/../x"), "x".encodeToByteArray()))
        out("list lnk/..", w.files.list(p(w, "lnk/..")))
        out("read lnk/inner", w.files.readBytes(p(w, "lnk/inner"), 100))
        out("createDirectories lnk/../n/m", w.files.createDirectories(p(w, "lnk/../n/m")))
        out("treeSize lnk/..", w.files.treeSize(p(w, "lnk/..")))
        out("metadata lnk/../b", w.files.metadata(p(w, "lnk/../b"), false))
    }

    @Test
    fun `moves of directories into themselves onto files and over each other`() = assertSameObservables { w, out ->
        mkdir(w, "d/sub")
        put(w, "f", "file")
        mkdir(w, "empty")
        mkdir(w, "full")
        put(w, "full/x", "x")
        out("dir into itself", w.files.move(p(w, "d"), p(w, "d/sub/d")))
        out("file onto dir", w.files.move(p(w, "f"), p(w, "empty")))
        out("dir onto empty dir", w.files.move(p(w, "d"), p(w, "empty")))
        out("dir onto full dir", w.files.move(p(w, "d"), p(w, "full")))
        out("same path replace", w.files.move(p(w, "f"), p(w, "f")))
        out("same path fail", w.files.move(p(w, "f"), p(w, "f"), MoveOptions(existing = ExistingFile.Fail)))
        out("same dir path", w.files.move(p(w, "d"), p(w, "d")))
        out("create parents under a file", w.files.move(p(w, "full/x"), p(w, "f/g/h"), MoveOptions(createParentDirectories = true)))
    }

    @Test
    fun `moves over symbolic links`() = assertSameObservables { w, out ->
        mkdir(w, "dir")
        put(w, "dir/in", "in")
        put(w, "f", "file")
        put(w, "g", "gile")
        w.symlink(p(w, "toDir"), p(w, "dir"))
        w.symlink(p(w, "dangling"), p(w, "nowhere"))
        out("file over link to dir", w.files.move(p(w, "f"), p(w, "toDir")))
        out("file over dangling link", w.files.move(p(w, "g"), p(w, "dangling")))
        out("read dangling", w.files.readBytes(p(w, "dangling"), 100))
        out("dir intact", w.files.readBytes(p(w, "dir/in"), 100))
        w.symlink(p(w, "lnk2"), p(w, "dir"))
        out("link moved onto a file", w.files.move(p(w, "lnk2"), p(w, "dangling")))
        out("metadata moved link", w.files.metadata(p(w, "dangling"), false))
    }

    @Test
    fun `a rename between two hard links of one file`() = assertSameObservables { w, out ->
        put(w, "a", "same inode")
        w.hardLink(p(w, "b"), p(w, "a"))
        out("replace a onto b", w.files.move(p(w, "a"), p(w, "b")))
        out("a after", w.files.metadata(p(w, "a"), false))
        out("fail a onto b", w.files.move(p(w, "a"), p(w, "b"), MoveOptions(existing = ExistingFile.Fail)))
        out("read b", w.files.readBytes(p(w, "b"), 100))
    }

    @Test
    fun `atomic writes with hostile parents and targets`() = assertSameObservables { w, out ->
        put(w, "f", "file")
        mkdir(w, "dir")
        put(w, "dir/in", "in")
        w.symlink(p(w, "dangling"), p(w, "nowhere"))
        w.symlink(p(w, "toDir"), p(w, "dir"))
        w.symlink(p(w, "toFile"), p(w, "f"))
        out("parent is a file", w.files.writeAtomically(p(w, "f/x"), "x".encodeToByteArray()))
        out("parent is a file with create", w.files.writeAtomically(p(w, "f/x"), "x".encodeToByteArray(), WriteOptions(createParentDirectories = true)))
        out("parent is a dangling link", w.files.writeAtomically(p(w, "dangling/x"), "x".encodeToByteArray()))
        out("parent is a dangling link with create", w.files.writeAtomically(p(w, "dangling/x"), "x".encodeToByteArray(), WriteOptions(createParentDirectories = true)))
        out("parent is a link to a dir", w.files.writeAtomically(p(w, "toDir/x"), "x".encodeToByteArray()))
        out("target is a link to a file", w.files.writeAtomically(p(w, "toFile"), "new".encodeToByteArray()))
        out("target is a dangling link", w.files.writeAtomically(p(w, "dangling"), "new".encodeToByteArray()))
        out("fail over a link to a dir", w.files.writeAtomically(p(w, "toDir"), "new".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)))
        out("replace over a link to a dir", w.files.writeAtomically(p(w, "toDir"), "new".encodeToByteArray()))
        out("temp directory is a file", w.files.writeAtomically(p(w, "t1"), "x".encodeToByteArray(), WriteOptions(tempDirectory = p(w, "f"))))
        out("temp directory missing", w.files.writeAtomically(p(w, "t2"), "x".encodeToByteArray(), WriteOptions(tempDirectory = p(w, "nope/tmp"))))
        out("temp directory created", w.files.writeAtomically(p(w, "t3"), "x".encodeToByteArray(), WriteOptions(tempDirectory = p(w, "mk/tmp"), createParentDirectories = true)))
        out("temp directory relative", w.files.writeAtomically(p(w, "t4"), "x".encodeToByteArray(), WriteOptions(tempDirectory = "relative")))
    }

    @Test
    fun `createDirectories over links files and parents that are files`() = assertSameObservables { w, out ->
        put(w, "f", "file")
        mkdir(w, "dir")
        w.symlink(p(w, "toDir"), p(w, "dir"))
        w.symlink(p(w, "toFile"), p(w, "f"))
        w.symlink(p(w, "dangling"), p(w, "nowhere"))
        out("through link", w.files.createDirectories(p(w, "toDir/a/b")))
        out("link to file", w.files.createDirectories(p(w, "toFile")))
        out("dangling", w.files.createDirectories(p(w, "dangling")))
        out("below dangling", w.files.createDirectories(p(w, "dangling/x")))
        out("below file", w.files.createDirectories(p(w, "f/x")))
        out("dot after new", w.files.createDirectories(p(w, "newdir/.")))
        out("dotdot", w.files.createDirectories(p(w, "dir/../made")))
        out("durable", w.files.createDirectories(p(w, "dur/a/b"), durable = true))
    }

    @Test
    fun `sweep treeSize and deleteRecursively over a tree with links`() = assertSameObservables { w, out ->
        mkdir(w, "t/sub")
        put(w, "t/a.tmp", "aaaa")
        put(w, "t/b.keep", "bb")
        put(w, "t/sub/c.tmp", "c")
        mkdir(w, "outside")
        put(w, "outside/o", "ooo")
        w.symlink(p(w, "t/lnk"), p(w, "outside"))
        w.symlink(p(w, "t/dead.tmp"), p(w, "nowhere"))
        out("treeSize", w.files.treeSize(p(w, "t")))
        out("sweep tmp", w.files.sweep(p(w, "t"), 0) { name: String -> name.endsWith(".tmp") }.let { r: FileResult<Int> -> if (r is FileResult.Success) "deleted>=0:" + (r.value >= 0) else "fail" })
        out("deleteRecursively", w.files.deleteRecursively(p(w, "t")))
        out("outside intact", w.files.readBytes(p(w, "outside/o"), 100))
    }

    @Test
    fun `list sorts by code unit and keeps the spelling of creation`() = assertSameObservables { w, out ->
        for (name: String in listOf("b", "B", "a", "_", "-", "1", "é", "z", "Étude")) put(w, "d/$name", name)
        out("list", w.files.list(p(w, "d")))
    }

    @Test
    fun `case folding and unicode spellings of one file`() = assertSameObservables { w, out ->
        put(w, "d/File", "one")
        out("read file", w.files.readBytes(p(w, "d/file"), 100))
        out("write FILE", w.files.writeAtomically(p(w, "d/FILE"), "two".encodeToByteArray()))
        out("fail over file", w.files.writeAtomically(p(w, "d/fIlE"), "three".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail)))
        out("list", w.files.list(p(w, "d")))
        out("rename spelling replace", w.files.move(p(w, "d/File"), p(w, "d/file")))
        out("list after rename", w.files.list(p(w, "d")))
        put(w, "d/other", "o")
        out("rename spelling fail", w.files.move(p(w, "d/other"), p(w, "d/OTHER"), MoveOptions(existing = ExistingFile.Fail)))
        out("list after fail", w.files.list(p(w, "d")))
        put(w, "d/café", "nfc")
        out("nfd read", w.files.readBytes(p(w, "d/café"), 100))
        out("sharp s", w.files.readBytes(p(w, "d/CAFÉ"), 100))
        put(w, "d/straße", "sharp")
        out("ss spelling", w.files.readBytes(p(w, "d/STRASSE"), 100))
        out("final list", w.files.list(p(w, "d")))
    }

    @Test
    fun `name length boundaries in bytes on creation`() = assertSameObservables { w, out ->
        val ascii255: String = "a".repeat(255)
        val ascii256: String = "a".repeat(256)
        out("255 ascii", w.files.writeAtomically(p(w, ascii255), "x".encodeToByteArray()))
        out("256 ascii", w.files.writeAtomically(p(w, ascii256), "x".encodeToByteArray()))
        out("mkdir 256", w.files.createDirectories(p(w, ascii256)))
        out("85 euro signs", w.files.writeAtomically(p(w, "\u20ac".repeat(85)), "x".encodeToByteArray()))
        out("move to 256", w.files.move(p(w, ascii255), p(w, ascii256)))
        out("tree", w.files.treeSize(w.root))
    }

    @Test
    fun `the cancellation check stops every long operation with Cancelled on both systems`() = assertSameObservables { w, out ->
        for (i: Int in 0 until 20) put(w, "t/d${i % 4}/f$i", "x$i")
        var calls: Int = 0
        val stopAfterThree: CancellationCheck = CancellationCheck { ++calls > 3 }
        out("deleteRecursively", w.files.deleteRecursively(p(w, "t"), stopAfterThree).let { r: FileResult<Unit> -> r::class.simpleName })
        out("treeSize cancelled at once", w.files.treeSize(p(w, "t")) { true })
        out("sweep cancelled at once", w.files.sweep(p(w, "t/d0"), 0, { true }) { _: String -> true })
        out("finish", w.files.deleteRecursively(p(w, "t")))
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