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
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * Round 8 of the breaker for unit B, Q1: a process death at every boundary of one download, replayed deterministically.
 *
 * The model of a death: the Nth call into the storage (the engine's and the host port's alike, plus an explicit step
 * between the chunks the port writes) and every call after it throw [ProcessDeath], so no cleanup of the dying process
 * can run. The directory is then left exactly as it is and a new engine over a new storage instance (the migration
 * record forgotten, as in a new process) is asked for the unit. Contract tested (docs/kmptoolkit-downloader/01-overview.md
 * "Survives process death", 03-guide.md "A crash therefore needs no recovery pass", 05-platform-notes.md): after any
 * death the final path never holds torn bytes, a Complete temp file is whole, a Partial one is a prefix of the payload,
 * the next `ensureAvailable` succeeds with the exact payload, resumes (one transfer at most, none when the bytes were
 * already complete or committed) and leaves no temp litter. The step-by-outcome table is printed to the test output.
 */
@RunWith(AndroidJUnit4::class)
class AndroidCrashPointsRound8Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "crash_points_r8")
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

    private class ProcessDeath(step: String) : RuntimeException("process death before $step")

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat,
        override val sha256: Sha256?,
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

    /** Counts the boundaries, and from the [dieAt]th on every one throws; [dieAt] 0 never dies. */
    private class Death(private val real: DownloaderStorage, private val dieAt: Int) : DownloaderStorage by real {
        val steps: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        @Volatile var dead: Boolean = false

        fun step(name: String) {
            val n: Int = synchronized(steps) { steps.add(name); steps.size }
            if (dead || (dieAt in 1..n && n == dieAt)) {
                dead = true
                throw ProcessDeath("#$n $name")
            }
        }

        override fun isResourceAvailable(unit: DownloadUnit): Boolean { step("isResourceAvailable"); return real.isResourceAvailable(unit) }
        override fun getResourcePath(unit: DownloadUnit): String = real.getResourcePath(unit)
        override fun getTempFilePath(unit: DownloadUnit): String = real.getTempFilePath(unit)
        override fun tempFileState(unit: DownloadUnit): TempFileState { step("tempFileState"); return real.tempFileState(unit) }
        override fun getTempFileSize(unit: DownloadUnit): Long { step("getTempFileSize"); return real.getTempFileSize(unit) }
        override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) { step("beginTempFile"); real.beginTempFile(unit, expectedSha256) }
        override fun markTempFileComplete(unit: DownloadUnit) { step("markTempFileComplete"); real.markTempFileComplete(unit) }
        override fun deleteTempFile(unit: DownloadUnit) { step("deleteTempFile"); real.deleteTempFile(unit) }
        override suspend fun commitResource(unit: DownloadUnit) { step("commitResource"); real.commitResource(unit) }
        override fun getResourceSize(unit: DownloadUnit): Long { step("getResourceSize"); return real.getResourceSize(unit) }
        override fun deleteResource(unit: DownloadUnit) { step("deleteResource"); real.deleteResource(unit) }
    }

    /**
     * The host's downloader as a Range-capable transfer: a missing temp file is begun and written in two chunks, a Partial
     * one is appended to from its own size, then the file is marked complete and FileReady follows.
     */
    private class Port(private val storage: Death, private val bytes: ByteArray, private val sha: Sha256) : BackgroundResourceDownloader {
        val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            val state: TempFileState = storage.tempFileState(unit)
            val from: Int
            if (state == TempFileState.Partial) {
                from = storage.getTempFileSize(unit).toInt()
            } else {
                storage.beginTempFile(unit, sha)
                from = 0
            }
            val path = File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
            val middle: Int = maxOf(from, bytes.size / 2)
            storage.step("write chunk 1")
            FileOutputStream(path, true).use { it.write(bytes, from, middle - from) }
            storage.step("write chunk 2")
            FileOutputStream(path, true).use { it.write(bytes, middle, bytes.size - middle) }
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
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
        ZipOutputStream(buffer).use { zip ->
            for ((name: String, data: ByteArray) in files) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private fun litter(): List<String> = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path }
        .filter { it.startsWith("tmp") && !it.endsWith("legacy-layout") }.sorted().toList()

    private class Scenario(val unit: TestUnit, val bytes: ByteArray, val expectedTree: Map<String, ByteArray>?)

    private fun opaque(): Scenario {
        val bytes: ByteArray = ByteArray(70_000) { (it % 251).toByte() }
        val group = Group("g")
        val unit = TestUnit("u", "files/u.bin", group, ResourceFormat.Opaque, sha(bytes))
        group.units = listOf(unit)
        return Scenario(unit, bytes, null)
    }

    private fun archive(): Scenario {
        val tree: Map<String, ByteArray> = mapOf(
            "marker.txt" to "m".encodeToByteArray(),
            "a.bin" to ByteArray(30_000) { (it % 13).toByte() },
            "sub/b.bin" to ByteArray(30_000) { (it % 7).toByte() },
        )
        val bytes: ByteArray = zip(tree)
        val group = Group("g")
        val unit = TestUnit("z", "archives/z", group, ResourceFormat.ZipArchive("marker.txt"), sha(bytes))
        group.units = listOf(unit)
        return Scenario(unit, bytes, tree)
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

    private fun newStorage(): DownloaderStorage {
        LegacyTempMigrations.resetForTests()
        return AndroidDownloaderStorage(context, config, NoopLogger)
    }

    private suspend fun runProcess(scenario: Scenario, dieAt: Int): Pair<Death, Port> {
        val storage = Death(newStorage(), dieAt)
        val port = Port(storage, scenario.bytes, scenario.unit.sha256!!)
        val engine: Downloader = engine(storage, port, scenario.unit.group)
        withContext(Dispatchers.Default) {
            withTimeout(60_000) { runCatching { engine.ensureAvailable(scenario.unit) } }
        }
        return storage to port
    }

    private fun assertResourceWhole(scenario: Scenario, where: String) {
        val unit: TestUnit = scenario.unit
        val path = File(newStorage().getResourcePath(unit))
        if (scenario.expectedTree == null) {
            if (path.exists()) assertTrue(scenario.bytes.contentEquals(path.readBytes()), "$where: torn bytes at the final path")
        } else if (path.exists() && File(path, "marker.txt").isFile) {
            for ((name: String, data: ByteArray) in scenario.expectedTree) {
                assertTrue(File(path, name).isFile && data.contentEquals(File(path, name).readBytes()), "$where: available tree misses or tears $name")
            }
        }
    }

    private fun sweep(label: String, make: () -> Scenario) = runBlocking<Unit> {
        // Run 0: no death, to learn the boundaries.
        val reference: Scenario = make()
        val (full: Death, _) = runProcess(reference, 0)
        val boundaries: List<String> = full.steps.toList()
        assertTrue(boundaries.size in 5..400, "boundaries: ${boundaries.size}")
        assertTrue(boundaries.contains("commitResource") && boundaries.contains("write chunk 2"), "$boundaries")
        root.deleteRecursively()
        val table = StringBuilder("CRASH TABLE $label (${boundaries.size} boundaries)\n")
        for (n: Int in 1..boundaries.size) {
            root.deleteRecursively()
            val scenario: Scenario = make()
            val (dying: Death, _) = runProcess(scenario, n)
            val stepName: String = dying.steps.getOrNull(n - 1) ?: "?"
            // The state the dead process left, judged by a new storage.
            val after: DownloaderStorage = newStorage()
            val tempState: TempFileState = after.tempFileState(scenario.unit)
            val resourceThere: Boolean = after.isResourceAvailable(scenario.unit)
            assertResourceWhole(scenario, "n=$n before $stepName")
            val temp = File(after.getTempFilePath(scenario.unit))
            val complete = File(temp.path + ".complete")
            if (tempState == TempFileState.Complete) assertTrue(scenario.bytes.contentEquals(complete.readBytes()), "n=$n before $stepName: a Complete temp file is torn")
            if (tempState == TempFileState.Partial) {
                val head: ByteArray = temp.readBytes()
                assertTrue(head.size <= scenario.bytes.size && head.contentEquals(scenario.bytes.copyOf(head.size)), "n=$n before $stepName: a Partial temp file is not a prefix")
            }
            // The restart.
            val storage2 = Death(newStorage(), 0)
            val port2 = Port(storage2, scenario.bytes, scenario.unit.sha256!!)
            val engine2: Downloader = engine(storage2, port2, scenario.unit.group)
            withContext(Dispatchers.Default) { withTimeout(60_000) { engine2.ensureAvailable(scenario.unit) } }
            assertTrue(engine2.isAvailable(scenario.unit), "n=$n before $stepName: not available after the restart")
            val finalPath = File(engine2.pathOf(scenario.unit))
            if (scenario.expectedTree == null) {
                assertTrue(scenario.bytes.contentEquals(finalPath.readBytes()), "n=$n before $stepName: restart ended with other bytes")
            } else {
                for ((name: String, data: ByteArray) in scenario.expectedTree) {
                    assertTrue(data.contentEquals(File(finalPath, name).readBytes()), "n=$n before $stepName: restart tree differs at $name")
                }
            }
            val expectedMaxTransfers: Int = if (tempState == TempFileState.Complete || resourceThere) 0 else 1
            assertTrue(port2.enqueues.get() <= expectedMaxTransfers, "n=$n before $stepName: ${port2.enqueues.get()} transfers after the restart, state $tempState resource $resourceThere")
            assertEquals(emptyList(), litter(), "n=$n before $stepName: temp litter after the restart")
            table.append("  n=$n before $stepName -> temp=$tempState resource=$resourceThere restartTransfers=${port2.enqueues.get()}\n")
        }
        println(table)
    }

    @Test
    fun `a death before any boundary of an opaque download leaves nothing torn and the restart resumes`() = sweep("opaque") { opaque() }

    @Test
    fun `a death before any boundary of an archive download leaves nothing torn and the restart resumes`() = sweep("archive") { archive() }
}
