package io.github.jamal_wia.kmptoolkit.testsupport

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assume.assumeTrue
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestFsTest {
    private val dir: File = Files.createTempDirectory("test-support-fs").toFile()

    @AfterTest
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun canCreateFileIn(target: File): Boolean = try {
        File(target, "probe-${System.nanoTime()}.bin").also { it.writeText("x") }.delete()
        true
    } catch (_: IOException) {
        false
    }

    /**
     * True on a POSIX host that ignores directory permissions (a super-user). Measured with a control
     * folder restricted directly through java.nio, NOT through the helper under test: if the helper
     * stopped restricting, the tests below must fail, not skip. Never true on Windows, where an ACL
     * DENY also binds administrators.
     */
    private fun hostIgnoresPermissions(): Boolean {
        if (TestHost.pathSyntax == PathSyntax.WINDOWS) return false
        val control: File = File(dir, "control-${System.nanoTime()}").also { it.mkdirs() }
        // path-hygiene-ignore: PH2 the control folder is restricted without the helper under test; this branch runs on POSIX hosts only
        Files.setPosixFilePermissions(control.toPath(), emptySet())
        try {
            return canCreateFileIn(control) || runCatching { control.list() }.getOrNull() != null
        } finally {
            // path-hygiene-ignore: PH2 restores the control folder on a POSIX host
            Files.setPosixFilePermissions(control.toPath(), PosixFilePermissions.fromString("rwx------"))
        }
    }

    @Test
    fun makeUnwritableBlocksCreatingAFileAndRestores() {
        val superUser: Boolean = hostIgnoresPermissions() // before the folder is restricted: it creates a control folder in it
        val restore: AutoCloseable = TestFs.makeUnwritable(dir)
        try {
            // A super-user ignores the permission; there is nothing to prove on such a host. Detected
            // independently of the helper, so a helper that restricts nothing fails here.
            assumeTrue("the host ignores directory permissions (super-user)", !superUser)
            assertFailsWith<IOException> { File(dir, "a.bin").writeText("x") }
            if (TestHost.pathSyntax == PathSyntax.POSIX) {
                // Measured on Windows: the ACL deny blocks creating a file but not a sub-directory.
                assertFailsWith<IOException> { Files.createDirectory(File(dir, "sub").toPath()) }
            }
        } finally {
            restore.close()
        }
        assertTrue(canCreateFileIn(dir), "closing the restorer must make the directory writable again")
        restore.close() // closing twice is harmless
        assertTrue(canCreateFileIn(dir))
    }

    @Test
    fun makeUnreadableBlocksReadingAndRestores() {
        val superUser: Boolean = hostIgnoresPermissions()
        val file: File = File(dir, "secret.txt").also { it.writeText("hidden") }
        val restore: AutoCloseable = TestFs.makeUnreadable(file)
        try {
            assumeTrue("the host ignores file permissions (super-user)", !superUser)
            assertFailsWith<IOException> { file.readText() }
        } finally {
            restore.close()
        }
        assertEquals("hidden", file.readText())
    }

    @Test
    fun openDescriptorCountIsPositiveOnPosixAndNullOnWindows() {
        val count: Long? = TestFs.openDescriptorCount()
        if (TestHost.pathSyntax == PathSyntax.WINDOWS) {
            assertEquals(null, count)
        } else {
            assertTrue(count != null && count > 0, "expected a positive count, was $count")
        }
    }

    @Test
    fun openDescriptorCountSeesAnOpenedFile() {
        val before: Long? = TestFs.openDescriptorCount()
        assumeTrue("the host cannot count descriptors", before != null)
        val streams: List<java.io.FileInputStream> = List(5) {
            File(dir, "f$it").also { f -> f.writeText("x") }.inputStream()
        }
        try {
            val during: Long = checkNotNull(TestFs.openDescriptorCount())
            assertTrue(during >= before!! + 5, "before=$before during=$during")
        } finally {
            streams.forEach { it.close() }
        }
    }
}
