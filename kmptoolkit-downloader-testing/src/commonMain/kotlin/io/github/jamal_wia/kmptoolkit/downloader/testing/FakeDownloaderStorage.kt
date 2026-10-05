package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.DownloaderStorage
import io.github.jamal_wia.kmptoolkit.downloader.ResourceIntegrityException
import io.github.jamal_wia.kmptoolkit.downloader.Sha256
import io.github.jamal_wia.kmptoolkit.downloader.TempFileState

/**
 * The one [DownloaderStorage] fake every consumer test should use. Availability is decided by
 * [availableIds] (mutable, so a test can flip a unit mid-scenario), what is left of a transfer by
 * [tempFileStates] (a unit absent from it has [TempFileState.None]), sizes by a fixed value given
 * at construction, and the destructive calls are recorded ([deletedResources] /
 * [deletedTempFiles]) as well as reported through [onEvent] — the hook exists for tests that
 * assert the ORDER of calls across several fakes by appending to one shared event list.
 *
 * **Strict about the hash-expectation protocol** — deliberately STRICTER than the real storages,
 * which accept a [markTempFileComplete] with no [beginTempFile] (a legacy partial, or a resume of a
 * transfer begun earlier). The party that owns the writer must call [beginTempFile] before
 * [markTempFileComplete], and a mark for an id not in [begunIds] throws [IllegalStateException] — a
 * downloader test that forgot the begin call fails here instead of silently losing its hash check
 * on a device. To model a transfer begun earlier (a correct `206` resume of a partial from before
 * the fake existed), seed [begunIds] together with [tempFileStates] instead of calling
 * [beginTempFile], which keeps [beganWith] empty. [beginTempFile] also enforces the contradiction
 * rule (unit hash and expected hash both set and different throw [IllegalArgumentException]) and is
 * recorded in [beganWith].
 *
 * [commitResource] mirrors the real contract too: it throws [IllegalStateException] unless the
 * unit's state is [TempFileState.Complete], and consumes the temp file on success. Add unit ids to
 * [failIntegrityFor] to make [commitResource] throw [ResourceIntegrityException] for them (a unit's
 * temp file is then consumed as a failed check deletes it, so a new [beginTempFile] is required).
 * The fake hashes nothing: it cannot tell whether a downloader passed the right hash, only that it
 * began the transfer; assert on [beganWith] for the hash itself.
 */
public class FakeDownloaderStorage(
    availableIds: Set<String> = emptySet(),
    private val sizeOnDisk: Long = 0L,
    private val onEvent: (String) -> Unit = {},
) : DownloaderStorage {

    public val availableIds: MutableSet<String> = availableIds.toMutableSet()
    public val tempFileStates: MutableMap<String, TempFileState> = mutableMapOf()
    public val deletedResources: MutableList<DownloadUnit> = mutableListOf()
    public val deletedTempFiles: MutableList<DownloadUnit> = mutableListOf()

    /**
     * Every [beginTempFile] call in order, as (unit id, expected hash). A null hash is a begin
     * without a backend-stated hash, which is different from no entry at all.
     */
    public val beganWith: MutableList<Pair<String, Sha256?>> = mutableListOf()

    /** Ids whose [commitResource] throws [ResourceIntegrityException], as a failed hash check would. */
    public val failIntegrityFor: MutableSet<String> = mutableSetOf()

    /**
     * Ids whose transfer has begun, which [markTempFileComplete] requires. [beginTempFile] adds to
     * it; [deleteTempFile] and [commitResource] remove from it. Seed it together with
     * [tempFileStates] to model a transfer begun before this fake existed, such as a partial being
     * resumed, without a [beganWith] entry.
     */
    public val begunIds: MutableSet<String> = mutableSetOf()

    override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit.id in availableIds
    override fun getResourcePath(unit: DownloadUnit): String = "/fake/${unit.relativePath}"
    override fun getTempFilePath(unit: DownloadUnit): String = ""
    override fun tempFileState(unit: DownloadUnit): TempFileState =
        tempFileStates[unit.id] ?: TempFileState.None

    override fun getTempFileSize(unit: DownloadUnit): Long = 0L

    /**
     * Replaces any transfer of [unit] (state [TempFileState.None] afterwards) and records the call
     * in [beganWith]. Throws [IllegalArgumentException], recording nothing, when [DownloadUnit.sha256]
     * and [expectedSha256] are both set and differ.
     */
    override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) {
        val unitSha256: Sha256? = unit.sha256
        require(unitSha256 == null || expectedSha256 == null || unitSha256 == expectedSha256) {
            "DownloadUnit.sha256 of $unit contradicts the hash the transfer expects"
        }
        beganWith += unit.id to expectedSha256
        begunIds += unit.id
        tempFileStates.remove(unit.id)
    }

    /**
     * Moves a [TempFileState.Partial] unit to [TempFileState.Complete]; anything else stays.
     * Throws [IllegalStateException] when [unit] is not in [begunIds]: [beginTempFile] was not
     * called for it since its last delete or commit.
     */
    override fun markTempFileComplete(unit: DownloadUnit) {
        check(unit.id in begunIds) {
            "markTempFileComplete($unit) without beginTempFile($unit, expectedSha256) first: the " +
                "host's downloader forgot to begin the temp file, so its hash would never be checked"
        }
        if (tempFileStates[unit.id] == TempFileState.Partial) {
            tempFileStates[unit.id] = TempFileState.Complete
        }
    }

    /**
     * Throws [IllegalStateException] unless [unit]'s state is [TempFileState.Complete]. Otherwise the
     * temp file is consumed (state [TempFileState.None], [begunIds] cleared) and, for an id in
     * [failIntegrityFor], [ResourceIntegrityException] is thrown as a failed hash check would.
     */
    override suspend fun commitResource(unit: DownloadUnit) {
        check(tempFileStates[unit.id] == TempFileState.Complete) {
            "No complete temp file for $unit — a partial transfer is resumed, never committed"
        }
        begunIds.remove(unit.id)
        tempFileStates.remove(unit.id)
        if (unit.id in failIntegrityFor) {
            throw ResourceIntegrityException("Fake integrity failure for $unit")
        }
    }

    override fun getResourceSize(unit: DownloadUnit): Long = sizeOnDisk

    override fun deleteTempFile(unit: DownloadUnit) {
        deletedTempFiles += unit
        tempFileStates.remove(unit.id)
        begunIds.remove(unit.id)
        onEvent("deleteTempFile:${unit.id}")
    }

    override fun deleteResource(unit: DownloadUnit) {
        deletedResources += unit
        availableIds.remove(unit.id)
        onEvent("deleteResource:${unit.id}")
    }
}
