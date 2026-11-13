package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

// path-hygiene-file: logical-paths the /tmp versus /private/tmp spellings are the subject of one test

/**
 * Round 5 of the breaker for unit G, Q3 (Apple file systems), on whatever host runs it: the macOS JVM, the Android host
 * runtime on a Mac and the iOS simulator. Every assertion is a documented sentence (docs/04) or a property that holds
 * whether the volume is case-insensitive or not, normalization-insensitive or not, so the test is meaningful on APFS and
 * does not lie on another volume.
 */
class AppleFilesystemR5Test {
    private lateinit var root: String
    private val files: FileAccess = createFileAccess()

    @BeforeTest
    fun setUp() {
        root = TestEnv.newRoot()
    }

    @AfterTest
    fun tearDown() {
        TestEnv.deleteTree(root)
    }

    private fun p(name: String): String = TestPaths.join(root, name)

    private fun bytes(text: String): ByteArray = text.encodeToByteArray()

    private fun tempLeft(directory: String): List<String> =
        (files.list(directory).getOrNull() ?: emptyList()).filter { FileNames.isTempName(it) }

    private val posix: Boolean get() = TestHost.pathSyntax == PathSyntax.POSIX

    @Test
    fun `two spellings that differ in case are one entry or two and never a torn or duplicated entry`() {
        val upper: String = p("Case.bin")
        val lower: String = p("case.bin")
        assertTrue(files.writeAtomically(upper, bytes("first")).isSuccess)
        val insensitive: Boolean = files.exists(lower)
        assertTrue(files.writeAtomically(lower, bytes("second")).isSuccess)
        val names: List<String> = files.list(root).getOrNull().orEmpty()
        if (insensitive) {
            assertEquals(1, names.size, "a case-insensitive volume has one entry for both spellings: $names")
            assertEquals("second", files.readBytes(upper, 100).getOrNull()?.decodeToString())
            assertEquals("second", files.readBytes(lower, 100).getOrNull()?.decodeToString())
            // PathKey equates the two spellings, so PathLocks serialises them (docs 04 PathKey).
            assertEquals(PathKey.of(upper, CaseFolding.Always), PathKey.of(lower, CaseFolding.Always))
            // A no-replace write over the other spelling is AlreadyExists, a replace move onto it succeeds in place.
            assertEquals(FileError.AlreadyExists(lower), files.writeAtomically(lower, bytes("x"), WriteOptions(existing = ExistingFile.Fail)).errorOrNull())
            assertTrue(files.delete(lower).isSuccess)
            assertTrue(!files.exists(upper), "delete through one spelling leaves the other")
        } else {
            assertEquals(listOf("Case.bin", "case.bin"), names)
            assertEquals("first", files.readBytes(upper, 100).getOrNull()?.decodeToString())
        }
        assertEquals(emptyList(), tempLeft(root), "a temporary file was left")
    }

    @Test
    fun `an NFC and an NFD spelling of one name never tear the entry and every listed name opens`() {
        val nfc = "café.txt"
        val nfd = "café.txt"
        assertTrue(files.writeAtomically(p(nfc), bytes("composed")).isSuccess)
        assertTrue(files.writeAtomically(p(nfd), bytes("decomposed")).isSuccess)
        val names: List<String> = files.list(root).getOrNull().orEmpty()
        assertTrue(names.size == 1 || names.size == 2, "$names")
        for (listed in names) {
            val meta: FileMetadata? = files.metadata(p(listed))
            assertNotNull(meta, "a listed name does not open: ${listed.map { it.code.toString(16) }}")
            assertEquals(FileKind.RegularFile, meta.kind)
        }
        if (names.size == 1) {
            // A normalization-insensitive volume: both spellings reach the entry that holds the last write, whole.
            assertEquals("decomposed", files.readBytes(p(nfc), 100).getOrNull()?.decodeToString())
            assertEquals("decomposed", files.readBytes(p(nfd), 100).getOrNull()?.decodeToString())
        } else {
            assertEquals("composed", files.readBytes(p(nfc), 100).getOrNull()?.decodeToString())
            assertEquals("decomposed", files.readBytes(p(nfd), 100).getOrNull()?.decodeToString())
        }
        // The keys fold both spellings together on every platform, which is what the locks need.
        assertEquals(PathKey.of(p(nfc), CaseFolding.Always), PathKey.of(p(nfd), CaseFolding.Always))
        // Names are compared as given: the names a listing returns sorted by String.compareTo.
        assertEquals(names.sorted(), names)
        assertEquals(emptyList(), tempLeft(root))
    }

