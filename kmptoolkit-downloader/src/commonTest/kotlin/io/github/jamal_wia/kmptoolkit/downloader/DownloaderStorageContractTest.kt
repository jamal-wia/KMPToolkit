package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The hash-expectation half of the [DownloaderStorage] contract, written once and run against both
 * shipped storages (the Android and iOS subclasses supply only the file access the test needs).
 *
 * Every case is derived from the contract in `DownloaderStorage`'s KDoc — what [beginTempFile]
 * promises, what [DownloaderStorage.commitResource] must check, and what must be left on disk
 * afterwards — not from what an implementation happens to do. Process death is modelled by asking
 * for a second storage instance over the same directory: nothing is shared between instances but
 * the files.
 */
abstract class DownloaderStorageContractTest {

    /** A new storage instance over this test's directory; repeated calls see the same files. */
    protected abstract fun newStorage(): DownloaderStorage

    /** Writes [bytes] to [path], creating missing parent directories. */
    protected abstract fun writeFile(path: String, bytes: ByteArray)

    /** The bytes of the regular file at [path], or null when there is none. */
    protected abstract fun readFile(path: String): ByteArray?

    /** True when [path] exists as a file or a directory. */
    protected abstract fun pathExists(path: String): Boolean

    /** Removes everything this test's storage wrote. */
    protected abstract fun deleteTestDirectory()

    /** The storage's base directory, exactly as the storage composes its paths from it. */
    protected abstract fun baseDirectory(): String

    /** Removes the file at [path] if there is one; used for a sentinel outside the base directory. */
    protected abstract fun deleteFile(path: String)

    @AfterTest
    fun deleteDirectoryAfterTest() {
        deleteTestDirectory()
        // The migration record is process-wide and keyed by base path and id; both are reused by
        // later tests, so a test that reuses an id would silently skip its migration without this.
        LegacyTempMigrations.resetForTests()
    }

    private val storage: DownloaderStorage by lazy { newStorage() }

