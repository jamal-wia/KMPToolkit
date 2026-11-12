package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Round 9 (documentation as a test): the Android rows of the "Platform" table in docs/kmptoolkit-uploader/04-api-reference.md
 * ("WorkManagerWakeConfig(uniqueWorkName = null, requiresNetwork = true, initialBackoff = 30.seconds, drainBudget = 1.minutes,
 * engineWait = 5.seconds)" and the UploadTransportConfig row), as printed, plus every bad argument.
 */
class PlatformConfigDocsRound9AdversarialTest {

    @Test
    fun `WorkManagerWakeConfig defaults are the ones the reference prints`() {
        val config = WorkManagerWakeConfig()
        assertEquals(null, config.uniqueWorkName)
        assertEquals(true, config.requiresNetwork)
        assertEquals(30.seconds, config.initialBackoff)
        assertEquals(1.minutes, config.drainBudget)
        assertEquals(5.seconds, config.engineWait)
        assertEquals(config, WorkManagerWakeConfig(null, true, 30.seconds, 1.minutes, 5.seconds))
    }

    @Test
    fun `WorkManagerWakeConfig refuses a blank name a backoff below WorkManager's floor and non-positive waits`() {
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(uniqueWorkName = "") }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(uniqueWorkName = "  ") }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(uniqueWorkName = "\t\n") }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(initialBackoff = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(initialBackoff = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(initialBackoff = 9_999.milliseconds) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(drainBudget = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(drainBudget = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(engineWait = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { WorkManagerWakeConfig(engineWait = (-5).seconds) }
        assertEquals(10.seconds, WorkManagerWakeConfig(initialBackoff = 10.seconds).initialBackoff, "exactly the floor is accepted")
        assertEquals(1.milliseconds, WorkManagerWakeConfig(drainBudget = 1.milliseconds, engineWait = 1.milliseconds).engineWait)
    }

    @Test
    fun `UploadTransportConfig defaults are the ones the reference prints`() {
        val config = UploadTransportConfig()
        assertEquals(null, config.uniqueWorkNamePrefix)
        assertEquals(null, config.workTag)
        assertEquals(true, config.requiresNetwork)
        assertEquals(15.minutes.inWholeMilliseconds, config.leaseMillis)
        assertEquals(30.seconds, config.workBackoff)
        assertEquals(5.seconds, config.engineWait)
        assertEquals(30_000, config.connectTimeoutMillis)
        assertEquals(60_000, config.readTimeoutMillis)
    }

    @Test
    fun `UploadTransportConfig refuses blank names a non-positive lease a backoff below the floor and non-positive timeouts`() {
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(uniqueWorkNamePrefix = "") }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(uniqueWorkNamePrefix = " ") }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workTag = "") }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workTag = "\t") }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(leaseMillis = 0L) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(leaseMillis = -1L) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(leaseMillis = Long.MIN_VALUE) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(workBackoff = 9.seconds) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(engineWait = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(connectTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(connectTimeoutMillis = -1) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(readTimeoutMillis = 0) }
        assertFailsWith<IllegalArgumentException> { UploadTransportConfig(readTimeoutMillis = Int.MIN_VALUE) }
        assertEquals(Long.MAX_VALUE, UploadTransportConfig(leaseMillis = Long.MAX_VALUE).leaseMillis)
        assertEquals(1, UploadTransportConfig(connectTimeoutMillis = 1, readTimeoutMillis = 1).readTimeoutMillis)
    }
}
