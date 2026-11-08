package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** markTempFileComplete is documented idempotent once complete, also when two callers meet. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderMarkCompleteRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "mark_complete_round3")
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
    fun `eight threads marking the same temp file complete at once all return and the file is complete`() {
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val storages: List<DownloaderStorage> = List(2) { AndroidDownloaderStorage(context, config, NoopLogger) }
        val failures: MutableList<String> = CopyOnWriteArrayList()
        repeat(500) { round: Int ->
            storages[0].beginTempFile(unit, null)
            File(storages[0].getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(32))
            val barrier = CyclicBarrier(8)
            val threads: List<Thread> = (0 until 8).map { index: Int ->
                thread(name = "mark-$index") {
                    barrier.await(10, TimeUnit.SECONDS)
                    try {
                        storages[index % 2].markTempFileComplete(unit)
                    } catch (e: Exception) {
                        failures += "${e.javaClass.simpleName}: ${e.message?.substringBefore(" for ")}"
                    }
                }
            }
            threads.forEach { it.join(15_000) }
            assertTrue(threads.none { it.isAlive }, "round $round: a thread is blocked")
            assertEquals(TempFileState.Complete, storages[0].tempFileState(unit), "round $round")
            storages[0].deleteTempFile(unit)
        }
        assertEquals(emptyMap(), failures.groupingBy { it }.eachCount(), "a call of an idempotent method threw")
    }
}
