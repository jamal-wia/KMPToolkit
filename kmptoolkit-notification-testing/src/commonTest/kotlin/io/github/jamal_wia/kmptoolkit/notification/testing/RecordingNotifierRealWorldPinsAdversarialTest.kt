package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationChannelSpec
import io.github.jamal_wia.kmptoolkit.notification.NotificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlinx.coroutines.test.runTest

/**
 * Equivalence audit, double against the real notifiers: a post that the platform refuses, coalesces
 * or fails leaves what is on the tray under that id exactly as it was, for every non-`Posted` result;
 * a blank id is rejected before anything is recorded; `cancelAll` empties the tray and the next post
 * shows again.
 */
class RecordingNotifierRealWorldPinsAdversarialTest {

    private val channel = NotificationChannelSpec(id = "c", name = "C")

    private fun note(title: String): LocalNotification = LocalNotification(title = title, body = "b", channel = channel)

    @Test
    fun `no result but Posted changes what is showing under an id`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", note("old"))
        val refusals: List<NotificationResult> = listOf(
            NotificationResult.PermissionDenied,
            NotificationResult.NotificationsDisabled,
            NotificationResult.ChannelBlocked("c"),
            NotificationResult.Coalesced,
            NotificationResult.Failed(IllegalStateException("x")),
            NotificationResult.Failed(null),
        )

        for (refusal: NotificationResult in refusals) {
            notifier.result = refusal
            assertEquals(refusal, notifier.post("a", note("new")))
            assertEquals("old", notifier.showing.getValue("a").title, "after $refusal")
        }
        assertEquals(1 + refusals.size, notifier.posted.size)
    }

    @Test
    fun `a blank id is rejected before it is recorded and leaves the screen alone`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", note("old"))

        for (blank: String in listOf("", " ", "\t", " ")) {
            assertFailsWith<IllegalArgumentException> { notifier.post(blank, note("x")) }
        }

        assertEquals(1, notifier.posted.size)
        assertEquals(setOf("a"), notifier.showing.keys)
    }

    @Test
    fun `after cancelAll the next post shows again and the counters stay independent`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", note("1"))
        notifier.cancelAll()
        notifier.cancelAll()
        notifier.post("a", note("2"))

        assertEquals("2", notifier.showing.getValue("a").title)
        assertEquals(2, notifier.cancelAllCount)
        assertEquals(emptyList(), notifier.cancelled)
        assertEquals(2, notifier.posted.size)
    }
}
