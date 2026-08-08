package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationChannelSpec
import io.github.jamal_wia.kmptoolkit.notification.NotificationProgress
import io.github.jamal_wia.kmptoolkit.notification.NotificationResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/**
 * The fixture's own contract, from its KDoc: it records every attempt, it tracks what would be on
 * screen, and the two are not the same list.
 */
class RecordingNotifierTest {

    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")

    private fun notification(title: String = "T"): LocalNotification =
        LocalNotification(title = title, body = "B", channel = channel)

    @Test
    fun `it reports Posted by default`() = runTest {
        assertEquals(NotificationResult.Posted, RecordingNotifier().post("a", notification()))
    }

    @Test
    fun `every post is recorded with its id in order`() = runTest {
        val notifier = RecordingNotifier()

        notifier.post("a", notification("first"))
        notifier.post("b", notification("second"))

        assertEquals(listOf("a", "b"), notifier.posted.map { it.id })
        assertEquals(listOf("first", "second"), notifier.posted.map { it.notification.title })
    }

    @Test
    fun `a refused post is still recorded but never reaches the screen`() = runTest {
        val notifier = RecordingNotifier(result = NotificationResult.PermissionDenied)

        val result: NotificationResult = notifier.post("a", notification())

        assertEquals(NotificationResult.PermissionDenied, result)
        assertEquals(1, notifier.posted.size)
        assertTrue(notifier.showing.isEmpty())
    }

    @Test
    fun `a blank id is rejected exactly as the real notifiers reject it`() = runTest {
        val notifier = RecordingNotifier()

        assertFailsWith<IllegalArgumentException> { notifier.post("", notification()) }

        assertTrue(notifier.posted.isEmpty(), "a rejected call is not a call the code made")
    }

    @Test
    fun `a blocked channel is reported verbatim`() = runTest {
        val notifier = RecordingNotifier(result = NotificationResult.ChannelBlocked("downloads"))

        assertEquals(
            NotificationResult.ChannelBlocked("downloads"),
            notifier.post("a", notification()),
        )
    }

    @Test
    fun `a coalesced post leaves the previous frame showing`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", notification("50%"))
        notifier.result = NotificationResult.Coalesced

        notifier.post("a", notification("51%"))

        assertEquals(2, notifier.posted.size)
        assertEquals("50%", notifier.showing.getValue("a").title)
    }

    @Test
    fun `re-posting an id replaces what is showing`() = runTest {
        val notifier = RecordingNotifier()

        notifier.post("a", notification("first"))
        notifier.post("a", notification("second"))

        assertEquals(1, notifier.showing.size)
        assertEquals("second", notifier.showing.getValue("a").title)
        assertEquals(2, notifier.posted.size, "both attempts are still in the log")
    }

    @Test
    fun `cancelling removes one and records the id`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", notification())
        notifier.post("b", notification())

        notifier.cancel("a")

        assertEquals(setOf("b"), notifier.showing.keys)
        assertEquals(listOf("a"), notifier.cancelled)
    }

    @Test
    fun `cancelling an id that is not showing is recorded and changes nothing else`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", notification())

        notifier.cancel("never-posted")

        assertEquals(listOf("never-posted"), notifier.cancelled)
        assertEquals(setOf("a"), notifier.showing.keys)
    }

    @Test
    fun `cancelAll clears the screen and is counted`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", notification())
        notifier.post("b", notification())

        notifier.cancelAll()

        assertTrue(notifier.showing.isEmpty())
        assertEquals(1, notifier.cancelAllCount)
        assertEquals(2, notifier.posted.size, "cancelAll does not erase the log")
    }

    @Test
    fun `the result can change mid-test`() = runTest {
        val notifier = RecordingNotifier()
        assertEquals(NotificationResult.Posted, notifier.post("a", notification()))

        notifier.result = NotificationResult.NotificationsDisabled

        assertEquals(NotificationResult.NotificationsDisabled, notifier.post("b", notification()))
        assertEquals(setOf("a"), notifier.showing.keys)
    }

    @Test
    fun `clear resets the log and the screen and the counters but not the result`() = runTest {
        val notifier = RecordingNotifier(result = NotificationResult.Coalesced)
        notifier.post("a", notification())
        notifier.cancel("a")
        notifier.cancelAll()

        notifier.clear()

        assertTrue(notifier.posted.isEmpty())
        assertTrue(notifier.showing.isEmpty())
        assertTrue(notifier.cancelled.isEmpty())
        assertEquals(0, notifier.cancelAllCount)
        assertEquals(NotificationResult.Coalesced, notifier.result)
    }

    @Test
    fun `the exposed collections are snapshots`() = runTest {
        val notifier = RecordingNotifier()
        notifier.post("a", notification())
        val postedBefore: List<PostedNotification> = notifier.posted
        val showingBefore: Map<String, LocalNotification> = notifier.showing

        notifier.post("b", notification())

        assertEquals(1, postedBefore.size)
        assertEquals(1, showingBefore.size)
    }

    @Test
    fun `progress is carried through untouched because the double coalesces nothing`() = runTest {
        val notifier = RecordingNotifier()

        notifier.post("a", notification().copy(progress = NotificationProgress.Determinate(40)))
        notifier.post("a", notification().copy(progress = NotificationProgress.Determinate(41)))

        assertEquals(2, notifier.posted.size)
        assertEquals(
            NotificationProgress.Determinate(41),
            notifier.showing.getValue("a").progress,
        )
    }
}
