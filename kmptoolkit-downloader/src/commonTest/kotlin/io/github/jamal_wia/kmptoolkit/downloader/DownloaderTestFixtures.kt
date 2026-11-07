package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Fixtures for the engine tests.
 *
 * These deliberately duplicate the small doubles `kmptoolkit-downloader-testing` publishes
 * (`TestUnit`, `TestGroup`, `RecordingNotifier`, `InMemoryStateStore`, `TestDownloadDispatchers`)
 * rather than depending on that module: `-testing` depends on this module, so the reverse edge
 * would be a project cycle — the same reasoning `kmptoolkit-uploader`'s own `UploaderTestFixtures.kt`
 * documents.
 */
internal class TestUnit(
    override val id: String,
    override val group: ResourceGroup,
) : DownloadUnit {
    override val apiPath: String = "test/$id"
    override val relativePath: String = "test/$id.bin"
    override fun toString(): String = "TestUnit($id)"
}

/**
 * A unit whose [sha256] getter throws, as a host's does when it builds the value with
 * [Sha256.parse] from a malformed constant.
 */
internal class UnreadableHashUnit(
    override val id: String,
    override val group: ResourceGroup,
) : DownloadUnit {
    override val apiPath: String = "test/$id"
    override val relativePath: String = "test/$id.bin"
    override val sha256: Sha256? get() = Sha256.parse("not-a-hash")
    override fun toString(): String = "UnreadableHashUnit($id)"
}

/** A group whose members are assigned after construction, so a unit can name its group up front. */
internal class TestGroup(override val key: String) : ResourceGroup {
    override var units: List<DownloadUnit> = emptyList()
}

/** Records what the engine asked the user to be told, in order. */
internal class RecordingNotifier : DownloadNotifier {
    enum class Kind { PROGRESS, COMPLETED, REMOVE, ERROR }

    data class Call(val kind: Kind, val groupKey: String, val progress: Float?)

    val calls: MutableList<Call> = mutableListOf()

    override suspend fun showProgress(group: ResourceGroup, progress: Float) {
        calls += Call(Kind.PROGRESS, group.key, progress)
    }

    override suspend fun showCompleted(group: ResourceGroup) {
        calls += Call(Kind.COMPLETED, group.key, null)
    }

    override suspend fun showError(group: ResourceGroup, error: DownloadError) {
        calls += Call(Kind.ERROR, group.key, null)
    }

    override fun remove(group: ResourceGroup) {
        calls += Call(Kind.REMOVE, group.key, null)
    }
}

/** In-memory [DownloadStateStore] — the stall counters survive only as long as the test. */
internal class InMemoryStateStore : DownloadStateStore {
    private val ints: MutableMap<String, Int> = mutableMapOf()

    override fun readInt(key: String, default: Int): Int = ints[key] ?: default
    override fun writeInt(key: String, value: Int) {
        ints[key] = value
    }

    override fun remove(key: String) {
        ints.remove(key)
    }
}

/**
 * Runs the engine's own coroutines on the test scheduler, so the five-minute stall timeout costs
 * virtual time rather than real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class TestDownloadDispatchers(scope: TestScope) : DownloadDispatchers {
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}

/**
 * Like [TestDownloadDispatchers], but the engine's own coroutines are queued rather than run at
 * once — for the tests about what happens between a call and the work it schedules.
 */
@OptIn(ExperimentalCoroutinesApi::class)
internal class StandardTestDownloadDispatchers(scope: TestScope) : DownloadDispatchers {
    private val dispatcher: CoroutineDispatcher = StandardTestDispatcher(scope.testScheduler)
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}

/**
 * A storage with no real bytes, driven by sets of unit ids: enough for the engine tests that care
 * about what the engine decides, not about how a storage holds bytes. [commitFailure] makes every
 * commit throw it; [deletedTempFiles] records every delete in order.
 */
