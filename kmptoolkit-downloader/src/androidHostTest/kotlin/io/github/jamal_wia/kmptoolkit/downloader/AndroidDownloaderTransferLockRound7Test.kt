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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/**
 * Round 7 (concurrency storm + lifecycle) on the cross-engine transfer lock of 4.1.0: engines over
 * one directory that wait for each other, are cancelled while waiting, are abandoned while holding
 * the lock, and are cancelled from a third engine that never transferred anything.
 *
 * Every wait is bounded; the port's transfer runs on its own thread, as a platform service does.
 */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderTransferLockRound7Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "adversarial_lock7")
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

    /**
     * One platform download service shared by every engine. A transfer writes half of the bytes,
     * announces it, waits for [go] (bounded), then writes the rest. A transfer whose temp file
     * disappeared meanwhile is recorded in [lostTempFiles] and reported as an error, which is what a
     * real downloader that holds the file open would hit.
     */
    private class AsyncPort(
        private val storage: DownloaderStorage,
        private val bytes: ByteArray,
        gated: Boolean,
        private val honoursCancel: Boolean = false,
    ) : BackgroundResourceDownloader {
        private val aborted: AtomicBoolean = AtomicBoolean(false)
        private val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 4096)
        private val running: MutableSet<String> = ConcurrentHashMap.newKeySet()
        val partialWritten: CountDownLatch = CountDownLatch(1)
        val go: CountDownLatch = CountDownLatch(if (gated) 1 else 0)
        val enqueues: AtomicInteger = AtomicInteger()
        val lostTempFiles: AtomicInteger = AtomicInteger()
        val cancels: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            if (!running.add(unit.id)) return
            thread(isDaemon = true, name = "round7-transfer-${unit.id}") {
                try {
                    val file = File(storage.getTempFilePath(unit))
                    file.parentFile!!.mkdirs()
                    file.writeBytes(bytes.copyOf(bytes.size / 2))
                    partialWritten.countDown()
                    go.await(10, TimeUnit.SECONDS)
                    if (honoursCancel && aborted.get()) {
                        running.remove(unit.id)
                        events.tryEmit(BackgroundDownloadEvent.Cancelled(unit))
                        return@thread
                    }
                    if (!file.exists()) {
                        lostTempFiles.incrementAndGet()
                        running.remove(unit.id)
                        events.tryEmit(BackgroundDownloadEvent.Error(unit, "HTTP 500 temp file lost"))
                        return@thread
                    }
                    file.writeBytes(bytes)
                    storage.markTempFileComplete(unit)
                    running.remove(unit.id)
                    events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
                } catch (error: Throwable) {
                    running.remove(unit.id)
                    events.tryEmit(BackgroundDownloadEvent.Error(unit, "HTTP 500 $error"))
                }
            }
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = unit.id in running
        override fun cancelDownload(unit: DownloadUnit) {
            cancels.incrementAndGet()
            aborted.set(true)
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

    private fun filesUnder(directory: File): List<String> =
        directory.walkTopDown().filter { it.isFile && it.name != "legacy-layout" }
            .map { it.relativeTo(directory).path }.sorted().toList()

    private fun transferLockRegistrySize(): Int {
        val field = UnitTransferLocks::class.java.getDeclaredField("entries").apply { isAccessible = true }
        val flow = field.get(UnitTransferLocks) as kotlinx.coroutines.flow.StateFlow<*>
        return (flow.value as Map<*, *>).size
    }

    private val payload: ByteArray = ByteArray(48 * 1024) { index: Int -> (index % 241).toByte() }

    /** Engine B waits behind A's transfer; a third engine that never ran anything cancels the unit. */
    @Test
    fun `cancelDownload on an idle engine while another engine transfers the unit leaves no lost temp file and no failed caller`() =
        runBlocking {
            val group = Group("catalogue")
            val unit = FileUnit("model", group).also { group.units = listOf(it) }
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val port = AsyncPort(storageA, payload, gated = true)
            val engineA: Downloader = engine(storageA, port, group)
            val idle: Downloader = engine(createDownloaderStorage(context, config), port, group)

            val a: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineA.ensureAvailable(unit) } }
            assertTrue(withContext(Dispatchers.IO) { port.partialWritten.await(5, TimeUnit.SECONDS) }, "A never started")

            idle.cancelDownload(unit)
            val tempAfterCancel: TempFileState = storageA.tempFileState(unit)
            port.go.countDown()

            val outcome: Result<Unit> = withTimeoutOrNull(15_000) { a.await() } ?: error("A never returned")
            assertEquals(
                TempFileState.Partial,
                tempAfterCancel,
                "an engine that never transferred anything deleted the temp file engine A is writing",
            )
            assertEquals(0, port.lostTempFiles.get(), "the transfer lost its file")
            assertTrue(outcome.isSuccess, "engine A failed with ${outcome.exceptionOrNull()}")
            assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes())
            assertEquals(emptyList(), filesUnder(File(root, "tmp")))
        }

    /** B waits behind A's transfer, is cancelled with cancelDownload and its job: A's file must survive. */
    @Test
    fun `a waiter cancelled by cancelDownload and its scope leaves the holder's temp file alone`() = runBlocking {
        val group = Group("catalogue")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val storageA: DownloaderStorage = createDownloaderStorage(context, config)
        val port = AsyncPort(storageA, payload, gated = true)
        val engineA: Downloader = engine(storageA, port, group)
        val engineB: Downloader = engine(createDownloaderStorage(context, config), port, group)

        val a: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineA.ensureAvailable(unit) } }
        assertTrue(withContext(Dispatchers.IO) { port.partialWritten.await(5, TimeUnit.SECONDS) }, "A never started")
        val b: Job = launchWaiter(engineB, unit)
        delay(400)
        engineB.cancelDownload(unit)
        withTimeoutOrNull(5_000) { b.cancelAndJoin() } ?: error("the waiter never ended after its scope was cancelled")
        val tempAfterCancel: TempFileState = storageA.tempFileState(unit)
        port.go.countDown()

        val outcome: Result<Unit> = withTimeoutOrNull(15_000) { a.await() } ?: error("A never returned")
        assertEquals(
            TempFileState.Partial,
            tempAfterCancel,
            "the cancelled waiter deleted the temp file of the engine that holds the transfer lock",
        )
        assertEquals(0, port.lostTempFiles.get(), "the transfer lost its file")
        assertTrue(outcome.isSuccess, "engine A failed with ${outcome.exceptionOrNull()}")
        assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes())
    }

    private fun CoroutineScope.launchWaiter(engine: Downloader, unit: DownloadUnit): Job =
        launch(Dispatchers.Default) { engine.ensureAvailable(unit) }

    /**
     * The platform service honours a cancel: whoever cancels the unit (here an engine that never
     * transferred) stops A's transfer. A's call then ends with an exception or a result, never hangs,
     * and nothing is left behind; a fresh engine afterwards gets the file.
     */
    @Test
    fun `a cancel from another engine that the service honours ends the holder cleanly and the next engine recovers`() =
        runBlocking {
            val group = Group("catalogue")
            val unit = FileUnit("model", group).also { group.units = listOf(it) }
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val port = AsyncPort(storageA, payload, gated = true, honoursCancel = true)
            val engineA: Downloader = engine(storageA, port, group)
            val other: Downloader = engine(createDownloaderStorage(context, config), port, group)

            val a: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineA.ensureAvailable(unit) } }
            assertTrue(withContext(Dispatchers.IO) { port.partialWritten.await(5, TimeUnit.SECONDS) }, "A never started")
            other.cancelDownload(unit)
            port.go.countDown()

            withTimeoutOrNull(20_000) { a.await() } ?: error("engine A never returned after another engine cancelled the unit")
            assertEquals(0, transferLockRegistrySize(), "the registry kept an entry")
            val fresh: Downloader = engine(createDownloaderStorage(context, config), AsyncPort(storageA, payload, gated = false), group)
            withTimeoutOrNull(20_000) { fresh.ensureAvailable(unit) } ?: error("a fresh engine never got the unit")
            assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes())
            assertEquals(emptyList(), filesUnder(File(root, "tmp")))
        }

    /** A scope-cancelled waiter (no cancelDownload) never touches anything and gives its registry slot back. */
    @Test
    fun `a hundred waiters cancelled by their scopes leave neither a registry entry nor a stuck lock`() = runBlocking {
        val group = Group("catalogue")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val storageA: DownloaderStorage = createDownloaderStorage(context, config)
        val port = AsyncPort(storageA, payload, gated = true)
        val engineA: Downloader = engine(storageA, port, group)

        val a: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineA.ensureAvailable(unit) } }
        assertTrue(withContext(Dispatchers.IO) { port.partialWritten.await(5, TimeUnit.SECONDS) }, "A never started")
        val waiters: List<Job> = List(100) { index: Int ->
            val engine: Downloader = engine(createDownloaderStorage(context, config), port, group)
            if (index % 2 == 0) launchWaiter(engine, unit)
            else launch(Dispatchers.Default) { engine.ensureAvailable(group) }
        }
        delay(500)
        waiters.forEach { it.cancel() }
        withTimeoutOrNull(10_000) { waiters.forEach { it.join() } } ?: error("a waiter did not end after its scope was cancelled")
        assertEquals(TempFileState.Partial, storageA.tempFileState(unit), "a cancelled waiter touched the holder's file")

        port.go.countDown()
        val outcome: Result<Unit> = withTimeoutOrNull(15_000) { a.await() } ?: error("A never returned")
        assertTrue(outcome.isSuccess, "engine A failed with ${outcome.exceptionOrNull()}")
        assertEquals(0, transferLockRegistrySize(), "the transfer-lock registry kept entries")
    }

    /** The holder's scope dies mid-transfer without any cancelDownload; the next engine takes over and finishes. */
    @Test
    fun `an engine abandoned while it holds the lock hands over to the next engine`() = runBlocking {
        val group = Group("catalogue")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val storageA: DownloaderStorage = createDownloaderStorage(context, config)
        val port = AsyncPort(storageA, payload, gated = true)
        val engineA: Downloader = engine(storageA, port, group)
        val engineB: Downloader = engine(createDownloaderStorage(context, config), port, group)

        val a: Job = launchWaiter(engineA, unit)
        assertTrue(withContext(Dispatchers.IO) { port.partialWritten.await(5, TimeUnit.SECONDS) }, "A never started")
        val b: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineB.ensureAvailable(unit) } }
        delay(300)
        withTimeoutOrNull(5_000) { a.cancelAndJoin() } ?: error("A did not end after its scope was cancelled")
        port.go.countDown()

        val outcome: Result<Unit> = withTimeoutOrNull(15_000) { b.await() } ?: error("B never returned")
        assertTrue(outcome.isSuccess, "engine B failed with ${outcome.exceptionOrNull()}")
        assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes())
        assertEquals(emptyList(), filesUnder(File(root, "tmp")))
        assertEquals(0, transferLockRegistrySize())
    }

    /** cancelDownload on the waiting engine ends its call with DownloadCancelledException once A is done, and keeps A's resource. */
    @Test
    fun `cancelDownload on a waiting engine ends that call with a cancelled exception and keeps the committed resource`() =
        runBlocking {
            val group = Group("catalogue")
            val unit = FileUnit("model", group).also { group.units = listOf(it) }
            val storageA: DownloaderStorage = createDownloaderStorage(context, config)
            val port = AsyncPort(storageA, payload, gated = true)
            val engineA: Downloader = engine(storageA, port, group)
            val engineB: Downloader = engine(createDownloaderStorage(context, config), port, group)

            val a: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineA.ensureAvailable(unit) } }
            assertTrue(withContext(Dispatchers.IO) { port.partialWritten.await(5, TimeUnit.SECONDS) }, "A never started")
            val b: Deferred<Result<Unit>> = async(Dispatchers.Default) { runCatching { engineB.ensureAvailable(unit) } }
            delay(300)
            engineB.cancelDownload(unit)
            port.go.countDown()

            val aOutcome: Result<Unit> = withTimeoutOrNull(15_000) { a.await() } ?: error("A never returned")
            val bOutcome: Result<Unit> = withTimeoutOrNull(15_000) { b.await() } ?: error("B never returned")
            assertTrue(aOutcome.isSuccess, "engine A failed with ${aOutcome.exceptionOrNull()}")
            assertTrue(
                bOutcome.exceptionOrNull() is DownloadCancelledException,
                "B should end cancelled, was $bOutcome",
            )
            assertContentEquals(payload, File(storageA.getResourcePath(unit)).readBytes(), "A's resource must survive B's cleanup")
            assertTrue(engineB.isAvailable(group))
            assertEquals(emptyList(), filesUnder(File(root, "tmp")))
            assertEquals(0, transferLockRegistrySize())
        }

    /**
     * Storm: many engines, units and surfaces, with cancelDownload / cancelAllDownloads from engines
     * that are not the ones transferring. Whatever each caller saw, one engine asked afterwards must
     * get every unit within a bound, no file may be half written, and nothing may be left in tmp.
     */
    @Test
    fun `a storm of engines with cross-engine cancels never deadlocks and always converges to whole files`() {
        val group = Group("catalogue")
        val units: List<FileUnit> = List(4) { FileUnit("u$it", group) }.also { group.units = it }
        repeat(6) { round: Int ->
            root.deleteRecursively()
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val port = AsyncPort(storage, payload, gated = false)
            val engines: List<Downloader> = List(5) { engine(createDownloaderStorage(context, config), port, group) }
            val stop = AtomicBoolean(false)
            val random = java.util.Random(7L + round)
            runBlocking {
                withContext(Dispatchers.Default) {
                    val callers: List<Deferred<Unit>> = List(12) { index: Int ->
                        async {
                            var step = 0
                            while (!stop.get() && step < 40) {
                                val engine: Downloader = engines[(index + step) % engines.size]
                                val unit: FileUnit = units[(index * 3 + step) % units.size]
                                when ((index + step + random.nextInt(4)) % 6) {
                                    0 -> engine.cancelDownload(unit)
                                    1 -> if (step % 9 == 0) engine.cancelAllDownloads()
                                    2 -> runCatching { withTimeoutOrNull(300) { engine.ensureAvailable(group) } }
                                    else -> runCatching { withTimeoutOrNull(300) { engine.ensureAvailable(unit) } }
                                }
                                step++
                            }
                        }
                    }
                    withTimeoutOrNull(60_000) { callers.awaitAll() } ?: run {
                        stop.set(true)
                        error("round $round: the storm callers did not finish (deadlock?)")
                    }
                }
            }
            val verifier: Downloader = engine(createDownloaderStorage(context, config), port, group)
            val settled: Boolean = runBlocking {
                withTimeoutOrNull(30_000) {
                    withContext(Dispatchers.Default) {
                        var done = false
                        var attempts = 0
                        while (!done && attempts < 6) {
                            attempts++
                            done = runCatching { verifier.ensureAvailable(group) }.isSuccess
                        }
                        done
                    }
                }
            } ?: error("round $round: the verifier never returned")
            assertTrue(settled, "round $round: the group never became available after the storm")
            units.forEach { unit: FileUnit ->
                assertContentEquals(payload, File(storage.getResourcePath(unit)).readBytes(), "round $round ${unit.id}")
            }
            assertEquals(emptyList(), filesUnder(File(root, "tmp")), "round $round: debris in tmp")
            assertEquals(0, transferLockRegistrySize(), "round $round: the transfer-lock registry kept entries")
            assertFalse(units.any { storage.tempFileState(it) != TempFileState.None })
        }
    }

    @Suppress("unused")
    private fun cancelled(error: Throwable?): Boolean = error is CancellationException
}
