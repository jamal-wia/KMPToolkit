package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import io.github.jamal_wia.kmptoolkit.files.spi.ReadableFile
import io.github.jamal_wia.kmptoolkit.testsupport.PathSyntax
import io.github.jamal_wia.kmptoolkit.testsupport.TestHost
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Assume

/**
 * R-16 (Windows only, ops level): a replace waits, inside the process, for a reader of the library to close. Ordered
 * by thread state and the reader's close, never by sleeping.
 */
@OptIn(ToolkitInternalApi::class)
class WindowsGateTest {
    private lateinit var root: File

    @BeforeTest
    fun setUp() {
        Assume.assumeTrue("needs a Windows host", TestHost.pathSyntax == PathSyntax.WINDOWS)
        root = Files.createTempDirectory("kmptoolkit-files-gate").toFile()
    }

    @AfterTest
    fun tearDown() {
        if (this::root.isInitialized) root.deleteRecursively()
    }

    @Test
    fun `a rename onto a path held open by a reader of this library waits for its close and then succeeds`() {
        val ops: NioFileSystemOps = NioFileSystemOps()
        val target: File = File(root, "target")
        val temp: File = File(root, "temp")
        target.writeText("old")
        temp.writeText("new")
        val reader: ReadableFile = ops.openForRead(target.path)
        val returned: AtomicBoolean = AtomicBoolean(false)
        var failure: Throwable? = null
        val renamer: Thread = thread {
            try {
                ops.rename(temp.path, target.path)
            } catch (t: Throwable) {
                failure = t
            }
            returned.set(true)
        }
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (renamer.state != Thread.State.WAITING) {
            check(System.nanoTime() < deadline) { "the rename never blocked on the reader: ${renamer.state}" }
            Thread.yield()
        }
        assertFalse(returned.get(), "the rename must not return while the reader is open")
        reader.close()
        renamer.join(20_000)
        assertTrue(returned.get())
        assertEquals(null, failure)
        assertEquals("new", target.readText())
    }

    @Test
    fun `readers that never pause cannot make a writer of the library fail`() {
        val files: FileAccess = createFileAccess()
        val target: String = File(root, "shared").path
        assertTrue(files.writeAtomically(target, ByteArray(64) { 1 }).isSuccess)
        val stop: AtomicBoolean = AtomicBoolean(false)
        val readers: List<Thread> = List(4) {
            thread { while (!stop.get()) files.readBytes(target, 1L shl 20) }
        }
        try {
            repeat(100) { assertTrue(files.writeAtomically(target, ByteArray(64) { 2 }).isSuccess, "write $it") }
        } finally {
            stop.set(true)
            readers.forEach { it.join(20_000) }
        }
    }
}
