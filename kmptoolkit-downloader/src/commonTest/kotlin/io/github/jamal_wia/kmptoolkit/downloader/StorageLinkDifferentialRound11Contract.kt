package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B round 11, Q1 (iOS-first differential). A symbolic link at the resource path of a unit, on both shipped storages.
 * Contract: 04-api-reference "isResourceAvailable ... a symbolic link to a file counts, a directory or a dangling link does
 * not"; getResourceSize is the size of the resource; deleteResource removes the resource and nothing else, so what a
 * link points to outside the resource path is never touched.
 */
abstract class StorageLinkDifferentialRound11Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun pathExists(path: String): Boolean
    protected abstract fun makeLink(link: String, target: String)
    protected abstract fun isLink(path: String): Boolean
    protected abstract fun deleteTestDirectory()

    @AfterTest
    fun cleanUp() {
        deleteTestDirectory()
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class LinkUnit(override val relativePath: String, override val format: ResourceFormat) : DownloadUnit {
        override val id: String = "link-unit"
        override val apiPath: String = "test/link-unit"
        override val group: ResourceGroup = Group("g")
    }

    private val payload: ByteArray = ByteArray(300) { b: Int -> (b % 7 + 1).toByte() }

    private fun opaque(): LinkUnit = LinkUnit("res/link.bin", ResourceFormat.Opaque)

    private fun archive(): LinkUnit = LinkUnit("res/tree", ResourceFormat.ZipArchive("marker.txt"))

    private fun elsewhere(storage: DownloaderStorage, unit: DownloadUnit, name: String): String =
        TestPaths.join(Paths.parentOf(storage.getResourcePath(unit))!!, name)

    @Test
    fun `deleting a link to a file removes the link and keeps the file`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque()
        val real: String = elsewhere(storage, unit, "real.bin")
        writeFile(real, payload)
        makeLink(storage.getResourcePath(unit), real)

        storage.deleteResource(unit)

        assertFalse(isLink(storage.getResourcePath(unit)), "the link must be gone")
        assertTrue(pathExists(real), "the file the link pointed to must stay")
        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `deleting a link to a live directory removes the link and keeps every file of the directory`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque()
        val live: String = elsewhere(storage, unit, "live")
        writeFile(TestPaths.join(live, "keep.txt"), payload)
        writeFile(TestPaths.join(live, "sub/keep2.txt"), payload)
        makeLink(storage.getResourcePath(unit), live)

        storage.deleteResource(unit)

        assertFalse(isLink(storage.getResourcePath(unit)), "the link must be gone")
        assertTrue(pathExists(TestPaths.join(live, "keep.txt")), "a file behind the link was deleted")
        assertTrue(pathExists(TestPaths.join(live, "sub/keep2.txt")), "a file behind the link was deleted")
    }

    @Test
    fun `a dangling link is not available has no size and deleting it leaves nothing that reads as a resource`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque()
        makeLink(storage.getResourcePath(unit), elsewhere(storage, unit, "nowhere.bin"))

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(0L, storage.getResourceSize(unit))

        storage.deleteResource(unit)

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(0L, storage.getResourceSize(unit))
    }

    @Test
    fun `deleting an archive unit whose tree path is a link to a live directory keeps the directory`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = archive()
        val live: String = elsewhere(storage, unit, "live-tree")
        writeFile(TestPaths.join(live, "marker.txt"), payload)
        writeFile(TestPaths.join(live, "keep.txt"), payload)
        makeLink(storage.getResourcePath(unit), live)

        storage.deleteResource(unit)

        assertTrue(pathExists(TestPaths.join(live, "marker.txt")), "a file behind the link was deleted")
        assertTrue(pathExists(TestPaths.join(live, "keep.txt")), "a file behind the link was deleted")
        assertFalse(isLink(storage.getResourcePath(unit)), "the link must be gone")
    }

    @Test
    fun `a commit onto a link to a file replaces the link and keeps the file it pointed to`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque()
        val real: String = elsewhere(storage, unit, "real.bin")
        writeFile(real, payload)
        makeLink(storage.getResourcePath(unit), real)

        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), ByteArray(50) { 5 })
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)

        assertFalse(isLink(storage.getResourcePath(unit)), "the commit must replace the link, not write through it")
        assertEquals(50L, storage.getResourceSize(unit))
        assertTrue(pathExists(real), "the file the link pointed to must stay")
        assertTrue(storage.isResourceAvailable(unit))
    }
}
