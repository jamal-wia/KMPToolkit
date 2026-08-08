package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * [NotificationConfig]'s contract: sane defaults, a broadcast action derived from the consumer's
 * own application id, and construction that fails rather than accepting a value which would only
 * misbehave later.
 */
class NotificationConfigTest {

    @Test
    fun `the defaults leave the broadcast action to be derived`() {
        val config = NotificationConfig()

        assertNull(config.actionBroadcastAction)
        assertEquals(10, config.progressBucketPercent)
        assertEquals(500.milliseconds, config.minProgressInterval)
    }

    @Test
    fun `a null broadcast action resolves against the application id`() {
        val resolved: String = NotificationConfig().resolveBroadcastAction("com.example.app")

        assertEquals("com.example.app.KMPTOOLKIT_NOTIFICATION_ACTION", resolved)
    }

    @Test
    fun `two applications never resolve to the same broadcast action`() {
        val config = NotificationConfig()

        assertEquals(
            false,
            config.resolveBroadcastAction("com.example.a") ==
                config.resolveBroadcastAction("com.example.b"),
        )
    }

    @Test
    fun `an explicit broadcast action is used verbatim`() {
        val config = NotificationConfig(actionBroadcastAction = "com.example.MY_ACTION")

        assertEquals("com.example.MY_ACTION", config.resolveBroadcastAction("com.example.app"))
    }

    @Test
    fun `a blank broadcast action is rejected`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationConfig(actionBroadcastAction = "  ")
        }
    }

    @Test
    fun `a bucket outside 1 to 100 is rejected`() {
        assertFailsWith<IllegalArgumentException> { NotificationConfig(progressBucketPercent = 0) }
        assertFailsWith<IllegalArgumentException> { NotificationConfig(progressBucketPercent = -10) }
        assertFailsWith<IllegalArgumentException> { NotificationConfig(progressBucketPercent = 101) }
    }

    @Test
    fun `the extreme bucket widths are both accepted`() {
        assertEquals(1, NotificationConfig(progressBucketPercent = 1).progressBucketPercent)
        assertEquals(100, NotificationConfig(progressBucketPercent = 100).progressBucketPercent)
    }

    @Test
    fun `a negative interval is rejected while zero is allowed`() {
        assertFailsWith<IllegalArgumentException> {
            NotificationConfig(minProgressInterval = (-1).seconds)
        }
        assertEquals(
            Duration.ZERO,
            NotificationConfig(minProgressInterval = Duration.ZERO).minProgressInterval,
        )
    }
}
