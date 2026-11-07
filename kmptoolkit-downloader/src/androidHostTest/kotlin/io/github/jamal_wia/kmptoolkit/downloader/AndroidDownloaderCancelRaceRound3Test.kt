package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadDispatchers
import io.github.jamal_wia.kmptoolkit.downloader.spi.DownloadStateStore
import io.github.jamal_wia.kmptoolkit.logging.NoopLogger
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/** A cancel racing the request it should end, on real threads, against the real Android storage. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderCancelRaceRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "cancel_race_round3")
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

    private class ArchiveUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val relativePath: String = "archives/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("marker.txt")
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
     * A port whose transfer always ends at once with "not found": a request that finds no complete temp
     * file (a cancel took it) fails fast and legitimately instead of waiting for a transfer that never comes.
     */
    private class NotFoundPort : BackgroundResourceDownloader {
        private val events: MutableSharedFlow<BackgroundDownloadEvent> = MutableSharedFlow(extraBufferCapacity = 64)
        override fun enqueueDownload(unit: DownloadUnit) {
            events.tryEmit(BackgroundDownloadEvent.Error(unit, "HTTP 404"))
        }

        override fun isDownloadInProgress(unit: DownloadUnit): Boolean = false
        override fun cancelDownload(unit: DownloadUnit) = Unit
        override fun observeProgress(unit: DownloadUnit): Flow<BackgroundDownloadEvent> = events
    }

    private fun engine(group: ResourceGroup): Downloader = createDownloader(
        storage = createDownloaderStorage(context, config),
        backgroundDownloader = NotFoundPort(),
        groups = listOf(group),
        stateStore = MapStateStore(),
        dispatchers = DownloadDispatchers.Default,
        logger = NoopLogger,
    )

    private fun seedCompleteTempFile(unit: DownloadUnit, payload: ByteArray) {
        val storage: DownloaderStorage = createDownloaderStorage(context, config)
        storage.beginTempFile(unit, null)
        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(payload)
        storage.markTempFileComplete(unit)
    }

    /**
     * Runs [rounds] races of one request against one cancel and returns how the requests ended, as a
     * count per outcome. Every round starts from a complete temp file and a new engine.
     */
    private fun race(rounds: Int, unit: DownloadUnit, payload: ByteArray, request: suspend (Downloader) -> Unit, cancel: (Downloader) -> Unit, group: ResourceGroup, prepare: (Downloader) -> Unit = {}): Map<String, Int> {
        val outcomes: MutableMap<String, Int> = HashMap()
        // A warm-up round first: the very first call in a Robolectric process loads a lot of classes.
        repeat(rounds + 1) { round: Int ->
            root.deleteRecursively()
            seedCompleteTempFile(unit, payload)
            val engine: Downloader = engine(group)
            prepare(engine)
            val barrier = CyclicBarrier(2)
            val outcome = AtomicReference<Result<Unit>?>(null)
            val requester = thread(name = "requester") {
                barrier.await(30, TimeUnit.SECONDS)
                outcome.set(runCatching { runBlocking { withTimeoutOrNull(20_000) { request(engine) } ?: error("timeout") } })
            }
            val canceller = thread(name = "canceller") {
                barrier.await(30, TimeUnit.SECONDS)
                if (round % 2 == 0) Thread.yield()
                cancel(engine)
            }
            requester.join(30_000)
            canceller.join(30_000)
            assertTrue(!requester.isAlive && !canceller.isAlive, "round $round: a thread is blocked")
            if (round == 0) return@repeat
            val error: Throwable? = outcome.get()?.exceptionOrNull()
            val key: String = when {
                outcome.get() == null -> "no outcome"
                error == null -> "completed"
                error is DownloadCancelledException || error is CancellationException -> "cancelled"
                error is DownloadFailedException && error.error == DownloadError.NotFound -> "completed" // a request that began after the cancel took the file
                else -> "${error.javaClass.simpleName}: ${error.message?.replace(Regex("@[0-9a-f]+"), "")}"
            }
            outcomes[key] = (outcomes[key] ?: 0) + 1
        }
        return outcomes
    }

    @Test
    fun `a group request and a group cancel with a complete temp file end as completed or cancelled never as a storage failure`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val outcomes: Map<String, Int> = race(
            rounds = 150, unit = unit, payload = payload, group = group,
            request = { it.ensureAvailable(group) },
            cancel = { it.cancelDownload(group) },
        )
        assertEquals(emptyList(), outcomes.keys.filter { it != "completed" && it != "cancelled" }.map { "$it x${outcomes[it]}" }, "outcomes: $outcomes")
    }

    @Test
    fun `a unit request racing a group cancel of the group that holds it ends as completed or cancelled never as a storage failure`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val outcomes: Map<String, Int> = race(
            rounds = 600, unit = unit, payload = payload, group = group,
            request = { it.ensureAvailable(unit) },
            cancel = { it.cancelDownload(group) },
        )
        assertEquals(emptyList(), outcomes.keys.filter { it != "completed" && it != "cancelled" }.map { "$it x${outcomes[it]}" }, "outcomes: $outcomes")
    }

    @Test
    fun `a request and a cancel of a tracked unit with a complete temp file end as completed or cancelled never as a storage failure`() {
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val group = Group("g")
        val unit = FileUnit("model", group).also { group.units = listOf(it) }
        val outcomes: Map<String, Int> = race(
            rounds = 600, unit = unit, payload = payload, group = group,
            request = { it.ensureAvailable(unit) },
            cancel = { it.cancelDownload(unit) },
            prepare = { engine -> runBlocking { engine.unitDownloadStateFlow(unit).first() } },
        )
        assertEquals(emptyList(), outcomes.keys.filter { it != "completed" && it != "cancelled" }.map { "$it x${outcomes[it]}" }, "outcomes: $outcomes")
    }
}