internal class SetStorage(
    available: Set<String> = emptySet(),
    partial: Set<String> = emptySet(),
    complete: Set<String> = emptySet(),
    var commitFailure: Exception? = null,
) : DownloaderStorage {
    private val availableIds: MutableSet<String> = available.toMutableSet()
    private val tempStates: MutableMap<String, TempFileState> = mutableMapOf<String, TempFileState>().apply {
        partial.forEach { put(it, TempFileState.Partial) }
        complete.forEach { put(it, TempFileState.Complete) }
    }
    val deletedTempFiles: MutableList<String> = mutableListOf()
    var commitCount: Int = 0
        private set

    fun setTempState(unit: DownloadUnit, state: TempFileState) {
        if (state == TempFileState.None) tempStates.remove(unit.id) else tempStates[unit.id] = state
    }

    override fun isResourceAvailable(unit: DownloadUnit): Boolean = unit.id in availableIds
    override fun tempFileState(unit: DownloadUnit): TempFileState = tempStates[unit.id] ?: TempFileState.None
    override fun getTempFileSize(unit: DownloadUnit): Long = 0L
    override fun beginTempFile(unit: DownloadUnit, expectedSha256: Sha256?) = Unit
    override fun markTempFileComplete(unit: DownloadUnit) {
        tempStates[unit.id] = TempFileState.Complete
    }

    override suspend fun commitResource(unit: DownloadUnit) {
        commitCount++
        commitFailure?.let { throw it }
        tempStates.remove(unit.id)
        availableIds += unit.id
    }

    override fun deleteTempFile(unit: DownloadUnit) {
        deletedTempFiles += unit.id
        tempStates.remove(unit.id)
    }

    override fun getResourcePath(unit: DownloadUnit): String = ""
    override fun getTempFilePath(unit: DownloadUnit): String = ""
    override fun getResourceSize(unit: DownloadUnit): Long = 0L
    override fun deleteResource(unit: DownloadUnit) {
        availableIds.remove(unit.id)
    }
}

/**
 * Makes a test downloader's events start only once the engine has enqueued, as a real transfer's do.
 * The engine drops whatever a subscription delivers before it enqueues (a replay of an earlier
 * attempt), so a double that emitted on subscription would have its events discarded. Each
 * subscription waits for its own enqueue: the n-th [awaitEnqueue] returns once n enqueues happened.
 */
internal class EnqueueGate {
    private val enqueues: MutableStateFlow<Int> = MutableStateFlow(0)
    private var subscriptions: Int = 0

    fun enqueued() {
        enqueues.update { it + 1 }
    }

    suspend fun awaitEnqueue() {
        awaitEnqueues(++subscriptions)
    }

    suspend fun awaitEnqueues(count: Int) {
        enqueues.first { it >= count }
    }
}

/**
 * A [BackgroundResourceDownloader] whose every attempt is scripted. An attempt is one subscription
 * to [observeProgress] (the engine subscribes once per attempt, before or after the enqueue), so the
 * script is indexed by subscriptions, never by enqueues. [enqueueTimes] records the virtual time of
 * each enqueue through [clock]; more than [maxEnqueues] enqueues fail loudly, so a retry loop that
 * should have stopped ends the test instead of hanging it.
 */
internal class ScriptedAttemptsDownloader(
    private val clock: () -> Long = { 0L },
    private val maxEnqueues: Int = 20,
    private val inProgress: Boolean = false,
    private val script: suspend FlowCollector<BackgroundDownloadEvent>.(attempt: Int, unit: DownloadUnit) -> Unit,
) : BackgroundResourceDownloader {
    /** `subscribe` and `enqueue`, in the order the engine did them. */
    val log: MutableList<String> = mutableListOf()
    val enqueueTimes: MutableList<Long> = mutableListOf()
    val cancelledUnits: MutableList<String> = mutableListOf()
    var subscriptions: Int = 0
        private set
    val enqueueCount: Int get() = enqueueTimes.size

    private val gate = EnqueueGate()

    override fun enqueueDownload(unit: DownloadUnit) {
        gate.enqueued()
        log += "enqueue"
        enqueueTimes += clock()
        check(enqueueTimes.size <= maxEnqueues) { "retry loop: more than $maxEnqueues enqueues" }
    }

    override fun isDownloadInProgress(unit: DownloadUnit): Boolean = inProgress
    override fun cancelDownload(unit: DownloadUnit) {
        cancelledUnits += unit.id
    }

    override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = flow {
        log += "subscribe"
        val attempt: Int = subscriptions++
        // A transfer already running (inProgress) has produced its events without an enqueue; the
        // retry that follows it enqueues, so the n-th enqueue belongs to the (n+1)-th subscription.
        if (!inProgress || attempt > 0) gate.awaitEnqueues(if (inProgress) attempt else attempt + 1)
        script(attempt, unit)
    }
}
