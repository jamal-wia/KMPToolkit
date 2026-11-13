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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8, Q3: a normative sentence of docs/kmptoolkit-downloader/04-api-reference.md (changed since 4.0.0) that no earlier
 * test pins: "The same entry name twice is not a conflict: the later bytes win, as in every unzip."
 */
@RunWith(AndroidJUnit4::class)
class AndroidDocsPinsRound8Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "docs_pins_r8")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class ArchiveUnit(override val group: ResourceGroup) : DownloadUnit {
        override val id: String = "dup"
        override val relativePath: String = "archives/dup"
        override val apiPath: String = "test/dup"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
    }

    @Test
    fun `the same entry name twice is not a conflict and the later bytes win`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val zip: BuiltZipRound6 = ZipBytesRound6.build(
                listOf(ZipEntryRound6("marker.txt", "m"), ZipEntryRound6("a.txt", "first"), ZipEntryRound6("a.txt", "second")),
            )
            val unit = ArchiveUnit(Group("g"))
            val storage: DownloaderStorage = AndroidDownloaderStorage(context, config, NoopLogger)
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(zip.bytes)
            storage.markTempFileComplete(unit)
            storage.commitResource(unit)
            assertEquals("second", File(storage.getResourcePath(unit), "a.txt").readText())
            assertEquals(true, storage.isResourceAvailable(unit))
        }
    }
}
