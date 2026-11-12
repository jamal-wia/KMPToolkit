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

/** Round 8 SUSPECT R8-S4: the documented `min(max, base * 2^(n-1))` stops at `base * 2^20` whenever that is below the max. */
class BackoffExponentCapRound8SuspectTest {

    @Test
    fun `the delay keeps doubling up to the maximum as documented`() {
        val policy = io.github.jamal_wia.kmptoolkit.uploader.ExponentialBackoffRetryPolicy(
            baseDelayMillis = 10,
            maxDelayMillis = 100_000_000,
            jitterRatio = 0.0,
        )

        assertEquals(100_000_000L, policy.backoffMillis(30), "min(max, 10 * 2^29) is the maximum; the code stops at 10 * 2^20")
        assertEquals(100_000_000L, policy.backoffMillis(Int.MAX_VALUE))
    }
}
