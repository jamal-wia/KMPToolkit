package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage

/**
 * The one [DownloaderStorage] fake every consumer test should use. Availability is decided by
 * [availableIds] (mutable, so a test can flip a unit mid-scenario), sizes by a fixed value given at
 * construction, and the destructive calls are recorded ([deletedResources] / [deletedTempFiles]) as
 * well as reported through [onEvent] — the hook exists for tests that assert the ORDER of calls
 * across several fakes by appending to one shared event list.
 */
public class FakeDownloaderStorage(
    availableIds: Set<String> = emptySet(),
    private val sizeOnDisk: Long = 0L,
    private val onEvent: (String) -> Unit = {},
) : DownloaderStorage {

    public val availableIds: MutableSet<String> = availableIds.toMutableSet()
    public val deletedResources: MutableList<DownloadUnit> = mutableListOf()
    public val deletedTempFiles: MutableList<DownloadUnit> = mutableListOf()

    override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit.id in availableIds
    override fun getResourcePath(unit: DownloadUnit): String = "/fake/${unit.relativePath}"
    override fun getTempFilePath(unit: DownloadUnit): String = ""
    override fun isTempFileAvailable(unit: DownloadUnit): Boolean = false
    override fun getTempFileSize(unit: DownloadUnit): Long = 0L
    override suspend fun commitResource(unit: DownloadUnit): Unit = Unit
    override fun getResourceSize(unit: DownloadUnit): Long = sizeOnDisk

    override fun deleteTempFile(unit: DownloadUnit) {
        deletedTempFiles += unit
        onEvent("deleteTempFile:${unit.id}")
    }

    override fun deleteResource(unit: DownloadUnit) {
        deletedResources += unit
        availableIds.remove(unit.id)
        onEvent("deleteResource:${unit.id}")
    }
}
