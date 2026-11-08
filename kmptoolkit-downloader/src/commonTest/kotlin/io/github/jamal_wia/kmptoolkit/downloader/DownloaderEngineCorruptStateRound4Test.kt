package io.github.jamal_wia.kmptoolkit.downloader

import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * The failure count lives in a store the host supplies. A value that cannot be a count of failures
 * (negative, or at the end of the Int range) must not turn "three attempts, then give up" into an
 * endless retry loop.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderEngineCorruptStateRound4Test {

    private val group = TestGroup("g")
    private val unit = TestUnit(id = "model", group = group)
    private val stateStore = InMemoryStateStore()

    init {
        group.units = listOf(unit)
    }

    private fun TestScope.engine(downloader: BackgroundResourceDownloader): DefaultDownloaderEngine =
        DefaultDownloaderEngine(
            storage = SetStorage(),
            notifier = RecordingNotifier(),
            backgroundDownloader = downloader,
            stateStore = stateStore,
            bundledResourcesPresent = false,
            groups = listOf(group),
            dispatchers = TestDownloadDispatchers(this),
            logger = NoopLogger,
        )

    private fun TestScope.failingPort(): ScriptedAttemptsDownloader = ScriptedAttemptsDownloader(
        clock = { testScheduler.currentTime },
        script = { _, u -> emit(BackgroundDownloadEvent.Error(u, "connection reset")) },
    )

    private fun runWith(seed: Int, surface: String) = runTest {
        stateStore.writeInt("download_stall_count_${unit.id}", seed)
        val port: ScriptedAttemptsDownloader = failingPort()
        val engine: DefaultDownloaderEngine = engine(port)
        // Ten virtual minutes hold a few dozen backoffs of up to 30 s: a bounded run stops long before.
        withTimeoutOrNull(10.minutes) {
            runCatching {
                if (surface == "group") engine.ensureAvailable(group) else engine.ensureAvailable(unit)
            }
        }
        assertTrue(port.enqueueCount <= 4, "$surface surface, stored count $seed: ${port.enqueueCount} transfers started")
    }

    @Test
    fun `a negative stored failure count does not allow endless retries of the group`() = runWith(-1_000, "group")

    @Test
    fun `a stored failure count at Int MAX does not wrap into endless retries of the group`() =
        runWith(Int.MAX_VALUE, "group")

    @Test
    fun `a negative stored failure count does not allow endless retries of a unit`() = runWith(-1_000, "unit")

    // Exact counts: the default three attempts stand for every stored value.

    private fun exactTransfers(seed: Int, calls: Int): List<Int> {
        val counts: MutableList<Int> = mutableListOf()
        runTest {
            stateStore.writeInt("download_stall_count_${unit.id}", seed)
            val port: ScriptedAttemptsDownloader = failingPort()
            val engine: DefaultDownloaderEngine = engine(port)
            var before = 0
            repeat(calls) {
                withTimeoutOrNull(10.minutes) { runCatching { engine.ensureAvailable(group) } }
                counts += port.enqueueCount - before
                before = port.enqueueCount
            }
        }
        return counts
    }

    @Test
    fun `a stored count of minus a thousand reads as no failures - exactly three transfers`() {
        assertEquals(listOf(3), exactTransfers(-1_000, calls = 1))
    }

    @Test
    fun `a stored count of Int MAX reads as exhausted - one transfer then three more once the count is cleared`() {
        assertEquals(listOf(1, 3), exactTransfers(Int.MAX_VALUE, calls = 2))
    }

    @Test
    fun `an exhausted in-range stored count allows exactly one transfer`() {
        assertEquals(listOf(1), exactTransfers(3, calls = 1))
    }
}
