package io.github.jamal_wia.kmptoolkit.downloader

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith

/**
 * B round 10, Q2: a base directory as 4.0.0 left it (the names are the tag's own: `git show 4.0.0:` of UnitFileLayout.kt gives
 * `tmp/<id>.<ext>`, `.complete`, `tmp/expect/<id>.sha256` holding `sha256:<hex>` and a newline, `tmp/staging-<id>`,
 * `tmp/kmptoolkit-internal/trash/<number>`; the committed resource is at `<base>/<relativePath>`), opened by this version through every
 * entry of [DownloaderStorage] that round 8 did not use: availability, size, path, temp state and size of a partial and of a complete
 * file, `deleteTempFile` with the trash sweep, `commitResource` of a complete file with its record, `deleteResource` of a file and of a tree.
 * Contract: 05-platform-notes "Storage locations" (the trash is swept by every later deleteTempFile and deleteResource of a directory)
 * and DownloaderStorage KDoc.
 */
@RunWith(AndroidJUnit4::class)
class AndroidUpgradeRound10Test {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val config: DownloaderStorageConfig = DownloaderStorageConfig(baseDirectoryName = "upgrade_r10")
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

    private class TestUnit(
        override val id: String,
        override val relativePath: String,
        override val group: ResourceGroup,
        override val format: ResourceFormat = ResourceFormat.Opaque,
        override val sha256: Sha256? = null,
    ) : DownloadUnit {
        override val apiPath: String = "test/$id"
        override val tempExtension: String = "tmp"
        override fun toString(): String = "TestUnit($id)"
    }

    private fun file(vararg parts: String): File = parts.fold(root) { parent: File, part: String -> File(parent, part) }

