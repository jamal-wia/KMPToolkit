package io.github.jamal_wia.kmptoolkit.files

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round 4 of the breaker for unit G (character: containment). Every entry point that takes a name or a relative path
 * and is documented to stay inside a root, on the host's real file system: `Paths.resolveInside`,
 * `AppDirectories.directory(name)`, `WriteOptions.tempPrefix`, the no-follow rules for the entry a path names
 * (hard links and symbolic links that lead out of a tree), and the typed `InvalidPath` of hostile spellings.
 * Seeds: fixed defaults, override with -Dkmptoolkit.test.seed; the seed is in every failure message.
 */
class ContainmentR4Test {
    private val seed: Long = System.getProperty("kmptoolkit.test.seed")?.toLongOrNull() ?: 40_420L
    private val files: FileAccess = createFileAccess()
    private lateinit var top: File
    private lateinit var base: File
    private lateinit var outside: File

    @BeforeTest
    fun setUp() {
        top = Files.createTempDirectory("kmptoolkit-containment-r4").toRealPath().toFile()
        base = File(top, "base").also { it.mkdirs() }
        outside = File(top, "outside").also { it.mkdirs() }
        File(outside, "sentinel").writeText("OUT")
    }

    @AfterTest
    fun tearDown() {
        top.deleteRecursively()
    }

    private fun outsideIntact(message: String) {
        assertEquals(listOf("sentinel"), outside.list()!!.sorted(), "$message: the outside directory gained or lost an entry")
        assertEquals("OUT", File(outside, "sentinel").readText(), "$message: the outside file changed")
    }

    @Test
    fun `resolveInside against the real tree is sound and complete for lexical routes`() {
        for (name: String in listOf("a", "b", "c")) File(File(File(base, "a"), "b"), name).mkdirs()
        val random: Random = Random(seed)
        val pieces: List<String> = listOf("a", "b", "c", "..", ".", "", "..")
        var inside: Int = 0
        var rejected: Int = 0
        repeat(2_000) {
            val relative: String = (0 until random.nextInt(0, 9)).joinToString("/") { pieces[random.nextInt(pieces.size)] }
            val resolved: String? = Paths.resolveInside(base.path, relative)
            if (relative.startsWith("/")) {
                assertNull(resolved, "seed=$seed '$relative' looks absolute and must be refused")
                return@repeat
            }
            // Oracle: walk the route; a step below the base depth leaves the base, even when the route comes back.
            var dipped: Boolean = false
            val stack: MutableList<String> = ArrayList()
            // path-hygiene-ignore: PH3 the test reads the parent of a path it built itself with File
            for (piece: String in relative.split('/')) {
                when {
                    piece.isEmpty() || piece == "." -> Unit
                    piece == ".." -> if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else dipped = true
                    else -> stack.add(piece)
                }
            }
            if (resolved != null) {
                inside++
                val canonical: Path = File(resolved).canonicalFile.toPath()
                assertTrue(canonical.startsWith(base.canonicalFile.toPath()), "seed=$seed relative='$relative' -> '$resolved' leaves the base")
            } else {
                rejected++
            }
            // Completeness for routes that never dip below the base: they must resolve.
            if (!dipped) assertNotNull(resolved, "seed=$seed relative='$relative' never leaves the base yet was refused")
        }
        assertTrue(inside > 100 && rejected > 100, "seed=$seed the generator must reach both outcomes: inside=$inside rejected=$rejected")
        // The named escapes.
        for (escape: String in listOf("..", "../outside", "../outside/sentinel", "a/../../outside", "a/b/../../../x", "./../.", "/etc/passwd", "a\u0000b")) {
            assertNull(Paths.resolveInside(base.path, escape), "'$escape' must be refused")
        }
        outsideIntact("after the resolveInside sweep")
    }

    @Test
    fun `writing to every non null resolveInside result never touches the outside`() {
        val random: Random = Random(seed + 1)
        val pieces: List<String> = listOf("a", "b", "..", ".", "", "c", "..")
        var written: Int = 0
        repeat(300) {
            val relative: String = (0 until random.nextInt(1, 8)).joinToString("/") { pieces[random.nextInt(pieces.size)] }
            val resolved: String = Paths.resolveInside(base.path, relative) ?: return@repeat
            if (resolved == base.path) return@repeat
            val target: String = resolved
            val result: FileResult<Unit> = files.writeAtomically(
                target, "x".encodeToByteArray(),
                WriteOptions(durability = Durability.Buffered, createParentDirectories = true),
            )
            // A directory already at the target is a typed failure; nothing else may fail.
            if (result is FileResult.Failure) assertTrue(result.error is FileError.IsADirectory || result.error is FileError.NotADirectory, "seed=$seed '$relative' -> ${result.error}")
            else written++
        }
        assertTrue(written > 20, "seed=$seed written=$written")
        assertEquals(listOf("base", "outside"), top.list()!!.sorted(), "seed=$seed the top directory gained an entry")
        outsideIntact("seed=$seed after the writes")
    }

