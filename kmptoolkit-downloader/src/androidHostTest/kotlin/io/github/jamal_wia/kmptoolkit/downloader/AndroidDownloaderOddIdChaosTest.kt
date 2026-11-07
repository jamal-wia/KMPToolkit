package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
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
 * The engine over the real Android storage for ids a file system cannot hold as they are (slashes,
 * NUL, 300 characters, names that look like the storage's own directories), through request,
 * delete and re-request, two engines over one directory, and a restarted process.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderOddIdChaosTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "odd_id_chaos")
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

    private class OddUnit(
        override val id: String,
        index: Int,
        override val group: ResourceGroup,
        override val tempExtension: String = "tmp",
    ) : DownloadUnit {
        override val apiPath: String = "test/$index"
        override val relativePath: String = "files/odd-$index.bin"
        override fun toString(): String = "OddUnit(${id.take(20)})"
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

    private class SharedService(private val storage: DownloaderStorage, private val bytes: ByteArray) :
        BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 4096)
        private val started: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            if (!started.add(unit.id)) return
            // A conforming port: it begins the temp file whenever it starts from byte zero.
            storage.beginTempFile(unit, null)
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

    private fun engine(storage: DownloaderStorage, port: BackgroundResourceDownloader, group: ResourceGroup): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    private val oddIds: List<String> = listOf(
        "models/v1",
        "../escape",
        "a\u0000b",
        "x".repeat(300),
        "y".repeat(248),
        "staging-foo/bar",
        "expect/foo",
        "trash/x",
        "kmptoolkit-internal/q",
        "/leading",
        "trailing/",
        "a//b",
        "",
        ".",
        "..",
        "tmp",
        "expect",
        "kmptoolkit-internal",
        "staging-foo",
        "emoji-😀-€-é",
    )

    private fun filesUnder(directory: File): List<String> =
        directory.walkTopDown().filter { it.isFile }.map { it.relativeTo(directory).path }.sorted().toList()

    private fun outsideRoot(): List<String> =
        context.filesDir.walkTopDown().filter { it.isFile && !it.startsWith(root) }.map { it.path }.toList()

    @Test
    fun `every odd id downloads commits and deletes through the engine, in one directory, repeatedly`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val group = Group("odd")
        val units: List<DownloadUnit> = oddIds.mapIndexed { index: Int, id: String -> OddUnit(id, index, group) }
        group.units = units
        val outsideBefore: List<String> = outsideRoot()
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val engine: Downloader = engine(storage, SharedService(storage, payload), group)

        repeat(3) { round: Int ->
            runBlocking {
                withTimeoutOrNull(30_000) { engine.ensureAvailable(group) } ?: error("round $round: never returned")
            }
            units.forEach { unit: DownloadUnit ->
                assertContentEquals(payload, File(storage.getResourcePath(unit)).readBytes(), "round $round $unit")
            }
            assertEquals(emptyList(), filesUnder(File(root, "tmp")), "round $round: temp area is clean")
            units.forEach { unit: DownloadUnit -> storage.deleteResource(unit) }
            assertTrue(units.none { engine.isAvailable(it) }, "round $round")
        }
        assertEquals(outsideBefore, outsideRoot(), "nothing was written outside the base directory")
    }

    @Test
    fun `cancel then an immediate request of an odd id leaves no trash and completes`() {
        val payload = ByteArray(512) { 1 }
        val group = Group("odd")
        val units: List<DownloadUnit> = oddIds.mapIndexed { index: Int, id: String -> OddUnit(id, index, group) }
        group.units = units
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        val engine: Downloader = engine(storage, SharedService(storage, payload), group)

        units.forEach { unit: DownloadUnit ->
            engine.cancelDownload(unit)
            runBlocking {
                withTimeoutOrNull(10_000) { engine.ensureAvailable(unit) } ?: error("$unit: never returned")
            }
            engine.cancelDownload(unit)
            assertTrue(engine.isAvailable(unit), "$unit")
        }
        assertEquals(emptyList(), filesUnder(File(root, "tmp")))
    }

    @Test
    fun `a restarted process with all odd ids half transferred resumes every one under its own name`() {
        val group = Group("odd")
        val units: List<DownloadUnit> = oddIds.mapIndexed { index: Int, id: String -> OddUnit(id, index, group) }
        group.units = units
        val first: DownloaderStorage = createDownloaderStorage(context, config)
        units.forEachIndexed { index: Int, unit: DownloadUnit ->
            first.beginTempFile(unit, null)
            File(first.getTempFilePath(unit)).writeBytes(ByteArray(index + 1) { index.toByte() })
        }

        LegacyTempMigrations.resetForTests()
        val second: DownloaderStorage = createDownloaderStorage(context, config)
        units.forEachIndexed { index: Int, unit: DownloadUnit ->
            assertEquals(TempFileState.Partial, second.tempFileState(unit), "$unit")
            assertEquals((index + 1).toLong(), second.getTempFileSize(unit), "$unit: another unit's bytes")
        }
        // Dropping one unit's temp file touches only that unit.
        second.deleteTempFile(units[2])
        units.forEachIndexed { index: Int, unit: DownloadUnit ->
            val expected: TempFileState = if (index == 2) TempFileState.None else TempFileState.Partial
            assertEquals(expected, second.tempFileState(unit), "$unit after dropping #2")
        }
    }

    @Test
    fun `two engines over one directory racing eight callers per odd id each end clean`() {
        val payload = ByteArray(16 * 1024) { (it % 239).toByte() }
        repeat(5) { round: Int ->
            root.deleteRecursively()
            val group = Group("odd")
            val units: List<DownloadUnit> = oddIds.take(9).mapIndexed { index: Int, id: String -> OddUnit(id, index, group) }
            group.units = units
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val storageB: DownloaderStorage = createDownloaderStorage(context, config)
            val service = SharedService(storageA, payload)
            val engineA: Downloader = engine(storageA, service, group)
            val engineB: Downloader = engine(storageB, service, group)
            val outcomes: List<Result<Unit>>? = runBlocking {
                withContext(Dispatchers.Default) {
                    val go = CompletableDeferred<Unit>()
                    val callers: List<Deferred<Result<Unit>>> = units.flatMap { unit: DownloadUnit ->
                        List(8) { caller: Int ->
                            async {
                                go.await()
                                runCatching { (if (caller % 2 == 0) engineA else engineB).ensureAvailable(unit) }
                            }
                        }
                    }
                    go.complete(Unit)
                    withTimeoutOrNull(30_000) { callers.awaitAll() }
                }
            }
            assertTrue(outcomes != null, "round $round: a caller never returned")
            outcomes.forEachIndexed { i: Int, r: Result<Unit> -> assertTrue(r.isSuccess, "round $round caller $i: ${r.exceptionOrNull()}") }
            units.forEach { unit: DownloadUnit ->
                assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes(), "round $round $unit")
            }
            assertEquals(emptyList(), filesUnder(File(root, "tmp")), "round $round: no temp file or record is left")
            assertFalse(units.any { storageB.tempFileState(it) != TempFileState.None }, "round $round")
        }
    }
}
