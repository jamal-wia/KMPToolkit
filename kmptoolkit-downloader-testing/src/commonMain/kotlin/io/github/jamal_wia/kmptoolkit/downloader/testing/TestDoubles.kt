package io.github.jamal_wia.kmptoolkit.downloader.testing

import io.github.jamal_wia.kmptoolkit.downloader.DownloadError
import io.github.jamal_wia.kmptoolkit.downloader.DownloadUnit
import io.github.jamal_wia.kmptoolkit.downloader.ResourceGroup
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadNotifier
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher

/**
 * A catalogue that exists only for tests.
 *
 * The engine is supposed to work against any host's resources, so proving it with a set of units
 * invented here — rather than any real application's — is the point: if anything in the engine came
 * to depend on a particular resource, a test using this would stop compiling or start failing.
 */
public class TestUnit(
    override val id: String,
    override val group: ResourceGroup,
) : DownloadUnit {
    override val apiPath: String = "test/$id"
    override val relativePath: String = "test/$id.bin"
    override fun toString(): String = "TestUnit($id)"
}

/** A group whose members are assigned after construction, so a unit can name its group up front. */
public class TestGroup(override val key: String) : ResourceGroup {
    override var units: List<DownloadUnit> = emptyList()
}

/** Records what a [io.github.jamal_wia.kmptoolkit.downloader.Downloader] asked the user to be told, in order. */
public class RecordingNotifier : DownloadNotifier {
    public enum class Kind { PROGRESS, COMPLETED, REMOVE, ERROR }

    public data class Call(
        public val kind: Kind,
        public val groupKey: String,
        public val progress: Float?,
    )

    public val calls: MutableList<Call> = mutableListOf()

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
public class InMemoryStateStore : DownloadStateStore {
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
 * Runs the engine's own coroutines on the test scheduler, so a five-minute stall timeout costs
 * virtual time rather than real time.
 */
@OptIn(ExperimentalCoroutinesApi::class)
public class TestDownloadDispatchers(scope: TestScope) : DownloadDispatchers {
    private val dispatcher: CoroutineDispatcher = UnconfinedTestDispatcher(scope.testScheduler)
    override val io: CoroutineDispatcher = dispatcher
    override val default: CoroutineDispatcher = dispatcher
}
