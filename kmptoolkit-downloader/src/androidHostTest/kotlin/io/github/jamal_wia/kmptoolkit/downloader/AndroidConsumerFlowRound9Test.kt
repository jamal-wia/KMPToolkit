package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B round 9, Q3: the public API as an app uses it (02-getting-started steps 5 to 7: createDownloaderStorage, createDownloader,
 * ensureAvailable, downloadState), over a fake transport and the real storage. Contract: 02-getting-started "Progress never
 * goes backwards, and Completed is preceded by a settle delay at 1f"; 02 step 6 (returns at once when available, throws
 * DownloadCancelledException on cancel); DownloaderStorage.commitResource ("a failed check ... the engine answers with a
 * fresh download rather than a failure"); 05-platform-notes "Available/Completed are process-lifetime".
 */
@RunWith(AndroidJUnit4::class)
class AndroidConsumerFlowRound9Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "flow_r9")
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

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat = ResourceFormat.Opaque,
        override val sha256: Sha256? = null,
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val tempExtension: String = "tmp"
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

    /** The host's downloader: [onEnqueue] stands for the transfer. [seen] records what the storage said at each enqueue. */
    private class Port(
        private val storage: DownloaderStorage,
        private val onEnqueue: (DownloadUnit, Port, Int) -> Unit,
    ) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()
        val seen: CopyOnWriteArrayList<Pair<TempFileState, Long>> = CopyOnWriteArrayList()
        val started: CompletableDeferred<Unit> = CompletableDeferred()

        override fun enqueueDownload(unit: DownloadUnit) {
            seen += storage.tempFileState(unit) to storage.getTempFileSize(unit)
            onEnqueue(unit, this, enqueues.incrementAndGet())
            started.complete(Unit)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events.filter { it.unit.id == unit.id }
    }

    private fun sha(bytes: ByteArray): Sha256 =
        Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun zip(files: Map<String, ByteArray>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip: ZipOutputStream ->
            for ((name: String, data: ByteArray) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun downloader(storage: DownloaderStorage, port: BackgroundResourceDownloader, group: ResourceGroup): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    private fun write(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray, expected: Sha256?, complete: Boolean) {
        storage.beginTempFile(unit, expected)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        if (complete) storage.markTempFileComplete(unit)
    }

    /** Collects the states of [flow] until the test cancels the job; the first value is the replayed current state. */
    private fun <T> kotlinx.coroutines.CoroutineScope.collectInto(flow: Flow<T>, into: MutableList<T>, until: (T) -> Boolean = { false }, done: CompletableDeferred<Unit> = CompletableDeferred()) =
        launch(Dispatchers.Default, start = CoroutineStart.UNDISPATCHED) { flow.collect { value: T -> into += value; if (until(value)) done.complete(Unit) } }

    @Test
    fun `a group of an archive and a file goes Idle then Downloading never backwards then Completed with both units on disk`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val real: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val archiveBytes: ByteArray = zip(mapOf("marker.txt" to "m".encodeToByteArray(), "p/1.bin" to ByteArray(500) { 1 }))
            val fileBytes: ByteArray = ByteArray(40_000) { (it % 251).toByte() }
            val archive = TestUnit("pages", "pages", group, ResourceFormat.ZipArchive("marker.txt"), sha(archiveBytes))
            val file = TestUnit("model", "models/m.bin", group, ResourceFormat.Opaque, sha(fileBytes))
            group.units = listOf(archive, file)
            val port = Port(real) { unit: DownloadUnit, p: Port, _: Int ->
                val bytes: ByteArray = if (unit.id == "pages") archiveBytes else fileBytes
                p.events.tryEmit(BackgroundDownloadEvent.Progress(unit, 0.3f))
                write(real, unit, bytes, (unit.sha256), complete = true)
                p.events.tryEmit(BackgroundDownloadEvent.Progress(unit, 0.2f))
                p.events.tryEmit(BackgroundDownloadEvent.Progress(unit, 1f))
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            }
            val downloader: Downloader = downloader(real, port, group)
            val states: MutableList<GroupDownloadState> = CopyOnWriteArrayList()
            val done: CompletableDeferred<Unit> = CompletableDeferred()
            val job = collectInto(downloader.downloadState(group), states, { it == GroupDownloadState.Completed }, done)
            downloader.ensureAvailable(group)
            assertEquals(GroupDownloadState.Completed, downloader.downloadState(group).value, "ensureAvailable returned before Completed was published")
            done.await()
            job.cancel()
            assertEquals(GroupDownloadState.Idle, states.first(), "$states")
            assertEquals(GroupDownloadState.Completed, states.last(), "$states")
            assertFalse(states.any { it is GroupDownloadState.Error }, "$states")
            val progress: List<Float> = states.filterIsInstance<GroupDownloadState.Downloading>().map { it.progress }
            assertEquals(progress.sorted(), progress, "progress went backwards: $states")
            assertTrue(progress.isNotEmpty() && progress.last() == 1f, "Completed was not preceded by Downloading(1f): $states")
            assertEquals(states.indexOf(GroupDownloadState.Completed), states.lastIndex, "a state follows Completed: $states")
            assertTrue(downloader.isAvailable(group))
            assertTrue(File(downloader.pathOf(archive), "p/1.bin").isFile)
            assertTrue(fileBytes.contentEquals(File(downloader.pathOf(file)).readBytes()))
            assertEquals(2, port.enqueues.get())
        }
    }

    @Test
    fun `after a restart a partial file is resumed from its size and a corrupt prefix ends in one fresh download not a failure`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val real: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val bytes: ByteArray = ByteArray(50_000) { (it % 241).toByte() }
            val unit = TestUnit("u", "files/u.bin", group, ResourceFormat.Opaque, sha(bytes))
            group.units = listOf(unit)
            // The process that died: half of the transfer on disk, its expectation recorded.
            write(real, unit, bytes.copyOf(25_000), sha(bytes), complete = false)
            val port = Port(real) { u: DownloadUnit, p: Port, _: Int ->
                val have: Long = real.getTempFileSize(u)
                if (have > 0) {
                    // A correct 206 resume: append the rest, then mark complete.
                    File(real.getTempFilePath(u)).appendBytes(bytes.copyOfRange(have.toInt(), bytes.size))
                    real.markTempFileComplete(u)
                } else {
                    write(real, u, bytes, sha(bytes), complete = true)
                }
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
            }
            val downloader: Downloader = downloader(createDownloaderStorage(context, config), port, group)
            downloader.ensureAvailable(unit)
            assertEquals(listOf(TempFileState.Partial to 25_000L), port.seen.toList(), "the resume did not see the partial file")
            assertTrue(bytes.contentEquals(File(downloader.pathOf(unit)).readBytes()))
            assertEquals(1, port.enqueues.get())

            // A restart over a partial file whose bytes are wrong (a corrupted prefix): the commit fails the check and
            // the engine downloads again from zero; the caller never sees a failure.
            real.deleteResource(unit)
            val corrupt: ByteArray = bytes.copyOf(25_000).also { it[100] = (it[100] + 1).toByte() }
            write(real, unit, corrupt, sha(bytes), complete = false)
            val second = Port(real) { u: DownloadUnit, p: Port, n: Int ->
                val have: Long = real.getTempFileSize(u)
                if (have > 0) {
                    File(real.getTempFilePath(u)).appendBytes(bytes.copyOfRange(have.toInt(), bytes.size))
                    real.markTempFileComplete(u)
                } else {
                    write(real, u, bytes, sha(bytes), complete = true)
                }
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
            }
            val again: Downloader = downloader(createDownloaderStorage(context, config), second, group)
            val states: MutableList<UnitDownloadState> = CopyOnWriteArrayList()
            val done: CompletableDeferred<Unit> = CompletableDeferred()
            val job = collectInto(again.unitDownloadStateFlow(unit), states, { it == UnitDownloadState.Completed }, done)
            again.ensureAvailable(unit)
            done.await()
            job.cancel()
            assertTrue(bytes.contentEquals(File(again.pathOf(unit)).readBytes()), "the fresh download did not land")
            assertEquals(2, second.enqueues.get(), "one resume (corrupt) and one fresh transfer expected; saw ${second.seen}")
            assertEquals(TempFileState.Partial, second.seen.first().first)
            assertEquals(TempFileState.None, second.seen.last().first, "the retry must start from nothing: ${second.seen}")
            assertFalse(states.any { it is UnitDownloadState.Error }, "$states")
        }
    }

    @Test
    fun `a unit deleted behind the engine is downloaded again and a cancel then a retry completes`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val real: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val first: ByteArray = ByteArray(20_000) { 1 }
            val unit = TestUnit("u", "files/u.bin", group)
            group.units = listOf(unit)
            var payload: ByteArray = first
            var hold = true
            val port = Port(real) { u: DownloadUnit, p: Port, _: Int ->
                if (hold) {
                    write(real, u, payload.copyOf(5_000), null, complete = false)
                } else {
                    write(real, u, payload, null, complete = true)
                    p.events.tryEmit(BackgroundDownloadEvent.FileReady(u))
                }
            }
            val downloader: Downloader = downloader(real, port, group)
            // Cancel mid-transfer: the call ends with the documented exception, the state returns to Idle, nothing is committed.
            val call = async(Dispatchers.Default) { runCatching { downloader.ensureAvailable(unit) }.exceptionOrNull() }
            port.started.await()
            downloader.cancelDownload(unit)
            assertTrue(call.await() is DownloadCancelledException)
            assertFalse(downloader.isAvailable(unit))
            assertEquals(GroupDownloadState.Idle, downloader.downloadState(group).value)
            assertEquals(TempFileState.None, real.tempFileState(unit), "the cancelled transfer left a partial file")
            // Retry: completes.
            hold = false
            downloader.ensureAvailable(unit)
            assertTrue(first.contentEquals(File(downloader.pathOf(unit)).readBytes()))
            // Replace: the resource is deleted around the engine (documented: flows stay stale), the next request transfers again.
            real.deleteResource(unit)
            assertFalse(downloader.isAvailable(unit), "availability must be read from the storage")
            payload = ByteArray(20_000) { 2 }
            downloader.ensureAvailable(unit)
            assertTrue(payload.contentEquals(File(downloader.pathOf(unit)).readBytes()), "the replaced resource is not the new payload")
            assertEquals(3, port.enqueues.get())
            assertEquals(emptyList(), root.walkTopDown().filter { it.isFile && it.name.contains(".tmp") }.map { it.name }.toList())
        }
    }
}
