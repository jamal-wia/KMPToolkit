package io.github.jamal_wia.kmptoolkit.testsupport

import java.io.File
import java.io.IOException
import java.nio.file.Files
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

    @Test
    fun makeUnwritableBlocksCreatingAFileAndRestores() {
        val restore: AutoCloseable = TestFs.makeUnwritable(dir)
        try {
            // A super-user ignores the permission; there is nothing to prove on such a host.
            assumeTrue("the host ignores directory permissions (super-user)", !canCreateFileIn(dir))
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
        val file: File = File(dir, "secret.txt").also { it.writeText("hidden") }
        val restore: AutoCloseable = TestFs.makeUnreadable(file)
        try {
            val readable: Boolean = runCatching { file.readText() }.isSuccess
            assumeTrue("the host ignores file permissions (super-user)", !readable)
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
