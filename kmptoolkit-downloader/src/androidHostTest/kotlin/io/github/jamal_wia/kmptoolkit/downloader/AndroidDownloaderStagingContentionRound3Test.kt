package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/** The documented effect of cancelling a unit while other units commit or cancel at the same moment. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStagingContentionRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "staging_contention_round3")
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

    private class MapStateStore : DownloadStateStore {
        private val ints: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        override fun readInt(key: String, default: Int): Int = ints[key] ?: default
        override fun writeInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun remove(key: String) {
            ints.remove(key)
        }
    }

    private class IdlePort : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            MutableSharedFlow<BackgroundDownloadEvent>()
    }

    @Test
    fun `control - cancelling a unit removes its leftover staging tree when nothing else runs`() {
        removesStaging(noiseThreads = 0)
    }

    @Test
    fun `cancelling a unit removes its leftover staging tree even while other units commit and cancel around it`() {
        removesStaging(noiseThreads = 4)
    }

    private fun removesStaging(noiseThreads: Int) {
        val group = Group("g")
        val unit = ArchiveUnit("pages", group).also { group.units = listOf(it) }
        val engine: Downloader = createDownloader(
            storage = createDownloaderStorage(context, config),
            backgroundDownloader = IdlePort(),
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )
        val base: String = root.absolutePath
        val stop = AtomicBoolean(false)
        // Commits of other units register and leave the lock registry all the time.
        val noise: List<Thread> = (0 until noiseThreads).map { index: Int ->
            thread(name = "noise-$index") {
                var i = 0
                while (!stop.get()) {
                    runBlocking { UnitCommitLocks.withUnitLock(base, "other-$index-${i++ % 4}") { } }
                }
            }
        }
        var left = 0
        try {
            repeat(300) { round: Int ->
                File(root, "tmp/staging-pages/nested").mkdirs()
                File(root, "tmp/staging-pages/nested/half.bin").writeText("half-extracted")
                engine.cancelDownload(unit)
                if (File(root, "tmp/staging-pages").exists()) left++
                File(root, "tmp/staging-pages").deleteRecursively()
            }
        } finally {
            stop.set(true)
            noise.forEach { it.join(10_000) }
        }
        assertTrue(noise.none { it.isAlive }, "a noise thread is blocked")
        assertEquals(0, left, "cancelDownload(unit) returned with the staging tree still there in $left of 300 rounds")
    }
}
