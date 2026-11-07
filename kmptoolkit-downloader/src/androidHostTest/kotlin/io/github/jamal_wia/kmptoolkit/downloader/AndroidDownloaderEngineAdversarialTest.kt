package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * The engine over the real Android storage when the DI graph is rebuilt, or the process is killed
 * and restarted, over one directory on disk.
 *
 * Process death is modelled by what a kill leaves on disk (a half-extracted archive, a complete
 * temp file nobody committed) and a new storage instance and engine built over it: no statics a
 * new process would not have are reset, because the storage keeps none that matter here. Two
 * engines over one directory share one transfer port, as two DI graphs in one app share the one
 * platform download service.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderEngineAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "adversarial_engine")
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

    /**
     * The one platform download service two engines share: the first enqueue of a unit writes its
     * bytes into the temp file the storage names and reports `FileReady`; an enqueue of a unit
     * already in flight joins it.
     */
    private class SharedService(private val storage: DownloaderStorage, private val bytes: ByteArray) :
        BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 4096)
        private val started: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            if (!started.add(unit.id)) return
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
            storage.markTempFileComplete(unit)
            started.remove(unit.id)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in started
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    /** A port that must never be asked to transfer anything. */
    private class UnusedPort : BackgroundResourceDownloader {
        val enqueues: AtomicInteger = AtomicInteger()
        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            MutableSharedFlow<BackgroundDownloadEvent>()
    }

    private fun engine(
        storage: DownloaderStorage,
        port: BackgroundResourceDownloader,
        group: ResourceGroup,
    ): Downloader = createDownloader(
        storage = storage,
        backgroundDownloader = port,
        groups = listOf(group),
        stateStore = MapStateStore(),
        dispatchers = DownloadDispatchers.Default,
        logger = NoopLogger,
    )

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out: ZipOutputStream ->
            entries.forEach { (name: String, content: String) ->
                out.putNextEntry(ZipEntry(name))
                out.write(content.encodeToByteArray())
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    /** The files below [directory], without the one-time layout marker, which is not a temp file or record. */
    private fun filesUnder(directory: File): List<String> =
        directory.walkTopDown().filter { it.isFile && it.name != "legacy-layout" }
            .map { it.relativeTo(directory).path }.sorted().toList()

    // -- A kill during extraction ----------------------------------------------------------------

    @Test
    fun `a staging tree a killed process left is gone after the next process cancels the unit`() {
        val group = Group("pages")
        val unit = ArchiveUnit("pages", group).also { group.units = listOf(it) }
        File(root, "tmp/staging-pages/nested").apply { mkdirs() }.resolve("half.bin").writeText("half-extracted")
        File(root, "tmp/staging-pages/marker.txt").writeText("half")

        // The next process: a new storage instance and a new engine over the same directory.
        val engine: Downloader = engine(createDownloaderStorage(context, config), UnusedPort(), group)
        engine.cancelDownload(unit)

        assertFalse(File(root, "tmp/staging-pages").exists(), "the staging tree must be swept")
        assertEquals(emptyList(), File(root, "tmp/trash").list()?.toList().orEmpty(), "and not left as trash")
        assertFalse(engine.isAvailable(unit), "half an archive is not an available resource")
    }

    @Test
    fun `a complete archive committed by the next process does not inherit what the killed one half extracted`() =
        runBlocking {
            val group = Group("pages")
            val unit = ArchiveUnit("pages", group).also { group.units = listOf(it) }
            val firstProcess: DownloaderStorage = createDownloaderStorage(context, config)
            File(firstProcess.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
                .writeBytes(zip("marker.txt" to "ready", "page1.webp" to "bytes"))
            firstProcess.markTempFileComplete(unit)
            // The kill hit mid-extraction: the staging tree holds a stale file the archive does not have.
            File(root, "tmp/staging-pages").apply { mkdirs() }.resolve("stale.bin").writeText("stale")

            val port = UnusedPort()
            val engine: Downloader = engine(createDownloaderStorage(context, config), port, group)
            withTimeoutOrNull(10_000) { engine.ensureAvailable(unit) } ?: error("ensureAvailable never returned")

            val target = File(createDownloaderStorage(context, config).getResourcePath(unit))
            assertEquals(listOf("marker.txt", "page1.webp"), filesUnder(target), "only the archive's own entries")
            assertFalse(File(root, "tmp/staging-pages").exists(), "the staging tree is gone")
            assertEquals(0, port.enqueues.get(), "a complete temp file is committed, not downloaded again")
        }

    // -- Two engines over one directory -----------------------------------------------------------

    @Test
    fun `two engines over one directory asked for the same unit at once leave one valid file and return`() {
        val payload: ByteArray = ByteArray(64 * 1024) { index: Int -> (index % 251).toByte() }
        repeat(ROUNDS) { round: Int ->
            root.deleteRecursively()
            val group = Group("catalogue")
            val unit = FileUnit("model", group).also { group.units = listOf(it) }
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val storageB: DownloaderStorage = createDownloaderStorage(context, config)
            val service = SharedService(storageA, payload)
            val engineA: Downloader = engine(storageA, service, group)
            val engineB: Downloader = engine(storageB, service, group)

            val outcomes: List<Result<Unit>>? = runBlocking {
                withContext(Dispatchers.Default) {
                    val go = CompletableDeferred<Unit>()
                    val callers: List<Deferred<Result<Unit>>> = List(8) { index: Int ->
                        async {
                            go.await()
                            runCatching { (if (index % 2 == 0) engineA else engineB).ensureAvailable(unit) }
                        }
                    }
                    go.complete(Unit)
                    withTimeoutOrNull(10_000) { callers.awaitAll() }
                }
            }

            assertTrue(outcomes != null, "round $round: a caller never returned")
            outcomes.forEachIndexed { index: Int, outcome: Result<Unit> ->
                assertTrue(outcome.isSuccess, "round $round: caller $index failed with ${outcome.exceptionOrNull()}")
            }
            assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes(), "round $round: the file is whole")
            assertEquals(TempFileState.None, storageA.tempFileState(unit), "round $round")
            assertEquals(TempFileState.None, storageB.tempFileState(unit), "round $round")
            assertEquals(emptyList(), filesUnder(File(root, "tmp")), "round $round: no temp file or record is left")
            assertTrue(storageB.isResourceAvailable(unit), "round $round")
        }
    }

    @Test
    fun `a commit racing 6 threads that delete the temp file ends with a whole resource or none never a partial one`() {
        val payload: ByteArray = ByteArray(256 * 1024) { index: Int -> (index % 241).toByte() }
        repeat(30) { round: Int ->
            root.deleteRecursively()
            val group = Group("catalogue")
            val unit = FileUnit("model", group).also { group.units = listOf(it) }
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(payload)
            storage.markTempFileComplete(unit)
            val go = java.util.concurrent.CountDownLatch(1)

            val committer = Thread {
                go.await()
                runBlocking { runCatching { createDownloaderStorage(context, config).commitResource(unit) } }
            }
            val deleters: List<Thread> = (0 until 6).map {
                Thread {
                    go.await()
                    repeat(20) { createDownloaderStorage(context, config).deleteTempFile(unit) }
                }
            }
            (deleters + committer).forEach(Thread::start)
            go.countDown()
            (deleters + committer).forEach { it.join(15_000) }
            assertTrue((deleters + committer).none { it.isAlive }, "round $round: a thread is blocked")

            val resource = File(storage.getResourcePath(unit))
            if (resource.exists()) {
                assertContentEquals(payload, resource.readBytes(), "round $round: the committed file is not whole")
            }
            assertFalse(
                File(storage.getTempFilePath(unit)).exists() && resource.exists() && storage.tempFileState(unit) == TempFileState.None,
                "round $round",
            )
        }
    }

    /**
     * The race of the test above made deterministic: the unit is committed by "the other engine"
     * (a second storage instance) while this engine's own transfer is in flight, and this
     * engine's transfer then finishes too. Its temp file must not outlive the call.
     */
    private class LateTransferService(
        private val storage: DownloaderStorage,
        private val otherEngineStorage: DownloaderStorage,
        private val bytes: ByteArray,
    ) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 16)

        override fun enqueueDownload(unit: DownloadUnit) {
            File(otherEngineStorage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
            otherEngineStorage.markTempFileComplete(unit)
            runBlocking { otherEngineStorage.commitResource(unit) }
            File(storage.getTempFilePath(unit)).writeBytes(bytes)
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    @Test
    fun `a unit another engine committed first leaves no temp file of this engine's transfer`() {
        val payload: ByteArray = ByteArray(1024) { index: Int -> (index % 251).toByte() }
        val group = Group("catalogue")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val other: DownloaderStorage = createDownloaderStorage(context, config)
        val engine: Downloader = engine(storage, LateTransferService(storage, other, payload), group)

        runBlocking {
            withTimeoutOrNull(10_000) { engine.ensureAvailable(unit) } ?: error("ensureAvailable never returned")
        }

        assertContentEquals(payload, File(storage.getResourcePath(unit)).readBytes())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(emptyList(), filesUnder(File(root, "tmp")), "no temp file or record is left")
    }

    @Test
    fun `a group unit another engine committed first leaves no temp file of this engine's transfer`() {
        val payload: ByteArray = ByteArray(1024) { index: Int -> (index % 251).toByte() }
        val group = Group("catalogue")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val other: DownloaderStorage = createDownloaderStorage(context, config)
        val engine: Downloader = engine(storage, LateTransferService(storage, other, payload), group)

        runBlocking {
            withTimeoutOrNull(10_000) { engine.ensureAvailable(group) } ?: error("ensureAvailable never returned")
        }

        assertContentEquals(payload, File(storage.getResourcePath(unit)).readBytes())
        assertEquals(TempFileState.None, storage.tempFileState(unit))
        assertEquals(emptyList(), filesUnder(File(root, "tmp")), "no temp file or record is left")
    }

    private companion object {
        const val ROUNDS: Int = 30
    }
}
