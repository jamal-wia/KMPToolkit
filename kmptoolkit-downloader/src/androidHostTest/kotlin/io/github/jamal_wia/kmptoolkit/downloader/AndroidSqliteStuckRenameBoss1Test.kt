package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.LogLevel
import io.github.jamal_wia.kmptoolkit.logging.Logger
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B boss 1 review batch (S1, S2): what the quarantine of a `SqliteDatabase` does when a name stays at the path. The
 * `rename` seam refuses the move of `<R>-wal` only (a log that cannot be moved, for example one a live connection keeps
 * recreating). Contract: a COMMIT never goes on beside a log it could not move (SQLite would replay it into the new
 * file), so it throws, keeps the temp file `Complete` and puts nothing new at the path; `deleteResource` is documented
 * as safe when the resource is not present and never throws for this, it logs a warning and leaves what stays; a
 * retry after the obstacle is gone ends with the new database and no sidecar of the old one. A second test pins the
 * bounded retry: a sweep of another unit may delete the fresh, still empty trash entry between its creation and the
 * renames. A third pair of tests refuses the move of the database file itself while the `-wal` move would succeed:
 * the quarantine then moves no sidecar either, so version 1 stays available with its log intact, on a commit (which
 * throws) and on a delete (which warns).
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteStuckRenameBoss1Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_stuck_rename_boss1")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val scratch: File get() = File(context.cacheDir, "sqlite_stuck_rename_boss1")
    private val fixtures: AndroidSqliteFixturesBoss1 get() = AndroidSqliteFixturesBoss1(scratch)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        scratch.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        scratch.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Unit1(
        override val id: String,
        override val relativePath: String,
        override val format: ResourceFormat,
    ) : DownloadUnit {
        override val apiPath: String = "/m/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@Unit1)
        }

        override fun toString(): String = "Unit1($id)"
    }

    private class WarningRecorder : Logger {
        override val tag: String = "test"
        val warnings: MutableList<String> = mutableListOf()

        override fun isLoggable(level: LogLevel): Boolean = true

        override fun log(level: LogLevel, throwable: Throwable?, message: () -> String) {
            val text: String = message()
            if (level == LogLevel.WARN) warnings += text
        }
    }

    private val dbUnit = Unit1("content", "m/content.db", ResourceFormat.SqliteDatabase())

    private fun plainStorage(): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(context, config, WarningRecorder())
    }

    /** A storage whose `rename` refuses every move of a `-wal` file and does the others for real. */
    private fun stuckWalStorage(logger: Logger): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(
            context, config, logger,
            rename = { from: File, to: File -> if (from.name.endsWith("-wal")) false else from.renameTo(to) },
        )
    }

    /** A storage whose `rename` refuses every move of the database file itself and does the others for real. */
    private fun stuckFileStorage(logger: Logger): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(
            context, config, logger,
            rename = { from: File, to: File -> if (from.name == File(dbUnit.relativePath).name) false else from.renameTo(to) },
        )
    }

    /** Every regular file anywhere under the trash directory, by name. */
    private fun trashedFileNames(): List<String> {
        val trash: File = File(File(File(root, "tmp"), "kmptoolkit-internal"), "trash")
        return trash.walkTopDown().filter { entry: File -> entry.isFile }.map { entry: File -> entry.name }.sorted().toList()
    }

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun sidecarsAt(resource: File): List<String> =
        listOf("-wal", "-shm", "-journal").filter { suffix: String -> File(resource.path + suffix).exists() }

    /** Version 1 committed with the log of a reader that died, and its snapshot returned. */
    private suspend fun committedWithLog(storage: DownloaderStorage): WalSnapshotBoss1 {
        val old: WalSnapshotBoss1 = fixtures.liveWalSnapshot()
        stage(storage, dbUnit, old.main)
        storage.commitResource(dbUnit)
        val resource: String = storage.getResourcePath(dbUnit)
        File("$resource-wal").writeBytes(old.wal)
        File("$resource-shm").writeBytes(old.shm)
        return old
    }

    @Test
    fun `a commit that cannot move the log aside throws keeps the temp file complete and a retry reads the new database`() =
        runBlocking<Unit> {
            withTimeout(60_000) {
                val old: WalSnapshotBoss1 = committedWithLog(plainStorage())
                stage(plainStorage(), dbUnit, fixtures.freshDatabase(rows = 3))
                val resource = File(plainStorage().getResourcePath(dbUnit))

                val failure: IllegalStateException =
                    assertFailsWith { stuckWalStorage(WarningRecorder()).commitResource(dbUnit) }

                assertTrue("Could not move" in failure.message.orEmpty(), "the failure names the unmoved database: $failure")
                val after: AndroidDownloaderStorage = plainStorage()
                assertEquals(TempFileState.Complete, after.tempFileState(dbUnit), "the temp file must stay complete for the retry")
                assertFalse(resource.exists(), "nothing new may be put at the path beside a log that could not be moved")
                assertFalse(after.isResourceAvailable(dbUnit))
                assertContentEquals(old.wal, File(resource.path + "-wal").readBytes(), "the old log stays where it was")

                // The obstacle is gone: the retry ends with the new database and no sidecar of the old one.
                after.commitResource(dbUnit)
                assertEquals(emptyList(), sidecarsAt(resource), "a sidecar of version 1 survived the retry")
                assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
            }
        }

    @Test
    fun `a delete that cannot move the log aside does not throw logs a warning and leaves what stays`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val old: WalSnapshotBoss1 = committedWithLog(plainStorage())
            val resource = File(plainStorage().getResourcePath(dbUnit))
            val logger = WarningRecorder()

            stuckWalStorage(logger).deleteResource(dbUnit)

            assertEquals(1, logger.warnings.size, "one warning for the delete that could not finish: ${logger.warnings}")
            assertTrue("Could not move" in logger.warnings.single(), "the warning says what failed: ${logger.warnings}")
            // The file moved first and went with the delete; only the log that refused to move is left.
            val after: AndroidDownloaderStorage = plainStorage()
            assertFalse(after.isResourceAvailable(dbUnit), "the unit reads as unavailable")
            assertFalse(resource.exists(), "the database file was moved away before the log refused")
            assertContentEquals(old.wal, File(resource.path + "-wal").readBytes(), "the log that refused to move stays")
            assertEquals(emptyList(), trashedFileNames().filter { name: String -> name.startsWith("db") }, "the stuck delete must sweep the trash, nothing of version 1 may wait in it")

            // The next download's commit moves the stray log and ends with exactly what was verified.
            stage(after, dbUnit, fixtures.freshDatabase(rows = 2))
            after.commitResource(dbUnit)
            assertEquals(emptyList(), sidecarsAt(resource), "the stray log survived the next download")
            assertEquals(listOf("new0", "new1"), fixtures.payloadsOrFailure(resource.path))
        }
    }

    @Test
    fun `a commit whose database file cannot be moved throws and leaves version 1 available with its log`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val old: WalSnapshotBoss1 = committedWithLog(plainStorage())
            stage(plainStorage(), dbUnit, fixtures.freshDatabase(rows = 3))
            val resource = File(plainStorage().getResourcePath(dbUnit))

            val failure: IllegalStateException =
                assertFailsWith { stuckFileStorage(WarningRecorder()).commitResource(dbUnit) }

            assertTrue("Could not move" in failure.message.orEmpty(), "the failure names the unmoved database: $failure")
            val after: AndroidDownloaderStorage = plainStorage()
            assertEquals(TempFileState.Complete, after.tempFileState(dbUnit), "the temp file must stay complete for the retry")
            assertTrue(after.isResourceAvailable(dbUnit), "version 1 is still the resource")
            assertContentEquals(old.main, resource.readBytes(), "the database file stays where it was")
            assertContentEquals(old.wal, File(resource.path + "-wal").readBytes(), "the log of version 1 must not have been moved")
            assertContentEquals(old.shm, File(resource.path + "-shm").readBytes(), "the shared memory file of version 1 must not have been moved")
            assertEquals(emptyList(), trashedFileNames().filter { name: String -> name.endsWith("-wal") }, "no log of version 1 may sit in the trash")

            // The obstacle is gone: the retry ends with the new database and no sidecar of the old one.
            after.commitResource(dbUnit)
            assertEquals(emptyList(), sidecarsAt(resource), "a sidecar of version 1 survived the retry")
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
        }
    }

    @Test
    fun `a delete whose database file cannot be moved does not throw and leaves version 1 available with its log`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val old: WalSnapshotBoss1 = committedWithLog(plainStorage())
            val resource = File(plainStorage().getResourcePath(dbUnit))
            val logger = WarningRecorder()

            stuckFileStorage(logger).deleteResource(dbUnit)

            assertEquals(1, logger.warnings.size, "one warning for the delete that could not finish: ${logger.warnings}")
            assertTrue("Could not move" in logger.warnings.single(), "the warning says what failed: ${logger.warnings}")
            // Nothing of version 1 moved: it stays the resource, whole, and the sweep finds nothing of it.
            val after: AndroidDownloaderStorage = plainStorage()
            assertTrue(after.isResourceAvailable(dbUnit), "version 1 is still the resource")
            assertContentEquals(old.main, resource.readBytes(), "the database file stays where it was")
            assertContentEquals(old.wal, File(resource.path + "-wal").readBytes(), "the log of version 1 must not have been moved or swept")
            assertContentEquals(old.shm, File(resource.path + "-shm").readBytes(), "the shared memory file of version 1 must not have been moved")
            assertEquals(emptyList(), trashedFileNames().filter { name: String -> name.startsWith("db") }, "nothing of version 1 may sit in the trash")
        }
    }

    @Test
    fun `a trash entry swept away before the first rename makes the commit retry with a fresh one`() = runBlocking<Unit> {
        withTimeout(60_000) {
            committedWithLog(plainStorage())
            stage(plainStorage(), dbUnit, fixtures.freshDatabase(rows = 3))
            val resource = File(plainStorage().getResourcePath(dbUnit))
            var swept = false
            LegacyTempMigrations.resetForTests()
            val storage = AndroidDownloaderStorage(
                context, config, WarningRecorder(),
                rename = { from: File, to: File ->
                    if (!swept) {
                        // The sweep of another unit deletes the empty entry the quarantine just made.
                        swept = true
                        assertTrue(to.parentFile!!.delete(), "the entry the first rename targets must exist and be empty")
                    }
                    from.renameTo(to)
                },
            )

            storage.commitResource(dbUnit)

            assertTrue(swept, "the seam never saw a rename")
            assertEquals(emptyList(), sidecarsAt(resource), "a sidecar of version 1 survived the retry")
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
        }
    }
}
