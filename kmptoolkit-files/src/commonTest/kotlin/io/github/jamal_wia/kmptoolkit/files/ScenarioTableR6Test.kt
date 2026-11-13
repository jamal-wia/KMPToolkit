package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round 6 of the breaker for unit G, Q4: ONE shared scenario table, every row a sentence of docs/04 (or a documented
 * platform difference of docs/05), run unchanged by the JVM, the Android host and the iOS simulator tasks. The three
 * engines (java.nio, android.system.Os path, POSIX) must produce the same outcome and the same resulting tree for every
 * row; a row whose outcome the docs make platform-dependent branches on a probe of the volume, never on the engine.
 *
 * Outcome notation: `ok` or the error class, plus its path (relative to the root, `~`) only where the docs name it.
 * Tree notation: `path:F<size>` file, `path:D` directory, `path:L` symbolic link, space separated, sorted.
 */
class ScenarioTableR6Test {
    private lateinit var root: String
    private val files: FileAccess = createFileAccess()
    private val posix: Boolean get() = TestHost.pathSyntax == PathSyntax.POSIX

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private fun p(name: String): String = TestPaths.join(root, name)

    private fun put(name: String, text: String = "x") {
        val parent: String = TestPaths.parent(p(name)) ?: error("no parent")
        assertTrue(files.createDirectories(parent).isSuccess)
        assertTrue(files.writeAtomically(p(name), text.encodeToByteArray()).isSuccess, "setup write of $name")
    }

    private fun mkdir(name: String) {
        assertTrue(files.createDirectories(p(name)).isSuccess, "setup mkdir $name")
    }

    private fun link(name: String, target: String) {
        if (!TestEnv.createSymbolicLink(p(name), p(target))) TestEnv.skip("no symbolic links here")
    }

    private fun rel(path: String): String = "~/" + (TestPaths.relativeLogical(root, path) ?: path)

    private fun out(result: FileResult<*>): String = when (result) {
        is FileResult.Success -> "ok"
        is FileResult.Failure -> result.error::class.simpleName ?: "?"
    }

    private fun outP(result: FileResult<*>): String = when (result) {
        is FileResult.Success -> "ok"
        is FileResult.Failure -> "${result.error::class.simpleName}(${rel(result.error.path ?: "")})"
    }

    private fun tree(dir: String = root, prefix: String = ""): String {
        val parts: MutableList<String> = ArrayList()
        for (name: String in files.list(dir).getOrNull().orEmpty()) {
            val full: String = TestPaths.join(dir, name)
            val meta: FileMetadata = files.metadata(full) ?: continue
            when (meta.kind) {
                FileKind.RegularFile -> parts += "$prefix$name:F${meta.sizeBytes}"
                FileKind.Directory -> {
                    parts += "$prefix$name:D"
                    val inner: String = tree(full, "$prefix$name/")
                    if (inner.isNotEmpty()) parts += inner
                }
                FileKind.SymbolicLink -> parts += "$prefix$name:L"
                FileKind.Other -> parts += "$prefix$name:O"
            }
        }
        return parts.joinToString(" ")
    }

    private fun text(name: String): String? = files.readBytes(p(name), 1000).getOrNull()?.decodeToString()

    private class Row(val id: String, val action: () -> String, val expected: String)

