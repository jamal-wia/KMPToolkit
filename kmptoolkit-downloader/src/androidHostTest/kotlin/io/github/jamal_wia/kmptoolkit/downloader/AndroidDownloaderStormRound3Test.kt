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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Many callers on real threads against the real Android storage: the layout marker created by
 * several storages at once, a no-replace move racing a trash sweep, cancels racing requests of
 * the same and of different units, and two engines over one directory.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderStormRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "storm_round3")
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

    // -- The layout marker created by many storages at once -------------------------------------------

    @Test
    fun `twelve threads deciding the marker of a base with no 3_0_0 file all get None and the marker says fresh`() {
        val base: String = root.absolutePath
        repeat(150) { round: Int ->
            root.deleteRecursively()
            LegacyTempMigrations.resetForTests()
            val go = CountDownLatch(1)
            val results: Array<AtomicReference<LegacyTempMigrations.Cutoff?>> = Array(12) { AtomicReference(null) }
            val threads: List<Thread> = (0 until 12).map { index: Int ->
                thread(name = "marker-$index") {
                    go.await()
                    val cutoff: LegacyTempMigrations.Cutoff =
                        LegacyTempMigrations.ensureLayout(base, RealFiles(), NoopLogger)
                    results[index].set(cutoff)
                    // What this version writes straight after its layout is decided.
                    if (index % 3 == 0) {
                        File(root, "tmp/new$index.tmp").apply { parentFile!!.mkdirs() }.writeText("x")
                    }
                }
            }
            go.countDown()
            joinAll(threads, 20_000, "round $round")
            val seen: Set<String> = results.map { describe(it.get() ?: error("no result")) }.toSet()
            assertEquals(setOf("None"), seen, "round $round: threads disagree or a fresh base was judged upgraded")
            assertEquals("fresh", File(root, UnitFileLayout.LEGACY_LAYOUT_MARKER).readText().trim(), "round $round")
            val leftovers: List<String> = File(root, UnitFileLayout.INTERNAL_DIRECTORY).list().orEmpty().toList()
            assertEquals(listOf("legacy-layout"), leftovers, "round $round: marker temp files left")
        }
    }

    @Test
    fun `twelve threads deciding the marker of a base holding a 3_0_0 file all get the same cutoff and the marker says upgraded`() {
        val base: String = root.absolutePath
        repeat(150) { round: Int ->
            root.deleteRecursively()
            LegacyTempMigrations.resetForTests()
            File(root, "tmp/m/v1.tmp").apply { parentFile!!.mkdirs() }.writeText("old")
            backdate(File(root, "tmp/m/v1.tmp"))
            val go = CountDownLatch(1)
            val results: Array<AtomicReference<LegacyTempMigrations.Cutoff?>> = Array(12) { AtomicReference(null) }
            val threads: List<Thread> = (0 until 12).map { index: Int ->
                thread(name = "marker-$index") {
                    go.await()
                    results[index].set(LegacyTempMigrations.ensureLayout(base, RealFiles(), NoopLogger))
                    if (index % 3 == 0) {
                        File(root, "tmp/new$index.tmp").apply { parentFile!!.mkdirs() }.writeText("x")
                    }
                }
            }
            go.countDown()
            joinAll(threads, 20_000, "round $round")
            val seen: Set<String> = results.map { describe(it.get() ?: error("no result")) }.toSet()
            assertEquals(1, seen.size, "round $round: threads disagree: $seen")
            assertTrue(seen.single().startsWith("Before("), "round $round: an upgraded base was judged $seen")
            assertEquals("upgraded", File(root, UnitFileLayout.LEGACY_LAYOUT_MARKER).readText().trim(), "round $round")
        }
    }

    // -- Hashed unit: migration racing deletes through four storages -------------------------------------

    @Test
    fun `a legacy partial of a hashed unit read and deleted by ten threads over four storages leaves nothing behind`() {
        val group = Group("g")
        val unit = HashedUnit("m/v1", group)
        repeat(60) { round: Int ->
            root.deleteRecursively()
            LegacyTempMigrations.resetForTests()
            File(root, "tmp/seed.tmp").apply { parentFile!!.mkdirs() }.writeText("old")
            backdate(File(root, "tmp/seed.tmp"))
            listOf("tmp/m/v1.tmp", "tmp/m/v1.tmp.complete", "tmp/expect/m/v1.sha256", "tmp/expect-staged/m/v1.sha256").forEach {
                File(root, it).apply { parentFile!!.mkdirs() }.writeText("legacy-bytes")
                backdate(File(root, it))
            }
            val storages: List<DownloaderStorage> = List(4) { AndroidDownloaderStorage(context, config, NoopLogger) }
            val go = CountDownLatch(1)
            val failure = AtomicReference<Throwable?>(null)
            val threads: List<Thread> = (0 until 10).map { index: Int ->
                thread(name = "hashed-$index") {
                    go.await()
                    try {
                        val storage: DownloaderStorage = storages[index % 4]
                        repeat(10) {
                            when (ThreadLocalRandom.current().nextInt(4)) {
                                0 -> storage.tempFileState(unit)
                                1 -> storage.getTempFileSize(unit)
                                2 -> storage.getTempFilePath(unit)
                                else -> storage.deleteTempFile(unit)
                            }
                        }
                        storage.deleteTempFile(unit)
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
            go.countDown()
            joinAll(threads, 20_000, "round $round")
            assertEquals(null, failure.get()?.stackTraceToString()?.take(2500), "round $round: an operation threw")
            // Every thread ended with a delete, so whatever the interleaving, nothing of the unit stays.
            val left: List<String> = root.walkTopDown().filter { it.isFile && it.name != "legacy-layout" && it.name != "seed.tmp" }
                .map { TestPaths.relativeLogical(root.path, it.path)!! }.sorted().toList()
            assertEquals(emptyList(), left, "round $round: files left behind")
            assertEquals(TempFileState.None, storages[0].tempFileState(unit), "round $round")
        }
    }

    // -- The no-replace move racing a trash sweep in one directory --------------------------------------

    @Test
    fun `migrations of 3_0_0 partials keep working while four threads detach and sweep staging trees beside them`() {
        val group = Group("g")
        val seedDir = File(root, "tmp")
        seedDir.mkdirs()
        File(seedDir, "seed.tmp").writeText("old")
        backdate(File(seedDir, "seed.tmp"))
        AndroidDownloaderStorage(context, config, NoopLogger).getTempFileSize(FileUnit("warm", group))
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val failure = AtomicReference<Throwable?>(null)
        val sweepers: List<Thread> = (0 until 4).map { index: Int ->
            thread(name = "sweeper-$index") {
                try {
                    val storage: DownloaderStorage = AndroidDownloaderStorage(context, config, NoopLogger)
                    val unit = ArchiveUnit("arch$index", group)
                    var i = 0
                    while (!stop.get() && i < 400) {
                        File(root, "tmp/staging-arch$index/a/b").apply { mkdirs() }
                        File(root, "tmp/staging-arch$index/a/b/f.bin").writeText("half")
                        storage.deleteTempFile(unit)
                        i++
                    }
                } catch (t: AssertionError) {
                    // Kotlin's file walker asserts when a tree vanishes under it; reported as its own suspect.
                } catch (t: Throwable) {
                    failure.compareAndSet(null, t)
                }
            }
        }
        val migrator = thread(name = "migrator") {
            try {
                val storage: DownloaderStorage = AndroidDownloaderStorage(context, config, NoopLogger)
                repeat(300) { i: Int ->
                    val unit = HashedUnit("h/$i", group)
                    val legacy = File(root, "tmp/h/$i.tmp").apply { parentFile!!.mkdirs() }
                    legacy.writeBytes(ByteArray(10 + i))
                    backdate(legacy)
                    val size: Long = storage.getTempFileSize(unit)
                    assertEquals((10 + i).toLong(), size, "hashed unit h/$i: the 3.0.0 partial was lost while trash was swept")
                }
            } catch (t: Throwable) {
                failure.compareAndSet(null, t)
            }
        }
        migrator.join(60_000)
        stop.set(true)
        joinAll(sweepers, 30_000, "sweepers")
        assertTrue(!migrator.isAlive, "the migrator is blocked")
        assertEquals(null, failure.get()?.stackTraceToString()?.take(2500))
        assertEquals("upgraded".length, File(root, UnitFileLayout.LEGACY_LAYOUT_MARKER).readText().trim().length)
    }

    // -- Two engines, a group and a unit surface, cancels from three threads ---------------------------------

    @Test
    fun `two engines over one directory under a storm of requests and cancels end whole with every caller returned`() {
        val filePayload: ByteArray = ByteArray(8 * 1024) { (it % 239).toByte() }
        val archivePayload: ByteArray = zip("marker.txt" to "ready", "page1.webp" to "bytes-1", "page2.webp" to "bytes-2")
        repeat(25) { round: Int ->
            root.deleteRecursively()
            LegacyTempMigrations.resetForTests()
            val group = Group("catalogue")
            val f1 = FileUnit("f1", group)
            val f2 = FileUnit("f2", group)
            val a1 = ArchiveUnit("a1", group)
            val h1 = HashedUnit("deep/h1", group)
            group.units = listOf(f1, a1, f2, h1)
            val payloads: Map<String, ByteArray> =
                mapOf("f1" to filePayload, "f2" to filePayload, "a1" to archivePayload, "deep/h1" to filePayload)
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val storageB: DownloaderStorage = createDownloaderStorage(context, config)
            val service = JitterService(storageA, payloads)
            val engines: List<Downloader> = listOf(engine(storageA, service, group), engine(storageB, service, group))
            val go = CountDownLatch(1)
            val violations: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
            fun record(who: String, outcome: Result<Unit>) {
                val e: Throwable = outcome.exceptionOrNull() ?: return
                // A cancel of one engine deletes the temp files a transfer of the other engine is writing, and
                // every caller attached to that transfer sees its failure: a download failure is fair game.
                if (e is DownloadCancelledException || e is CancellationException || e is DownloadFailedException) return
                violations += "$who: $e"
            }
            val callers: List<Thread> = (0 until 6).map { index: Int ->
                thread(name = "caller-$index") {
                    go.await()
                    val engine: Downloader = engines[index % 2]
                    repeat(3) {
                        val outcome: Result<Unit> = runCatching {
                            runBlocking {
                                withTimeoutOrNull(20_000) {
                                    when (index % 3) {
                                        0 -> engine.ensureAvailable(group)
                                        1 -> engine.ensureAvailable(f1)
                                        else -> engine.ensureAvailable(a1)
                                    }
                                } ?: error("a caller timed out (a hang)")
                            }
                        }
                        record(if (index % 2 == 0) "caller-$index" else "caller-$index-odd", outcome)
                    }
                }
            }
            val cancellers: List<Thread> = (0 until 3).map { index: Int ->
                thread(name = "canceller-$index") {
                    go.await()
                    repeat(12) {
                        val engine: Downloader = engines[0]
                        when (ThreadLocalRandom.current().nextInt(4)) {
                            0 -> engine.cancelDownload(group)
                            1 -> engine.cancelDownload(f1)
                            2 -> engine.cancelDownload(a1)
                            else -> runBlocking { engine.cancelAllDownloads() }
                        }
                        Thread.sleep(ThreadLocalRandom.current().nextLong(0, 4))
                    }
                }
            }
            go.countDown()
            joinAll(callers + cancellers, 60_000, "round $round")
            assertEquals(emptyList(), violations.take(5), "round $round: a caller failed for another reason than a cancel")

            // Liveness and wholeness after the storm: one clean request completes everything.
            runBlocking {
                withTimeoutOrNull(20_000) { engines[0].ensureAvailable(group) } ?: error("round $round: the final request hung")
            }
            for (unit in listOf(f1, f2, h1)) {
                assertContentEquals(filePayload, File(storageA.getResourcePath(unit)).readBytes(), "round $round: ${unit.id}")
            }
            val archive = File(storageA.getResourcePath(a1))
            assertEquals(listOf("marker.txt", "page1.webp", "page2.webp"), archive.list().orEmpty().sorted(), "round $round: archive")
            // Nothing runs any more: no engine may still claim a download (a state stuck at Downloading is a lost cancel).
            for ((engineIndex: Int, engine: Downloader) in engines.withIndex()) {
                assertTrue(engine.downloadState(group).value !is GroupDownloadState.Downloading, "round $round: engine $engineIndex group state ${engine.downloadState(group).value}")
                for (unit in listOf(f1, f2, a1, h1)) {
                    val state: UnitDownloadState = runBlocking { engine.unitDownloadStateFlow(unit).first() }
                    assertTrue(state !is UnitDownloadState.Downloading, "round $round: engine $engineIndex ${unit.id} state $state")
                }
            }
            val staging: List<String> = File(root, "tmp").list().orEmpty().filter { it.startsWith("staging-") }
            assertEquals(emptyList(), staging, "round $round: staging trees left")
            val trash: List<String> = File(root, UnitFileLayout.TRASH_DIRECTORY).list().orEmpty().toList()
            assertEquals(emptyList(), trash, "round $round: trash left")
            for (unit in listOf(f1, f2, a1, h1)) {
                assertEquals(TempFileState.None, storageB.tempFileState(unit), "round $round: temp state of ${unit.id}")
            }
        }
    }
}
