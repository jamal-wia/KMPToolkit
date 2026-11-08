package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import java.util.Collections
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 6: the engine path "an archive that cannot be extracted: one fresh download, then Corrupted" end to end over
 * the Android storage, with archives laid out by [ZipBytesRound6] (a corrupt archive followed by a good one, a user retry
 * after Corrupted, a group of two units).
 */
@RunWith(AndroidJUnit4::class)
class AndroidArchiveEngineRound6Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "engine_round6")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val relativePath: String = "archives/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
        override val apiPath: String = "test/$id"
        override fun toString(): String = "TestUnit($id)"
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

    /** Delivers the payloads of [payloads] in turn (the last one repeats), per unit id, like a finished transfer. */
    private class Service(private val storage: DownloaderStorage, val payloads: Map<String, List<ByteArray>>) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()

        override fun enqueueDownload(unit: DownloadUnit) {
            val n: Int = enqueues.getOrPut(unit.id) { AtomicInteger() }.getAndIncrement()
            val list: List<ByteArray> = payloads.getValue(unit.id)
            storage.beginTempFile(unit, null)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(list[minOf(n, list.lastIndex)])
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        fun count(id: String): Int = enqueues[id]?.get() ?: 0
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    private class RecordingNotifier : DownloadNotifier {
        val calls: MutableList<String> = Collections.synchronizedList(ArrayList())
        override suspend fun showProgress(group: ResourceGroup, progress: Float) {
            calls += "progress"
        }

        override suspend fun showCompleted(group: ResourceGroup) {
            calls += "completed"
        }

        override suspend fun showError(group: ResourceGroup, error: DownloadError) {
            calls += "error:${error::class.simpleName}"
        }

        override fun remove(group: ResourceGroup) {
            calls += "remove"
        }
    }

    private fun engine(
        storage: DownloaderStorage,
        port: BackgroundResourceDownloader,
        group: ResourceGroup,
        notifier: DownloadNotifier = DownloadNotifier.NoOp,
    ): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            notifier = notifier,
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    private val good: ByteArray = ZipBytesRound6.build(listOf(ZipEntryRound6("marker.txt", "ok\n"))).bytes
    // Cut inside the data of the only entry (a cut inside the central directory would still be a whole entry).
    private val cut: ByteArray = good.copyOf(41)

    @Test
    fun `a corrupt archive followed by a good one completes after exactly two transfers`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("fresh", group)
            group.units = listOf(unit)
            val service = Service(storage, mapOf("fresh" to listOf(cut, good)))
            val engine: Downloader = engine(storage, service, group)

            engine.ensureAvailable(unit)

            assertEquals(2, service.count("fresh"))
            assertTrue(engine.isAvailable(unit))
            assertEquals("ok\n", File(storage.getResourcePath(unit), "marker.txt").readText())
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }

    @Test
    fun `a failed call leaves the next call a fresh download`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("again", group)
            group.units = listOf(unit)
            val service = Service(storage, mapOf("again" to listOf(cut, cut, cut, good)))
            val engine: Downloader = engine(storage, service, group)

            val first: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()
            assertTrue(first is DownloadFailedException && first.error is DownloadError.Corrupted, "$first")
            assertEquals(2, service.count("again"))

            // A user retry is a new call: one more fresh download is allowed, the third payload is cut again and the
            // fourth is good.
            val second: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()
            assertEquals(null, second, "$second")
            assertEquals(4, service.count("again"))
            assertTrue(engine.isAvailable(unit))
        }
    }

    @Test
    fun `in a group one unit that needs a fresh download does not stop the other from committing`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val a = TestUnit("ga", group)
            val b = TestUnit("gb", group)
            group.units = listOf(a, b)
            val service = Service(storage, mapOf("ga" to listOf(good), "gb" to listOf(cut, good)))
            val engine: Downloader = engine(storage, service, group)

            engine.ensureAvailable(group)

            assertEquals(1, service.count("ga"))
            assertEquals(2, service.count("gb"))
            assertTrue(engine.isAvailable(group))
            assertFalse(File(root, "tmp/staging-ga").exists())
            assertFalse(File(root, "tmp/staging-gb").exists())
        }
    }

    @Test
    fun `the fresh download after a corrupt archive shows no error and the final failure shows exactly one`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("quiet", group)
            group.units = listOf(unit)

            // One corrupt delivery, then a good one: the user never hears of the first.
            val recovered = RecordingNotifier()
            val seenRecovered: MutableList<GroupDownloadState> = Collections.synchronizedList(ArrayList())
            val one: Downloader = engine(storage, Service(storage, mapOf("quiet" to listOf(cut, good))), group, recovered)
            val watcher = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { one.downloadState(group).collect { seenRecovered += it } }
            one.ensureAvailable(group)
            watcher.cancel()
            assertTrue(recovered.calls.none { it.startsWith("error") }, "an error was shown for a download that recovered: ${recovered.calls}")
            assertTrue(seenRecovered.none { it is GroupDownloadState.Error }, "an error state was published: $seenRecovered")
            assertEquals(GroupDownloadState.Completed, one.downloadState(group).value)

            // Two corrupt deliveries: exactly one error, Corrupted.
            val unit2 = TestUnit("loud", group)
            group.units = listOf(unit2)
            val failed = RecordingNotifier()
            val two: Downloader = engine(storage, Service(storage, mapOf("loud" to listOf(cut))), group, failed)
            val failure: Throwable? = runCatching { two.ensureAvailable(group) }.exceptionOrNull()
            assertTrue(failure is DownloadFailedException && failure.error is DownloadError.Corrupted, "$failure")
            assertEquals(listOf("error:Corrupted"), failed.calls.filter { it.startsWith("error") })
            assertTrue(two.downloadState(group).value is GroupDownloadState.Error, "${two.downloadState(group).value}")
        }
    }

    @Test
    fun `an archive with a name in the local code page fails the engine with Corrupted after exactly two transfers`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("latin", group)
            group.units = listOf(unit)
            val latin: ByteArray = ZipBytesRound6.build(
                listOf(
                    ZipEntryRound6("marker.txt", "ok\n"),
                    ZipEntryRound6(byteArrayOf(0x63, 0x61, 0x66, 0xE9.toByte(), 0x2E, 0x74), "x".encodeToByteArray()),
                ),
            ).bytes
            val service = Service(storage, mapOf("latin" to listOf(latin)))
            val engine: Downloader = engine(storage, service, group)

            val failure: Throwable? = runCatching { engine.ensureAvailable(unit) }.exceptionOrNull()

            assertTrue(failure is DownloadFailedException, "$failure")
            assertTrue(failure.error is DownloadError.Corrupted, "expected Corrupted, was ${failure.error}")
            assertEquals(2, service.count("latin"))
            assertEquals(TempFileState.None, storage.tempFileState(unit))
        }
    }
}