    private fun rows(): List<Row> = listOf(
        // ---- writeAtomically
        Row("W01 write new file", { out(files.writeAtomically(p("a"), "x".encodeToByteArray())) + " " + tree() }, "ok a:F1"),
        Row("W02 write replaces", { put("a", "x"); out(files.writeAtomically(p("a"), "xyz".encodeToByteArray())) + " " + tree() }, "ok a:F3"),
        Row("W03 write Fail over existing keeps the old", {
            put("a", "old")
            outP(files.writeAtomically(p("a"), "new".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail))) + " " + tree() + " " + text("a")
        }, "AlreadyExists(~/a) a:F3 old"),
        Row("W04 write Fail on absent", { out(files.writeAtomically(p("a"), "x".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail))) + " " + tree() }, "ok a:F1"),
        Row("W05 write missing parent leaves nothing", { out(files.writeAtomically(p("nodir/a"), "x".encodeToByteArray())) + " [" + tree() + "]" }, "NotFound []"),
        Row("W06 write creates parents", {
            out(files.writeAtomically(p("d/e/f"), "x".encodeToByteArray(), WriteOptions(createParentDirectories = true))) + " " + tree()
        }, "ok d:D d/e:D d/e/f:F1"),
        Row("W07 write over a directory", { mkdir("d"); outP(files.writeAtomically(p("d"), "x".encodeToByteArray())) + " " + tree() }, "IsADirectory(~/d) d:D"),
        Row("W08 write below a file", { put("f"); out(files.writeAtomically(p("f/a"), "x".encodeToByteArray())) + " " + tree() }, "NotADirectory f:F1"),
        Row("W09 write replaces a link by a file and leaves the target", {
            put("t", "target"); link("l", "t")
            out(files.writeAtomically(p("l"), "abc".encodeToByteArray())) + " " + tree() + " " + text("t")
        }, "ok l:F3 t:F6 target"),
        Row("W10 write over a dangling link", {
            link("l", "gone"); out(files.writeAtomically(p("l"), "abc".encodeToByteArray())) + " " + tree()
        }, "ok l:F3"),
        Row("W19 write over a link to a directory replaces the link and leaves the directory", {
            mkdir("d"); put("d/x"); link("l", "d")
            out(files.writeAtomically(p("l"), "abc".encodeToByteArray())) + " " + tree()
        }, "ok d:D d/x:F1 l:F3"),
        Row("W11 write with a trailing separator names the same entry", {
            out(files.writeAtomically(p("a") + (if (posix) "/" else "\\"), "x".encodeToByteArray())) + " " + tree()
        }, "ok a:F1"),
        Row("W12 write of a relative path", { out(files.writeAtomically("rel/a", "x".encodeToByteArray())) }, "InvalidPath"),
        Row("W13 write of a NUL path", { out(files.writeAtomically(p("a") + "\u0000b", "x".encodeToByteArray())) + " [" + tree() + "]" }, "InvalidPath []"),
        Row("W14 write of zero bytes", { out(files.writeAtomically(p("a"), ByteArray(0))) + " " + tree() }, "ok a:F0"),
        Row("W15 write of a non ASCII name reads back", {
            out(files.writeAtomically(p("naïve-日本"), "é".encodeToByteArray())) + " " + text("naïve-日本")
        }, "ok é"),
        Row("W16 write of a 255 byte name", { out(files.writeAtomically(p("n".repeat(255)), "x".encodeToByteArray())) + " " + tree().length }, "ok ${255 + 3}"),
        Row("W17 write of a 256 byte name is NameTooLong with nothing left", {
            out(files.writeAtomically(p("n".repeat(256)), "x".encodeToByteArray())) + " [" + tree() + "]"
        }, "NameTooLong []"),
        Row("W18 write at Buffered Synced and SyncedWithDirectory all end the same", {
            val results: List<String> = listOf(Durability.Buffered, Durability.Synced, Durability.SyncedWithDirectory).map { d: Durability ->
                out(files.writeAtomically(p("a"), d.name.encodeToByteArray(), WriteOptions(durability = d))) + ":" + text("a")
            }
            results.joinToString(",") + " " + tree()
        }, "ok:Buffered,ok:Synced,ok:SyncedWithDirectory a:F19"),
        // ---- move
        Row("M01 move renames a file", { put("a"); out(files.move(p("a"), p("b"))) + " " + tree() }, "ok b:F1"),
        Row("M02 move Replace over a file", { put("a", "new"); put("b", "old"); out(files.move(p("a"), p("b"))) + " " + tree() + " " + text("b") }, "ok b:F3 new"),
        Row("M03 move Fail over an existing file", {
            put("a", "new"); put("b", "old")
            outP(files.move(p("a"), p("b"), MoveOptions(existing = ExistingFile.Fail))) + " " + tree() + " " + text("b")
        }, "AlreadyExists(~/b) a:F3 b:F3 old"),
        Row("M04 move Fail to an absent name", { put("a"); out(files.move(p("a"), p("b"), MoveOptions(existing = ExistingFile.Fail))) + " " + tree() }, "ok b:F1"),
        Row("M05 move of an absent source names the source", { outP(files.move(p("a"), p("b"))) }, "NotFound(~/a)"),
        Row("M06 move onto a directory", { put("a"); mkdir("d"); outP(files.move(p("a"), p("d"))) + " " + tree() }, "IsADirectory(~/d) a:F1 d:D"),
        Row("M07 move renames a directory", { put("d/x"); out(files.move(p("d"), p("e"))) + " " + tree() }, "ok e:D e/x:F1"),
        Row("M08 move Fail of a directory is Unsupported naming the source", {
            mkdir("d"); outP(files.move(p("d"), p("e"), MoveOptions(existing = ExistingFile.Fail))) + " " + tree()
        }, "Unsupported(~/d) d:D"),
        Row("M09 move Fail of a link is Unsupported naming the source", {
            put("t"); link("l", "t"); outP(files.move(p("l"), p("m"), MoveOptions(existing = ExistingFile.Fail))) + " " + tree()
        }, "Unsupported(~/l) l:L t:F1"),
        Row("M10 move Replace moves a link as a link", {
            put("t"); link("l", "t"); out(files.move(p("l"), p("m"))) + " " + tree()
        }, "ok m:L t:F1"),
        Row("M11 move into a missing parent names the destination", { put("a"); outP(files.move(p("a"), p("nodir/b"))) + " " + tree() }, "NotFound(~/nodir/b) a:F1"),
        Row("M12 move with createParentDirectories", {
            put("a"); out(files.move(p("a"), p("x/y/b"), MoveOptions(createParentDirectories = true))) + " " + tree()
        }, "ok x:D x/y:D x/y/b:F1"),
        Row("M13 move a directory onto a directory", { mkdir("d"); mkdir("e"); outP(files.move(p("d"), p("e"))) + " " + tree() }, "IsADirectory(~/e) d:D e:D"),
        Row("M14 move below a file", { put("a"); put("f"); out(files.move(p("a"), p("f/b"))) + " " + tree() }, "NotADirectory a:F1 f:F1"),
        Row("M15 move with trailing separators", {
            put("a"); val s: String = if (posix) "/" else "\\"
            out(files.move(p("a") + s, p("b") + s)) + " " + tree()
        }, "ok b:F1"),
        // ---- delete, deleteRecursively
        Row("D01 delete of an absent entry", { out(files.delete(p("a"))) }, "ok"),
        Row("D02 delete of a file", { put("a"); out(files.delete(p("a"))) + " [" + tree() + "]" }, "ok []"),
        Row("D03 delete of an empty directory", { mkdir("d"); out(files.delete(p("d"))) + " [" + tree() + "]" }, "ok []"),
        Row("D04 delete of a non-empty directory", { put("d/x"); outP(files.delete(p("d"))) + " " + tree() }, "DirectoryNotEmpty(~/d) d:D d/x:F1"),
        Row("D05 delete of a link keeps the target", { mkdir("d"); put("d/x"); link("l", "d"); out(files.delete(p("l"))) + " " + tree() }, "ok d:D d/x:F1"),
        Row("D06 deleteRecursively never follows a link inside", {
            put("keep/k"); put("t/a"); link("t/l", "keep"); out(files.deleteRecursively(p("t"))) + " " + tree()
        }, "ok keep:D keep/k:F1"),
        Row("D07 deleteRecursively of an absent path", { out(files.deleteRecursively(p("a"))) }, "ok"),
        Row("D08 deleteRecursively of a link root removes the link only", {
            put("d/x"); link("l", "d"); out(files.deleteRecursively(p("l"))) + " " + tree()
        }, "ok d:D d/x:F1"),
        Row("D09 deleteRecursively of a deep tree", {
            put("t/1/2/3/4/5/f"); put("t/1/g"); out(files.deleteRecursively(p("t"))) + " [" + tree() + "]"
        }, "ok []"),
        // ---- list, metadata, read, treeSize, createDirectories, sweep, freeSpace
        Row("L01 list of an absent directory", { files.list(p("d")).let { out(it) + " " + it.getOrNull() } }, "ok []"),
        Row("L02 list of a file", { put("a"); out(files.list(p("a"))) }, "NotADirectory"),
        Row("L03 list is sorted by String compareTo", {
            for (n: String in listOf("b", "Z", "a", "C", "_z", "10", "9")) put(n)
            files.list(root).getOrNull().toString()
        }, "[10, 9, C, Z, _z, a, b]"),
        Row("L04 list with a trailing separator", { put("d/x"); files.list(p("d") + (if (posix) "/" else "\\")).getOrNull().toString() }, "[x]"),
        Row("Q01 metadata of an absent path", { files.metadata(p("a")).toString() }, "null"),
        Row("Q02 metadata of a link with and without following", {
            put("t", "four"); link("l", "t")
            "${files.metadata(p("l"))?.kind} ${files.metadata(p("l"), followLinks = true)?.kind} ${files.metadata(p("l"), true)?.sizeBytes}"
        }, "SymbolicLink RegularFile 4"),
        Row("Q03 metadata of a dangling link", { link("l", "gone"); "${files.metadata(p("l"))?.kind} ${files.metadata(p("l"), true)}" }, "SymbolicLink null"),
        Row("Q04 metadata of a relative path", { files.metadata("rel/a").toString() }, "null"),
        Row("R01 read of a directory", { mkdir("d"); outP(files.readBytes(p("d"), 10)) }, "IsADirectory(~/d)"),
        Row("R02 read of an absent file", { outP(files.readBytes(p("a"), 10)) }, "NotFound(~/a)"),
        Row("R03 read over the limit is TooLarge not partial", {
            put("a", "12345"); val r: FileResult<ByteArray> = files.readBytes(p("a"), 2)
            "${out(r)} ${(r.errorOrNull() as? FileError.TooLarge)?.let { "${it.sizeBytes}/${it.maxBytes}" }}"
        }, "TooLarge 5/2"),
        Row("R04 read of an empty file with a zero limit", { put("a", ""); "${out(files.readBytes(p("a"), 0))} ${files.readBytes(p("a"), 0).getOrNull()?.size}" }, "ok 0"),
        Row("R05 read through a link", { put("t", "via"); link("l", "t"); files.readBytes(p("l"), 10).getOrNull()?.decodeToString().toString() }, "via"),
        Row("R06 read of a dangling link", { link("l", "gone"); out(files.readBytes(p("l"), 10)) }, "NotFound"),
        Row("T01 treeSize of an absent path", { files.treeSize(p("a")).getOrNull().toString() }, "0"),
        Row("T02 treeSize sums regular files and counts a link as 0", {
            put("t/a", "123"); put("t/s/b", "45"); put("big", "123456789"); link("t/l", "big"); files.treeSize(p("t")).getOrNull().toString()
        }, "5"),
        Row("T03 treeSize of one file", { put("a", "1234"); files.treeSize(p("a")).getOrNull().toString() }, "4"),
        Row("C01 createDirectories nested then again", {
            out(files.createDirectories(p("a/b/c"))) + " " + out(files.createDirectories(p("a/b/c"))) + " " + tree()
        }, "ok ok a:D a/b:D a/b/c:D"),
        Row("C02 createDirectories over a file", { put("a"); out(files.createDirectories(p("a"))) + " " + tree() }, "NotADirectory a:F1"),
        Row("C03 createDirectories below a file", { put("a"); out(files.createDirectories(p("a/b/c"))) + " " + tree() }, "NotADirectory a:F1"),
        Row("C04 createDirectories with a trailing separator", {
            out(files.createDirectories(p("a/b") + (if (posix) "/" else "\\"))) + " " + tree()
        }, "ok a:D a/b:D"),
        Row("S01 sweep deletes only matching old regular files and links", {
            put("old.tmp"); put("old.keep"); put("new.tmp"); mkdir("dir.tmp")
            val ok: Boolean = TestEnv.setLastModified(p("old.tmp"), 1_000_000L) && TestEnv.setLastModified(p("old.keep"), 1_000_000L) &&
                TestEnv.setLastModified(p("dir.tmp"), 1_000_000L)
            if (!ok) TestEnv.skip("cannot set the modification time")
            "${files.sweep(root, 60_000L) { it.endsWith(".tmp") }.getOrNull()} ${tree()}"
        }, "1 dir.tmp:D new.tmp:F1 old.keep:F1"),
        Row("S02 sweep of an absent directory", { files.sweep(p("d"), 0L) { true }.getOrNull().toString() }, "0"),
        Row("F01 free space of a missing path is read at the nearest ancestor", {
            val free: Long? = files.freeSpaceBytes(p("a/b/c"))
            "${free != null && free >= 0} ${files.freeSpaceBytes("rel/x")}"
        }, "true null"),
        // ---- documented differences of docs/05
        Row("P01 a 200 CJK character name (600 bytes) is accepted on the Apple and Windows file systems", {
            val name: String = "日".repeat(200)
            out(files.writeAtomically(p(name), "x".encodeToByteArray())) + " " + text(name)
        }, "ok x"),
        Row("P02 case spellings are one entry or two, never torn (the platform decides, docs 05)", {
            put("File", "one")
            val insensitive: Boolean = files.exists(p("file"))
            val moved: String = out(files.move(p("File"), p("file"), MoveOptions(existing = ExistingFile.Fail)))
            "$insensitive $moved ${tree()}"
        }, if (hostIsCaseInsensitive()) "true AlreadyExists File:F3" else "false ok file:F3"),
        Row("P03 list returns names as stored and PathKey equates the NFC and NFD spellings", {
            val nfc = "café"
            val nfd = "café"
            put(nfc, "x")
            val listed: String = files.list(root).getOrNull()?.single() ?: "?"
            "${listed == nfc || listed == nfd} ${PathKey.of(p(nfc), CaseFolding.Always) == PathKey.of(p(nfd), CaseFolding.Always)}"
        }, "true true"),
        Row("P04 PathKey with Never keeps the spellings apart and Always joins case", {
            "${PathKey.of(p("A"), CaseFolding.Never) == PathKey.of(p("a"), CaseFolding.Never)} ${PathKey.of(p("A"), CaseFolding.Always) == PathKey.of(p("a"), CaseFolding.Always)}"
        }, "false true"),
        Row("P05 a 256 character plain name classified NameTooLong on move creation", {
            put("a"); out(files.move(p("a"), p("m".repeat(300)))) + " " + tree()
        }, "NameTooLong a:F1"),
    )

    private fun hostIsCaseInsensitive(): Boolean {
        val probe: String = p("CaseProbe")
        files.writeAtomically(probe, ByteArray(0))
        val insensitive: Boolean = files.exists(p("caseprobe"))
        files.delete(probe)
        return insensitive
    }

    @Test
    fun `every row of the shared scenario table gives its documented outcome`() {
        val all: List<Row> = rows()
        assertTrue(all.size >= 40, "the table must have at least 40 rows, has ${all.size}")
        val mismatches: MutableList<String> = ArrayList()
        for (row: Row in all) {
            // Each row on a fresh root so rows cannot influence each other.
            TestEnv.deleteTree(root)
            root = TestEnv.newRoot()
            val actual: String = try {
                row.action()
            } catch (e: Throwable) {
                if (e::class.simpleName?.contains("Skip", ignoreCase = true) == true || e::class.simpleName == "AssumptionViolatedException") "SKIPPED" else "THREW ${e::class.simpleName}: ${e.message}"
            }
            if (actual != "SKIPPED" && actual != row.expected) mismatches += "${row.id}: expected <${row.expected}> but was <$actual>"
        }
        assertEquals(emptyList(), mismatches, "rows that differ from the documented outcome (host ${TestHost.pathSyntax}):")
    }
}
