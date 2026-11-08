package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith

/** What a caller sees the instant cancelAllDownloads returns, with a request racing it. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderCancelAllRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "cancel_all_round3")
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

    /** A port that accepts a transfer and never finishes it. [inProgress] follows the port contract. */
    private class HangingPort : BackgroundResourceDownloader {
        val inProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val enqueues: AtomicInteger = AtomicInteger()
        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            inProgress.add(unit.id)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in inProgress
        override fun cancelDownload(unit: DownloadUnit) {
            inProgress.remove(unit.id)
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            MutableSharedFlow<BackgroundDownloadEvent>()
    }

    @Test
    fun `right after cancelAllDownloads returns no unit shows Downloading and the port holds no transfer, 3000 races`() {
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val port = HangingPort()
        val engine: Downloader = createDownloader(
            storage = createDownloaderStorage(context, config),
            backgroundDownloader = port,
            groups = listOf(group),
            stateStore = MapStateStore(),
            dispatchers = DownloadDispatchers.Default,
            logger = NoopLogger,
        )
        var activeAfterCancel = 0
        var portBusyAfterCancel = 0
        var reached = 0
        repeat(3000) { round: Int ->
            val caller = thread(name = "caller") {
                runCatching { runBlocking { engine.ensureAvailable(unit) } }
            }
            // A few microseconds, so the cancel lands at different points of the request.
            val until: Long = System.nanoTime() + (round % 60) * 1_000L
            while (System.nanoTime() < until) Thread.onSpinWait()
            runBlocking { engine.cancelAllDownloads() }
            val active: Boolean = runBlocking { engine.hasActiveUnitDownload() }
            val portBusy: Boolean = unit.id in port.inProgress
            // A request the cancel reached ends at once. One that only started after the cancel is a new
            // request and keeps waiting: its state says nothing about the cancel, so it is not counted.
            caller.join(300)
            if (!caller.isAlive) {
                reached++
                if (active) activeAfterCancel++
                if (portBusy) portBusyAfterCancel++
            } else {
                val deadline: Long = System.currentTimeMillis() + 10_000
                while (caller.isAlive && System.currentTimeMillis() < deadline) {
                    runBlocking { engine.cancelAllDownloads() }
                    caller.join(5)
                }
                assertTrue(!caller.isAlive, "round $round: a request never ended after repeated cancels")
            }
        }
        assertTrue(reached > 100, "only $reached requests were reached by the cancel: the race is not exercised")
        assertEquals(
            0 to 0,
            activeAfterCancel to portBusyAfterCancel,
            "of $reached races: hasActiveUnitDownload true after the cancel in $activeAfterCancel, port still holding the transfer in $portBusyAfterCancel",
        )
    }

    /** A port whose enqueueDownload parks inside the call until [release] is counted down. */
    private class BlockingEnqueuePort : BackgroundResourceDownloader {
        val inProgress: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        override fun enqueueDownload(unit: DownloadUnit) {
            entered.countDown()
            release.await(10, TimeUnit.SECONDS)
            inProgress.add(unit.id)
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in inProgress
        override fun cancelDownload(unit: DownloadUnit) {
            inProgress.remove(unit.id)
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            MutableSharedFlow<BackgroundDownloadEvent>()
    }

    private fun engineOver(port: BackgroundResourceDownloader, group: Group): Downloader = createDownloader(
        storage = createDownloaderStorage(context, config),
        backgroundDownloader = port,
        groups = listOf(group),
        stateStore = MapStateStore(),
        dispatchers = DownloadDispatchers.Default,
        logger = NoopLogger,
    )

    private fun startingRequestIsAwaitedByCancelAll(
        request: suspend (Downloader, Group, DownloadUnit) -> Unit,
        assertIdle: suspend (Downloader, Group, DownloadUnit) -> Unit,
    ) {
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val port = BlockingEnqueuePort()
        val engine: Downloader = engineOver(port, group)
        val outcome = AtomicReference<Throwable?>(null)
        val caller = thread(name = "caller") {
            outcome.set(runCatching { runBlocking { request(engine, group, unit) } }.exceptionOrNull())
        }
        assertTrue(port.entered.await(10, TimeUnit.SECONDS), "the request never reached the port")
        val canceller = thread(name = "canceller") { runBlocking { engine.cancelAllDownloads() } }
        canceller.join(300)
        val returnedEarly: Boolean = !canceller.isAlive
        port.release.countDown()
        canceller.join(10_000)
        caller.join(10_000)
        assertFalse(returnedEarly, "cancelAllDownloads returned while a request was still starting its transfer")
        assertTrue(!canceller.isAlive && !caller.isAlive, "a thread is stuck")
        runBlocking { assertIdle(engine, group, unit) }
        assertTrue(port.inProgress.isEmpty(), "the port still holds the cancelled transfer: ${port.inProgress}")
        assertTrue(outcome.get() is DownloadCancelledException, "the request ended as ${outcome.get()}")
    }

    @Test
    fun `cancelAllDownloads waits for a unit request that is inside enqueueDownload and leaves nothing behind`() {
        startingRequestIsAwaitedByCancelAll(
            request = { engine, _, unit -> engine.ensureAvailable(unit) },
            assertIdle = { engine, _, _ -> assertFalse(engine.hasActiveUnitDownload()) },
        )
    }

    @Test
    fun `cancelAllDownloads waits for a group request that is inside enqueueDownload and leaves the group Idle`() {
        startingRequestIsAwaitedByCancelAll(
            request = { engine, group, _ -> engine.ensureAvailable(group) },
            assertIdle = { engine, group, _ ->
                assertEquals(GroupDownloadState.Idle, engine.downloadState(group).first())
            },
        )
    }

    @Test
    fun `a request made after cancelAllDownloads returned is neither waited for nor cancelled`() {
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val port = BlockingEnqueuePort().also { it.release.countDown() }
        val engine: Downloader = engineOver(port, group)
        runBlocking { engine.cancelAllDownloads() }
        val caller = thread(name = "caller") { runCatching { runBlocking { engine.ensureAvailable(unit) } } }
        assertTrue(port.entered.await(10, TimeUnit.SECONDS), "the new request never started its transfer")
        assertTrue(runBlocking { engine.hasActiveUnitDownload() }, "the new request must be running, not cancelled")
        runBlocking { engine.cancelAllDownloads() }
        caller.join(10_000)
        assertFalse(caller.isAlive)
    }
}
