package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

/**
 * Adopting a directory an app already populated before it used this module.
 *
 * An app that downloaded resources with its own storage has them on its users' devices under a
 * name of its choosing. Pointing [DownloaderStorageConfig.baseDirectoryName] at that name must make
 * every one of them count as present — committed files, unpacked archives, and a half-finished
 * transfer that resumes from where it stopped — or every user re-downloads everything and the old
 * copies are orphaned on disk. These cases write that layout by hand, as the earlier code left it,
 * and only then create the storage.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStorageAdoptionTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val existingRoot: File get() = File(context.filesDir, EXISTING_DIRECTORY)

    private fun storage(): DownloaderStorage =
        createDownloaderStorage(context, DownloaderStorageConfig(baseDirectoryName = EXISTING_DIRECTORY))

    @AfterTest
    fun cleanUp() {
        existingRoot.deleteRecursively()
        File(context.filesDir, DownloaderStorageConfig().baseDirectoryName).deleteRecursively()
    }

    @Test
    fun `a file committed under the existing directory is found in place`() {
        val unit = TestUnit(id = "translation-en", relativePath = "translations/en.json")
        File(existingRoot, "translations/en.json").apply { parentFile!!.mkdirs() }.writeText("{}")

        val storage: DownloaderStorage = storage()

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(File(existingRoot, "translations/en.json").absolutePath, storage.getResourcePath(unit))
        assertEquals(2L, storage.getResourceSize(unit))
    }

    @Test
    fun `an archive unpacked under the existing directory is found by its marker`() {
        val unit = TestUnit(
            id = "mushaf-pages",
            relativePath = "mushaf-pages",
            format = ResourceFormat.ZipArchive(availabilityMarker = "604.png"),
        )
        File(existingRoot, "mushaf-pages").mkdirs()
        File(existingRoot, "mushaf-pages/1.png").writeBytes(ByteArray(3))
        File(existingRoot, "mushaf-pages/604.png").writeBytes(ByteArray(4))

        val storage: DownloaderStorage = storage()

        assertTrue(storage.isResourceAvailable(unit))
        assertEquals(7L, storage.getResourceSize(unit))
    }

    @Test
    fun `a partial transfer left under the existing directory resumes from its size`() {
        val unit = TestUnit(id = "quran-db", relativePath = "database/quran.db")
        File(existingRoot, "tmp/quran-db.tmp").apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(1024))

        val storage: DownloaderStorage = storage()

        assertTrue(storage.isTempFileAvailable(unit))
        assertEquals(1024L, storage.getTempFileSize(unit))
        assertEquals(File(existingRoot, "tmp/quran-db.tmp").absolutePath, storage.getTempFilePath(unit))
    }

    @Test
    fun `a resumed transfer commits into the existing directory`() = runTest {
        val unit = TestUnit(id = "audio-index", relativePath = "audio/index.json")
        File(existingRoot, "tmp/audio-index.tmp").apply { parentFile!!.mkdirs() }.writeText("[1]")

        val storage: DownloaderStorage = storage()
        storage.commitResource(unit)

        assertEquals("[1]", File(existingRoot, "audio/index.json").readText())
        assertFalse(storage.isTempFileAvailable(unit))
    }

    @Test
    fun `the default directory does not see resources under another name`() {
        // The control: what the cases above find is found because of the configured name, not
        // because storage looks everywhere.
        val unit = TestUnit(id = "translation-en", relativePath = "translations/en.json")
        File(existingRoot, "translations/en.json").apply { parentFile!!.mkdirs() }.writeText("{}")

        assertFalse(createDownloaderStorage(context).isResourceAvailable(unit))
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
        override val apiPath: String = "/resources/$id"
        override val group: ResourceGroup = object : ResourceGroup {
            override val key: String = id
            override val units: List<DownloadUnit> get() = listOf(this@TestUnit)
        }
    }

    private companion object {
        const val EXISTING_DIRECTORY: String = "resources"
    }
}
