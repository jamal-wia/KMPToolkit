package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
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
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Resuming 3.0.0 partial files of hashed units through three engines over one directory at once.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderResumeStormRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "resume_storm_round3")
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

    /** A service that resumes from whatever the storage names as the partial file, as a ranged request would. */
    private class ResumeService(private val storage: DownloaderStorage, private val payloads: Map<String, ByteArray>) :
        BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 4096)
        private val started: MutableSet<String> = ConcurrentHashMap.newKeySet()

        override fun enqueueDownload(unit: DownloadUnit) {
            if (!started.add(unit.id)) return
            try {
                val bytes: ByteArray = payloads.getValue(unit.id)
                // As the port guide asks: a complete file needs no transfer at all.
                if (storage.tempFileState(unit) == TempFileState.Complete) {
                    started.remove(unit.id)
                    events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
                    return
                }
                val have: Long = storage.getTempFileSize(unit)
                if (have == 0L) storage.beginTempFile(unit, null)
                val file = File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }
                if (have > 0L) {
                    file.appendBytes(bytes.copyOfRange(have.toInt(), bytes.size))
                } else {
                    file.writeBytes(bytes)
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
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    @Test
    fun `legacy partials of four hashed units resumed through three engines at once end whole with no legacy file left`() {
        val payload: ByteArray = ByteArray(10_000) { (it % 247).toByte() }
        repeat(40) { round: Int ->
            root.deleteRecursively()
            LegacyTempMigrations.resetForTests()
            val group = Group("catalogue")
            val units: List<DownloadUnit> = (0 until 4).map { HashedUnit("deep/part$it", group) }
            group.units = units
            // The 3.0.0 layout: one partial per unit and a record, all older than this version's first use.
            File(root, "tmp/seed.tmp").apply { parentFile!!.mkdirs() }.writeText("old")
            backdate(File(root, "tmp/seed.tmp"))
            for (unit in units) {
                val partial = File(root, "tmp/${unit.id}.tmp").apply { parentFile!!.mkdirs() }
                partial.writeBytes(payload.copyOfRange(0, 4_000 + round))
                backdate(partial)
            }
            val storages: List<DownloaderStorage> = List(3) { createDownloaderStorage(context, config) }
            val service = ResumeService(storages[0], units.associate { it.id to payload })
            val engines: List<Downloader> = storages.map { engine(it, service, group) }
            val go = CountDownLatch(1)
            val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
            val callers: List<Thread> = (0 until 12).map { index: Int ->
                thread(name = "resume-$index") {
                    go.await()
                    val unit: DownloadUnit = units[index % 4]
                    val outcome: Result<Unit> = runCatching {
                        // One engine per unit: two engines starting one unit would run two transfers over the same file.
                        runBlocking { withTimeoutOrNull(20_000) { engines[(index % 4) % 3].ensureAvailable(unit) } ?: error("timeout") }
                    }
                    outcome.exceptionOrNull()?.let { failures += "$index: $it" }
                }
            }
            go.countDown()
            joinAll(callers, 40_000, "round $round")
            assertEquals(emptyList(), failures.take(5), "round $round")
            for (unit in units) {
                assertContentEquals(payload, File(storages[0].getResourcePath(unit)).readBytes(), "round $round: ${unit.id}")
            }
            val left: List<String> = File(root, "tmp").walkTopDown().filter { it.isFile && it.name != "legacy-layout" && it.name != "seed.tmp" }
                .map { TestPaths.relativeLogical(root.path, it.path)!! }.sorted().toList()
            assertEquals(emptyList(), left, "round $round: temp files or records left")
        }
    }
}