    @Test
    fun `a symbolic link in a parent segment leads out of a lexically contained path as documented`() {
        val link: File = File(base, "lnk")
        if (!TestEnv.createSymbolicLink(link.path, outside.path)) return
        val resolved: String = assertNotNull(Paths.resolveInside(base.path, "lnk/planted"), "lexical containment cannot see a link")
        // Documented (04, Paths: "Does not know about symbolic links"; FileAccess: parent links resolve as usual).
        assertTrue(files.writeAtomically(resolved, "x".encodeToByteArray()) is FileResult.Success)
        assertTrue(File(outside, "planted").exists(), "the write followed the parent link, which the docs state")
        // The entry the path NAMES is never followed: delete and deleteRecursively remove the link, not the target.
        assertTrue(files.deleteRecursively(link.path) is FileResult.Success)
        assertTrue(!Files.isSymbolicLink(link.toPath()) && !link.exists())
        assertEquals(listOf("planted", "sentinel"), outside.list()!!.sorted(), "the target directory lost an entry")
    }

    @Test
    fun `a hard link out of the tree is never written through or removed through`() {
        val outsideFile: File = File(outside, "sentinel")
        fun relink(name: String): File {
            val inside: File = File(base, name)
            inside.delete()
            Files.createLink(inside.toPath(), outsideFile.toPath())
            return inside
        }
        // Replace: the rename breaks the link; the outside inode keeps its content.
        var inside: File = relink("h1")
        assertTrue(files.writeAtomically(inside.path, "NEW".encodeToByteArray()) is FileResult.Success)
        assertEquals("NEW", inside.readText())
        assertEquals("OUT", outsideFile.readText(), "write replaced the content of the outside inode")
        // Fail: refuses, outside untouched.
        inside = relink("h2")
        val failed: FileResult<Unit> = files.writeAtomically(inside.path, "NEW".encodeToByteArray(), WriteOptions(existing = ExistingFile.Fail))
        assertTrue(failed.errorOrNull() is FileError.AlreadyExists, "$failed")
        assertEquals("OUT", outsideFile.readText())
        // move Replace over the link.
        inside = relink("h3")
        File(base, "src").writeText("MOVED")
        assertTrue(files.move(File(base, "src").path, inside.path) is FileResult.Success)
        assertEquals("MOVED", inside.readText())
        assertEquals("OUT", outsideFile.readText(), "move wrote through the link")
        // move of the link itself, then removal.
        inside = relink("h4")
        assertTrue(files.move(inside.path, File(base, "h4-moved").path) is FileResult.Success)
        assertEquals("OUT", File(base, "h4-moved").readText())
        // deleteRecursively and sweep remove the names, never the content seen from outside.
        relink("h5")
        assertTrue(files.deleteRecursively(base.path) is FileResult.Success)
        assertTrue(!base.exists())
        assertEquals("OUT", outsideFile.readText(), "deleteRecursively removed content through a hard link")
        base.mkdirs()
        val swept: File = relink("h6")
        swept.setLastModified(1_000_000_000_000L) // 2001: far older than the 1 s bound, no clock read
        assertEquals(1, files.sweep(base.path, olderThanMillis = 1_000L) { it == "h6" }.getOrNull())
        assertTrue(outsideFile.exists() && outsideFile.readText() == "OUT", "sweep removed the outside name")
        assertEquals(listOf("sentinel"), outside.list()!!.sorted())
    }

    @Test
    fun `a symbolic link as the final entry is replaced or moved as a link and its target is untouched`() {
        val outsideFile: File = File(outside, "sentinel")
        val link: File = File(base, "lf")
        if (!TestEnv.createSymbolicLink(link.path, outsideFile.path)) return
        assertTrue(files.writeAtomically(link.path, "NEW".encodeToByteArray()) is FileResult.Success)
        assertTrue(!Files.isSymbolicLink(link.toPath()), "a regular file replaced the link")
        assertEquals("OUT", outsideFile.readText())
        File(base, "lf2").let { second: File ->
            assertTrue(TestEnv.createSymbolicLink(second.path, outsideFile.path))
            assertTrue(files.move(second.path, File(base, "lf3").path) is FileResult.Success)
            assertTrue(Files.isSymbolicLink(File(base, "lf3").toPath()), "move moved the link as a link")
        }
        assertEquals(0L, files.treeSize(File(base, "lf3").path).getOrNull(), "a link counts 0")
        assertEquals("OUT", outsideFile.readText())
        assertTrue(files.deleteRecursively(base.path) is FileResult.Success)
        outsideIntact("after deleting the tree that holds links")
    }

