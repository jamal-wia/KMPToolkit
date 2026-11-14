package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B round 10, Q3 (resource budget): the cost of a unit, a wave of units and a large archive is counted, never timed.
 * Contract: 05-platform-notes ("The lock registries keep an entry only while a call holds or awaits it", "Available and
 * Completed are process-lifetime for the per-unit surface"), DownloaderStorage.commitResource (an archive is extracted
 * entry by entry, every entry or a ResourceIntegrityException), and the recorded hash record that a commit consumes.
 * Counters: the two lock registries' activeKeyCount, the files left under tmp/, the live thread count, the bytes the
 * committing thread allocates.
 */
@RunWith(AndroidJUnit4::class)
class AndroidBudgetRound10Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "budget_r10")
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

    /** The host's downloader: the transfer is [onEnqueue], run at once on the engine's thread. */
    private class Port(private val onEnqueue: (DownloadUnit, Port) -> Unit) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 1024)
        val enqueues: AtomicInteger = AtomicInteger()
        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            onEnqueue(unit, this)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events.filter { it.unit.id == unit.id }
    }

    private fun sha(bytes: ByteArray): Sha256 =
        Sha256.parse(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })

    private fun zip(entries: Int, name: (Int) -> String, size: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        val body: ByteArray = ByteArray(size) { 7 }
        ZipOutputStream(buffer).use { zip: ZipOutputStream ->
            for (i: Int in 0 until entries) {
                zip.putNextEntry(ZipEntry(name(i)))
                zip.write(body)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun filesUnder(dir: File): List<String> =
        dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(dir).invariantSeparatorsPath }.sorted().toList()

    private fun allocatedBytes(): Long {
        val bean: com.sun.management.ThreadMXBean = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
        return bean.getThreadAllocatedBytes(Thread.currentThread().id)
    }

    /** Runs [block] on one IO thread (a commit stays on it when nothing contends) and returns what that thread allocated. */
    private suspend fun allocatedBy(block: suspend () -> Unit): Long = withContext(Dispatchers.IO) {
        val before: Long = allocatedBytes()
        block()
        allocatedBytes() - before
    }

    private fun liveThreads(): Int = Thread.getAllStackTraces().keys.count { it.isAlive }

    private fun stage(storage: DownloaderStorage, unit: DownloadUnit, bytes: ByteArray) {
        storage.beginTempFile(unit, unit.sha256)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(bytes)
        storage.markTempFileComplete(unit)
    }

    @Test
    fun `two hundred units in flight over the real storage leave no lock entry no record no temp file and a bounded thread count`() = runBlocking<Unit> {
        withTimeout(120_000) {
            val real: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val units: List<TestUnit> = (0 until 200).map { i: Int ->
                val bytes: ByteArray = ByteArray(2_000 + i) { (it + i).toByte() }
                // Half the units carry a recorded hash, so the record of a commit is part of what must be gone afterwards.
                TestUnit("unit-$i", "files/u$i.bin", group, ResourceFormat.Opaque, if (i % 2 == 0) sha(bytes) else null)
            }
            group.units = units
            fun payload(unit: DownloadUnit): ByteArray {
                val i: Int = unit.id.removePrefix("unit-").toInt()
                return ByteArray(2_000 + i) { (it + i).toByte() }
            }
            val port = Port { unit: DownloadUnit, p: Port ->
                real.beginTempFile(unit, unit.sha256)
                File(real.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(payload(unit))
                real.markTempFileComplete(unit)
                p.events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            }
            val downloader: Downloader = createDownloader(
                storage = real,
                backgroundDownloader = port,
                groups = listOf(group),
                stateStore = MapStateStore(),
                dispatchers = DownloadDispatchers.Default,
                logger = NoopLogger,
            )
            val cores: Int = Runtime.getRuntime().availableProcessors()
            // Dispatchers.IO is capped at 64 threads (or the core count when higher), Default at the core count; one thread
            // per unit would be 200 more.
            val ceiling: Int = liveThreads() + 64 + 2 * cores + 16
            for (wave: Int in 0 until 2) {
                units.map { unit: TestUnit -> async(Dispatchers.Default) { downloader.ensureAvailable(unit) } }.awaitAll()
                assertEquals(0, UnitTransferLocks.activeKeyCount, "transfer lock entries left after wave $wave")
                assertEquals(0, UnitCommitLocks.activeKeyCount, "commit lock entries left after wave $wave")
                assertTrue(units.all { downloader.isAvailable(it) })
                units.forEach { assertTrue(payload(it).contentEquals(File(downloader.pathOf(it)).readBytes()), "${it.id} differs") }
                assertEquals(emptyList(), filesUnder(File(root, "tmp")).filter { !it.startsWith("kmptoolkit-internal/") && !it.endsWith(".marker") }.take(5), "temp files or hash records survived the commits")
                assertTrue(liveThreads() <= ceiling, "threads grew with the number of units: ${liveThreads()} > $ceiling")
                // Delete everything: the second wave transfers all of it again.
                if (wave == 0) units.forEach { real.deleteResource(it) }
            }
            assertEquals(400, port.enqueues.get(), "each wave transfers each unit exactly once")
            // A third wave over resources that are all there transfers nothing.
            units.map { unit: TestUnit -> async(Dispatchers.Default) { downloader.ensureAvailable(unit) } }.awaitAll()
            assertEquals(400, port.enqueues.get(), "available units were transferred again")
            assertEquals(0, UnitTransferLocks.activeKeyCount)
            assertEquals(0, UnitCommitLocks.activeKeyCount)
        }
    }

    @Test
    fun `an archive of fifty thousand entries commits whole and the committing thread allocates a bounded amount per entry`() = runBlocking<Unit> {
        withTimeout(240_000) {
            val real: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val entries = 50_000
            // 250 directories of 200 files: a realistic wide tree.
            val bytes: ByteArray = zip(entries, { i: Int -> "d${i / 200}/f$i.bin" }, size = 8)
            val unit = TestUnit("big", "tree", group, ResourceFormat.ZipArchive("d0/f0.bin"), sha(bytes))
            group.units = listOf(unit)
            stage(real, unit, bytes)
            val allocated: Long = allocatedBy { real.commitResource(unit) }
            assertTrue(real.isResourceAvailable(unit))
            val tree = File(real.getResourcePath(unit))
            assertEquals(entries, tree.walkTopDown().count { it.isFile }, "entries missing after the commit")
            assertEquals(8L, File(tree, "d249/f49999.bin").length())
            assertEquals(emptyList(), filesUnder(File(root, "tmp")).filter { !it.startsWith("kmptoolkit-internal/") }, "litter after the commit")
            // Bytes allocated by the thread that extracts: the reusable buffer plus per-entry bookkeeping. A copy of the
            // archive or a per-entry buffer would show as megabytes per thousand entries.
            assertTrue(allocated < entries * 16_384L, "the commit allocated $allocated bytes for $entries entries")
        }
    }

    @Test
    fun `a 256 MB entry is streamed through one reusable buffer`() = runBlocking<Unit> {
        withTimeout(240_000) {
            val real: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("huge", "tree", group, ResourceFormat.ZipArchive("big.bin"))
            group.units = listOf(unit)
            real.beginTempFile(unit, null)
            val tempFile = File(real.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
            val megabyte: ByteArray = ByteArray(1 shl 20)
            val megabytes = 256
            ZipOutputStream(FileOutputStream(tempFile).buffered()).use { zip: ZipOutputStream ->
                zip.putNextEntry(ZipEntry("big.bin"))
                repeat(megabytes) { zip.write(megabyte) }
                zip.closeEntry()
            }
            real.markTempFileComplete(unit)
            val allocated: Long = allocatedBy { real.commitResource(unit) }
            assertEquals(megabytes.toLong() shl 20, File(real.getResourcePath(unit), "big.bin").length())
            assertTrue(allocated < 32L shl 20, "the commit allocated $allocated bytes for one $megabytes MB entry: it is not streamed")
            assertFalse(tempFile.exists(), "the temp file survived the commit")
        }
    }
}
