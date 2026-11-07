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
import java.io.ByteArrayOutputStream
import java.util.concurrent.ThreadLocalRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.runner.RunWith

/** A group cancel landing at every point of a real archive extraction, on real threads. */
@RunWith(AndroidJUnit4::class)
class AndroidDownloaderArchiveCancelRound3Test {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config = DownloaderStorageConfig(baseDirectoryName = "archive_cancel_round3")
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

    private fun bigZip(entries: Int): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { out: ZipOutputStream ->
            out.putNextEntry(ZipEntry("marker.txt")); out.write("ready".encodeToByteArray()); out.closeEntry()
            repeat(entries) { index: Int ->
                out.putNextEntry(ZipEntry("pages/page$index.webp"))
                out.write(ByteArray(2048) { (it + index).toByte() })
                out.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    @Test
    fun `a group cancel at any moment of an archive extraction leaves each archive whole or absent and every thread returns`() {
        val zip: ByteArray = bigZip(300)
        val bad: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        var cancelled = 0
        var completed = 0
        repeat(40) { round: Int ->
            root.deleteRecursively()
            val group = Group("pages")
            val units: List<ArchiveUnit> = (0 until 3).map { ArchiveUnit("archive$it", group) }
            group.units = units
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            for (unit in units) {
                storage.beginTempFile(unit, null)
                File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(zip)
                storage.markTempFileComplete(unit)
            }
            val engine: Downloader = engine(group)
            val barrier = CyclicBarrier(2)
            val outcome = AtomicReference<Result<Unit>?>(null)
            val requester = thread(name = "requester") {
                barrier.await(30, TimeUnit.SECONDS)
                outcome.set(runCatching { runBlocking { withTimeoutOrNull(60_000) { engine.ensureAvailable(group) } ?: error("timeout") } })
            }
            val canceller = thread(name = "canceller") {
                barrier.await(30, TimeUnit.SECONDS)
                Thread.sleep(ThreadLocalRandom.current().nextLong(0, 40))
                if (round % 2 == 0) engine.cancelDownload(group) else runBlocking { engine.cancelAllDownloads() }
            }
            requester.join(70_000)
            canceller.join(10_000)
            assertTrue(!requester.isAlive && !canceller.isAlive, "round $round: a thread is blocked")
            val error: Throwable? = outcome.get()?.exceptionOrNull()
            when {
                outcome.get() == null -> bad += "round $round: no outcome"
                error == null -> completed++
                error is DownloadCancelledException || error is CancellationException -> cancelled++
                // The request found its temp file gone (the cancel took it) and the port answers not-found.
                error is DownloadFailedException && error.error == DownloadError.NotFound -> cancelled++
                else -> bad += "round $round: $error"
            }
            for (unit in units) {
                val target = File(storage.getResourcePath(unit))
                if (target.exists()) {
                    val entries: Int = target.walkTopDown().count { it.isFile }
                    if (entries != 301) bad += "round $round: ${unit.id} exists with $entries files instead of 301"
                    if (!storage.isResourceAvailable(unit)) bad += "round $round: ${unit.id} exists but is not available"
                }
            }
        }
        assertEquals(emptyList(), bad.take(5), "${bad.size} violations (completed $completed, cancelled $cancelled)")
    }
}
