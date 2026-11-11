package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Round 7: failures inside the cross-engine transfer lock. A transfer whose bytes fail their hash
 * releases the lock like any other exit, and the engine that waited behind it then runs (and may fail)
 * on its own account: no hang, no debris, bounded downloads, and a later engine with good bytes recovers.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderIntegrityLockRound7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "adversarial_integrity7")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class HashedUnit(
        override val id: String,
        override val group: ResourceGroup,
        override val sha256: Sha256?,
    ) : DownloadUnit {
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

    /** Transfers on the calling thread: transfer n (counted per unit, from 1) writes `bytesFor(n)`. */
    private class QuickPort(
        private val storage: DownloaderStorage,
        private val bytesFor: (unit: DownloadUnit, transfer: Int) -> ByteArray,
    ) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 4096)
        private val transfers: ConcurrentHashMap<String, AtomicInteger> = ConcurrentHashMap()
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            val transfer: Int = transfers.computeIfAbsent(unit.id) { AtomicInteger() }.incrementAndGet()
            val file = File(storage.getTempFilePath(unit))
            file.parentFile!!.mkdirs()
            file.writeBytes(bytesFor(unit, transfer))
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
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

    private fun sha(bytes: ByteArray): Sha256 =
        Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun filesUnder(directory: File): List<String> =
        directory.walkTopDown().filter { it.isFile && it.name != "legacy-layout" }
            .map { it.relativeTo(directory).path }.sorted().toList()

    private fun transferLockRegistrySize(): Int {
        val field = UnitTransferLocks::class.java.getDeclaredField("entries").apply { isAccessible = true }
        val flow = field.get(UnitTransferLocks) as kotlinx.coroutines.flow.StateFlow<*>
        return (flow.value as Map<*, *>).size
    }

    private val good: ByteArray = ByteArray(20_000) { index: Int -> (index % 233).toByte() }
    private val bad: ByteArray = ByteArray(20_000) { 7 }

    @Test
    fun `one corrupt transfer before a good one never fails either of two engines`() = runBlocking {
        val group = Group("catalogue")
        val unit = HashedUnit("model", group, sha(good)).also { group.units = listOf(it) }
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val port = QuickPort(storage) { _, transfer -> if (transfer == 1) bad else good }
        val engineA: Downloader = engine(storage, port, group)
        val engineB: Downloader = engine(createDownloaderStorage(context, config), port, group)

        val outcomes: List<Result<Unit>> = withTimeoutOrNull(30_000) {
            withContext(Dispatchers.Default) {
                val a: Deferred<Result<Unit>> = async { runCatching { engineA.ensureAvailable(unit) } }
                val b: Deferred<Result<Unit>> = async { runCatching { engineB.ensureAvailable(group) } }
                listOf(a.await(), b.await())
            }
        } ?: error("a caller never returned")

        outcomes.forEachIndexed { index: Int, outcome: Result<Unit> ->
            assertTrue(outcome.isSuccess, "caller $index failed with ${outcome.exceptionOrNull()}")
        }
        assertContentEquals(good, File(storage.getResourcePath(unit)).readBytes())
        assertEquals(emptyList(), filesUnder(File(root, "tmp")))
        assertEquals(0, transferLockRegistrySize())
    }

    @Test
    fun `permanently corrupt bytes fail every engine in bounded downloads and a later engine with good bytes recovers`() =
        runBlocking {
            val group = Group("catalogue")
            val unit = HashedUnit("model", group, sha(good)).also { group.units = listOf(it) }
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val badPort = QuickPort(storage) { _, _ -> bad }
            val engines: List<Downloader> = List(4) { engine(createDownloaderStorage(context, config), badPort, group) }

            val outcomes: List<Result<Unit>> = withTimeoutOrNull(60_000) {
                withContext(Dispatchers.Default) {
                    engines.mapIndexed { index: Int, engine: Downloader ->
                        async { runCatching { if (index % 2 == 0) engine.ensureAvailable(unit) else engine.ensureAvailable(group) } }
                    }.map { it.await() }
                }
            } ?: error("a caller never returned (a lock was not released after an integrity failure?)")

            outcomes.forEachIndexed { index: Int, outcome: Result<Unit> ->
                assertTrue(outcome.exceptionOrNull() is DownloadFailedException, "caller $index: $outcome")
            }
            // Each call downloads at most 1 + MAX_INTEGRITY_RETRIES times; four calls, one transfer after another.
            assertTrue(badPort.enqueues.get() in 4..8, "downloads: ${badPort.enqueues.get()}")
            assertEquals(false, storage.isResourceAvailable(unit))
            assertEquals(emptyList(), filesUnder(File(root, "tmp")), "debris after the failures")
            assertEquals(0, transferLockRegistrySize())

            val recovery: Downloader = engine(createDownloaderStorage(context, config), QuickPort(storage) { _, _ -> good }, group)
            withTimeoutOrNull(20_000) { recovery.ensureAvailable(unit) } ?: error("recovery never returned")
            assertContentEquals(good, File(storage.getResourcePath(unit)).readBytes())
        }
}
