package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.testsupport.TestPaths
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B round 11, Q2: the refusal of an over-long entry path (B r10 F-1 fix) leaves nothing behind however often and from
 * however many threads it happens. Contract: 05-platform-notes ("The lock registries keep an entry only while a call holds
 * or awaits it", a failed commit leaves the committed tree, the temp files and the staging directory clean) and
 * DownloaderStorage.commitResource (a ResourceIntegrityException deletes the temp file). No path of 1,024 to 4,095 bytes
 * exists on the file system: every entry here is refused above 4,095 before a file system call.
 */
@RunWith(AndroidJUnit4::class)
class AndroidPathLimitRefusalRound11Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "pathlimit_refusal_r11")
    private val root: File get() = File(context.filesDir, config.baseDirectoryName)

    @BeforeTest
    fun setUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
        LegacyTempMigrations.resetForTests()
    }

    private class Group(override val key: String) : ResourceGroup {
        override var units: List<DownloadUnit> = emptyList()
    }

    private class TestUnit(override val id: String, override val group: ResourceGroup) : DownloadUnit {
        override val relativePath: String = "archives/$id"
        override val apiPath: String = "test/$id"
        override val format: ResourceFormat = ResourceFormat.ZipArchive("ok.txt")
    }

    @Test
    fun `eight hundred refusals of an over long entry from five units leave no lock no file and no staging behind`() = runBlocking {
        withTimeout(120_000) {
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            val group = Group("g")
            val units: List<TestUnit> = (0 until 5).map { index: Int -> TestUnit("long$index", group) }
            val tooLong: String = "d/".repeat(2_100) + "f.bin"
            val archive: ByteArray = ZipBytesRound6.build(
                listOf(ZipEntryRound6("ok.txt", "ok\n"), ZipEntryRound6(tooLong, "payload\n")),
            ).bytes
            val refusals = java.util.concurrent.atomic.AtomicInteger()
            units.map { unit: TestUnit ->
                launch(Dispatchers.Default) {
                    repeat(160) {
                        storage.beginTempFile(unit, null)
                        File(storage.getTempFilePath(unit)).apply { parentFile!!.mkdirs() }.writeBytes(archive)
                        storage.markTempFileComplete(unit)
                        val failure: Throwable? = runCatching { storage.commitResource(unit) }.exceptionOrNull()
                        if (failure is ResourceIntegrityException) refusals.incrementAndGet()
                        assertEquals(TempFileState.None, storage.tempFileState(unit), "the temp file of a refused archive must be gone")
                    }
                }
            }.joinAll()

            assertEquals(800, refusals.get(), "every commit must be refused as ResourceIntegrityException")
            assertEquals(0, UnitCommitLocks.activeKeyCount, "the commit lock registry must be empty")
            val files: List<String> = root.walkTopDown().filter { it.isFile }.map { TestPaths.relativeLogical(root.path, it.path)!! }.toList()
            assertEquals(listOf(UnitFileLayout.LEGACY_LAYOUT_MARKER), files, "nothing but the layout marker may stay")
            units.forEach { unit: TestUnit -> assertTrue(!storage.isResourceAvailable(unit), "a refused archive must not be available") }
        }
    }
}
