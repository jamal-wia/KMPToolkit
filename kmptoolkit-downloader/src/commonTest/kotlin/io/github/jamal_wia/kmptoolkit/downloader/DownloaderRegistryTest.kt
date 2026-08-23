package io.github.jamal_wia.kmptoolkit.downloader

import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The rendezvous between the host's graph and the entry points the OS creates on its own. What
 * matters here is the race: a service restarted by the system can be constructed before the host
 * has published anything, so awaiting must succeed when the downloader arrives late — and must
 * give up rather than hang when it never arrives at all.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DownloaderRegistryTest {

    @AfterTest
    fun tearDown() {
        // The registry is a process-wide object; leaving one test's downloader published would
        // decide the next test's result for it.
        DownloaderRegistry.clear()
    }

    @Test
    fun `await returns immediately when the downloader is already published`() = runTest {
        val downloader = FakeManagerDownloader()
        DownloaderRegistry.register(downloader)

        assertSame(downloader, DownloaderRegistry.await())
    }

    @Test
    fun `await resumes with the downloader published while it was waiting`() = runTest {
        // The real race: the OS built an entry point before the host finished its graph.
        var awaited: Downloader? = null
        val waiter = launch { awaited = DownloaderRegistry.await(10.seconds) }
        runCurrent()
        assertNull(awaited, "nothing is published yet, so the waiter must still be suspended")

        val downloader = FakeManagerDownloader()
        DownloaderRegistry.register(downloader)
        waiter.join()

        assertSame(downloader, awaited)
    }

    @Test
    fun `await gives up and returns null when the downloader never arrives`() = runTest {
        var awaited: Downloader? = FakeManagerDownloader()
        val waiter = launch { awaited = DownloaderRegistry.await(10.seconds) }

        advanceTimeBy(10_001)
        waiter.join()

        assertNull(awaited, "an entry point must give up rather than wait forever")
    }

    @Test
    fun `registering again replaces the published downloader`() = runTest {
        val first = FakeManagerDownloader()
        val second = FakeManagerDownloader()
        DownloaderRegistry.register(first)
        DownloaderRegistry.register(second)

        assertSame(second, DownloaderRegistry.await())
    }

    @Test
    fun `clear un-publishes the downloader`() = runTest {
        DownloaderRegistry.register(FakeManagerDownloader())
        DownloaderRegistry.clear()

        assertEquals(null, DownloaderRegistry.current.value)
    }

    private class FakeManagerDownloader : Downloader {
        override fun isAvailable(group: ResourceGroup): Boolean = false
        override fun isAvailable(unit: DownloadUnit): Boolean = false
        override fun pathOf(unit: DownloadUnit): String = ""
        override fun downloadState(group: ResourceGroup): StateFlow<GroupDownloadState> =
            MutableStateFlow(GroupDownloadState.Idle)

        override suspend fun ensureAvailable(group: ResourceGroup) = Unit
        override fun cancelDownload(group: ResourceGroup) = Unit
        override suspend fun cancelAllDownloads() = Unit
        override fun retryDownload(group: ResourceGroup) = Unit
        override suspend fun ensureAvailable(unit: DownloadUnit) = Unit
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override suspend fun hasActiveUnitDownload(): Boolean = false
        override fun unitDownloadStateFlow(unit: DownloadUnit): Flow<UnitDownloadState> = emptyFlow()
    }
}
