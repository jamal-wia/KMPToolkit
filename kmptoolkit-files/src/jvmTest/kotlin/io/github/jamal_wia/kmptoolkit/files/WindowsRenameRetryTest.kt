package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * R-14, Windows only: a reader that keeps the target open without FILE_SHARE_DELETE blocks the final rename. A short
 * hold is retried until the target is free; a long hold ends in AccessDenied with the target and directory unchanged.
 */
class WindowsRenameRetryTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue("needs a Windows host", TestHost.pathSyntax == PathSyntax.WINDOWS)
        root = Files.createTempDirectory("kmptoolkit-files-win").toFile()
    }

    @AfterTest
    fun tearDown() {
        if (this::root.isInitialized) root.deleteRecursively()
    }

    private fun holdOpen(target: File, millis: Long): Thread {
        val opened: CountDownLatch = CountDownLatch(1)
        val thread: Thread = Thread {
            FileInputStream(target).use {
                opened.countDown()
                Thread.sleep(millis)
            }
        }
        thread.start()
        assertTrue(opened.await(10, TimeUnit.SECONDS))
        return thread
    }

    @Test
    fun `a target held open for 100 ms is replaced after the retries`() {
        val files: FileAccess = createFileAccess()
        val target: File = File(root, "f")
        target.writeText("old")
        val holder: Thread = holdOpen(target, 100)
        val result: FileResult<Unit> = files.writeAtomically(target.path, "new".encodeToByteArray())
        holder.join(10_000)
        assertTrue(result.isSuccess, "$result")
        assertEquals("new", target.readText())
        assertEquals(listOf("f"), root.list()?.toList())
    }

    @Test
    fun `a target held open for two seconds is AccessDenied with the target unchanged and no temp left`() {
        val files: FileAccess = createFileAccess()
        val target: File = File(root, "f")
        target.writeText("old")
        val holder: Thread = holdOpen(target, 2000)
        val result: FileResult<Unit> = files.writeAtomically(target.path, "new".encodeToByteArray())
        assertTrue(result.errorOrNull() is FileError.AccessDenied, "$result")
        holder.join(10_000)
        assertEquals("old", target.readText())
        assertEquals(listOf("f"), root.list()?.toList())
    }
}
