package io.github.jamal_wia.kmptoolkit.notification

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlinx.coroutines.test.runTest

/**
 * [noOpNotifier]'s contract: it is the instance you inject when notifications are off, so it must
 * be safe to call unconditionally and must say why nothing appeared.
 */
class NoOpNotifierTest {

    private val notification = LocalNotification(
        title = "T",
        body = "B",
        channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
    )

    @Test
    fun `posting reports that notifications are disabled`() = runTest {
        assertEquals(
            NotificationResult.NotificationsDisabled,
            noOpNotifier().post("a", notification),
        )
    }

    @Test
    fun `cancelling anything is a no-op`() = runTest {
        val notifier: Notifier = noOpNotifier()
        notifier.post("a", notification)

        notifier.cancel("a")
        notifier.cancel("never-posted")
        notifier.cancelAll()

        // Still the same answer afterwards: the instance carries no state to corrupt.
        assertEquals(NotificationResult.NotificationsDisabled, notifier.post("a", notification))
    }

    @Test
    fun `the instance is shared rather than allocated per call`() {
        assertSame(noOpNotifier(), noOpNotifier())
    }

    @Test
    fun `a blank id is rejected even where nothing would be posted`() = runTest {
        // Otherwise the bug hides in exactly the configuration that posts nothing, and surfaces as
        // a process death on iOS the day notifications are switched back on.
        assertFailsWith<IllegalArgumentException> { noOpNotifier().post("", notification) }
        assertFailsWith<IllegalArgumentException> { noOpNotifier().post("   ", notification) }
    }
}