    private fun hex(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun write(target: File, bytes: ByteArray) {
        target.parentFile!!.mkdirs()
        target.writeBytes(bytes)
    }

    @Test
    fun `a base directory left by 4_0_0 is read swept committed and deleted correctly by every entry of the storage`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val opaqueBytes: ByteArray = ByteArray(70_000) { (it % 251).toByte() }
            val opaque = TestUnit("u", "files/u.bin", group)
            val treeFiles: Map<String, ByteArray> = mapOf("marker.txt" to "m".encodeToByteArray(), "a.bin" to ByteArray(30_000) { 1 }, "sub" to ByteArray(0))
            val archive = TestUnit("z", "archives/z", group, ResourceFormat.ZipArchive("marker.txt"))
            val partialBytes: ByteArray = ByteArray(50_000) { (it % 241).toByte() }
            val partial = TestUnit("p", "files/p.bin", group, ResourceFormat.Opaque)
            val completeBytes: ByteArray = ByteArray(40_000) { (it % 239).toByte() }
            val complete = TestUnit("c", "files/c.bin", group, ResourceFormat.Opaque)
            group.units = listOf(opaque, archive, partial, complete)

            // The 4.0.0 base.
            write(file("files", "u.bin"), opaqueBytes)
            write(file("archives", "z", "marker.txt"), treeFiles.getValue("marker.txt"))
            write(file("archives", "z", "a.bin"), treeFiles.getValue("a.bin"))
            file("archives", "z", "sub").mkdirs()
            write(file("tmp", "p.tmp"), partialBytes.copyOf(20_000))
            write(file("tmp", "expect", "p.sha256"), "sha256:${hex(partialBytes)}\n".encodeToByteArray())
            write(file("tmp", "c.tmp.complete"), completeBytes)
            write(file("tmp", "expect", "c.sha256"), "sha256:${hex(completeBytes)}\n".encodeToByteArray())
            write(file("tmp", "staging-z", "half.bin"), ByteArray(5_000))
            val trashRoot: File = file("tmp", "kmptoolkit-internal", "trash")
            write(File(File(trashRoot, "17"), "old.bin"), ByteArray(3_000))
            write(File(trashRoot, "18"), ByteArray(10))
            write(File(File(File(trashRoot, "9223372036854775807"), "deep"), "x.bin"), ByteArray(1))

            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            // Availability, path and size of what was committed.
            assertTrue(storage.isResourceAvailable(opaque))
            assertTrue(storage.isResourceAvailable(archive))
            assertEquals(file("files", "u.bin").absolutePath, File(storage.getResourcePath(opaque)).absolutePath)
            assertEquals(70_000L, storage.getResourceSize(opaque))
            assertEquals(30_001L, storage.getResourceSize(archive), "the size of a tree is the sum of its files")
            assertFalse(storage.isResourceAvailable(partial))
            assertEquals(0L, storage.getResourceSize(partial))
            // Temp state, size and path of the interrupted transfers.
            assertEquals(TempFileState.Partial, storage.tempFileState(partial))
            assertEquals(20_000L, storage.getTempFileSize(partial))
            assertEquals(file("tmp", "p.tmp").absolutePath, File(storage.getTempFilePath(partial)).absolutePath)
            assertEquals(TempFileState.Complete, storage.tempFileState(complete))
            assertEquals(TempFileState.None, storage.tempFileState(opaque))

            // The complete file commits with its 4.0.0 record, which it consumes.
            storage.commitResource(complete)
            assertTrue(completeBytes.contentEquals(file("files", "c.bin").readBytes()))
            assertFalse(file("tmp", "c.tmp.complete").exists())
            assertFalse(file("tmp", "expect", "c.sha256").exists(), "the record of a consumed temp file stays behind")

            // Deleting the partial takes its record with it and sweeps the 4.0.0 trash, whoever left it.
            storage.deleteTempFile(partial)
            assertFalse(file("tmp", "p.tmp").exists())
            assertFalse(file("tmp", "expect", "p.sha256").exists())
            assertEquals(emptyList(), trashRoot.listFiles().orEmpty().map { it.name }, "4.0.0 trash entries survived the sweep")
            assertEquals(TempFileState.None, storage.tempFileState(partial))

            // A tree and a file are deleted whole, and nothing is left in the trash.
            storage.deleteResource(archive)
            assertFalse(file("archives", "z").exists())
            assertFalse(storage.isResourceAvailable(archive))
            storage.deleteResource(opaque)
            assertFalse(file("files", "u.bin").exists())
            assertEquals(0L, storage.getResourceSize(opaque))
            assertEquals(emptyList(), trashRoot.listFiles().orEmpty().map { it.name })
            // The staging leftover of the archive goes with its unit's temp state.
            storage.deleteTempFile(archive)
            assertFalse(file("tmp", "staging-z").exists())
        }
    }

    @Test
    fun `a record that is not exactly none or sha256 and a hash is an integrity failure never no check and the 4_0_0 spellings still commit`() = runBlocking<Unit> {
        withTimeout(60_000) {
            val group = Group("g")
            val bytes: ByteArray = ByteArray(10_000) { (it % 233).toByte() }
            val good: String = hex(bytes)
            val storage: DownloaderStorage = createDownloaderStorage(context, config)
            // 03-guide: "A record that exists but cannot be read is also an integrity failure, never 'no check'."
            val damaged: List<String> = listOf(
                "nonesuch\n", "none and more\n", "x sha256:$good\n", "garbage sha256:$good\n", "", "sha256:\n", "sha256:${good.dropLast(1)}\n",
            )
            damaged.forEachIndexed { index: Int, text: String ->
                val unit = TestUnit("d$index", "files/d$index.bin", group)
                write(file("tmp", "d$index.tmp.complete"), bytes)
                write(file("tmp", "expect", "d$index.sha256"), text.encodeToByteArray())
                assertFailsWith<ResourceIntegrityException>("record '${text.trim()}' was taken for a readable one") { storage.commitResource(unit) }
                assertFalse(storage.isResourceAvailable(unit), "a damaged record let '${text.trim()}' commit")
                assertFalse(file("tmp", "d$index.tmp.complete").exists(), "the file of a damaged record was kept")
            }
            // The spellings 4.0.0 wrote (and that a CRLF editor or an upper-case hash leaves) are read.
            val readable: List<String> = listOf("none\n", "none", "none\r\n", "sha256:$good\n", "sha256:$good", "sha256:${good.uppercase()}\n", "sha256:$good\r\n")
            readable.forEachIndexed { index: Int, text: String ->
                val unit = TestUnit("r$index", "files/r$index.bin", group)
                write(file("tmp", "r$index.tmp.complete"), bytes)
                write(file("tmp", "expect", "r$index.sha256"), text.encodeToByteArray())
                storage.commitResource(unit)
                assertTrue(bytes.contentEquals(file("files", "r$index.bin").readBytes()), "record '${text.trim()}' did not commit")
            }
            // A readable record of a different hash fails, whatever else is right.
            val other = TestUnit("w", "files/w.bin", group)
            write(file("tmp", "w.tmp.complete"), bytes)
            write(file("tmp", "expect", "w.sha256"), "sha256:${hex(ByteArray(1))}\n".encodeToByteArray())
            assertFailsWith<ResourceIntegrityException> { storage.commitResource(other) }
            assertFalse(storage.isResourceAvailable(other))
        }
    }
}
