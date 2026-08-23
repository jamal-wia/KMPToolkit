package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * Fixtures for the engine tests.
 *
 * These deliberately duplicate the small doubles `kmptoolkit-downloader-testing` publishes
 * (`TestUnit`, `TestGroup`, `RecordingNotifier`, `InMemoryStateStore`, `TestDownloadDispatchers`)
 * rather than depending on that module: `-testing` depends on this module, so the reverse edge
 * would be a project cycle — the same reasoning `kmptoolkit-outbox`'s own `OutboxTestFixtures.kt`
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
