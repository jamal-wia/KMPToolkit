package io.github.jamal_wia.kmptoolkit.uploader.testing

import io.github.jamal_wia.kmptoolkit.uploader.AttemptContext
import io.github.jamal_wia.kmptoolkit.uploader.AttemptResult
import io.github.jamal_wia.kmptoolkit.uploader.UploadAttempt
import io.github.jamal_wia.kmptoolkit.uploader.UploadGateway
import io.github.jamal_wia.kmptoolkit.uploader.UploadResult
import io.github.jamal_wia.kmptoolkit.uploader.UploaderEngineRegistry
import io.github.jamal_wia.kmptoolkit.uploader.UploaderHandler
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItem
import io.github.jamal_wia.kmptoolkit.uploader.UploaderItemState
import io.github.jamal_wia.kmptoolkit.uploader.createUploaderEngine
import io.github.jamal_wia.kmptoolkit.uploader.spi.UploaderStore
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

/** Round 8 SUSPECT R8-S8: FakeUploader copies its whole call list on every enqueue, quadratic; 30,000 calls take 27 s on iOS. */
class FakeUploaderScaleRound8SuspectTest {

    private class Follow : UploaderHandler<String> {
        override val type: String = "follow"
        override fun encodePayload(payload: String): String = payload
        override fun decodePayload(raw: String): String = raw
        override suspend fun execute(context: AttemptContext, payload: String): AttemptResult = AttemptResult.Success
    }

    @Test
    fun `thirty thousand FakeUploader enqueues finish in a few seconds on every platform`() = runTest {
        val fake = FakeUploader()
        val follow = Follow()
        val fakeMillis: Long = withContext(Dispatchers.Default) {
            val mark = TimeSource.Monotonic.markNow()
            repeat(30_000) { fake.enqueue(follow, "p$it") }
            mark.elapsedNow().inWholeMilliseconds
        }
        assertEquals(30_000, fake.enqueued.size)
        assertTrue(fakeMillis < 10_000, "30000 FakeUploader enqueues took $fakeMillis ms")

    }

}
