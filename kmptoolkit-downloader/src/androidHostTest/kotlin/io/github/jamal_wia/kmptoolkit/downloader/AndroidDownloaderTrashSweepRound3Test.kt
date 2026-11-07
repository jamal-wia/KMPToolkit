package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.runner.RunWith

/** deleteTempFile never throws, also when the recursive deletes of several threads meet. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderTrashSweepRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "trash_sweep_round3")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class ArchiveUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "archives/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
    }

    @Test
    fun `deleteTempFile of different units on four threads never throws even when their trash sweeps meet`() {
        val group = Group("g")
        val failure = AtomicReference<Throwable?>(null)
        val go = CountDownLatch(1)
        val threads: List<Thread> = (0 until 4).map { index: Int ->
            thread(name = "sweeper-$index") {
                try {
                    val storage: DownloaderStorage = createDownloaderStorage(context, config)
                    val unit = ArchiveUnit("arch$index", group)
                    go.await()
                    repeat(400) {
                        File(root, "tmp/staging-arch$index/a/b").mkdirs()
                        File(root, "tmp/staging-arch$index/a/b/f.bin").writeText("half")
                        storage.deleteTempFile(unit)
                    }
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        go.countDown()
        threads.forEach { it.join(60_000) }
        assertTrue(threads.none { it.isAlive }, "a sweeper is blocked")
        assertEquals(null, failure.get()?.stackTraceToString()?.take(1500), "deleteTempFile is documented never to throw")
    }
}
