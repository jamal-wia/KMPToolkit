package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Round 7: what the engine that waited behind another engine's transfer publishes. Its group progress
 * stays inside 0..1 and never goes backwards although the units it counted as work were finished by
 * the other engine, and it ends in a finished state, not stuck at Downloading or Idle.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStateLockRound7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "adversarial_state7")
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

    /** One service for both engines; every transfer reports progress, waits for [release], then completes. */
    private class SlowPort(private val storage: DownloaderStorage, private val bytes: ByteArray) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 4096)
        private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val firstStarted: CountDownLatch = CountDownLatch(1)
        val release: CountDownLatch = CountDownLatch(1)

        override fun enqueueDownload(unit: DownloadUnit) {
            if (!running.add(unit.id)) return
            thread(isDaemon = true) {
                firstStarted.countDown()
                events.tryEmit(BackgroundDownloadEvent.Progress(unit, 0.3f))
                release.await(10, TimeUnit.SECONDS)
                val file = File(storage.getTempFilePath(unit))
                file.parentFile!!.mkdirs()
                file.writeBytes(bytes)
                storage.markTempFileComplete(unit)
                running.remove(unit.id)
                events.tryEmit(BackgroundDownloadEvent.Progress(unit, 0.9f))
                events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            }
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in running
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    private fun engine(storage: DownloaderStorage, port: BackgroundResourceDownloader, group: ResourceGroup): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    private val payload: ByteArray = ByteArray(8 * 1024) { (it % 199).toByte() }

    @Test
    fun `the group state of an engine that waited behind another stays in range never goes backwards and ends finished`() =
        runBlocking {
            val group = Group("catalogue")
            val units: List<FileUnit> = List(3) { FileUnit("u$it", group) }.also { group.units = it }
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val port = SlowPort(storageA, payload)
            val engineA: Downloader = engine(storageA, port, group)
            val engineB: Downloader = engine(createDownloaderStorage(context, config), port, group)

            val seen: MutableList<GroupDownloadState> = java.util.Collections.synchronizedList(mutableListOf())
            val collector: Job = launch(Dispatchers.Default) { engineB.downloadState(group).collect { seen += it } }
            val a: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineA.ensureAvailable(group) } }
            assertTrue(withContext(Dispatchers.IO) { port.firstStarted.await(5, TimeUnit.SECONDS) }, "A never started")
            val b: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineB.ensureAvailable(group) } }
            kotlinx.coroutines.delay(300)
            port.release.countDown()

            val aOutcome: Result<Unit> = withTimeoutOrNull(30_000) { a.await() } ?: error("A never returned")
            val bOutcome: Result<Unit> = withTimeoutOrNull(30_000) { b.await() } ?: error("B never returned")
            kotlinx.coroutines.delay(200)
            collector.cancel()

            assertTrue(aOutcome.isSuccess, "A: $aOutcome")
            assertTrue(bOutcome.isSuccess, "B: $bOutcome")
            var last = -1f
            seen.filterIsInstance<GroupDownloadState.Downloading>().forEach { state: GroupDownloadState.Downloading ->
                assertTrue(state.progress in 0f..1f, "progress out of range: ${state.progress}")
                assertTrue(state.progress >= last, "progress went backwards: $last then ${state.progress}")
                last = state.progress
            }
            val final: GroupDownloadState = engineB.downloadState(group).value
            assertTrue(final is GroupDownloadState.Completed, "B ended in $final after $seen")
            assertEquals(true, engineB.isAvailable(group))
            units.forEach { assertEquals(true, engineB.isAvailable(it)) }
        }
}
