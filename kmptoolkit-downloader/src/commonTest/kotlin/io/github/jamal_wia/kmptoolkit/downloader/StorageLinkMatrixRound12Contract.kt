package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.files.Paths
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * B round 12, Q2: the link and size family after the r11 fix, on both platforms through one table. Sentences it is derived from
 * (DownloaderStorage KDoc, 04-api-reference): "a symbolic link to a file counts [as available], a directory or a dangling link
 * does not"; "a symbolic link at the unit's path is followed (it is the resource), links inside a directory are not followed and
 * add nothing"; "a commit replaces a link, never writes through it"; deleting removes a link and never touches what it points
 * to; deleteTempFile and beginTempFile discard the temp file, and a link at the temp path is that file, never the bytes behind it.
 */
abstract class StorageLinkMatrixRound12Contract {

    protected abstract fun newStorage(): DownloaderStorage
    protected abstract fun writeFile(path: String, bytes: ByteArray)
    protected abstract fun readFile(path: String): ByteArray?
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

    private class LinkUnit(override val id: String, override val relativePath: String, override val format: ResourceFormat) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val group: ResourceGroup = Group("g")
    }

    private val payload: ByteArray = ByteArray(300) { b: Int -> (b % 7 + 1).toByte() }

    private fun opaque(id: String): LinkUnit = LinkUnit(id, "res/$id.bin", ResourceFormat.Opaque)

    private fun archive(id: String): LinkUnit = LinkUnit(id, "res/$id", ResourceFormat.ZipArchive("marker.txt"))

    private fun elsewhere(storage: DownloaderStorage, unit: DownloadUnit, name: String): String =
        TestPaths.join(Paths.parentOf(storage.getResourcePath(unit))!!, name)

    private fun baseOf(storage: DownloaderStorage, unit: DownloadUnit): String = Paths.parentOf(Paths.parentOf(storage.getResourcePath(unit))!!)!!

    private fun stageTemp(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), bytes)
        storage.markTempFileComplete(unit)
    }

    @Test
    fun `a chain of links to a file is available has the size of the file and deleting removes only the first link`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("chain")
        val real: String = elsewhere(storage, unit, "real.bin")
        val middle: String = elsewhere(storage, unit, "middle-link")
        writeFile(real, payload)
        makeLink(middle, real)
        makeLink(storage.getResourcePath(unit), middle)

        assertTrue(storage.isResourceAvailable(unit), "a chain of links to a file is the file")
        assertEquals(payload.size.toLong(), storage.getResourceSize(unit))
        storage.deleteResource(unit)
        assertFalse(pathExists(storage.getResourcePath(unit)) || isLink(storage.getResourcePath(unit)), "the link at the unit path must be gone")
        assertTrue(isLink(middle), "the next link of the chain must stay")
        assertEquals(payload.toList(), readFile(real)?.toList(), "the file must stay whole")
    }

    @Test
    fun `a link loop at the unit path is not available has no size and a commit replaces it`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("loop")
        val first: String = storage.getResourcePath(unit)
        val second: String = elsewhere(storage, unit, "loop-b")
        makeLink(first, second)
        makeLink(second, first)

        assertFalse(storage.isResourceAvailable(unit), "a loop does not resolve to a file")
        assertEquals(0L, storage.getResourceSize(unit))
        storage.deleteResource(unit) // must not throw
        assertFalse(storage.isResourceAvailable(unit))

        stageTemp(storage, unit, payload)
        storage.commitResource(unit)
        assertTrue(storage.isResourceAvailable(unit), "a commit over a loop must produce the resource")
        assertEquals(payload.toList(), readFile(first)?.toList())
        assertFalse(isLink(first), "a commit replaces the link, it never writes through it")
    }

    @Test
    fun `a link to a live directory at a plain file path is not available is totalled and deleted as a link`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("dirlink")
        val live: String = elsewhere(storage, unit, "live-dir")
        writeFile(TestPaths.join(live, "a.bin"), payload)
        writeFile(TestPaths.join(live, "sub/b.bin"), ByteArray(40) { 1 })
        makeLink(storage.getResourcePath(unit), live)

        assertFalse(storage.isResourceAvailable(unit), "a directory at a plain file path is not the resource")
        assertEquals(payload.size + 40L, storage.getResourceSize(unit), "a link at the unit path is followed, a directory there is totalled")
        storage.deleteResource(unit)
        assertFalse(isLink(storage.getResourcePath(unit)), "the link must be gone")
        assertEquals(payload.toList(), readFile(TestPaths.join(live, "a.bin"))?.toList(), "the directory behind the link must stay whole")
    }

    @Test
    fun `a link to a tree with its marker is an available archive and one without the marker is not`() {
        val storage: DownloaderStorage = newStorage()
        val withMarker: LinkUnit = archive("withmarker")
        val live: String = elsewhere(storage, withMarker, "live-with")
        writeFile(TestPaths.join(live, "marker.txt"), payload)
        makeLink(storage.getResourcePath(withMarker), live)
        assertTrue(storage.isResourceAvailable(withMarker), "a link at the unit's path is the resource")

        val without: LinkUnit = archive("nomarker")
        val half: String = elsewhere(storage, without, "live-without")
        writeFile(TestPaths.join(half, "other.txt"), payload)
        makeLink(storage.getResourcePath(without), half)
        assertFalse(storage.isResourceAvailable(without), "a tree without its marker does not read as available")
        assertEquals(payload.size.toLong(), storage.getResourceSize(without), "a tree whose marker is missing is still totalled")
    }

    @Test
    fun `a commit never writes through a link at the complete temp path`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("completelink")
        storage.beginTempFile(unit, null)
        val outside: String = elsewhere(storage, unit, "outside-complete.bin")
        writeFile(outside, payload)
        makeLink(storage.getTempFilePath(unit) + ".complete", outside)

        // A link standing in for the complete file reads as Complete (it resolves to bytes); committing must move or fail, never alter the target.
        val state: TempFileState = storage.tempFileState(unit)
        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
        assertEquals(payload.toList(), readFile(outside)?.toList(), "the bytes behind the link must be intact (state $state, failure $failure)")
        if (failure == null) assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `a link in place of the staging directory is removed as a link and the commit extracts into a fresh directory`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = archive("staginglink")
        val outsideDir: String = elsewhere(storage, unit, "outside-staging")
        writeFile(TestPaths.join(outsideDir, "keep.txt"), payload)
        makeLink(TestPaths.join(baseOf(storage, unit), "tmp/staging-${unit.id}"), outsideDir)
        stageTemp(storage, unit, ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("x.txt", "payload"))).bytes)

        storage.commitResource(unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(payload.toList(), readFile(TestPaths.join(outsideDir, "keep.txt"))?.toList(), "the directory behind the staging link must stay whole")
        assertEquals(null, readFile(TestPaths.join(outsideDir, "x.txt")), "nothing may be extracted through the staging link")
        assertNotNull(readFile(TestPaths.join(storage.getResourcePath(unit), "x.txt")))
    }

    @Test
    fun `an interrupted staging that is a link is removed as a link when the temp file is deleted`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = archive("staginglink2")
        val outsideDir: String = elsewhere(storage, unit, "outside-staging2")
        writeFile(TestPaths.join(outsideDir, "keep.txt"), payload)
        val staging: String = TestPaths.join(baseOf(storage, unit), "tmp/staging-${unit.id}")
        makeLink(staging, outsideDir)

        storage.deleteTempFile(unit)

        assertEquals(payload.toList(), readFile(TestPaths.join(outsideDir, "keep.txt"))?.toList(), "deleteTempFile must not walk through a link into a directory outside the base")
    }

    @Test
    fun `a link inside an archive tree to an outside file or directory adds nothing and deleting the unit keeps both`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = archive("innerlinks")
        val tree: String = storage.getResourcePath(unit)
        writeFile(TestPaths.join(tree, "marker.txt"), payload)
        writeFile(TestPaths.join(tree, "inside.bin"), ByteArray(10) { 1 })
        val outsideFile: String = elsewhere(storage, unit, "outside-file.bin")
        val outsideDir: String = elsewhere(storage, unit, "outside-dir2")
        writeFile(outsideFile, ByteArray(500) { 2 })
        writeFile(TestPaths.join(outsideDir, "far.bin"), ByteArray(700) { 3 })
        makeLink(TestPaths.join(tree, "to-file"), outsideFile)
        makeLink(TestPaths.join(tree, "to-dir"), outsideDir)
        makeLink(TestPaths.join(tree, "to-inside"), TestPaths.join(tree, "inside.bin"))

        assertEquals(payload.size + 10L, storage.getResourceSize(unit))
        storage.deleteResource(unit)
        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(500, readFile(outsideFile)?.size, "the file behind a link inside the tree must stay")
        assertEquals(700, readFile(TestPaths.join(outsideDir, "far.bin"))?.size, "the directory behind a link inside the tree must stay")
    }

    @Test
    fun `an empty partial file reads as no temp file as documented`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("emptypartial")
        storage.beginTempFile(unit, null)
        writeFile(storage.getTempFilePath(unit), ByteArray(0))

        assertEquals(TempFileState.None, storage.tempFileState(unit), "TempFileState.None is \"no file, or an empty one\"")
        assertEquals(0L, storage.getTempFileSize(unit))
    }

    @Test
    fun `every entry of the trash directory is reclaimed by the next deleteTempFile of any unit`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("sweeper")
        val trash: String = TestPaths.join(baseOf(storage, unit), "tmp/kmptoolkit-internal/trash")
        for (name: String in listOf("1", "2", "3")) writeFile(TestPaths.join(trash, "$name/leftover.bin"), payload)

        storage.deleteTempFile(unit)

        for (name: String in listOf("1", "2", "3")) assertFalse(pathExists(TestPaths.join(trash, name)), "trash entry $name must be swept")
    }

    @Test
    fun `a directory spelled like the marker does not make an archive available`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = archive("markerdir")
        val tree: String = storage.getResourcePath(unit)
        writeFile(TestPaths.join(tree, "marker.txt/inner.bin"), payload)
        writeFile(TestPaths.join(tree, "other.bin"), payload)

        assertFalse(storage.isResourceAvailable(unit), "the availability marker must be a regular file")
    }

    @Test
    fun `a link at the temp path is the temp file and deleting or beginning never touches the bytes behind it`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("templink")
        storage.beginTempFile(unit, null)
        val outside: String = elsewhere(storage, unit, "outside.bin")
        writeFile(outside, payload)
        makeLink(storage.getTempFilePath(unit), outside)

        assertEquals(TempFileState.Partial, storage.tempFileState(unit), "a link to a non-empty file reads as the partial it points to")
        assertEquals(payload.size.toLong(), storage.getTempFileSize(unit))
        storage.deleteTempFile(unit)
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(payload.toList(), readFile(outside)?.toList(), "deleteTempFile must remove the link, not the file behind it")

        makeLink(storage.getTempFilePath(unit), outside)
        storage.beginTempFile(unit, null)
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(payload.toList(), readFile(outside)?.toList(), "beginTempFile must remove the link, not the file behind it")
    }

    @Test
    fun `a dangling link at the temp path reads as no temp file and deleting it never throws`() {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("tempdangling")
        storage.beginTempFile(unit, null)
        val missing: String = elsewhere(storage, unit, "missing.bin")
        makeLink(storage.getTempFilePath(unit), missing)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))
        storage.deleteTempFile(unit)
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(isLink(storage.getTempFilePath(unit)), "a dangling link at the temp path is part of the temp file and must be removed")
    }

    @Test
    fun `a link in place of the trash directory is removed as a link and never followed by a sweep`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque("trashlink")
        val outsideDir: String = elsewhere(storage, unit, "outside-trash")
        writeFile(TestPaths.join(outsideDir, "keep.txt"), payload)
        val trash: String = TestPaths.join(baseOf(storage, unit), "tmp/kmptoolkit-internal/trash")
        makeLink(trash, outsideDir)

        storage.deleteTempFile(unit)

        assertEquals(payload.toList(), readFile(TestPaths.join(outsideDir, "keep.txt"))?.toList(), "the trash sweep must not delete through a link into a directory outside the base")
        assertFalse(isLink(trash), "the link in place of the trash directory must be removed as a link")

        // A later detach and sweep work: an archive commit over an existing tree moves the old tree aside.
        val archiveUnit: LinkUnit = archive("aftertrashlink")
        writeFile(TestPaths.join(storage.getResourcePath(archiveUnit), "marker.txt"), payload)
        writeFile(TestPaths.join(storage.getResourcePath(archiveUnit), "old.txt"), payload)
        stageTemp(storage, archiveUnit, ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("new.txt", "fresh"))).bytes)
        storage.commitResource(archiveUnit)
        assertNotNull(readFile(TestPaths.join(storage.getResourcePath(archiveUnit), "new.txt")))
        assertEquals(null, readFile(TestPaths.join(storage.getResourcePath(archiveUnit), "old.txt")), "the old tree must be gone after the commit")
        assertEquals(payload.toList(), readFile(TestPaths.join(outsideDir, "keep.txt"))?.toList())
    }

    /** Puts a link at the trash path whose target [targetOf] names, runs a sweep, and checks the link is gone and the base keeps every file. */
    private fun assertTrashLinkRemovedAsLink(id: String, what: String, targetOf: (storage: DownloaderStorage, unit: LinkUnit, base: String) -> String) {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = opaque(id)
        val base: String = baseOf(storage, unit)
        val trash: String = TestPaths.join(base, "tmp/kmptoolkit-internal/trash")
        val outsideFile: String = elsewhere(storage, unit, "outside-$id.bin")
        val baseProbe: String = TestPaths.join(base, "probe-$id.bin")
        writeFile(outsideFile, payload)
        writeFile(baseProbe, payload)
        makeLink(trash, targetOf(storage, unit, base))

        storage.deleteTempFile(unit)

        assertFalse(isLink(trash), "$what in place of the trash directory must be removed as a link")
        assertEquals(payload.toList(), readFile(outsideFile)?.toList(), "the file next to the link target must stay")
        assertEquals(payload.toList(), readFile(baseProbe)?.toList(), "the base directory must stay whole")
    }

    @Test
    fun `a link to a file in place of the trash directory is removed as a link and the file stays`() {
        assertTrashLinkRemovedAsLink("trashfile", "a link to a file") { storage, unit, _ -> elsewhere(storage, unit, "outside-trashfile.bin") }
    }

    @Test
    fun `a dangling link in place of the trash directory is removed as a link`() {
        assertTrashLinkRemovedAsLink("trashdangling", "a dangling link") { storage, unit, _ -> elsewhere(storage, unit, "outside-trash-missing") }
    }

    @Test
    fun `a link to the base directory in place of the trash directory is removed as a link and the base stays whole`() {
        assertTrashLinkRemovedAsLink("trashbase", "a link to the base directory") { _, _, base -> base }
    }

    @Test
    fun `a commit over an existing archive with a link in place of the trash directory moves the old tree into a real trash inside the base`() = runTest {
        val storage: DownloaderStorage = newStorage()
        val unit: LinkUnit = archive("detachlink")
        val tree: String = storage.getResourcePath(unit)
        writeFile(TestPaths.join(tree, "marker.txt"), payload)
        writeFile(TestPaths.join(tree, "old.txt"), payload)
        val outsideDir: String = elsewhere(storage, unit, "outside-detach")
        writeFile(TestPaths.join(outsideDir, "keep.txt"), payload)
        val trash: String = TestPaths.join(baseOf(storage, unit), "tmp/kmptoolkit-internal/trash")
        makeLink(trash, outsideDir)
        stageTemp(storage, unit, ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("new.txt", "fresh"))).bytes)

        storage.commitResource(unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertNotNull(readFile(TestPaths.join(tree, "new.txt")), "the new tree must be in place")
        assertEquals(null, readFile(TestPaths.join(tree, "old.txt")), "the old tree must be gone")
        assertFalse(isLink(trash), "the link in place of the trash directory must be removed as a link")
        assertEquals(payload.toList(), readFile(TestPaths.join(outsideDir, "keep.txt"))?.toList(), "nothing outside the trash may change")
    }
}
