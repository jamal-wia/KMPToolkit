package io.github.jamal_wia.kmptoolkit.notification.testing

import io.github.jamal_wia.kmptoolkit.notification.LocalNotification
import io.github.jamal_wia.kmptoolkit.notification.NotificationAction
import io.github.jamal_wia.kmptoolkit.notification.NotificationChannelSpec
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import io.github.jamal_wia.kmptoolkit.notification.NotificationIcon
import io.github.jamal_wia.kmptoolkit.notification.NotificationOptions
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * `posted` is "every attempt", in order: what the code under test handed in at each call. A caller
 * that keeps one mutable button list or extras map and edits it between posts must not rewrite the
 * recording of the earlier posts. The real notifier reads those collections once, at the start of
 * the post (`04-api-reference.md`, coalescing), so the history it works from is the history at the
 * call; the fixture has to show the same.
 */
class RecordingNotifierSnapshotAdversarialTest {

    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")

    @Test
    fun `a button list edited after the post does not rewrite the recorded post`() = runTest {
        val notifier = RecordingNotifier()
        val buttons: MutableList<NotificationAction> = mutableListOf(NotificationAction(id = "pause", label = "Pause"))

        notifier.post("a", LocalNotification(title = "T", body = "B", channel = channel, actions = buttons))
        buttons[0] = NotificationAction(id = "resume", label = "Resume")
        notifier.post("a", LocalNotification(title = "T", body = "B", channel = channel, actions = buttons))

        assertEquals(listOf("pause", "resume"), notifier.posted.map { it.notification.actions.single().id })
    }

    @Test
    fun `an extras map edited after the post does not rewrite what was showing`() = runTest {
        val notifier = RecordingNotifier()
        val extras: MutableMap<String, String> = mutableMapOf("screen" to "downloads")

        notifier.post("a", LocalNotification(title = "T", body = "B", channel = channel, contentExtras = extras))
        extras["screen"] = "settings"

        assertEquals("downloads", notifier.showing.getValue("a").contentExtras?.get("screen"))
        assertEquals("downloads", notifier.posted.single().notification.contentExtras?.get("screen"))
    }

    @Test
    fun `a recorded notification is equal to the one passed in`() = runTest {
        val notifier = RecordingNotifier()
        val notification = LocalNotification(
            title = "T",
            body = "B",
            channel = channel,
            actions = listOf(NotificationAction(id = "pause", label = "Pause")),
            contentExtras = mapOf("screen" to "downloads"),
        )

        notifier.post("a", notification)

        assertEquals(notification, notifier.posted.single().notification)
        assertEquals(notification, notifier.showing.getValue("a"))
    }

    @Test
    fun `an actionIcons map cleared after the post does not rewrite the recorded options`() = runTest {
        val notifier = RecordingNotifier()
        val icons: MutableMap<String, NotificationIcon> = mutableMapOf("pause" to NotificationIcon.Default)

        notifier.post(
            "a",
            LocalNotification(title = "T", body = "B", channel = channel),
            NotificationOptions(actionIcons = icons),
        )
        icons.clear()

        assertEquals(setOf("pause"), notifier.posted.single().options.actionIcons.keys)
    }

    @Test
    fun `the two-argument post records the DEFAULT options as the same instance`() = runTest {
        val notifier = RecordingNotifier()

        notifier.post("a", LocalNotification(title = "T", body = "B", channel = channel))

        assertSame(NotificationOptions.DEFAULT, notifier.posted.first().options)
    }
}
