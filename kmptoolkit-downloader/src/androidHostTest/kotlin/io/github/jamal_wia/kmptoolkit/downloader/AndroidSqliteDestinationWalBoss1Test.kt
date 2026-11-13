package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B boss round 1: a `SqliteDatabase` unit replaced in place. Contract (DownloadUnit KDoc of `SqliteDatabase`, 03-guide,
 * 05-platform-notes): the database is verified before it counts as committed, so a corrupt one never surfaces later in a
 * reader, and a stale `-wal` "would otherwise be replayed" into the next file. The temp side removes its sidecars; this
 * class asks the same of the resource path: the database a reader opens after a commit is the one that was verified,
 * even when the previous version left the write-ahead log of a process that died with the database open.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteDestinationWalBoss1Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_destination_wal_boss1")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val scratch: File get() = File(context.cacheDir, "sqlite_destination_wal_boss1")
    private val fixtures: AndroidSqliteFixturesBoss1 get() = AndroidSqliteFixturesBoss1(scratch)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        scratch.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class DbUnit(override val id: String) : DownloadUnit {
        override val apiPath: String = "/m/$id"
        override val relativePath: String = "m/$id.db"
        override val format: ResourceFormat = ResourceFormat.SqliteDatabase()
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@DbUnit)
        }
    }

    private suspend fun commit(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
        storage.commitResource(unit)
    }

    @Test
    fun `a new version replacing a database whose reader died with an open write-ahead log reads as the new version`() = runBlocking {
        withTimeout(60_000) {
            val old: WalSnapshotBoss1 = fixtures.liveWalSnapshot()
            val fresh: ByteArray = fixtures.freshDatabase(rows = 3)
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = DbUnit("content")
            commit(storage, unit, old.main)
            val resource: String = storage.getResourcePath(unit)
            // The reader of version 1 wrote and was killed: its log and index stay beside the resource.
            File("$resource-wal").writeBytes(old.wal)
            File("$resource-shm").writeBytes(old.shm)

            commit(storage, unit, fresh)

            assertEquals(
                listOf("new0", "new1", "new2"),
                fixtures.payloadsOrFailure(resource),
                "the committed database is not the verified one: the previous version's write-ahead log was replayed into it " +
                    "(sidecars now: -wal ${File("$resource-wal").exists()}, -shm ${File("$resource-shm").exists()})",
            )
        }
    }

    @Test
    fun `a database deleted and downloaded again reads as the new download whatever the deleted one left beside it`() = runBlocking {
        withTimeout(60_000) {
            val old: WalSnapshotBoss1 = fixtures.liveWalSnapshot()
            val fresh: ByteArray = fixtures.freshDatabase(rows = 2)
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = DbUnit("deleted")
            commit(storage, unit, old.main)
            val resource: String = storage.getResourcePath(unit)
            File("$resource-wal").writeBytes(old.wal)
            File("$resource-shm").writeBytes(old.shm)

            storage.deleteResource(unit)
            commit(storage, unit, fresh)

            assertEquals(
                listOf("new0", "new1"),
                fixtures.payloadsOrFailure(resource),
                "deleteResource left the old write-ahead log, replayed into the new download",
            )
        }
    }
}
