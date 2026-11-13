package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.files.FileAccess
import io.github.jamal_wia.kmptoolkit.files.FileError
import io.github.jamal_wia.kmptoolkit.files.FileResult
import io.github.jamal_wia.kmptoolkit.files.MoveOptions
import io.github.jamal_wia.kmptoolkit.files.createFileAccess
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 6 of the breaker for unit G, Q5: the code sample of docs/kmptoolkit-downloader/07-background-downloader.md
 * "Handing an existing file to the storage", copied as printed (the lines of `handOff` below between the two markers are
 * the doc's lines, only `return reportFailure(...)` is the doc's own), run against the real Android storage and the real
 * file engine. Documented sentences checked: the move replaces whatever stood at the temp path, the mark applies to the
 * new bytes only, a failed move never marks and leaves the older partial file as it was, and the committed resource holds
 * exactly the handed bytes.
 */
@RunWith(AndroidJUnit4::class)
class DocsHandOffSampleR6Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "docs_handoff_r6")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val elsewhere: File get() = File(context.cacheDir, "docs-handoff-r6")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        elsewhere.deleteRecursively()
        elsewhere.mkdirs()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        elsewhere.deleteRecursively()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val relativePath: String = "files/$id.bin"
        override val format: ResourceFormat = ResourceFormat.Opaque
        override val sha256: Sha256? = null
        override val tempExtension: String = "tmp"
        override val apiPath: String = "test/$id"
    }

    private val failures: MutableList<FileError> = mutableListOf()

    private fun reportFailure(unit: DownloadUnit, error: FileError) {
        failures += error
    }

    /** The doc's sample, inside the function a downloader would put it in. */
    private fun handOff(files: FileAccess, storage: DownloaderStorage, unit: DownloadUnit, location: String) {
        // ---- the lines of the doc sample
        val moved: FileResult<Unit> =
            files.move(location, storage.getTempFilePath(unit), MoveOptions(createParentDirectories = true)) // cross-volume safe
        if (moved is FileResult.Failure) {
            // Never mark here: the temp path may still hold an older partial file, and marking would promote it.
            // Report the transfer as failed through your downloader's failure path; the engine then calls deleteTempFile.
            return reportFailure(unit, moved.error)
        }
        storage.markTempFileComplete(unit)   // the temp path now holds exactly the finished download
        // ---- end of the doc sample
    }

    @Test
    fun `the sample hands a file over replaces an older partial one and the commit holds exactly the handed bytes`() = runBlocking<Unit> {
        withTimeout(30_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = TestUnit("h1", Group("g"))
            val files: FileAccess = createFileAccess()
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(500) { 1 })
            assertEquals(TempFileState.Partial, storage.tempFileState(unit))
            val handed: ByteArray = ByteArray(2_000) { 9 }
            val source = File(elsewhere, "download.bin").apply { writeBytes(handed) }

            handOff(files, storage, unit, source.path)

            assertEquals(emptyList(), failures)
            assertFalse(source.exists(), "the handed file stays at its old place")
            assertEquals(TempFileState.Complete, storage.tempFileState(unit))
            storage.commitResource(unit)
            assertTrue(storage.isResourceAvailable(unit))
            assertTrue(handed.contentEquals(File(storage.getResourcePath(unit)).readBytes()), "the resource is not exactly the handed bytes")
        }
    }

    @Test
    fun `a failed move reports the failure marks nothing and leaves the older partial file as it was`() = runBlocking<Unit> {
        withTimeout(30_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val unit = TestUnit("h2", Group("g"))
            val files: FileAccess = createFileAccess()
            storage.beginTempFile(unit, null)
            val partial: ByteArray = ByteArray(500) { 1 }
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(partial)

            handOff(files, storage, unit, File(elsewhere, "missing.bin").path)

            assertEquals(1, failures.size)
            assertTrue(failures.single() is FileError.NotFound, "${failures.single()}")
            assertEquals(TempFileState.Partial, storage.tempFileState(unit), "a failed hand-off promoted the older partial file")
            assertTrue(partial.contentEquals(File(storage.getTempFilePath(unit)).readBytes()))
        }
    }
}
