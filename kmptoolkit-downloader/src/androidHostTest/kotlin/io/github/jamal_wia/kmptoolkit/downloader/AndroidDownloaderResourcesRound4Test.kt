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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * What the process keeps after the work is done: the registry of per-unit commit locks, the
 * coroutines of the engine's own scope, and the entries of the engine's per-unit registry.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderResourcesRound4Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "resources_round4")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat = ResourceFormat.Opaque,
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
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

    /** Accepts every transfer and never reports anything: a connection that hangs. */
    private class SilentService : BackgroundResourceDownloader {
        val enqueues: AtomicInteger = AtomicInteger()
        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow { awaitCancellation() }
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

    @Suppress("UNCHECKED_CAST")
    private fun lockRegistrySize(): Int {
        val field = UnitCommitLocks::class.java.getDeclaredField("entries")
        field.isAccessible = true
        return ((field.get(null) as MutableStateFlow<Map<*, *>>).value).size
    }

    private fun engineScopeChildren(engine: Downloader): Int {
        val field = engine.javaClass.getDeclaredField("scope")
        field.isAccessible = true
        val scope = field.get(engine) as CoroutineScope
        return scope.coroutineContext[Job]!!.children.count()
    }

    @Test
    fun `the commit lock registry is empty after throwing actions and probes from many threads`() = runBlocking {
        withTimeout(60_000) {
            val keys: List<String> = listOf("a", "b", "c")
            (0 until 8).map { worker: Int ->
                launch(Dispatchers.Default) {
                    repeat(150) { round: Int ->
                        try {
                            UnitCommitLocks.withUnitLock("base", keys[(worker + round) % 3]) {
                                if (round % 7 == 0) error("boom")
                                if (round % 11 == 0) delay(1)
                            }
                        } catch (_: IllegalStateException) {
                        }
                        try {
                            UnitCommitLocks.tryWithUnitLock("base", keys[round % 3]) {
                                if (round % 13 == 0) error("boom")
                            }
                        } catch (_: IllegalStateException) {
                        }
                        try {
                            UnitCommitLocks.tryDetachThenDispose(
                                "base", keys[(round + 1) % 3],
                                detach = { if (round % 5 == 0) error("boom") else round },
                                dispose = { if (round % 3 == 0) error("boom") },
                            )
                        } catch (_: IllegalStateException) {
                        }
                    }
                }
            }.joinAll()
            assertEquals(0, lockRegistrySize())
        }
    }

    @Test
    fun `fifty commits cancelled while they wait for a held unit leave no registry entry behind`() = runBlocking {
        withTimeout(60_000) {
            val holding = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder: Job = launch(Dispatchers.Default) {
                UnitCommitLocks.withUnitLock("base", "k") {
                    holding.complete(Unit)
                    release.await()
                }
            }
            holding.await()
            assertFalse(UnitCommitLocks.tryWithUnitLock("base", "k") { }, "a held unit was claimed")
            val waiters: List<Job> = (0 until 50).map {
                launch(Dispatchers.Default) { UnitCommitLocks.withUnitLock("base", "k") { } }
            }
            delay(100)
            waiters.forEach { it.cancel() }
            waiters.joinAll()
            release.complete(Unit)
            holder.join()
            assertEquals(0, lockRegistrySize())
            assertTrue(UnitCommitLocks.tryWithUnitLock("base", "k") { }, "the unit is still reported as held")
        }
    }

    @Test
    fun `two hundred failing archive commits across five units leave the registry and the tree clean`() = runBlocking {
        withTimeout(120_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val units: List<TestUnit> = (0 until 5).map { index: Int ->
                TestUnit("arch$index", "archives/arch$index", group, ResourceFormat.ZipArchive("ok.txt"))
            }
            val corrupt: ByteArray = ByteArrayOutputStream().also { bytes ->
                ZipOutputStream(bytes).use { out ->
                    out.putNextEntry(ZipEntry("ok.txt"))
                    out.write(kotlin.random.Random(7).nextBytes(5_000))
                    out.closeEntry()
                }
            }.toByteArray().let { it.copyOf(it.size / 2) }
            units.map { unit: TestUnit ->
                launch(Dispatchers.Default) {
                    repeat(40) {
                        storage.beginTempFile(unit, null)
                        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(corrupt)
                        storage.markTempFileComplete(unit)
                        runCatching { storage.commitResource(unit) }
                        storage.deleteTempFile(unit)
                    }
                }
            }.joinAll()
            assertEquals(0, lockRegistrySize())
            val files: List<String> = root.walkTopDown().filter { it.isFile }.map { TestPaths.relativeLogical(root.path, it.path)!! }.toList()
            assertEquals(listOf(UnitFileLayout.LEGACY_LAYOUT_MARKER), files)
        }
    }

    @Test
    fun `after cancelAllDownloads no coroutine of the engine scope is left, however many retries were launched`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("u1", "files/u1.bin", group)
            group.units = listOf(unit)
            val service = SilentService()
            val engine: Downloader = engine(storage, service, group)
            repeat(30) { engine.retryDownload(group) }
            delay(300)
            engine.cancelAllDownloads()
            var children: Int = engineScopeChildren(engine)
            var waited = 0
            while (children > 0 && waited < 100) {
                delay(50)
                waited++
                children = engineScopeChildren(engine)
            }
            assertEquals(0, children, "coroutines of the engine scope outlived cancelAllDownloads")
            assertFalse(engine.hasActiveUnitDownload())
            assertEquals(GroupDownloadState.Idle, engine.downloadState(group).first())
        }
    }

    @Test
    fun `a notification Retry tapped many times while the first one is still starting runs one download`() = runBlocking {
        withTimeout(60_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val unit = TestUnit("u2", "files/u2.bin", group)
            group.units = listOf(unit)
            val service = SilentService()
            val engine: Downloader = engine(storage, service, group)
            repeat(40) { engine.retryDownload(group) }
            delay(500)
            assertEquals(1, service.enqueues.get())
            engine.cancelAllDownloads()
            delay(300)
            assertEquals(1, service.enqueues.get(), "a queued retry started its own transfer after the cancel")
        }
    }
}