    // 1
    @Test
    fun `a transfer begun with a hash commits when the bytes match and leaves nothing behind`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals("abc", readFile(storage.getResourcePath(unit))?.decodeToString())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)), "the record must not outlive the temp file")
    }

    // 2
    @Test
    fun `a transfer begun with a hash that the bytes do not match is rejected and cleaned up`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(pathExists(storage.getResourcePath(unit)), "nothing may reach the final path")
        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    // 3
    @Test
    fun `a mismatching archive is rejected before anything is extracted`() = runTest {
        val unit = ContractUnit("pages", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/pages")
        storage.beginTempFile(unit, OTHER)
        writeTempBytes(storage, unit, zipOf("marker.txt", "hello".encodeToByteArray()))

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(pathExists(storage.getResourcePath(unit)), "no directory may have been extracted")
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    @Test
    fun `an archive that passes its check is extracted and its record is dropped`() = runTest {
        val unit = ContractUnit("pages", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/pages")
        storage.beginTempFile(unit, null)
        writeTempBytes(storage, unit, zipOf("marker.txt", "hello".encodeToByteArray()))

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    @Test
    fun `a database whose hash is right but whose bytes are not a database is rejected and its record dropped`() = runTest {
        val unit = ContractUnit("db", format = ResourceFormat.SqliteDatabase())
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    // 4
    @Test
    fun `a transfer begun without a hash commits any bytes`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "whatever the server sent")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
    }

    @Test
    fun `beginning without a hash still enforces the unit's own hash`() = runTest {
        val unit = ContractUnit("model", declaredSha256 = ABC)
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "abd")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }

        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `bytes must match both the unit's hash and the recorded one`() = runTest {
        // Both state ABC: one comparison satisfies both.
        val unit = ContractUnit("model", declaredSha256 = ABC)
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `bytes matching the recorded hash but not the unit's are rejected`() = runTest {
        storage.beginTempFile(ContractUnit("model"), ABC)
        val committing = ContractUnit("model", declaredSha256 = OTHER)
        writeTemp(storage, committing, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, committing) }

        assertFalse(storage.isResourceAvailable(committing))
        assertEquals(TempFileState.None, storage.tempFileState(committing))
    }

    @Test
    fun `bytes matching the unit's hash but not the recorded one are rejected`() = runTest {
        storage.beginTempFile(ContractUnit("model"), OTHER)
        val committing = ContractUnit("model", declaredSha256 = ABC)
        writeTemp(storage, committing, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, committing) }

        assertFalse(storage.isResourceAvailable(committing))
        assertEquals(TempFileState.None, storage.tempFileState(committing))
    }

    // 5
    @Test
    fun `beginning again discards the partial and complete files and the old expectation`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abd")
        storage.markTempFileComplete(unit)
        writeTemp(storage, unit, "partial")

        storage.beginTempFile(unit, ABC)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))
        // The first hash is no longer enforced: the new bytes satisfy only the second one.
        writeTemp(storage, unit, "abc")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `the expectation of a later begin is the one enforced`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, unit) }
        assertFalse(storage.isResourceAvailable(unit))
    }

    // 6
    @Test
    fun `deleting the temp file drops the record and a later begin without a hash commits anything`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, OTHER)
        writeTemp(storage, unit, "abc")

        storage.deleteTempFile(unit)

        assertFalse(pathExists(recordPath(storage, unit)))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "arbitrary")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    // 7
    @Test
    fun `a second instance over the same directory enforces the recorded hash on mismatch`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, OTHER)
        writeTemp(before, unit, "abc")

        val after: DownloaderStorage = newStorage()
        assertEquals(TempFileState.Partial, after.tempFileState(unit))

        assertFailsWith<ResourceIntegrityException> { finish(after, unit) }
        assertFalse(after.isResourceAvailable(unit))
        assertFalse(pathExists(recordPath(after, unit)))
    }

    @Test
    fun `a second instance over the same directory commits when the recorded hash matches`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, ABC)
        writeTemp(before, unit, "abc")

        finish(newStorage(), unit)

        assertTrue(newStorage().isResourceAvailable(unit))
    }

    @Test
    fun `a file already complete before the second instance exists is still checked against the record`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, OTHER)
        writeTemp(before, unit, "abc")
        before.markTempFileComplete(unit)

        val after: DownloaderStorage = newStorage()
        assertEquals(TempFileState.Complete, after.tempFileState(unit))
        assertFailsWith<ResourceIntegrityException> { after.commitResource(unit) }

        assertFalse(after.isResourceAvailable(unit))
        assertEquals(TempFileState.None, after.tempFileState(unit))
        assertFalse(pathExists(recordPath(after, unit)))
    }

    @Test
    fun `a complete file recovered by a second instance commits when the record matches`() = runTest {
        val unit = ContractUnit("model")
        val before: DownloaderStorage = newStorage()
        before.beginTempFile(unit, ABC)
        writeTemp(before, unit, "abc")
        before.markTempFileComplete(unit)

        newStorage().commitResource(unit)

        assertTrue(newStorage().isResourceAvailable(unit))
        assertFalse(pathExists(recordPath(before, unit)))
    }

    // 8
    @Test
    fun `a partial without a record resumes and only the unit's hash is checked`() = runTest {
        val matching = ContractUnit("legacy-a", declaredSha256 = ABC)
        writeTemp(storage, matching, "abc")
        assertEquals(TempFileState.Partial, storage.tempFileState(matching))
        assertEquals(3L, storage.getTempFileSize(matching))
        finish(storage, matching)
        assertTrue(storage.isResourceAvailable(matching))

        val mismatching = ContractUnit("legacy-b", declaredSha256 = ABC)
        writeTemp(storage, mismatching, "abd")
        assertFailsWith<ResourceIntegrityException> { finish(storage, mismatching) }
        assertFalse(storage.isResourceAvailable(mismatching))

        val unconstrained = ContractUnit("legacy-c")
        writeTemp(storage, unconstrained, "anything")
        finish(storage, unconstrained)
        assertTrue(storage.isResourceAvailable(unconstrained))
    }

    // 9
    @Test
    fun `a record without a temp file reads as none and the next begin overwrites it`() = runTest {
        val unit = ContractUnit("model")
        writeFile(recordPath(storage, unit), "sha256:${OTHER.hex}\n".encodeToByteArray())

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(0L, storage.getTempFileSize(unit))

        storage.beginTempFile(unit, null)
        assertEquals("none", readFile(recordPath(storage, unit))?.decodeToString()?.trim())
        writeTemp(storage, unit, "abc")
        finish(storage, unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    @Test
    fun `beginning always writes a record even without a hash`() {
        val unit = ContractUnit("model")

        storage.beginTempFile(unit, null)

        assertTrue(pathExists(recordPath(storage, unit)))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    // 10
    @Test
    fun `an unreadable record is an integrity failure and never no check`() = runTest {
        for ((index: Int, garbage: String) in listOf("not a record", "", "sha256:xyz", "sha256:" + "a".repeat(63)).withIndex()) {
            val unit = ContractUnit("model-$index")
            storage.beginTempFile(unit, ABC)
            writeTemp(storage, unit, "abc")
            storage.markTempFileComplete(unit)
            writeFile(recordPath(storage, unit), garbage.encodeToByteArray())

            assertFailsWith<ResourceIntegrityException>("record '$garbage'") { storage.commitResource(unit) }

            assertFalse(storage.isResourceAvailable(unit), "record '$garbage'")
            assertEquals(TempFileState.None, storage.tempFileState(unit), "record '$garbage'")
            assertFalse(pathExists(recordPath(storage, unit)), "record '$garbage'")
        }
    }

    // 11
    @Test
    fun `contradictory hashes make begin fail and leave the existing transfer untouched`() {
        val original = ContractUnit("model")
        storage.beginTempFile(original, OTHER)
        writeTemp(storage, original, "partial")
        val recordBefore: ByteArray? = readFile(recordPath(storage, original))

        val contradictory = ContractUnit("model", declaredSha256 = ABC)
        assertFailsWith<IllegalArgumentException> { storage.beginTempFile(contradictory, OTHER) }

        assertEquals(TempFileState.Partial, storage.tempFileState(original))
        assertEquals(7L, storage.getTempFileSize(original))
        assertContentEquals(recordBefore, readFile(recordPath(storage, original)))
        assertFalse(pathExists(stagedRecordPath(storage, original)), "no staged record may be left behind")
    }

    @Test
    fun `the same hash stated twice is not a contradiction`() {
        val unit = ContractUnit("model", declaredSha256 = ABC)

        storage.beginTempFile(unit, ABC)

        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    // 12
    @Test
    fun `uppercase hex is accepted and equals its lowercase form`() = runTest {
        val upper: Sha256 = Sha256.parse(ABC.hex.uppercase())
        val unit = ContractUnit("model", declaredSha256 = upper)
        storage.beginTempFile(unit, upper)
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
    }

    // 13
    @Test
    fun `two unit instances with the same id share one record`() = runTest {
        val first = ContractUnit("model")
        val second = ContractUnit("model")
        storage.beginTempFile(first, OTHER)
        writeTemp(storage, second, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, second) }

        assertFalse(storage.isResourceAvailable(first))
        assertFalse(pathExists(recordPath(storage, first)))
    }

    @Test
    fun `the record is found whatever the temp extension of the committing unit`() = runTest {
        storage.beginTempFile(ContractUnit("model", tempExtension = "tmp"), OTHER)
        // Same id, another extension: its temp file is a different file, but the record is keyed by
        // id alone, so it must still be found and enforced.
        val part = ContractUnit("model", tempExtension = "part")
        writeTemp(storage, part, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, part) }

        assertFalse(storage.isResourceAvailable(part))
    }

    @Test
    fun `units whose ids differ only by a path separator keep separate records`() = runTest {
        val flat = ContractUnit("x")
        val nested = ContractUnit("x/y")
        storage.beginTempFile(flat, OTHER)
        storage.beginTempFile(nested, ABC)
        writeTemp(storage, flat, "abc")
        writeTemp(storage, nested, "abc")

        assertFailsWith<ResourceIntegrityException> { finish(storage, flat) }
        finish(storage, nested)

        assertFalse(storage.isResourceAvailable(flat))
        assertTrue(storage.isResourceAvailable(nested))
    }

    @Test
    fun `two different ids never share a record`() = runTest {
        val a = ContractUnit("a")
        val b = ContractUnit("a.tmp")
        storage.beginTempFile(a, OTHER)
        storage.beginTempFile(b, null)
        writeTemp(storage, b, "abc")

        finish(storage, b)

        assertTrue(storage.isResourceAvailable(b))
        assertTrue(pathExists(recordPath(storage, a)), "committing b must not touch a's record")
    }

    @Test
    fun `begin fails without renaming a record when the old temp file cannot be deleted`() {
        val unit = ContractUnit("stuck")
        // A non-empty directory at the partial path cannot be deleted on either platform:
        // java.io.File.delete() and POSIX remove(3) (which is rmdir(2) for a directory) both
        // refuse it, remove(3) with ENOTEMPTY, and neither deletes recursively.
        val partial: String = storage.getTempFilePath(unit)
        writeFile("$partial/child", "x".encodeToByteArray())
        val tmpDirectory: String = partial.substringBeforeLast('/')

        assertFailsWith<IllegalStateException> { storage.beginTempFile(unit, null) }

        assertFalse(pathExists("$tmpDirectory/expect/${unit.id}.sha256"), "the new record must not be paired with the surviving old file")
        assertFalse(pathExists("$tmpDirectory/expect-staged/${unit.id}.sha256"), "the staged record must be dropped")
        assertTrue(pathExists("$partial/child"), "the obstacle must still be there: nothing may remove it another way")
    }

    @Test
    fun `deleting a temp file that cannot be deleted keeps its record`() {
        val unit = ContractUnit("stuck-delete")
        storage.beginTempFile(unit, OTHER)
        // A non-empty directory at the partial path cannot be deleted by either platform.
        writeFile("${storage.getTempFilePath(unit)}/child", "x".encodeToByteArray())

        storage.deleteTempFile(unit)

        assertTrue(pathExists("${storage.getTempFilePath(unit)}/child"), "the data file must have survived")
        assertTrue(pathExists(recordPath(storage, unit)), "the record of a surviving file must be kept")
    }

    @Test
    fun `a record that cannot be read fails the commit and drops the file`() = runTest {
        val unit = ContractUnit("model")
        writeTemp(storage, unit, "abc")
        storage.markTempFileComplete(unit)
        // A directory where the record belongs: reading it fails on both platforms.
        writeFile("${recordPath(storage, unit)}/child", "x".encodeToByteArray())

        assertFailsWith<ResourceIntegrityException> { storage.commitResource(unit) }

        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(storage.isResourceAvailable(unit))
    }

    @Test
    fun `a commit that fails for another reason than integrity keeps the file and its record`() = runTest {
        val unit = ContractUnit("model")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")
        storage.markTempFileComplete(unit)

        val brokenGetter = ContractUnit("model", hashGetterThrows = true)
        assertFailsWith<IllegalArgumentException> { storage.commitResource(brokenGetter) }

        assertEquals(TempFileState.Complete, storage.tempFileState(unit))
        assertTrue(pathExists(recordPath(storage, unit)), "the retry must still be checked against the record")
        // The retry through a healthy unit is checked and succeeds.
        storage.commitResource(unit)
        assertTrue(storage.isResourceAvailable(unit))
    }

    // -- concurrent commits of one unit --------------------------------------------------------
    //
    // The contract says a commit is at-least-once (the group surface, the per-unit surface, a
    // second engine over the same directory and a self-committing downloader may all commit the
    // same unit), so one that arrives after another already finalized the unit must succeed and
    // must not touch what was committed.

    @Test
    fun `a commit that finds the unit already committed by another instance returns normally`() = runTest {
        val unit = ContractUnit("model")
        val first: DownloaderStorage = newStorage()
        first.beginTempFile(unit, ABC)
        writeTemp(first, unit, "abc")
        finish(first, unit)

        newStorage().commitResource(unit)

        assertEquals("abc", readFile(storage.getResourcePath(unit))?.decodeToString())
    }

    @Test
    fun `two instances committing one file at the same time both succeed and keep the file`() = runTest {
        commitTwiceConcurrently(ContractUnit("model"), "abc".encodeToByteArray())
    }

    @Test
    fun `two instances committing one archive at the same time both succeed and keep the extraction`() = runTest {
        val unit = ContractUnit("pages", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/pages")
        commitTwiceConcurrently(unit, zipOf("marker.txt", "hello".encodeToByteArray()))

        assertEquals("hello", readFile(storage.getResourcePath(unit) + "/marker.txt")?.decodeToString())
    }

    /**
     * Two storage instances over one directory commit [unit] at once. The first is parked inside its
     * commit, past every precondition, by a hash getter that blocks; the second starts only then.
     * Without mutual exclusion the second commits the whole unit while the first is parked, and the
     * first then resumes against a source that is gone (on iOS removing the committed destination
     * first). With it, the second waits, finds the unit committed and returns.
     */
    private suspend fun commitTwiceConcurrently(unit: ContractUnit, bytes: ByteArray) {
        val first: DownloaderStorage = newStorage()
        val second: DownloaderStorage = newStorage()
        first.beginTempFile(unit, null)
        writeTempBytes(first, unit, bytes)
        first.markTempFileComplete(unit)

        val parkedInside = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val parkingUnit = ContractUnit(
            id = unit.id,
            format = unit.format,
            relativePath = unit.relativePath,
            onHashRead = {
                parkedInside.complete(Unit)
                runBlocking { release.await() }
            },
        )
        // Real threads: commitResource does blocking file I/O on Dispatchers.IO, so virtual time
        // has nothing to schedule here.
        withContext(Dispatchers.Default) {
            val firstCommit: Deferred<Result<Unit>> = async { runCatching { first.commitResource(parkingUnit) } }
            parkedInside.await()
            val secondCommit: Deferred<Result<Unit>> = async { runCatching { second.commitResource(unit) } }
            // Give the second commit every chance to run to the end while the first is parked; with
            // the commit serialized it cannot, and this simply times out.
            withTimeoutOrNull(RACE_WINDOW) { secondCommit.join() }
            release.complete(Unit)

            val firstResult: Result<Unit> = firstCommit.await()
            val secondResult: Result<Unit> = secondCommit.await()
            assertTrue(firstResult.isSuccess, "first commit failed: ${firstResult.exceptionOrNull()}")
            assertTrue(secondResult.isSuccess, "second commit failed: ${secondResult.exceptionOrNull()}")
        }
        assertTrue(storage.isResourceAvailable(unit), "the committed resource must still be there")
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(recordPath(storage, unit)))
        if (!unit.isDirectoryResource) {
            assertContentEquals(bytes, readFile(storage.getResourcePath(unit)))
        }
    }

    // -- path safety: unit ids and relativePath ----------------------------------------------------
    // Ids that cannot be file names are stored under hashed names; see UnitFileLayout. The migration
    // runs once per process per unit; the @AfterTest above resets that record
    // (LegacyTempMigrations.resetForTests), so ids may be reused across tests, though each test here
    // still uses one of its own.

    // 1
    @Test
    fun `a temp file of an id with a slash survives another unit's cancel sweeping the trash`() {
        val unit = ContractUnit("trash/x", relativePath = "resources/p1.bin")
        writeTemp(storage, unit, "abc")

        storage.deleteTempFile(ContractUnit("other-1"))

        assertEquals(TempFileState.Partial, storage.tempFileState(unit))
        assertEquals(3L, storage.getTempFileSize(unit))
    }

    // 2
    @Test
    fun `a temp file of an id that looks like a staging subtree survives that unit's archive commit`() = runTest {
        val unit = ContractUnit("staging-foo/bar", relativePath = "resources/p2.bin")
        writeTemp(storage, unit, "abc")
        val archive = ContractUnit("foo", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/foo")
        storage.beginTempFile(archive, null)
        writeTempBytes(storage, archive, zipOf("marker.txt", "hello".encodeToByteArray()))

        finish(storage, archive)

        assertTrue(storage.isResourceAvailable(archive))
        assertEquals(3L, storage.getTempFileSize(unit), "the partial of 'staging-foo/bar' is not foo's staging tree")
    }

    // 3
    @Test
    fun `a unit named like another unit's record does not lose its partial when that unit begins`() {
        val victim = ContractUnit("expect/q3", tempExtension = "sha256", relativePath = "resources/p3.bin")
        writeTemp(storage, victim, "abc")

        storage.beginTempFile(ContractUnit("q3", relativePath = "resources/q3.bin"), null)

        assertEquals(TempFileState.Partial, storage.tempFileState(victim))
        assertEquals(3L, storage.getTempFileSize(victim))
    }

    // 4
    @Test
    fun `an id with dot-dot never puts its temp file outside the tmp directory`() {
        val unit = ContractUnit("../x", relativePath = "resources/p4.bin")

        val path: String = normalized(storage.getTempFilePath(unit))

        assertTrue(path.startsWith(baseDirectory() + "/tmp/"), "temp path was $path")
    }

    // 5
    @Test
    fun `an id of 300 characters completes the whole flow`() = runTest {
        val unit = ContractUnit("a".repeat(300), relativePath = "resources/p5.bin")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
    }

    // 6
    @Test
    fun `an id with a NUL character completes the whole flow`() = runTest {
        val unit = ContractUnit("a\u0000b", relativePath = "resources/p6.bin")
        storage.beginTempFile(unit, ABC)
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals("abc", readFile(storage.getResourcePath(unit))?.decodeToString())
    }

    // 7
    @Test
    fun `a temp extension with a slash completes the flow and the record stays keyed by the id alone`() = runTest {
        val unit = ContractUnit("model7", tempExtension = "a/b", relativePath = "resources/p7.bin")
        storage.beginTempFile(unit, ABC)
        assertTrue(pathExists(baseDirectory() + "/tmp/expect/model7.sha256"), "the record is tmp/expect/<id>.sha256")
        assertTrue(
            pathExists(storage.getTempFilePath(unit).substringBeforeLast('/')),
            "beginTempFile must leave the directory of the temp path in place, so a writer can open the file",
        )
        writeTemp(storage, unit, "abc")

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertFalse(pathExists(baseDirectory() + "/tmp/expect/model7.sha256"))
    }

    // 8 (guard)
    @Test
    fun `a plain id keeps exactly the documented paths`() = runTest {
        val unit = ContractUnit("model-1", relativePath = "resources/p8.bin")
        val base: String = baseDirectory()

        assertEquals("$base/tmp/model-1.tmp", storage.getTempFilePath(unit))
        storage.beginTempFile(unit, ABC)
        assertTrue(pathExists("$base/tmp/expect/model-1.sha256"))
        writeTemp(storage, unit, "abc")
        storage.markTempFileComplete(unit)
        assertTrue(pathExists("$base/tmp/model-1.tmp.complete"))
        assertFalse(pathExists("$base/tmp/model-1.tmp"))
        assertFalse(pathExists("$base/tmp/kmptoolkit-internal"), "a plain id never touches the hashed subtree")
    }

    // 9
    @Test
    fun `the 3_0_0 temp files of an id with a slash are moved to the hashed place and still honoured`() = runTest {
        val base: String = baseDirectory()
        for ((id: String, recorded: Sha256, passes: Boolean) in listOf(
            Triple("models/v1-t9-ok", ABC, true),
            Triple("models/v1-t9-bad", OTHER, false),
        )) {
            val unit = ContractUnit(id, relativePath = "resources/p9-${id.substringAfterLast('-')}.bin")
            writeFile("$base/tmp/$id.tmp", "abc".encodeToByteArray())
            writeFile("$base/tmp/expect/$id.sha256", "sha256:${recorded.hex}\n".encodeToByteArray())

            assertEquals(TempFileState.Partial, storage.tempFileState(unit), id)
            assertEquals(3L, storage.getTempFileSize(unit), id)
            assertFalse(pathExists("$base/tmp/$id.tmp"), "the legacy partial of $id must be gone")
            assertFalse(pathExists("$base/tmp/expect/$id.sha256"), "the legacy record of $id must be gone")
            assertTrue(storage.getTempFilePath(unit).startsWith("$base/tmp/kmptoolkit-internal/"), id)

            if (passes) {
                finish(storage, unit)
                assertTrue(storage.isResourceAvailable(unit), "the migrated record matches the bytes")
            } else {
                assertFailsWith<ResourceIntegrityException>(id) { finish(storage, unit) }
            }
        }
    }

    // 10
    @Test
    fun `migration never touches another unit's staging tree or record`() {
        val base: String = baseDirectory()
        writeFile("$base/tmp/staging-a/b.tmp", "xyz".encodeToByteArray())
        writeFile("$base/tmp/expect/foo.sha256", "sha256:${OTHER.hex}\n".encodeToByteArray())
        val inStaging = ContractUnit("staging-a/b", relativePath = "resources/p10a.bin")
        val namedLikeARecord = ContractUnit("expect/foo", tempExtension = "sha256", relativePath = "resources/p10b.bin")

        for (unit: ContractUnit in listOf(inStaging, namedLikeARecord)) {
            assertEquals(TempFileState.None, storage.tempFileState(unit), "$unit")
            assertEquals(0L, storage.getTempFileSize(unit), "$unit")
        }

        assertEquals("xyz", readFile("$base/tmp/staging-a/b.tmp")?.decodeToString())
        assertEquals("sha256:${OTHER.hex}\n", readFile("$base/tmp/expect/foo.sha256")?.decodeToString())
    }

    // 11
    @Test
    fun `a migration cut short by a crash is completed and a destination that exists is never overwritten`() = runTest {
        val base: String = baseDirectory()
        val layout = UnitFileLayout(caseInsensitive = false)

        val resumed = ContractUnit("models/v1-t11", relativePath = "resources/p11a.bin")
        val resumedNames: UnitFileLayout.Names = layout.namesOf(resumed)
        writeFile("$base/${resumedNames.record}", "sha256:${ABC.hex}\n".encodeToByteArray())
        writeFile("$base/tmp/models/v1-t11.tmp", "abc".encodeToByteArray())
        assertEquals(3L, storage.getTempFileSize(resumed))
        assertFalse(pathExists("$base/tmp/models/v1-t11.tmp"))
        finish(storage, resumed)
        assertTrue(storage.isResourceAvailable(resumed), "the commit verifies against the record that was already moved")

        val both = ContractUnit("models/v1-t11b", relativePath = "resources/p11b.bin")
        writeFile("$base/${layout.namesOf(both).partial}", "new!".encodeToByteArray())
        writeFile("$base/tmp/models/v1-t11b.tmp", "old".encodeToByteArray())
        assertEquals(4L, storage.getTempFileSize(both), "the destination is authoritative")
        assertFalse(pathExists("$base/tmp/models/v1-t11b.tmp"), "the stale legacy file is deleted")
    }

    // a no-replace move is the primitive the migration's "never overwrites" rests on
    @Test
    fun `renameNoReplace never replaces an existing destination and moves to a free one`() {
        val base: String = baseDirectory()
        writeFile("$base/nr/from.bin", "legacy".encodeToByteArray())
        writeFile("$base/nr/taken.bin", "authoritative".encodeToByteArray())

        assertFalse(renameNoReplace("$base/nr/from.bin", "$base/nr/taken.bin"), "the destination exists")
        assertEquals("authoritative", readFile("$base/nr/taken.bin")?.decodeToString(), "it must not be replaced")
        assertEquals("legacy", readFile("$base/nr/from.bin")?.decodeToString(), "the source must stay")

        assertTrue(renameNoReplace("$base/nr/from.bin", "$base/nr/free.bin"))
        assertEquals("legacy", readFile("$base/nr/free.bin")?.decodeToString())
        assertFalse(pathExists("$base/nr/from.bin"), "the source name is gone after a successful move")
        assertFalse(renameNoReplace("$base/nr/missing.bin", "$base/nr/other.bin"), "a missing source fails")
    }

    // 12
    @Test
    fun `concurrent first touches of the same unsafe unit converge on one migrated file`() = runTest {
        val base: String = baseDirectory()
        val unit = ContractUnit("models/v1-t12", relativePath = "resources/p12.bin")
        writeFile("$base/tmp/models/v1-t12.tmp", "hello".encodeToByteArray())

        val sizes: List<Long> = withContext(Dispatchers.Default) {
            withTimeout(10.seconds) {
                val start = CompletableDeferred<Unit>()
                val callers: List<Deferred<Long>> = List(4) {
                    async {
                        start.await()
                        storage.getTempFileSize(unit)
                    }
                }
                start.complete(Unit)
                callers.awaitAll()
            }
        }

        assertEquals(listOf(5L, 5L, 5L, 5L), sizes)
        assertFalse(pathExists("$base/tmp/models/v1-t12.tmp"))
        assertEquals("hello", readFile(storage.getTempFilePath(unit))?.decodeToString())
    }

    // 13
    @Test
    fun `a relativePath that can never be used is rejected by every resource method and touches nothing`() = runTest {
        val outside: String = baseDirectory().substringBeforeLast('/') + "/x"
        writeFile(outside, "sentinel".encodeToByteArray())
        try {
            val reasons = InvalidDownloadUnitException.Reason.entries
            val cases: List<Pair<String, InvalidDownloadUnitException.Reason>> = listOf(
                "" to reasons[0], "/" to reasons[0], "./" to reasons[0], "a/.." to reasons[0],
                "../x" to InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY,
                "a/../../x" to InvalidDownloadUnitException.Reason.ESCAPES_BASE_DIRECTORY,
                "x\u0000" to InvalidDownloadUnitException.Reason.INVALID_CHARACTER,
                "tmp" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                "tmp/expect" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                "tmp/expect/foo.sha256" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                "tmp/staging-q/r" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                "tmp/kmptoolkit-internal/z" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
                "tmp/own13.tmp" to InvalidDownloadUnitException.Reason.RESERVED_INTERNAL_PATH,
            )
            for ((path: String, reason: InvalidDownloadUnitException.Reason) in cases) {
                val unit = ContractUnit("own13", relativePath = path)

                assertEquals(reason, assertFailsWith<InvalidDownloadUnitException>("getResourcePath '$path'") {
                    storage.getResourcePath(unit)
                }.reason, "path '$path'")
                assertFalse(storage.isResourceAvailable(unit), "path '$path'")
                assertEquals(0L, storage.getResourceSize(unit), "path '$path'")
                assertFailsWith<InvalidDownloadUnitException>("deleteResource '$path'") { storage.deleteResource(unit) }
            }
            assertEquals("sentinel", readFile(outside)?.decodeToString(), "a file outside the base must survive deleteResource")
        } finally {
            deleteFile(outside)
        }
    }

    @Test
    fun `committing to a rejected relativePath fails before anything moves and the temp work stays cleanable`() = runTest {
        val unit = ContractUnit("own13c", relativePath = "tmp/expect")
        storage.beginTempFile(unit, null)
        writeTemp(storage, unit, "abc")
        storage.markTempFileComplete(unit)

        assertFailsWith<InvalidDownloadUnitException> { storage.commitResource(unit) }

        assertEquals(TempFileState.Complete, storage.tempFileState(unit), "the bytes stay for a corrected catalogue")
        storage.deleteTempFile(unit)
        assertEquals(TempFileState.None, storage.tempFileState(unit), "the temp side never looks at relativePath")
    }

    // 14 (guard)
    @Test
    fun `relative paths that worked in 3_0_0 are accepted unchanged and commit`() = runTest {
        // tmp/trash/... did not exist in 3.0.0 (the trash is under tmp/kmptoolkit-internal now), so it worked there.
        for ((index: Int, path: String) in listOf(
            "tmp/foo.bin", "/abs/x", "a/./b", "a/../b", "tmp/trash/a.bin",
        ).withIndex()) {
            val unit = ContractUnit("guard14-$index", relativePath = path)
            storage.beginTempFile(unit, null)
            writeTemp(storage, unit, "abc")

            finish(storage, unit)

            assertTrue(storage.getResourcePath(unit).endsWith(path), "path '$path' must be used as written")
            assertTrue(storage.isResourceAvailable(unit), "path '$path'")
            assertEquals("abc", readFile(storage.getResourcePath(unit))?.decodeToString(), "path '$path'")
        }
    }

    // 15
    @Test
    fun `the staging tree of a 247 byte id is reclaimed by a cancel`() {
        val id: String = "q".repeat(247)
        val unit = ContractUnit(id, format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/p15")
        val staging: String = baseDirectory() + "/tmp/staging-" + id
        writeFile("$staging/nested/marker.txt", "half-extracted".encodeToByteArray())

        storage.deleteTempFile(unit)

        assertFalse(pathExists(staging), "the trash name must not depend on the id's length")
    }

    @Test
    fun `an archive of an id that is not a file name extracts and commits`() = runTest {
        val unit = ContractUnit("pages/v1-t16", format = ResourceFormat.ZipArchive("marker.txt"), relativePath = "archives/p16")
        storage.beginTempFile(unit, null)
        writeTempBytes(storage, unit, zipOf("marker.txt", "hello".encodeToByteArray()))

        finish(storage, unit)

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertFalse(pathExists(baseDirectory() + "/tmp/pages"), "nothing of a hashed id is created under its raw name")
    }

    /** Resolves `.` and `..` lexically, as a canonical path would. */
    private fun normalized(path: String): String {
        val segments: ArrayList<String> = ArrayList()
        for (segment: String in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (segments.isNotEmpty()) segments.removeAt(segments.lastIndex)
                else -> segments.add(segment)
            }
        }
        return "/" + segments.joinToString("/")
    }

    // -- helpers -----------------------------------------------------------------------------

    private fun writeTemp(on: DownloaderStorage, unit: DownloadUnit, text: String) =
        writeTempBytes(on, unit, text.encodeToByteArray())

    private fun writeTempBytes(on: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        writeFile(on.getTempFilePath(unit), bytes)
    }

    private suspend fun finish(on: DownloaderStorage, unit: DownloadUnit) {
        on.markTempFileComplete(unit)
        on.commitResource(unit)
    }

    /** Where the contract says the record lives: `tmp/expect/<id>.sha256`, next to the temp files. */
    private fun recordPath(on: DownloaderStorage, unit: DownloadUnit): String =
        tmpDirectory(on, unit) + "/expect/" + unit.id + ".sha256"

    /** Where the record is staged before it is renamed into place. */
    private fun stagedRecordPath(on: DownloaderStorage, unit: DownloadUnit): String =
        tmpDirectory(on, unit) + "/expect-staged/" + unit.id + ".sha256"

    /** The `tmp` directory; ids may contain a separator, so it is not simply the temp file's parent. */
    private fun tmpDirectory(on: DownloaderStorage, unit: DownloadUnit): String {
        val tempPath: String = on.getTempFilePath(unit)
        val idDepth: Int = unit.id.count { it == '/' }
        var directory: String = tempPath
        repeat(idDepth + 1) { directory = directory.substringBeforeLast('/') }
        return directory
    }

    private class ContractUnit(
        override val id: String,
        private val declaredSha256: Sha256? = null,
        override val format: ResourceFormat = ResourceFormat.Opaque,
        override val relativePath: String = "resources/$id.bin",
        override val tempExtension: String = "tmp",
        private val hashGetterThrows: Boolean = false,
        /** Runs on every read of [sha256] — the one call a commit makes between its checks and its move. */
        private val onHashRead: () -> Unit = {},
    ) : DownloadUnit {
        override val sha256: Sha256?
            get() {
                onHashRead()
                return if (hashGetterThrows) throw IllegalArgumentException("malformed hash") else declaredSha256
            }

        override val apiPath: String = "/resources/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@ContractUnit)
        }

        override fun toString(): String = "ContractUnit($id)"
    }

    private companion object {
        /** How long a racing commit is given to finish while the other one is parked inside its own. */
        val RACE_WINDOW: Duration = 1.seconds

        /** SHA-256 of the three ASCII bytes "abc", the FIPS 180-2 test vector. */
        val ABC: Sha256 = Sha256.parse("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")

        /** A well-formed hash that none of the test bytes has. */
        val OTHER: Sha256 = Sha256.parse("0".repeat(64))

        /** A one-entry ZIP using STORED (no compression), valid for both platforms' extractors. */
        fun zipOf(name: String, content: ByteArray): ByteArray {
            val nameBytes: ByteArray = name.encodeToByteArray()
            val crc: Int = crc32(content)
            val local: ByteArray = le32(0x04034b50) + le16(20) + le16(0) + le16(0) + le16(0) + le16(0x21) +
                le32(crc) + le32(content.size) + le32(content.size) + le16(nameBytes.size) + le16(0) +
                nameBytes + content
            val central: ByteArray = le32(0x02014b50) + le16(20) + le16(20) + le16(0) + le16(0) + le16(0) +
                le16(0x21) + le32(crc) + le32(content.size) + le32(content.size) + le16(nameBytes.size) +
                le16(0) + le16(0) + le16(0) + le16(0) + le32(0) + le32(0) + nameBytes
            val end: ByteArray = le32(0x06054b50) + le16(0) + le16(0) + le16(1) + le16(1) +
                le32(central.size) + le32(local.size) + le16(0)
            return local + central + end
        }

        private fun le16(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte())

        private fun le32(value: Int): ByteArray = le16(value) + le16(value shr 16)

        private fun crc32(data: ByteArray): Int {
            var crc: Int = -1
            for (byte: Byte in data) {
                crc = crc xor (byte.toInt() and 0xFF)
                repeat(8) { crc = if (crc and 1 != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 }
            }
            return crc.inv()
        }
    }
}
