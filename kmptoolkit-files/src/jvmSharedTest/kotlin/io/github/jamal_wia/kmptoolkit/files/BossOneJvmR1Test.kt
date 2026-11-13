@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Unit G, stage 2 (final boss), round 1, java.nio engine (JVM and Android host): the two r6 survivors whose killer
 * r6 said did not exist inside the module.
 *
 * - N06 (the directory sync replaced by nothing): a directory the process may enter and write but not open for reading
 *   cannot be synced. The KDoc promises that a failure of the final directory sync of SyncedWithDirectory is
 *   [FileError.Io] with the new bytes in place, and that a sync failure of durable createDirectories is Io with the
 *   directories created; a sync that silently does nothing would report Success for an entry that is not durable.
 * - N07 (usable space replaced by total space): freeSpaceBytes is "bytes available to this app", which is the volume's
 *   usable space, never its total size.
 */
class BossOneJvmR1Test {
    private lateinit var root: File
    private val files: FileAccess = createFileAccess()

    @BeforeTest
    fun setUp() {
        root = Files.createTempDirectory("kmptoolkit-boss1").toFile()
    }

    @AfterTest
    fun tearDown() {
        root.walkBottomUp().forEach { file: File ->
            // path-hygiene-ignore: PH2 teardown restores the permission bits a POSIX-only test dropped
            file.setReadable(true, false)
            // path-hygiene-ignore: PH2 teardown restores the permission bits a POSIX-only test dropped
            file.setWritable(true, false)
            // path-hygiene-ignore: PH2 teardown restores the permission bits a POSIX-only test dropped
            file.setExecutable(true, false)
        }
        root.deleteRecursively()
    }

    /** Makes [directory] enterable and writable but not readable; skips when that does not hold (a super-user). */
    private fun makeUnreadable(directory: File) {
        assertTrue(directory.isDirectory)
        TestHost.assumePosixFileSemantics("a directory that can be entered but not opened for reading")
        // path-hygiene-ignore: PH2 guarded by assumePosixFileSemantics and by the enforcement check below
        assumeTrue("cannot drop read permission here", directory.setReadable(false, false))
        assumeTrue("read permission is not enforced for this user", directory.list() == null)
    }

    @Test
    fun `a SyncedWithDirectory write into a directory that cannot be opened for its sync is Io with the new bytes in place`() {
        val dir: File = File(root, "d").apply { assertTrue(mkdirs()) }
        val target: String = File(dir, "f").path
        assertTrue(files.writeAtomically(target, "old".encodeToByteArray()).isSuccess)
        makeUnreadable(dir)
        val result: FileResult<Unit> =
            files.writeAtomically(target, "new".encodeToByteArray(), WriteOptions(durability = Durability.SyncedWithDirectory))
        val error: FileError? = result.errorOrNull()
        assertTrue(error is FileError.Io, "expected Io from the failed directory sync, was $result")
        assertEquals("new", files.readBytes(target, 100).getOrNull()?.decodeToString())
        // path-hygiene-ignore: PH2 restores the read bit dropped above on a POSIX host
        dir.setReadable(true, false)
        assertEquals(listOf("f"), dir.list()?.sorted(), "no temporary file may be left")
    }

    @Test
    fun `a Synced write into the same directory never syncs it and succeeds`() {
        val dir: File = File(root, "d").apply { assertTrue(mkdirs()) }
        val target: String = File(dir, "f").path
        makeUnreadable(dir)
        assertEquals(FileResult.Success(Unit), files.writeAtomically(target, "new".encodeToByteArray()))
        assertEquals("new", files.readBytes(target, 100).getOrNull()?.decodeToString())
    }

    @Test
    fun `durable createDirectories under a parent that cannot be opened for its sync is Io and the directory exists`() {
        val parent: File = File(root, "p").apply { assertTrue(mkdirs()) }
        makeUnreadable(parent)
        val made: String = File(parent, "new").path
        val result: FileResult<Unit> = files.createDirectories(made, durable = true)
        assertTrue(result.errorOrNull() is FileError.Io, "expected Io from the failed sync, was $result")
        assertEquals(FileKind.Directory, files.metadata(made)?.kind)
        assertEquals(FileResult.Success(Unit), files.createDirectories(File(parent, "other").path, durable = false))
    }

    @Test
    fun `freeSpaceBytes is the usable space of the volume and never its total size`() {
        val total: Long = root.totalSpace
        val usable: Long = root.usableSpace
        val gap: Long = total - usable
        assumeTrue("the volume is nearly empty: total and usable space cannot be told apart", gap > (1L shl 30))
        val free: Long = assertNotNull(files.freeSpaceBytes(root.path))
        val missing: Long = assertNotNull(files.freeSpaceBytes(File(root, "not/yet/there").path))
        // Other processes write meanwhile: allow a quarter of the gap, far below the gap itself.
        assertTrue(kotlin.math.abs(free - usable) < gap / 4, "freeSpaceBytes $free, usable $usable, total $total")
        assertTrue(kotlin.math.abs(missing - usable) < gap / 4, "nearest-ancestor answer $missing, usable $usable")
    }
}
