package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B round 12, Q4: a seeded differential of the layout of an archive from resolved segments against an INDEPENDENT oracle
 * derived from the documented contract (names resolved lexically on `/` only, a climbing name fails, a name that is a file
 * for one entry and a directory for another fails, the same name twice is one entry and the later bytes win, every entry is
 * extracted or the commit fails and leaves nothing). The oracle shares no code with the storages: it resolves with a
 * path stack of its own and keeps a map of kinds. Names use lower case ASCII only, so no volume folds two of them.
 * Both platforms run the same lists (same seed), so a divergence between Android and iOS shows as a failure on one of them.
 */
abstract class ZipSeededOracleRound12Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun isDirectory(path: String): Boolean
    protected abstract fun deleteTestDirectory()

    @AfterTest
    fun cleanUp() {
        deleteTestDirectory()
        LegacyTempMigrations.resetForTests()
    }

    private class ArchiveUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/archives/$id"
        override val relativePath: String = "archives/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@ArchiveUnit)
        }

        override fun toString(): String = "ArchiveUnit($id)"
    }

    private class Entry(val name: String, val directory: Boolean, val data: String)

    /** What the contract says one archive becomes: null when the commit must fail, else path to (data, or null for a directory). */
    private fun oracle(entries: List<Entry>): Map<String, String?>? {
        val tree: MutableMap<String, String?> = LinkedHashMap()
        tree["marker.txt"] = "m"
        for (entry: Entry in entries) {
            val stack: ArrayList<String> = ArrayList()
            // path-hygiene-ignore: PH3 a zip entry name, not a native path: the format splits it on / on every platform
            for (part: String in entry.name.split("/")) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (stack.isEmpty()) return null else stack.removeAt(stack.size - 1)
                    else -> stack.add(part)
                }
            }
            if (stack.isEmpty()) {
                // The extraction directory itself: a directory entry is a no-op, a file entry cannot be the directory.
                if (entry.directory) continue else return null
            }
            for (depth: Int in 1..stack.size) {
                val path: String = stack.subList(0, depth).joinToString("/")
                val wantsDirectory: Boolean = depth < stack.size || entry.directory
                if (path in tree) {
                    val isFile: Boolean = tree[path] != null
                    if (isFile == wantsDirectory) return null
                } else {
                    tree[path] = if (wantsDirectory) null else entry.data
                }
            }
            if (!entry.directory) tree[stack.joinToString("/")] = entry.data
        }
        return tree
    }

    private fun randomEntries(random: Random): List<Entry> {
        val segments: List<String> = listOf("a", "b", "c", "d", ".", "..", "", "a", "b")
        return List(1 + random.nextInt(6)) { index: Int ->
            val parts: List<String> = List(1 + random.nextInt(4)) { segments[random.nextInt(segments.size)] }
            val trailing: Boolean = random.nextInt(4) == 0
            val name: String = parts.joinToString("/") + if (trailing) "/" else ""
            Entry(name, name.endsWith("/"), "e$index")
        }
    }

    /** Every path over {a,b,c,d} of depth up to three: the universe the oracle's tree is compared on. */
    private val universe: List<String> = buildList {
        val letters: List<String> = listOf("a", "b", "c", "d")
        for (first: String in letters) {
            add(first)
            for (second: String in letters) {
                add("$first/$second")
                for (third: String in letters) add("$first/$second/$third")
            }
        }
    }

    @Test
    fun `fifteen hundred seeded archives extract as the independent oracle says`() = runTest {
        val seed = 12
        val random = Random(seed)
        val storage: DownloaderStorage = newStorage()
        var accepted = 0
        var refused = 0
        for (round: Int in 0 until 1_500) {
            val entries: List<Entry> = randomEntries(random)
            val expected: Map<String, String?>? = oracle(entries)
            val label = "seed $seed archive #$round ${entries.map { it.name }}"
            val unit = ArchiveUnit("o$round")
            storage.beginTempFile(unit, null)
            val zipEntries: List<ZipEntryRound6> =
                listOf(ZipEntryRound6("marker.txt", "m")) + entries.map { ZipEntryRound6(it.name, if (it.directory) "" else it.data) }
            writeFile(storage.getTempFilePath(unit), ZipBytesRound6.build(zipEntries).bytes)
            storage.markTempFileComplete(unit)
            val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
            if (expected == null) {
                refused++
                assertTrue(failure is ResourceIntegrityException, "$label: expected a refusal, was $failure")
                assertFalse(storage.isResourceAvailable(unit), "$label: a refused archive is available")
                assertEquals(TempFileState.None, storage.tempFileState(unit), "$label: temp file kept")
            } else {
                accepted++
                assertEquals(null, failure, "$label: expected a clean commit")
                assertTrue(storage.isResourceAvailable(unit), "$label: not available")
                val base: String = storage.getResourcePath(unit)
                for (path: String in universe + "marker.txt") {
                    val kind: String? = expected[path]
                    val onDisk: Boolean = pathExists(TestPaths.join(base, path))
                    assertEquals(path in expected, onDisk, "$label: existence of '$path' (oracle tree $expected)")
                    if (path in expected) {
                        if (kind == null) assertTrue(isDirectory(TestPaths.join(base, path)), "$label: '$path' must be a directory")
                        else assertEquals(kind, readFile(TestPaths.join(base, path))?.decodeToString(), "$label: bytes of '$path'")
                    }
                }
                storage.deleteResource(unit)
            }
        }
        // The generator must reach both outcomes in numbers, or the differential is vacuous.
        assertTrue(accepted >= 100 && refused >= 100, "seed $seed: accepted $accepted refused $refused")
    }
}
