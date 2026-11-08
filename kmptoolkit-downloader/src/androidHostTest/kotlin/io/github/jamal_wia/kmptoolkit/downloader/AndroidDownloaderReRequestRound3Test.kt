package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Re-request storms: eight units, one thread per unit that asks, cancels and asks again, with a chaos thread cancelling
 * and a host deleting resources underneath, over two engines and one directory.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderReRequestRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "rerequest_round3")
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

    private class FileUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "files/$id.bin"
    }

    private class ArchiveUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "archives/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
    }

    private class HashedUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/hashed"
        override val relativePath: String = "files/hashed-${id.hashCode()}.bin"
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

    /** A port that never transfers anything: complete temp files are committed straight away. */
    private class UnusedPort : BackgroundResourceDownloader {
        override fun enqueueDownload(unit: DownloadUnit) = Unit
        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            MutableSharedFlow<BackgroundDownloadEvent>()
    }

    /**
     * One platform service two engines share. A transfer writes its bytes in small steps with a jitter
     * between them; a cancel of a transfer in flight ends it with `Cancelled`.
     */
    private class JitterService(private val storage: DownloaderStorage, private val payloads: Map<String, ByteArray>) :
        BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 4096)
        private val started: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val cancelRequested: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun enqueueDownload(unit: DownloadUnit) {
            if (!started.add(unit.id)) return
            cancelRequested.remove(unit.id)
            try {
                storage.beginTempFile(unit, null)
                val file = File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
                val bytes: ByteArray = payloads.getValue(unit.id)
                file.outputStream().use { out ->
                    for (chunk in bytes.toList().chunked(maxOf(1, bytes.size / 4))) {
                        out.write(chunk.toByteArray())
                        if (ThreadLocalRandom.current().nextInt(3) == 0) Thread.sleep(1)
                    }
                }
                if (cancelRequested.remove(unit.id)) {
                    started.remove(unit.id)
                    events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
                    return
                }
                storage.markTempFileComplete(unit)
                started.remove(unit.id)
                events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
            } catch (e: Exception) {
                started.remove(unit.id)
                events.tryEmit(BackgroundDownloadEvent.Error(unit, "io: ${e.message}"))
            }
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in started
        override fun cancelDownload(unit: DownloadUnit) {
            if (unit.id in started) cancelRequested.add(unit.id)
        }

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

    private fun backdate(file: File) {
        check(file.setLastModified(System.currentTimeMillis() - 3_600_000L)) { "could not backdate $file" }
    }

    /** The real file operations of the Android storage, as the marker logic sees them. */
    private class RealFiles : LegacyTempMigrations.Files {
        override fun exists(path: String): Boolean = File(path).exists()
        override fun renameNoReplace(from: String, to: String): Boolean =
            io.github.jamal_wia.kmptoolkit.downloader.renameNoReplace(from, to)

        override fun delete(path: String) {
            File(path).delete()
        }

        override fun createParentDirectory(path: String) {
            File(path).parentFile?.mkdirs()
        }

        override fun lastModifiedMillis(path: String): Long? = File(path).lastModified().takeIf { it > 0L }
        override fun hasEntriesOtherThan(directory: String, name: String): Boolean =
            File(directory).list().orEmpty().any { it != name }

        override fun readText(path: String): String? = try {
            File(path).readText()
        } catch (e: IOException) {
            null
        }

        override fun writeNew(path: String, text: String): Boolean {
            val target = File(path)
            val temporary = File(target.parentFile, "${target.name}.${Random.nextLong().toULong()}.tmp")
            return try {
                temporary.writeText(text)
                io.github.jamal_wia.kmptoolkit.downloader.renameNoReplace(temporary.path, target.path)
            } catch (e: IOException) {
                false
            } finally {
                temporary.delete()
            }
        }
    }

    private fun joinAll(threads: List<Thread>, millis: Long, what: String) {
        val deadline: Long = System.currentTimeMillis() + millis
        threads.forEach { it.join(maxOf(1L, deadline - System.currentTimeMillis())) }
        val alive: List<Thread> = threads.filter { it.isAlive }
        assertTrue(alive.isEmpty(), "$what: ${alive.size} thread(s) still blocked after $millis ms: ${alive.map { it.name }}")
    }

    private fun describe(cutoff: LegacyTempMigrations.Cutoff): String = when (cutoff) {
        LegacyTempMigrations.Cutoff.None -> "None"
        is LegacyTempMigrations.Cutoff.Before -> "Before(${cutoff.millis})"
    }

    @Test
    fun `eight units asked cancelled and asked again on sixteen threads with resources deleted underneath end whole and every caller returns`() {
        val payload: ByteArray = ByteArray(6 * 1024) { (it % 233).toByte() }
        repeat(8) { round: Int ->
            root.deleteRecursively()
            LegacyTempMigrations.resetForTests()
            val group = Group("catalogue")
            val units: List<DownloadUnit> = (0 until 8).map { index: Int ->
                if (index % 4 == 3) HashedUnit("deep/unit$index", group) else FileUnit("unit$index", group)
            }
            group.units = units
            val payloads: Map<String, ByteArray> = units.associate { it.id to payload }
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val storageB: DownloaderStorage = createDownloaderStorage(context, config)
            val service = JitterService(storageA, payloads)
            val engines: List<Downloader> = listOf(engine(storageA, service, group), engine(storageB, service, group))
            val go = CountDownLatch(1)
            val violations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
            val askers: List<Thread> = units.mapIndexed { index: Int, unit: DownloadUnit ->
                thread(name = "asker-$index") {
                    go.await()
                    repeat(12) { iteration: Int ->
                        val engine: Downloader = engines[(index + iteration) % 2]
                        val outcome: Result<Unit> = runCatching {
                            runBlocking { withTimeoutOrNull(20_000) { engine.ensureAvailable(unit) } ?: error("an asker timed out (a hang)") }
                        }
                        val e: Throwable? = outcome.exceptionOrNull()
                        if (e != null && e !is DownloadCancelledException && e !is CancellationException && e !is DownloadFailedException) {
                            violations += "unit$index: $e"
                        }
                        if (iteration % 3 == 2) engine.cancelDownload(unit)
                    }
                }
            }
            val chaos: List<Thread> = (0 until 8).map { index: Int ->
                thread(name = "chaos-$index") {
                    go.await()
                    repeat(30) {
                        val unit: DownloadUnit = units[ThreadLocalRandom.current().nextInt(units.size)]
                        val engine: Downloader = engines[ThreadLocalRandom.current().nextInt(2)]
                        when (ThreadLocalRandom.current().nextInt(5)) {
                            0 -> engine.cancelDownload(unit)
                            1 -> engine.cancelDownload(group)
                            2 -> try { storageB.deleteResource(unit) } catch (e: Exception) { violations += "deleteResource: $e" }
                            3 -> runBlocking { engine.cancelAllDownloads() }
                            else -> engine.retryDownload(group)
                        }
                        Thread.sleep(ThreadLocalRandom.current().nextLong(0, 3))
                    }
                }
            }
            go.countDown()
            joinAll(askers + chaos, 90_000, "round $round")
            assertEquals(emptyList(), violations.take(5), "round $round: unexpected failures")

            // Quiesce what the chaos left running (a retry on an engine scope cleans up asynchronously), then one clean request per unit.
            runBlocking { engines.forEach { it.cancelAllDownloads() } }
            Thread.sleep(200)
            for (unit in units) {
                try {
                    runBlocking {
                        withTimeoutOrNull(20_000) { engines[0].ensureAvailable(unit) } ?: error("round $round: the final request of ${unit.id} hung")
                    }
                } catch (e: Throwable) {
                    val alive: List<String> = Thread.getAllStackTraces().keys.filter { it.isAlive && (it.name.startsWith("DefaultDispatcher")) }.map { it.name + ":" + it.state }
                    throw AssertionError("round $round: final request of ${unit.id} failed: ${e.stackTraceToString().take(1800)} | dispatcher threads: $alive", e)
                }
                assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes(), "round $round: ${unit.id}")
            }
            // What a cancelled retry left running on an engine scope finishes its cleanup before the next round reuses the directory.
            runBlocking { engines.forEach { it.cancelAllDownloads() } }
            Thread.sleep(200)
            // Nothing runs any more, so no engine may still claim a download: a state stuck at Downloading is a lost cancel.
            for ((engineIndex: Int, engine: Downloader) in engines.withIndex()) {
                assertTrue(engine.downloadState(group).value !is GroupDownloadState.Downloading, "round $round: engine $engineIndex group state ${engine.downloadState(group).value}")
                for (unit in units) {
                    val state: UnitDownloadState = runBlocking { engine.unitDownloadStateFlow(unit).first() }
                    assertTrue(state !is UnitDownloadState.Downloading, "round $round: engine $engineIndex ${unit.id} state $state")
                }
                assertTrue(!runBlocking { engine.hasActiveUnitDownload() }, "round $round: engine $engineIndex still reports an active unit download")
            }
            assertEquals(emptyList(), File(root, UnitFileLayout.TRASH_DIRECTORY).list().orEmpty().toList(), "round $round: trash")
            assertEquals(emptyList(), File(root, "tmp").list().orEmpty().filter { it.startsWith("staging-") }, "round $round: staging")
        }
    }
}
