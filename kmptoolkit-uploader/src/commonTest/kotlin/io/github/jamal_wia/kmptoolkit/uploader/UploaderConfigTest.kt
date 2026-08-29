package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

/** [UploaderConfig]'s validation and the one knob whose effect is observable from the outside. */
class UploaderConfigTest {

    @Test
    fun `the defaults are the documented ones`() {
        val config = UploaderConfig()

        assertEquals(30.seconds, config.heartbeatInterval)
        assertEquals(50.milliseconds, config.minAlarmDelay)
        assertEquals(500.milliseconds, config.drainPollInterval)
        assertEquals(2, config.clockAnomalyFactor)
    }

    @Test
    fun `a non-positive heartbeat interval is rejected`() {
        assertFailsWith<IllegalArgumentException> { UploaderConfig(heartbeatInterval = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { UploaderConfig(heartbeatInterval = (-1).seconds) }
    }

    @Test
    fun `a non-positive minimum alarm delay is rejected`() {
        assertFailsWith<IllegalArgumentException> { UploaderConfig(minAlarmDelay = Duration.ZERO) }
    }

    @Test
    fun `a non-positive drain poll interval is rejected`() {
        assertFailsWith<IllegalArgumentException> { UploaderConfig(drainPollInterval = Duration.ZERO) }
    }

    @Test
    fun `a clock anomaly factor below one is rejected`() {
        assertFailsWith<IllegalArgumentException> { UploaderConfig(clockAnomalyFactor = 0) }
        assertFailsWith<IllegalArgumentException> { UploaderConfig(clockAnomalyFactor = -2) }
    }

    @Test
    fun `a larger clock anomaly factor tolerates a further-off gate`() = runTest {
        val handler = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1_000L))
        val gate: Long = 5_000L // 5x the policy maximum
        fun store(): TestUploaderStore = TestUploaderStore(
            listOf(
                UploaderItem(
                    id = "a",
                    type = handler.type,
                    payload = "p",
                    schemaVersion = 1,
                    uniqueKey = null,
                    orderingKey = null,
                    tag = null,
                    state = UploaderItemState.PENDING,
                    attempts = 0,
                    nextRunAtEpochMillis = gate,
                    createdAtEpochMillis = 0L,
                    lastError = null,
                ),
            ),
        )

        val strict = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1_000L))
        testEngine(
            store(),
            listOf(strict),
            backgroundScope,
            clock = TestClock(millis = 0L),
            config = UploaderConfig(clockAnomalyFactor = 2),
        ).drain()
        assertEquals(1, strict.attempts.size, "2x1000ms cannot reach a 5000ms gate — treat as a jump")

        val tolerant = TestHandler(retryPolicy = FixedRetryPolicy(delayMillis = 1_000L))
        testEngine(
            store(),
            listOf(tolerant),
            backgroundScope,
            clock = TestClock(millis = 0L),
            config = UploaderConfig(clockAnomalyFactor = 10),
        ).drain()
        assertEquals(0, tolerant.attempts.size, "10x1000ms can reach it — honor the gate")
    }

    @Test
    fun `a longer heartbeat interval means fewer safety-net drains`() = runTest {
        val handler = TestHandler()
        val store = TestUploaderStore()
        val engine: UploaderEngine = testEngine(
            store,
            listOf(handler),
            backgroundScope,
            config = UploaderConfig(heartbeatInterval = 5.minutes),
        )
        engine.start()
        runCurrent()
        store.insertKeep(
            UploaderItem(
                id = "sneaked",
                type = handler.type,
                payload = "p",
                schemaVersion = 1,
                uniqueKey = null,
                orderingKey = null,
                tag = null,
                state = UploaderItemState.PENDING,
                attempts = 0,
                nextRunAtEpochMillis = 0L,
                createdAtEpochMillis = 0L,
                lastError = null,
            ),
        )

        advanceTimeBy(1.minutes)
        runCurrent()

        assertEquals(0, handler.attempts.size, "the heartbeat is five minutes away")
    }
}
