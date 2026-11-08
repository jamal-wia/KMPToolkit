package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/** What extraction produces for archives with a root directory, empty directories and odd spellings of paths. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderArchiveRound4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "archive_round4")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val sentinel: File get() = File(context.filesDir, "archive-round4-sentinel")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        sentinel.writeText("keep")
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        sentinel.delete()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
    }

    private fun zip(vararg entries: Pair<String, String?>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { out: ZipOutputStream ->
            for ((name: String, text: String?) in entries) {
                out.putNextEntry(ZipEntry(name))
                if (text != null) out.write(text.encodeToByteArray())
                out.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    private fun storage(): DownloaderStorage = createDownloaderStorage(context, config)

    private fun DownloaderStorage.getResourcePathOrNull(unit: DownloadUnit): String? =
        try { getResourcePath(unit) } catch (e: InvalidDownloadUnitException) { null }

    @Test
    fun `an archive with one root directory is moved up and available`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit = TestUnit("n1", "pages/n1", Group("g"), ResourceFormat.ZipArchive("ok.txt"))
            stage(storage, unit, zip("pages/ok.txt" to "1", "pages/p1.webp" to "x", "pages/sub/p2.webp" to "y"))
            storage.commitResource(unit)
            assertTrue(storage.isResourceAvailable(unit))
            val target = File(storage.getResourcePath(unit))
            assertEquals("y", File(target, "sub/p2.webp").readText())
            assertFalse(File(root, "tmp/staging-n1").exists())
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    @Test
    fun `an archive with two root directories and no marker fails the engine path and leaves no staging`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit = TestUnit("n2", "pages/n2", Group("g"), ResourceFormat.ZipArchive("ok.txt"))
            stage(storage, unit, zip("a/ok.txt" to "1", "b/x" to "2"))
            storage.commitResource(unit)
            assertFalse(storage.isResourceAvailable(unit))
            assertFalse(File(root, "tmp/staging-n2").exists())
        }
    }

    @Test
    fun `an archive that nests a directory of the same name as its root still ends consistent`() = runBlocking {
        withTimeout(30_000) {
            val storage: DownloaderStorage = storage()
            val unit = TestUnit("n3", "pages/n3", Group("g"), ResourceFormat.ZipArchive("ok.txt"))
            stage(storage, unit, zip("pages/pages/ok.txt" to "1"))
            runCatching { storage.commitResource(unit) }
            assertFalse(File(root, "tmp/staging-n3").exists(), "staging left behind")
        }
    }

    @Test
    fun `relative path spellings of one file are one file inside the base`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = storage()
            val group = Group("g")
            val spellings: List<String> = listOf(
                "files/a.bin", "/files/a.bin", "files//a.bin", "./files/a.bin", "files/./a.bin",
                "files/a.bin/", "//files/a.bin", "../archive_round4/files/a.bin",
                "files/../files/a.bin", 
            )
            val problems: MutableList<String> = mutableListOf()
            for ((index: Int, spelling: String) in spellings.withIndex()) {
                val unit = TestUnit("s$index", spelling, group)
                if (storage.getResourcePathOrNull(unit) == null) continue // refused by the storage: nothing to hold it to
                stage(storage, unit, byteArrayOf(index.toByte()))
                try {
                    storage.commitResource(unit)
                    val path = File(storage.getResourcePath(unit))
                    if (path.canonicalPath != File(root, "files/a.bin").canonicalPath) problems += "$spelling resolved to ${path.canonicalPath}"
                    if (!storage.isResourceAvailable(unit)) problems += "$spelling not available after commit"
                    storage.deleteResource(unit)
                    if (storage.isResourceAvailable(unit)) problems += "$spelling still available after delete"
                } catch (e: Exception) {
                    problems += "$spelling: commit threw $e".take(160)
                }
                storage.deleteTempFile(unit)
                File(root, "files").deleteRecursively()
            }
            assertEquals(emptyList(), problems)
            assertEquals("keep", sentinel.readText())
        }
    }
}
