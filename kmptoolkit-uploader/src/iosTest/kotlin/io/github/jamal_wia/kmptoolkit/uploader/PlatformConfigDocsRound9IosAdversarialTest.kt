package io.github.jamal_wia.kmptoolkit.uploader

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Round 9 (documentation as a test): the iOS rows of the "Platform" table in docs/kmptoolkit-uploader/04-api-reference.md,
 * as printed, plus every bad argument the sentences name ("both must be positive and finite").
 */
class PlatformConfigDocsRound9IosAdversarialTest {

    @Test
    fun `BackgroundTaskWakeConfig defaults are the ones the reference prints and its bad arguments are refused`() {
        val config = BackgroundTaskWakeConfig()
        assertEquals(null, config.taskIdentifier)
        assertEquals(true, config.requiresNetworkConnectivity)
        assertEquals(false, config.requiresExternalPower)
        assertEquals(5.seconds, config.engineWait)
        assertEquals(25.seconds, config.drainBudget)
        assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(taskIdentifier = "") }
        assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(taskIdentifier = "  ") }
        assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(engineWait = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(engineWait = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(drainBudget = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundTaskWakeConfig(drainBudget = (-1).seconds) }
    }

    @Test
    fun `BackgroundUploadConfig defaults are the ones the reference prints and its bad arguments are refused`() {
        val config = BackgroundUploadConfig()
        assertEquals(null, config.sessionIdentifierPrefix)
        assertEquals(60.minutes, config.lease)
        assertEquals(3.seconds, config.rehandFlushWindow)
        assertEquals(60.seconds, config.requestTimeout)
        assertEquals(7.days, config.resourceTimeout)
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(sessionIdentifierPrefix = "") }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(sessionIdentifierPrefix = "\t") }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(lease = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(lease = (-1).minutes) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(rehandFlushWindow = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(requestTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(requestTimeout = (-1).seconds) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(requestTimeout = Duration.INFINITE) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(resourceTimeout = Duration.ZERO) }
        assertFailsWith<IllegalArgumentException> { BackgroundUploadConfig(resourceTimeout = Duration.INFINITE) }
        assertEquals(Duration.ZERO, BackgroundUploadConfig(rehandFlushWindow = Duration.ZERO).rehandFlushWindow, "a zero flush window is allowed")
    }
}
