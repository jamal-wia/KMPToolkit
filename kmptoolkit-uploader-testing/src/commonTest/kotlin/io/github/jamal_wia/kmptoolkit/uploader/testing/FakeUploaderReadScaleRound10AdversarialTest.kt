package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * Round 10 (compatibility with 4.0.0, performance): in 4.0.0 `FakeUploader.enqueued` was a read of one stored list. A test
 * that asserts after every call (`assertEquals(n, fake.enqueued.size)` in a loop) was linear overall. Since the log became a
 * chain, each read materializes the whole list: the natural loop is quadratic again, the cost round 8 removed from the write.
 */
class FakeUploaderReadScaleRound10AdversarialTest {

    private class Follow : UploaderHandler<String> {
        override val type: String = "follow"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Success
    }

    @Test
    fun `asserting the recorded count after every one of thirty thousand enqueues stays fast on every platform`() = runTest {
        val fake = FakeUploader()
        val follow = Follow()
        val millis: Long = withContext(Dispatchers.Default) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(30_000) { index ->
                fake.enqueue(follow, "p$index")
                assertEquals(index + 1, fake.enqueued.size)
            }
            mark.elapsedNow().inWholeMilliseconds
        }
        assertTrue(millis < 10_000, "30000 enqueues each followed by a read of fake.enqueued took $millis ms")
    }

    @Test
    fun `reading the last recorded call after every one of thirty thousand cancels stays fast on every platform`() = runTest {
        val fake = FakeUploader()
        val millis: Long = withContext(Dispatchers.Default) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(30_000) { index ->
                fake.cancel("id-$index")
                assertEquals("id-$index", fake.cancelled.last())
            }
            mark.elapsedNow().inWholeMilliseconds
        }
        assertTrue(millis < 10_000, "30000 cancels each followed by a read of fake.cancelled took $millis ms")
    }
}
