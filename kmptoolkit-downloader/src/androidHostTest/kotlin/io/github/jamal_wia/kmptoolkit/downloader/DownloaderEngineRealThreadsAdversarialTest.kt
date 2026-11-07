package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Many callers for one unit on real threads: the engine runs on [Dispatchers.Default] and 32
 * callers start behind one gate, for many rounds, against doubles that are safe to call from any
 * thread. A transfer that is enqueued twice, a commit that runs twice, or a caller that is never
 * woken is a failure; a caller that is never woken is reported with the stacks of the threads
 * that could be holding it.
 */
class DownloaderEngineRealThreadsAdversarialTest {

    private class CatalogueUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "test/$id.bin"
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    /** A storage whose every method is safe to call from any thread; counts the commits. */
    private class ConcurrentStorage : DownloaderStorage {
        private val available: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private val temp: ConcurrentHashMap<String, TempFileState> = ConcurrentHashMap()
        val commits: AtomicInteger = AtomicInteger()

        fun markComplete(unit: DownloadUnit) {
            temp[unit.id] = TempFileState.Complete
        }

        override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit.id in available
        override fun tempFileState(unit: DownloadUnit): TempFileState = temp[unit.id] ?: TempFileState.None
        override fun getTempFileSize(unit: DownloadUnit): Long = 0L
        override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) = Unit
        override fun markTempFileComplete(unit: DownloadUnit) = markComplete(unit)

        override suspend fun commitResource(unit: DownloadUnit) {
            commits.incrementAndGet()
            temp.remove(unit.id)
            available += unit.id
        }

        override fun deleteTempFile(unit: DownloadUnit) {
            temp.remove(unit.id)
        }

        override fun getResourcePath(unit: DownloadUnit): String = "/resources/${unit.id}"
        override fun getTempFilePath(unit: DownloadUnit): String = "/tmp/${unit.id}"
        override fun getResourceSize(unit: DownloadUnit): Long = 0L
        override fun deleteResource(unit: DownloadUnit) {
            available -= unit.id
        }
    }

    /** A port whose transfers are whole the moment they start; safe to call from any thread. */
    private class ConcurrentPort(private val storage: ConcurrentStorage) : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> =
            MutableSharedFlow(extraBufferCapacity = 4096)
        val enqueues: AtomicInteger = AtomicInteger()
        val cancels: AtomicInteger = AtomicInteger()

        override fun enqueueDownload(unit: DownloadUnit) {
            enqueues.incrementAndGet()
            storage.markComplete(unit)
            events.tryEmit(BackgroundDownloadEvent.FileReady(unit))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) {
            cancels.incrementAndGet()
        }

        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> =
            events.filter { it.unit.id == unit.id }
    }

    @Test
    fun `32 callers for one unit on real threads share one transfer and one commit for 100 rounds`() {
        stress(rounds = 100, surface = Surface.UNIT)
    }

    @Test
    fun `32 callers for one group on real threads share one transfer and one commit for 20 rounds`() {
        // A group call settles for 300 ms of real time after its last commit, so fewer rounds.
        stress(rounds = 20, surface = Surface.GROUP)
    }

    @Test
    fun `callers on both surfaces for one unit on real threads share one transfer and one commit`() {
        stress(rounds = 20, surface = Surface.BOTH)
    }

    private enum class Surface { UNIT, GROUP, BOTH }

    private fun stress(rounds: Int, surface: Surface) {
        for (round: Int in 1..rounds) {
            val group = Group("catalogue-$round")
            val unit = CatalogueUnit("model-$round", group)
            group.units = listOf(unit)
            val storage = ConcurrentStorage()
            val port = ConcurrentPort(storage)
            val engine: Downloader = createDownloader(
                storage = storage,
                backgroundDownloader = port,
                groups = listOf(group),
                stateStore = ConcurrentStateStore(),
                dispatchers = DownloadDispatchers.Default,
                logger = NoopLogger,
            )

            val outcomes: List<Result<Unit>>? = runBlocking {
                withContext(Dispatchers.Default) {
                    val go = CompletableDeferred<Unit>()
                    val callers: List<Deferred<Result<Unit>>> = List(CALLERS) { index: Int ->
                        async {
                            go.await()
                            runCatching {
                                val viaUnit: Boolean = when (surface) {
                                    Surface.UNIT -> true
                                    Surface.GROUP -> false
                                    Surface.BOTH -> index % 2 == 0
                                }
                                if (viaUnit) engine.ensureAvailable(unit) else engine.ensureAvailable(group)
                            }
                        }
                    }
                    go.complete(Unit)
                    withTimeoutOrNull(TIMEOUT_MILLIS) { callers.awaitAll() }
                }
            }

            assertTrue(outcomes != null, "round $round: a caller was never woken\n${threadDump()}")
            outcomes.forEachIndexed { index: Int, outcome: Result<Unit> ->
                assertTrue(outcome.isSuccess, "round $round: caller $index failed with ${outcome.exceptionOrNull()}")
            }
            assertEquals(1, port.enqueues.get(), "round $round: one transfer")
            assertEquals(1, storage.commits.get(), "round $round: one commit")
            assertEquals(0, port.cancels.get(), "round $round: nothing is cancelled")
            // Completed when the unit surface did the commit, Available when the group surface did (or
            // did and a unit call found the file already there); Downloading, Idle or Error is stuck.
            val state: UnitDownloadState = engine.unitStateOf(unit)
            assertTrue(
                state == UnitDownloadState.Completed || state == UnitDownloadState.Available,
                "round $round: the unit ended in $state",
            )
        }
    }

    private class ConcurrentStateStore : DownloadStateStore {
        private val ints: ConcurrentHashMap<String, Int> = ConcurrentHashMap()
        override fun readInt(key: String, default: Int): Int = ints[key] ?: default
        override fun writeInt(key: String, value: Int) {
            ints[key] = value
        }

        override fun remove(key: String) {
            ints.remove(key)
        }
    }

    private fun Downloader.unitStateOf(unit: DownloadUnit): UnitDownloadState = runBlocking {
        withTimeoutOrNull(TIMEOUT_MILLIS) { unitDownloadStateFlow(unit).first() }
            ?: error("the state flow of $unit never produced a value")
    }

    private fun threadDump(): String = Thread.getAllStackTraces().entries
        .filter { (thread: Thread, _) ->
            thread.name == "main" || thread.name.startsWith("DefaultDispatcher-") || thread.name.startsWith("kmptoolkit-")
        }
        .joinToString("\n") { (thread: Thread, stack: Array<StackTraceElement>) ->
            "${thread.name}:\n" + stack.take(12).joinToString("\n") { "    at $it" }
        }

    private companion object {
        const val CALLERS: Int = 32
        const val TIMEOUT_MILLIS: Long = 10_000
    }
}