    @Test
    fun `temp prefix rules refuse separators and names that cannot be one file name and accept the rest`() {
        val prefixes: List<String> = listOf(
            "", "../", "a/", "/", "a\u0000", "\u0000", "x".repeat(233), "x".repeat(232), "x".repeat(231), "..", ".", "-", "~", "é", "a b", "a\\b", "\uD83D",
            "..a", "a..", ".kmptoolkit-", "é".repeat(60), "é".repeat(117), "é".repeat(116),
        )
        val directory: File = File(base, "w").also { it.mkdirs() }
        for (prefix: String in prefixes) {
            val bytes: Int = if (prefix.any { it in '\uD800'..'\uDFFF' } && prefix == "\uD83D") -1 else prefix.encodeToByteArray().size
            val acceptable: Boolean = bytes in 1..FileAccessConfig.MAX_TEMP_PREFIX_BYTES && FileNames.isPlainName(prefix + "0.tmp")
            if (!acceptable) {
                assertFailsWith<IllegalArgumentException>("prefix '${prefix.take(20)}' in WriteOptions") { WriteOptions(tempPrefix = prefix) }
                assertFailsWith<IllegalArgumentException>("prefix '${prefix.take(20)}' in FileAccessConfig") { FileAccessConfig(tempPrefix = prefix) }
                continue
            }
            val options: WriteOptions = WriteOptions(tempPrefix = prefix)
            val target: File = File(directory, "t")
            assertTrue(files.writeAtomically(target.path, "x".encodeToByteArray(), options) is FileResult.Success, "prefix '${prefix.take(20)}'")
            assertTrue(createFileAccess(FileAccessConfig(prefix)).writeAtomically(target.path, "y".encodeToByteArray()) is FileResult.Success)
            assertEquals(listOf("t"), directory.list()!!.sorted(), "prefix '${prefix.take(20)}' left a temporary name or created one elsewhere")
        }
        assertEquals(listOf("base", "outside"), top.list()!!.sorted())
        outsideIntact("after the hostile prefixes")
    }

    @Test
    fun `a relative temp directory is a typed failure that creates nothing in the working directory`() {
        val cwdBefore: List<String> = File(".").list()!!.sorted()
        val target: File = File(base, "t")
        for (temp: String in listOf("rel-temp-r4", "../rel-temp-r4", ".", "", "x\u0000y")) {
            for (create: Boolean in listOf(false, true)) {
                val result: FileResult<Unit> = files.writeAtomically(
                    target.path, "x".encodeToByteArray(),
                    WriteOptions(tempDirectory = temp, createParentDirectories = create),
                )
                assertTrue(result is FileResult.Failure, "tempDirectory='$temp' create=$create -> $result")
                assertTrue(!target.exists(), "tempDirectory='$temp' create=$create wrote the target")
            }
        }
        assertEquals(cwdBefore, File(".").list()!!.sorted(), "a relative temp directory created something in the working directory")
    }

    @Test
    fun `hostile spellings are InvalidPath with the path as given for every function and null for queries`() {
        val hostile: List<String> = listOf("", "relative/x", "x", "\u0000", "/ok/\u0000/x", "./x", "../x")
        for (path: String in hostile) {
            fun invalid(result: FileResult<*>, what: String) =
                assertEquals(FileError.InvalidPath(path), result.errorOrNull(), "$what('${path.replace("\u0000", "<NUL>")}')")
            invalid(files.list(path), "list")
            invalid(files.createDirectories(path), "createDirectories")
            invalid(files.createDirectories(path, durable = true), "createDirectories durable")
            invalid(files.readBytes(path, 10), "readBytes")
            invalid(files.writeAtomically(path, ByteArray(1)), "writeAtomically")
            invalid(files.delete(path), "delete")
            invalid(files.deleteRecursively(path), "deleteRecursively")
            invalid(files.treeSize(path), "treeSize")
            invalid(files.sweep(path, 0L) { true }, "sweep")
            assertTrue(files.move(path, File(base, "d").path).errorOrNull() is FileError.InvalidPath, "move from")
            assertTrue(files.move(File(base, "s").path, path).errorOrNull() is FileError.InvalidPath, "move to")
            assertNull(files.metadata(path), "metadata")
            assertNull(files.metadata(path, followLinks = true), "metadata follow")
            assertNull(files.freeSpaceBytes(path), "freeSpaceBytes")
            assertTrue(!files.exists(path), "exists")
        }
        assertEquals(listOf("base", "outside"), top.list()!!.sorted())
        assertEquals(emptyList(), base.list()!!.toList(), "a hostile path created something")
    }
}
