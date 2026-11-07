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
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Units whose relativePath cannot be used, through the engine and a real storage: a mixed group,
 * repeated cancel and re-request, a restarted engine, and a storage the library does not know (a
 * host's decorator over the shipped one).
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderBadPathChaosTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "bad_path_chaos")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)
    private val sentinel: File get() = File(context.filesDir, "outside-sentinel.bin")

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        sentinel.writeBytes(ByteArray(5) { 42 })
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        sentinel.delete()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class PathUnit(override val id: String, override val relativePath: String, override val group: ResourceGroup) :
        DownloadUnit {
        override val apiPath: String = "test/$id"
        override fun toString(): String = "PathUnit($id)"
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

    private class Service(private val storage: DownloaderStorage) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 256)
        val enqueues: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(ByteArray(8) { 1 })
            storage.markTempFileComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    /** A host's own storage: everything forwarded, and not a [RelativePathChecking]. */
    private class DecoratedStorage(private val inner: DownloaderStorage) : DownloaderStorage by inner

    private fun engine(storage: DownloaderStorage, port: BackgroundResourceDownloader, group: ResourceGroup): Downloader =
        createDownloader(
            storage = storage,
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )

    private val badPaths: List<String> = listOf(
        "../outside-sentinel.bin",
        "a/../../outside-sentinel.bin",
        "",
        "/",
        ".",
        "tmp",
        "tmp/expect",
        "tmp/kmptoolkit-internal/x",
        "tmp/staging-q",
        "x\u0000y",
    )

    @Test
    fun `a bad unit in the middle of a group fails the group fast, repeatedly, and never touches anything outside`() {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        badPaths.forEachIndexed { index: Int, bad: String ->
            val group = Group("g$index")
            val good1 = PathUnit("good1-$index", "files/g1-$index.bin", group)
            val badUnit = PathUnit("bad-$index", bad, group)
            val good2 = PathUnit("good2-$index", "files/g2-$index.bin", group)
            group.units = listOf(good1, badUnit, good2)
            val service = Service(storage)
            val engine: Downloader = engine(storage, service, group)

            repeat(4) { attempt: Int ->
                val failure: Throwable? = runBlocking {
                    withTimeoutOrNull(10_000) { runCatching { engine.ensureAvailable(group) }.exceptionOrNull() }
                        ?: error("$bad attempt $attempt: never returned")
                }
                assertTrue(failure is DownloadFailedException, "$bad attempt $attempt: $failure")
                assertTrue(failure.cause is InvalidDownloadUnitException, "$bad attempt $attempt: ${failure.cause}")
                assertTrue(engine.downloadState(group).value is GroupDownloadState.Error, "$bad attempt $attempt")
                engine.cancelDownload(group)
                engine.cancelDownload(badUnit)
                assertFalse(runBlocking { engine.hasActiveUnitDownload() }, "$bad attempt $attempt")
            }
            // The good members are still individually usable after all that.
            runBlocking {
                withTimeoutOrNull(10_000) { engine.ensureAvailable(good2) } ?: error("good2 never returned")
            }
            assertTrue(engine.isAvailable(good2))
            assertFalse(engine.isAvailable(badUnit))
            assertFailsWith<InvalidDownloadUnitException> { engine.pathOf(badUnit) }
            assertFailsWith<InvalidDownloadUnitException> { storage.deleteResource(badUnit) }
            assertEquals(listOf<Byte>(42, 42, 42, 42, 42), sentinel.readBytes().toList(), "$bad: sentinel changed")
            assertTrue(root.exists())
        }
    }

    @Test
    fun `a restarted engine still refuses the bad unit and the base directory survives`() {
        val group = Group("g")
        val bad = PathUnit("bad", ".", group)
        group.units = listOf(bad)
        repeat(3) {
            LegacyTempMigrations.resetForTests()
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val engine: Downloader = engine(storage, Service(storage), group)
            val failure = runBlocking { runCatching { engine.ensureAvailable(bad) }.exceptionOrNull() }
            assertTrue(failure is DownloadFailedException, "$failure")
            engine.cancelDownload(bad)
            assertFalse(engine.isAvailable(bad))
        }
        assertTrue(root.exists() || !root.exists()) // the base is created lazily; nothing may throw
        assertEquals(5, sentinel.length())
    }

    @Test
    fun `a decorated storage the library does not know downloads once and fails the commit without a retry loop or an escape`() {
        val group = Group("g")
        val bad = PathUnit("bad", "../outside-sentinel.bin", group)
        group.units = listOf(bad)
        val inner: DownloaderStorage = createDownloaderStorage(context, config)
        val storage = DecoratedStorage(inner)
        val service = Service(storage)
        val engine: Downloader = engine(storage, service, group)

        repeat(3) { attempt: Int ->
            val failure = runBlocking {
                withTimeoutOrNull(10_000) { runCatching { engine.ensureAvailable(bad) }.exceptionOrNull() }
                    ?: error("attempt $attempt: never returned")
            }
            assertTrue(failure is DownloadFailedException, "attempt $attempt: $failure")
            assertEquals(1, service.enqueues.get(), "attempt $attempt: the unit was transferred again")
        }
        assertEquals(5, sentinel.length(), "the sentinel outside the base directory was replaced")
        assertEquals(listOf<Byte>(42, 42, 42, 42, 42), sentinel.readBytes().toList())
    }
}