    @Test
    fun `names at and over the 255 byte edge are written and replaced and moved and listed or refused with a typed error and no litter`() {
        val edge: String = "e".repeat(255)
        val over: String = "e".repeat(256)
        for (durability in Durability.entries) {
            assertTrue(files.writeAtomically(p(edge), bytes("one"), WriteOptions(durability = durability)).isSuccess, "$durability")
            assertTrue(files.writeAtomically(p(edge), bytes("two"), WriteOptions(durability = durability)).isSuccess, "$durability replace")
        }
        assertEquals("two", files.readBytes(p(edge), 10).getOrNull()?.decodeToString())
        assertEquals(listOf(edge), files.list(root).getOrNull())
        val moved: FileResult<Unit> = files.move(p(edge), p("m".repeat(255)))
        assertTrue(moved.isSuccess, "$moved")
        // 256 bytes: the kernel refuses; a typed NameTooLong that names the path, nothing created, no temporary file.
        val refused: FileResult<Unit> = files.writeAtomically(p(over), bytes("x"))
        assertTrue(refused.errorOrNull() is FileError.NameTooLong, "$refused")
        assertTrue(files.writeAtomically(p(over), bytes("x"), WriteOptions(existing = ExistingFile.Fail)).errorOrNull() is FileError.NameTooLong)
        assertTrue(files.move(p("m".repeat(255)), p(over)).errorOrNull() is FileError.NameTooLong)
        assertEquals(null, files.metadata(p(over)))
        assertTrue(files.createDirectories(p(over)).errorOrNull() is FileError.NameTooLong)
        assertEquals(listOf("m".repeat(255)), files.list(root).getOrNull(), "a refused name left something behind")
        // Wider names (docs 05: APFS limits a name to 255 characters, NTFS 255 UTF-16 units, ext4 255 bytes). FileAccess does
        // not pre-reject any of them (docs 04 MAX_NAME_BYTES): the answer is the platform's, typed, and a success is coherent.
        // Measured on a Mac: open(O_CREAT) refuses more than 255 UTF-16 units of the decomposed name (127 e-acute), rename
        // accepts 255 e-acute; the temp-then-rename write therefore succeeds where a plain create would not.
        val wides: List<String> = listOf("\u00e9".repeat(128), "\u00e9".repeat(255), "\uD55C".repeat(86), "\u20ac".repeat(255), "\uD83D\uDE00".repeat(128))
        for (wide in wides) {
            val wideResult: FileResult<Unit> = files.writeAtomically(p(wide), bytes("wide"))
            val wideError: FileError? = wideResult.errorOrNull()
            assertTrue(wideError == null || wideError is FileError.NameTooLong, "${wide.length} units: $wideResult")
            if (wideError == null) {
                assertEquals("wide", files.readBytes(p(wide), 10).getOrNull()?.decodeToString())
                assertTrue(files.list(root).getOrNull().orEmpty().size >= 2, "the wide name is listed beside the moved one")
                assertTrue(files.delete(p(wide)).isSuccess)
            } else {
                assertEquals(null, files.metadata(p(wide)), "a refused wide name left an entry")
            }
        }
        assertEquals(listOf("m".repeat(255)), files.list(root).getOrNull(), "a wide name left something behind")
        assertEquals(emptyList(), tempLeft(root))
    }

    @Test
    fun `a path over the kernel limit is a typed error that leaves nothing behind`() {
        if (!posix) return
        var long: String = root
        val segment: String = "s".repeat(100)
        repeat(12) { long = TestPaths.join(long, segment) }
        val created: FileResult<Unit> = files.createDirectories(long)
        // PATH_MAX is 1024 on Darwin: the creation must stop at the limit with NameTooLong (or Io), never throw, never hang.
        assertTrue(created.isSuccess || created.errorOrNull() is FileError.NameTooLong || created.errorOrNull() is FileError.Io, "$created")
        val written: FileResult<Unit> = files.writeAtomically(TestPaths.join(long, "f"), bytes("x"), WriteOptions(createParentDirectories = true))
        assertTrue(written.isSuccess || written.errorOrNull() !is FileError.InvalidPath, "$written")
        // Whatever happened, the tree is removable from the root and the root holds no temporary file.
        assertTrue(files.deleteRecursively(p(segment)).isSuccess)
        assertEquals(emptyList(), tempLeft(root))
    }

    @Test
    fun `the tmp and private tmp spellings reach one tree and a symbolic link in a parent is followed as documented`() {
        if (!posix) return
        val tmp: FileMetadata = files.metadata("/tmp") ?: return
        if (tmp.kind != FileKind.SymbolicLink) return // not a Mac: no symlinked prefix to test
        // docs 04: the entry the path names is not followed; a link in a parent segment is resolved by the platform.
        assertEquals(FileKind.SymbolicLink, files.metadata("/tmp/")?.kind, "a trailing separator never follows a link")
        assertEquals(FileKind.Directory, files.metadata("/tmp", followLinks = true)?.kind)
        assertEquals(0L, files.treeSize("/tmp").getOrNull(), "a link counts 0")
        val name = "kmptoolkit-r5-" + root.takeLast(12).filter { it.isLetterOrDigit() }
        val viaLink = "/tmp/$name"
        val direct = "/private/tmp/$name"
        try {
            if (files.createDirectories(viaLink).errorOrNull() is FileError.AccessDenied) return // a sandboxed host
            assertEquals(FileKind.Directory, files.metadata(direct)?.kind)
            assertTrue(files.writeAtomically(TestPaths.join(viaLink, "a.bin"), bytes("through the link")).isSuccess)
            assertEquals("through the link", files.readBytes(TestPaths.join(direct, "a.bin"), 100).getOrNull()?.decodeToString())
            assertEquals(files.list(viaLink).getOrNull(), files.list(direct).getOrNull())
            assertTrue(files.move(TestPaths.join(viaLink, "a.bin"), TestPaths.join(direct, "b.bin")).isSuccess)
            assertEquals(listOf("b.bin"), files.list(viaLink).getOrNull())
            assertEquals(files.treeSize(viaLink).getOrNull(), files.treeSize(direct).getOrNull())
            // The lexical key does not equate them (documented: "Lexical: it does not equate ... links").
            assertTrue(PathKey.of(viaLink, CaseFolding.Always) != PathKey.of(direct, CaseFolding.Always))
        } finally {
            assertTrue(files.deleteRecursively(direct).isSuccess)
        }
        assertEquals(null, files.metadata(direct), "the tree was not removed")
        assertEquals(FileKind.SymbolicLink, files.metadata("/tmp")?.kind, "the link itself must still be there")
    }

