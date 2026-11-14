package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.test.runTest
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileType
import platform.Foundation.NSFileTypeRegular
import platform.Foundation.NSFileTypeSymbolicLink
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.symlink

/**
 * B round 8, finding F1, iOS twin of the Android crash sweeps. A mid-walk kill cannot be injected on iOS, so this pins the
 * observable outcome of the move-aside swap (03-guide "move the old tree aside, rename the new one into place"): after a
 * replace, a delete or a first commit under a nested relativePath, the resource path holds exactly the new tree or nothing,
 * and `tmp/kmptoolkit-internal/trash/` is empty, with a leftover of a killed run swept.
 */
@OptIn(ExperimentalForeignApi::class)
class IosArchiveSwapRound8Test {
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "swap_round8")
    private val manager: NSFileManager = NSFileManager.defaultManager

    private val baseDirectory: String
        get() {
            val appSupport: String = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true)
                .first() as String
            return TestPaths.join(appSupport, config.baseDirectoryName)
        }

    // path-hygiene-ignore: PH4 UnitFileLayout.TRASH_DIRECTORY is a logical '/'-separated layout name; it is handed to TestPaths.join
    private val trashDirectory: String get() = TestPaths.join(baseDirectory, UnitFileLayout.TRASH_DIRECTORY)

    @BeforeTest
    fun setUp() {
        manager.removeItemAtPath(baseDirectory, error = null)
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        manager.removeItemAtPath(baseDirectory, error = null)
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class ArchiveUnit(override val group: ResourceGroup, override val relativePath: String) : DownloadUnit {
        override val id: String = "swap"
        override val apiPath: String = "test/swap"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
    }

    private fun writeFile(path: String, bytes: ByteArray) {
        manager.createDirectoryAtPath(
            path = path.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        val file = fopen(path, "wb") ?: error("could not open $path")
        try {
            if (bytes.isNotEmpty()) {
                bytes.usePinned { pinned -> fwrite(pinned.addressOf(0), 1u.convert(), bytes.size.convert(), file) }
            }
        } finally {
            fclose(file)
        }
    }

    private suspend fun commit(storage: DownloaderStorage, unit: DownloadUnit, entries: List<ZipEntryRound6>) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), ZipBytesRound6.build(entries).bytes)
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)
    }

    /** The relative names of the regular files under [directory], sorted. */
    private fun filesUnder(directory: String): List<String> =
        manager.subpathsOfDirectoryAtPath(directory, error = null).orEmpty().filterIsInstance<String>().filter { name: String ->
            val attributes: Map<Any?, *>? = manager.attributesOfItemAtPath(TestPaths.join(directory, name), error = null)
            (attributes?.get(NSFileType) as? String) == NSFileTypeRegular
        }.sorted()

    private fun trashEntries(): List<String> =
        manager.contentsOfDirectoryAtPath(trashDirectory, error = null).orEmpty().filterIsInstance<String>()

    /** A tree a killed run left in the trash: a marker over missing files, which no resource path can name. */
    private fun plantTrashLeftover() {
        writeFile(TestPaths.join(trashDirectory, "424242/marker.txt"), "stale".encodeToByteArray())
        writeFile(TestPaths.join(trashDirectory, "424242/sub/old.bin"), ByteArray(10))
    }

    @Test
    fun `replacing a committed archive leaves exactly the new tree and an empty trash`() = runTest {
        val unit = ArchiveUnit(Group("g"), "archives/swap")
        val storage: DownloaderStorage = createDownloaderStorage(config)
        commit(
            storage,
            unit,
            listOf(
                ZipEntryRound6("marker.txt", "v1"),
                ZipEntryRound6("old-only.txt", "x"),
                ZipEntryRound6("sub/old-deep.txt", "y"),
                ZipEntryRound6("shared.txt", "v1"),
            ),
        )
        plantTrashLeftover()

        commit(storage, unit, listOf(ZipEntryRound6("marker.txt", "v2"), ZipEntryRound6("shared.txt", "v2")))

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(listOf("marker.txt", "shared.txt"), filesUnder(storage.getResourcePath(unit)), "files of the replaced version survived")
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(emptyList(), trashEntries(), "the replaced tree and the leftover of a killed run must be gone from the trash")
    }

    @Test
    fun `deleting a committed archive leaves no resource and an empty trash`() = runTest {
        val unit = ArchiveUnit(Group("g"), "archives/swap")
        val storage: DownloaderStorage = createDownloaderStorage(config)
        commit(storage, unit, listOf(ZipEntryRound6("marker.txt", "v1"), ZipEntryRound6("sub/deep.txt", "y")))
        plantTrashLeftover()

        storage.deleteResource(unit)

        assertFalse(storage.isResourceAvailable(unit))
        assertFalse(manager.fileExistsAtPath(storage.getResourcePath(unit)), "a part of the tree is left at the resource path")
        assertEquals(emptyList(), trashEntries())
        // Safe when it is not present.
        storage.deleteResource(unit)
        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `the first commit under a nested relativePath lands the whole tree and leaves nothing in the trash`() = runTest {
        val unit = ArchiveUnit(Group("g"), "archives/pages/deep")
        val storage: DownloaderStorage = createDownloaderStorage(config)
        assertFalse(manager.fileExistsAtPath(TestPaths.parent(storage.getResourcePath(unit))!!), "the parent must be absent on a first commit")

        commit(storage, unit, listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("sub/a.txt", "a")))

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(listOf("marker.txt", "sub/a.txt"), filesUnder(storage.getResourcePath(unit)))
        assertEquals(emptyList(), trashEntries())
    }

    private fun isSymbolicLink(path: String): Boolean =
        (manager.attributesOfItemAtPath(path, error = null)?.get(NSFileType) as? String) == NSFileTypeSymbolicLink

    /** Makes [parent] of [link] and a symbolic link at [link] to [target]; the target need not exist. */
    private fun makeLink(link: String, target: String) {
        manager.createDirectoryAtPath(
            path = link.substringBeforeLast('/'),
            withIntermediateDirectories = true,
            attributes = null,
            error = null,
        )
        assertTrue(symlink(target, link) == 0, "could not create the symbolic link $link")
    }

    /**
     * A symbolic link at the resource path that points nowhere reads as absent to fileExistsAtPath, but the rename moves
     * the link itself. The commit used to skip the move aside on that reading and then fail to move the staging tree onto
     * the link, on every retry; now it replaces the link.
     */
    @Test
    fun `a commit replaces a dangling symbolic link at the resource path`() = runTest {
        val unit = ArchiveUnit(Group("g"), "archives/swap")
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val target: String = storage.getResourcePath(unit)
        makeLink(target, TestPaths.join(baseDirectory, "nowhere"))
        assertFalse(manager.fileExistsAtPath(target), "the link must dangle")
        assertTrue(isSymbolicLink(target))

        commit(storage, unit, listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("sub/a.txt", "a")))

        assertFalse(isSymbolicLink(target), "the link survived the commit")
        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(listOf("marker.txt", "sub/a.txt"), filesUnder(target))
        assertEquals(emptyList(), trashEntries())
    }

    /** A link to a live directory is replaced as a link: the directory it points to keeps every file. */
    @Test
    fun `a commit replaces a symbolic link to a live directory without touching the directory`() = runTest {
        val unit = ArchiveUnit(Group("g"), "archives/swap")
        val storage: DownloaderStorage = createDownloaderStorage(config)
        val target: String = storage.getResourcePath(unit)
        val live: String = TestPaths.join(baseDirectory, "live-elsewhere")
        writeFile(TestPaths.join(live, "keep.txt"), "keep".encodeToByteArray())
        makeLink(target, live)

        commit(storage, unit, listOf(ZipEntryRound6("marker.txt", "m")))

        assertFalse(isSymbolicLink(target), "the link survived the commit")
        assertEquals(listOf("marker.txt"), filesUnder(target))
        assertEquals(listOf("keep.txt"), filesUnder(live), "the commit reached through the link")
        assertEquals(emptyList(), trashEntries())
    }
}
