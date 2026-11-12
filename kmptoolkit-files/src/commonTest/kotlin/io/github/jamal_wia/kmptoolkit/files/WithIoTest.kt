@file:OptIn(ToolkitInternalApi::class)

package io.github.jamal_wia.kmptoolkit.files

import io.github.jamal_wia.kmptoolkit.core.ToolkitInternalApi
import io.github.jamal_wia.kmptoolkit.files.spi.fileAccessOver
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** [withIo]: the block runs on the dispatcher with a live check, and a cancelled caller gets CancellationException. */
class WithIoTest {

    @Test
    fun `withIo returns the value of the block and hands it a check that is not cancelled`() = runTest {
        val files: FileAccess = fileAccessOver(TestEnv.realOps())
        val cancelled: Boolean = files.withIo { check: CancellationCheck -> check.isCancelled() }
        assertFalse(cancelled)
        assertEquals("v", files.withIo { "v" })
    }

    @Test
    fun `the block runs with this instance as its receiver`() = runTest {
        val files: FileAccess = fileAccessOver(TestEnv.realOps())
        assertTrue(files.withIo { this === files })
    }

    @Test
    fun `a cancelled caller gets CancellationException and the check turned true`() = runTest(timeout = 60.seconds) {
        val files: FileAccess = fileAccessOver(TestEnv.realOps())
        val started: CompletableDeferred<Unit> = CompletableDeferred()
        var sawCancel: Boolean = false
        val job = async(Dispatchers.Default) {
            files.withIo { check: CancellationCheck ->
                started.complete(Unit)
                val begin = TimeSource.Monotonic.markNow()
                while (!check.isCancelled() && begin.elapsedNow() < 20.seconds) Unit
                sawCancel = check.isCancelled()
            }
        }
        started.await()
        job.cancel()
        assertFailsWith<CancellationException> { job.await() }
        assertTrue(sawCancel, "the block must see the cancellation through its check")
    }
}