    @Test
    fun `a directory of ten thousand entries lists complete and in String compareTo order and sweeps and deletes`() {
        val directory: String = p("big")
        assertTrue(files.createDirectories(directory).isSuccess)
        val prefixes: List<String> = listOf("Z", "a", "_", "0", "B")
        val expected: MutableList<String> = mutableListOf()
        for (i in 0 until 10_000) {
            val name: String = prefixes[i % prefixes.size] + i.toString().padStart(5, '0')
            expected += name
            val written: FileResult<Unit> = files.writeAtomically(TestPaths.join(directory, name), ByteArray(i % 3), WriteOptions(durability = Durability.Buffered))
            assertTrue(written.isSuccess, "$name: $written")
        }
        val listed: List<String> = files.list(directory).getOrNull().orEmpty()
        assertEquals(10_000, listed.size)
        assertEquals(expected.sorted(), listed, "the listing is not sorted by String.compareTo or is not the written set")
        assertEquals(listed, files.list(directory).getOrNull(), "a second listing differs")
        assertEquals(expected.indices.sumOf { (it % 3).toLong() }, files.treeSize(directory).getOrNull())
        // sweep: nothing is older than a day; matching everything still deletes nothing.
        assertEquals(FileResult.Success(0), files.sweep(directory, olderThanMillis = 86_400_000L) { true })
        assertEquals(10_000, files.list(directory).getOrNull()?.size)
        assertTrue(files.delete(directory).errorOrNull() is FileError.DirectoryNotEmpty)
        assertTrue(files.deleteRecursively(directory).isSuccess)
        assertEquals(null, files.metadata(directory))
    }

    @Test
    fun `files replaced or removed while a sweep walks the listing never fail it or tear a file`() {
        val directory: String = p("walk")
        assertTrue(files.createDirectories(directory).isSuccess)
        val names: List<String> = (0 until 60).map { "f" + it.toString().padStart(3, '0') + ".tmp" }
        val old = 1_000L
        for (name in names) {
            assertTrue(files.writeAtomically(TestPaths.join(directory, name), bytes("old $name")).isSuccess)
            assertTrue(TestEnv.setLastModified(TestPaths.join(directory, name), old))
        }
        val visited: MutableList<String> = mutableListOf()
        val result: FileResult<Int> = files.sweep(directory, olderThanMillis = 60_000L) { name ->
            visited += name
            val index: Int = names.indexOf(name)
            // While the walk is inside the listing: replace the next file (fresh mtime) and delete the one after it.
            if (index % 3 == 0 && index + 2 < names.size) {
                assertTrue(files.writeAtomically(TestPaths.join(directory, names[index + 1]), bytes("fresh ${names[index + 1]}")).isSuccess)
                assertTrue(files.delete(TestPaths.join(directory, names[index + 2])).isSuccess)
            }
            true
        }
        assertTrue(result.isSuccess, "$result")
        // The order of a sweep is not specified (list is sorted, sweep is not): replay the order it really took.
        assertEquals(names, visited.sorted(), "every entry of the listing taken at the start is offered to match exactly once")
        val present: MutableSet<String> = names.toMutableSet()
        val fresh: MutableSet<String> = mutableSetOf()
        var expectedDeleted = 0
        for (name in visited) {
            val index: Int = names.indexOf(name)
            if (index % 3 == 0 && index + 2 < names.size) {
                present += names[index + 1]
                fresh += names[index + 1]
                present -= names[index + 2]
            }
            // match is true for every name here; an entry is deleted when it is still there and not newer than the age.
            if (name in present && name !in fresh) {
                expectedDeleted++
                present -= name
            }
        }
        val left: List<String> = files.list(directory).getOrNull().orEmpty()
        assertEquals(present.sorted(), left, "survivors differ from the replay of the visiting order")
        for (name in left) assertEquals("fresh $name", files.readBytes(TestPaths.join(directory, name), 100).getOrNull()?.decodeToString())
        assertEquals(expectedDeleted, result.getOrNull(), "the count is the number of entries this sweep deleted")
    }
}
