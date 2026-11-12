@file:OptIn(io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.files.internal.NioFileSystemOps
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** SUSPECT G6 live: a reader that holds a file through the extended-length spelling must make a plain-spelling replace wait, not fail. */
class WindowsExtendedSpellingGateR1Test {
    @Test
    fun `a replace waits for a reader that holds the same file under another spelling`() {
        if (File.separatorChar != '\\') return
        val root: String = Files.createTempDirectory("kmptoolkit-winr1gate").toRealPath().toString()
        try {
            val plain: String = "$root\\f"
            val fs: FileAccess = createFileAccess()
            assertTrue(fs.writeAtomically(plain, "old".encodeToByteArray()).isSuccess)
            val ops: NioFileSystemOps = NioFileSystemOps()
            val opened: CountDownLatch = CountDownLatch(1)
            val reader: Thread = thread {
                val handle = ops.openForRead("\\\\?\\$plain")
                opened.countDown()
                Thread.sleep(700)
                handle.close()
            }
            assertTrue(opened.await(10, TimeUnit.SECONDS))
            val result: FileResult<Unit> = fs.writeAtomically(plain, "new".encodeToByteArray())
            reader.join(10_000)
            assertTrue(result.isSuccess, "the replace failed instead of waiting: $result")
            assertEquals("new", fs.readBytes(plain, 10).getOrNull()?.decodeToString())
        } finally {
            File(root).deleteRecursively()
        }
    }
}
