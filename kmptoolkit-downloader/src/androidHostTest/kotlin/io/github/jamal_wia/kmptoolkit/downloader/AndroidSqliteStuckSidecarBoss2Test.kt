package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B boss round 2: a sidecar that cannot be moved holds back only itself. Contract (deleteResource KDoc, 04-api-reference):
 * the old file moves into the trash with its `-wal`, `-shm` and `-journal`; a name that stays is left ("leaving what
 * stays"), and only the database file itself staying keeps its sidecars in place (so the old version keeps its log).
 * With the `-wal` refused (the `rename` seam), the file, `-shm` and `-journal` still leave, on a delete and on a commit,
 * and only the `-wal` stays at the path.
 */
@RunWith(AndroidJUnit4::class)
class AndroidSqliteStuckSidecarBoss2Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "sqlite_stuck_sidecar_boss2")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val scratch: File get() = File(context.cacheDir, "sqlite_stuck_sidecar_boss2")
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

    private class Unit2(override val id: String, override val relativePath: String) : DownloadUnit {
        override val apiPath: String = "/m/$id"
        override val format: ResourceFormat = ResourceFormat.SqliteDatabase()
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@Unit2)
        }

        override fun toString(): String = "Unit2($id)"
    }

    private val dbUnit = Unit2("content", "m/content.db")

    private fun plainStorage(): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(context, config, NoopLogger)
    }

    /** A storage whose `rename` refuses every move of a `-wal` file and does the others for real. */
    private fun stuckWalStorage(): AndroidDownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(
            context, config, NoopLogger,
            rename = { from: File, to: File -> if (from.name.endsWith("-wal")) false else from.renameTo(to) },
        )
    }

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun sidecarsAt(resource: File): List<String> =
        listOf("-wal", "-shm", "-journal").filter { suffix: String -> File(resource.path + suffix).exists() }

    /** Version 1 committed, with all three sidecars planted beside it. */
    private suspend fun committedWithSidecars(): File {
        val storage: AndroidDownloaderStorage = plainStorage()
        stage(storage, dbUnit, fixtures.freshDatabase(rows = 4))
        storage.commitResource(dbUnit)
        val resource = File(storage.getResourcePath(dbUnit))
        for (suffix: String in listOf("-wal", "-shm", "-journal")) File(resource.path + suffix).writeBytes(byteArrayOf(1, 2))
        return resource
    }

    @Test
    fun `a delete whose log cannot move still moves the database file and the other sidecars`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val resource: File = committedWithSidecars()

            stuckWalStorage().deleteResource(dbUnit)

            assertFalse(resource.exists(), "the database file must leave first")
            assertEquals(listOf("-wal"), sidecarsAt(resource), "only the name that refused to move may stay")
        }
    }

    @Test
    fun `a commit whose log cannot move throws but still moves the database file and the other sidecars`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val resource: File = committedWithSidecars()
            val stuck: AndroidDownloaderStorage = stuckWalStorage()
            stage(stuck, dbUnit, fixtures.freshDatabase(rows = 2))

            assertFailsWith<IllegalStateException> { stuck.commitResource(dbUnit) }

            assertFalse(resource.exists(), "the new file must not land beside a log, and the old file left first")
            assertEquals(listOf("-wal"), sidecarsAt(resource), "only the name that refused to move may stay")
            assertEquals(TempFileState.Complete, stuck.tempFileState(dbUnit))
        }
    }
}
