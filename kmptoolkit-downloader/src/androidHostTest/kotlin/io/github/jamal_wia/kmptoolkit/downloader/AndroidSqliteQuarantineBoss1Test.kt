package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B boss round 1 fix: replacing or deleting a `SqliteDatabase` resource moves the old file, then its `-wal`, `-shm` and
 * `-journal`, into the trash before the new file takes its place. Contract (SqliteDatabase KDoc, 03-guide, 05-platform-notes):
 * the database a reader opens after a commit is the verified one, and a unit never stays unavailable for good. A process
 * death at EVERY rename of the quarantine and of the final move (the `rename` seam: the Nth call and every call after it
 * throws) leaves either the old database with its log intact, or the resource unavailable beside the complete temp file;
 * a retry then reads exactly the new database with no sidecar of the old one. Also pinned: a hot rollback journal is not
 * rolled back into the new file, files named like sidecars beside an opaque resource are never touched, and a connection
 * left open across the replace keeps reading the old version while a new one reads the new.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteQuarantineBoss1Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_quarantine_boss1")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val scratch: File get() = File(context.cacheDir, "sqlite_quarantine_boss1")
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

    private class ProcessDeath(step: String) : RuntimeException("process death at $step")

    /** Counts renames; from the [dieAt]th on, every one throws. [dieAt] 0 never dies. */
    private class Clock(private val dieAt: Int) {
        val calls: MutableList<String> = mutableListOf()
        private var dead: Boolean = false

        fun tick(what: String) {
            calls += what
            if (dead || (dieAt > 0 && calls.size >= dieAt)) {
                dead = true
                throw ProcessDeath("#${calls.size} $what")
            }
        }
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

    private val dbUnit = Unit1("content", "m/content.db", ResourceFormat.SqliteDatabase())

    private fun storage(clock: Clock?): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return if (clock == null) {
            AndroidDownloaderStorage(context, config, NoopLogger)
        } else {
            AndroidDownloaderStorage(
                context, config, NoopLogger,
                rename = { from: File, to: File ->
                    clock.tick("rename ${from.name} to ${to.name}")
                    from.renameTo(to)
                },
            )
        }
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

    /** What a new process may find after a death: version 1 with its log, or an unavailable unit beside its temp file. */
    private fun judgeAfterDeath(after: AndroidDownloaderStorage, old: WalSnapshotBoss1, where: String) {
        val resource = File(after.getResourcePath(dbUnit))
        if (after.isResourceAvailable(dbUnit)) {
            assertContentEquals(old.main, resource.readBytes(), "$where: an available resource must be version 1 untouched")
            assertContentEquals(old.wal, File(resource.path + "-wal").readBytes(), "$where: version 1 lost its log")
            assertEquals(AndroidSqliteFixturesBoss1.OLD_WITH_LOG, fixtures.payloadsOrFailure(resource.path), "$where: version 1 without its rows")
        } else {
            assertFalse(resource.exists(), "$where: unavailable, yet something stands at the path")
        }
    }

    @Test
    fun `a death at every rename of replacing a database leaves the old one with its log or the complete temp file and a retry reads the new one`() =
        runBlocking<Unit> {
            withTimeout(120_000) {
                val fresh: ByteArray = fixtures.freshDatabase(rows = 3)
                suspend fun prepare(): WalSnapshotBoss1 {
                    root.deleteRecursively()
                    val old: WalSnapshotBoss1 = committedWithLog(storage(null))
                    stage(storage(null), dbUnit, fresh)
                    return old
                }
                prepare()
                val reference = Clock(0)
                storage(reference).commitResource(dbUnit)
                val total: Int = reference.calls.size
                assertEquals(5, total, "renames of one database replace (file, -wal, -shm, -journal, final): ${reference.calls}")
                for (n: Int in 1..total) {
                    val old: WalSnapshotBoss1 = prepare()
                    val clock = Clock(n)
                    runCatching { storage(clock).commitResource(dbUnit) }
                    val where = "death at rename #$n (${reference.calls[n - 1]})"
                    val after: AndroidDownloaderStorage = storage(null)
                    judgeAfterDeath(after, old, where)
                    if (!after.isResourceAvailable(dbUnit)) {
                        assertEquals(TempFileState.Complete, after.tempFileState(dbUnit), "$where: unavailable and no complete temp file to commit")
                    }
                    if (after.tempFileState(dbUnit) == TempFileState.Complete) after.commitResource(dbUnit)
                    val resource = File(after.getResourcePath(dbUnit))
                    assertEquals(emptyList(), sidecarsAt(resource), "$where: a sidecar of version 1 survived the retry")
                    assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path), "$where: the retry did not end with the new database")
                }
            }
        }

    @Test
    fun `a death at every rename of deleting a database never lets the old log reach the next download`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val fresh: ByteArray = fixtures.freshDatabase(rows = 2)
            suspend fun prepare(): WalSnapshotBoss1 {
                root.deleteRecursively()
                return committedWithLog(storage(null))
            }
            prepare()
            val reference = Clock(0)
            storage(reference).deleteResource(dbUnit)
            val total: Int = reference.calls.size
            assertEquals(4, total, "renames of one database delete (file, -wal, -shm, -journal): ${reference.calls}")
            for (n: Int in 1..total) {
                val old: WalSnapshotBoss1 = prepare()
                runCatching { storage(Clock(n)).deleteResource(dbUnit) }
                val where = "death at rename #$n (${reference.calls[n - 1]})"
                val after: AndroidDownloaderStorage = storage(null)
                judgeAfterDeath(after, old, where)
                stage(after, dbUnit, fresh)
                after.commitResource(dbUnit)
                val resource = File(after.getResourcePath(dbUnit))
                assertEquals(emptyList(), sidecarsAt(resource), "$where: a sidecar of the deleted database survived the next download")
                assertEquals(listOf("new0", "new1"), fixtures.payloadsOrFailure(resource.path), "$where: the next download is not what was verified")
            }
        }
    }

    @Test
    fun `a hot rollback journal beside the replaced database is not rolled back into the new one`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val hot: JournalSnapshotBoss1 = fixtures.hotJournalSnapshot()
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            stage(storage, dbUnit, fixtures.freshDatabase(rows = 7))
            storage.commitResource(dbUnit)
            val resource = File(storage.getResourcePath(dbUnit))
            resource.writeBytes(hot.main)
            File(resource.path + "-journal").writeBytes(hot.journal)

            stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
            storage.commitResource(dbUnit)

            assertFalse(File(resource.path + "-journal").exists(), "the journal of the replaced database still stands at the path")
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
        }
    }

    @Test
    fun `a hot rollback journal of a deleted database is gone with it`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val hot: JournalSnapshotBoss1 = fixtures.hotJournalSnapshot()
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            stage(storage, dbUnit, fixtures.freshDatabase(rows = 7))
            storage.commitResource(dbUnit)
            val resource = File(storage.getResourcePath(dbUnit))
            resource.writeBytes(hot.main)
            File(resource.path + "-journal").writeBytes(hot.journal)

            storage.deleteResource(dbUnit)

            assertEquals(emptyList(), sidecarsAt(resource))
            assertFalse(resource.exists())
        }
    }

    @Test
    fun `a database that is gone but left a log is cleaned by the next commit`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val old: WalSnapshotBoss1 = committedWithLog(storage)
            val resource = File(storage.getResourcePath(dbUnit))
            assertTrue(resource.delete(), "the fixture removes the main file only")
            assertContentEquals(old.wal, File(resource.path + "-wal").readBytes())

            stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
            storage.commitResource(dbUnit)

            assertEquals(emptyList(), sidecarsAt(resource))
            assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource.path))
        }
    }

    @Test
    fun `a delete of a database whose file is gone still removes the log it left`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            committedWithLog(storage)
            val resource = File(storage.getResourcePath(dbUnit))
            assertTrue(resource.delete(), "the fixture removes the main file only")

            storage.deleteResource(dbUnit)

            assertEquals(emptyList(), sidecarsAt(resource))
        }
    }

    @Test
    fun `files named like sidecars beside an opaque resource survive its commit and its delete`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = Unit1("blob", "m/blob.bin", ResourceFormat.Opaque)
            stage(storage, unit, "one".encodeToByteArray())
            storage.commitResource(unit)
            val resource = File(storage.getResourcePath(unit))
            val kept: Map<String, ByteArray> = mapOf("-wal" to byteArrayOf(1), "-shm" to byteArrayOf(2), "-journal" to byteArrayOf(3))
            for ((suffix: String, bytes: ByteArray) in kept) File(resource.path + suffix).writeBytes(bytes)

            stage(storage, unit, "two".encodeToByteArray())
            storage.commitResource(unit)
            for ((suffix: String, bytes: ByteArray) in kept) {
                assertContentEquals(bytes, File(resource.path + suffix).readBytes(), "an opaque commit touched $suffix")
            }
            storage.deleteResource(unit)
            for ((suffix: String, bytes: ByteArray) in kept) {
                assertContentEquals(bytes, File(resource.path + suffix).readBytes(), "an opaque delete touched $suffix")
            }
        }
    }

    @Test
    fun `a connection left open across the replace keeps the old version while a new one reads the new`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val old: WalSnapshotBoss1 = fixtures.liveWalSnapshot()
            stage(storage, dbUnit, old.main)
            storage.commitResource(dbUnit)
            val resource: String = storage.getResourcePath(dbUnit)
            val live: SQLiteDatabase = SQLiteDatabase.openDatabase(resource, null, SQLiteDatabase.OPEN_READONLY)
            try {
                val before: List<String> = live.rawQuery("SELECT payload FROM t ORDER BY id", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertEquals(listOf("old0"), before, "the main file alone holds version 1's first row")

                stage(storage, dbUnit, fixtures.freshDatabase(rows = 3))
                storage.commitResource(dbUnit)

                assertEquals(listOf("new0", "new1", "new2"), fixtures.payloadsOrFailure(resource), "a new connection must read the new version")
                val still: List<String> = live.rawQuery("SELECT payload FROM t ORDER BY id", null).use { cursor ->
                    buildList { while (cursor.moveToNext()) add(cursor.getString(0)) }
                }
                assertEquals(before, still, "the connection opened before the replace must keep reading its own version")
            } finally {
                live.close()
            }
        }
    }
}
