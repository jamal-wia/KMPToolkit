package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** What a reader of the temp state sees while another thread finishes the transfer. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderTempStateRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "temp_state_round3")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class FileUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "files/$id.bin"
    }

    @Test
    fun `a temp file that exists as partial or complete is never read as none while the transfer completes`() {
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val writerStorage: DownloaderStorage = AndroidDownloaderStorage(context, config, NoopLogger)
        val readerStorage: DownloaderStorage = AndroidDownloaderStorage(context, config, NoopLogger)
        val live = AtomicBoolean(false)
        val done = AtomicBoolean(false)
        val none = AtomicInteger()
        val samples = AtomicInteger()
        val reader = thread(name = "reader") {
            while (!done.get()) {
                val before: Boolean = live.get()
                val state: TempFileState = readerStorage.tempFileState(unit)
                val after: Boolean = live.get()
                if (before && after) {
                    samples.incrementAndGet()
                    if (state == TempFileState.None) none.incrementAndGet()
                }
            }
        }
        val writer = thread(name = "writer") {
            repeat(4000) {
                writerStorage.beginTempFile(unit, null)
                File(writerStorage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(16))
                live.set(true)
                writerStorage.markTempFileComplete(unit)
                Thread.yield()
                live.set(false)
                writerStorage.deleteTempFile(unit)
            }
            done.set(true)
        }
        writer.join(60_000)
        reader.join(10_000)
        assertTrue(!writer.isAlive && !reader.isAlive, "a thread is blocked")
        assertTrue(samples.get() > 100, "only ${samples.get()} samples were taken while the file existed")
        assertEquals(0, none.get(), "tempFileState said None ${none.get()} times of ${samples.get()} while the file existed")
    }
}
