package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.RecordingPermissionHandler
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [NotificationOptions] and the three public Android helpers that let a foreground service and an
 * app's startup code share the notifier's rendering: [buildForegroundNotification],
 * [notificationIdOf] and [NotificationChannels].
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotificationOptionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val shown: List<Notification>
        get() = shadowOf(manager).allNotifications

    private val permissions = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)

    private val config = NotificationConfig(minProgressInterval = Duration.ZERO)

    private fun notifier(): Notifier = createNotifier(context, permissions, config)

    private val stop = NotificationAction("stop", "Stop")

    private fun notification(
        title: String = "Title",
        actions: List<NotificationAction> = emptyList(),
        progress: NotificationProgress? = null,
    ): LocalNotification = LocalNotification(
        title = title,
        body = "Body",
        channel = NotificationChannelSpec(id = CHANNEL_ID, name = "Playback"),
        actions = actions,
        progress = progress,
    )

    private val Notification.alertsOnce: Boolean
        get() = (flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0

    // --- alertOnce ---------------------------------------------------------------------------

    @Test
    fun `the two-argument post alerts once`() = runTest {
        notifier().post("reminder", notification())

        assertTrue(shown.single().alertsOnce)
    }

    @Test
    fun `alertOnce false lets a re-post alert again`() = runTest {
        notifier().post("reminder", notification(), NotificationOptions(alertOnce = false))

        assertEquals(false, shown.single().alertsOnce)
    }

    // --- dismissAction -----------------------------------------------------------------------

    @Test
    fun `a dismiss action broadcasts the action and notification id when swiped away`() = runTest {
        notifier().post("adhan", notification(actions = listOf(stop)), NotificationOptions(dismissAction = stop))

        val deleteIntent: Intent = shadowOf(requireNotNull(shown.single().deleteIntent)).savedIntent
        assertEquals(context.packageName + ".KMPTOOLKIT_NOTIFICATION_ACTION", deleteIntent.action)
        assertEquals("stop", NotificationActionIntent.actionId(deleteIntent))
        assertEquals("adhan", NotificationActionIntent.notificationId(deleteIntent))
    }

    @Test
    fun `the dismissal and the button with the same action keep distinct pending intents`() = runTest {
        notifier().post("adhan", notification(actions = listOf(stop)), NotificationOptions(dismissAction = stop))

        val posted: Notification = shown.single()
        assertNotEquals(
            shadowOf(posted.actions[0].actionIntent).requestCode,
            shadowOf(requireNotNull(posted.deleteIntent)).requestCode,
        )
    }

    @Test
    fun `a dismiss action is not drawn as a button of its own`() = runTest {
        notifier().post("adhan", notification(), NotificationOptions(dismissAction = stop))

        val actions: Array<Notification.Action>? = shown.single().actions
        assertTrue(actions == null || actions.isEmpty())
    }

    @Test
    fun `without a dismiss action there is no delete intent`() = runTest {
        notifier().post("adhan", notification(actions = listOf(stop)))

        assertNull(shown.single().deleteIntent)
    }

    // --- mediaStyle and action icons ---------------------------------------------------------

    @Test
    fun `media style shows the first action in the collapsed row`() = runTest {
        notifier().post("adhan", notification(actions = listOf(stop)), NotificationOptions(mediaStyle = true))

        val extras = shown.single().extras
        assertTrue(extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle"))
        assertContentEquals(intArrayOf(0), extras.getIntArray(Notification.EXTRA_COMPACT_ACTIONS))
    }

    @Test
    fun `media style without actions leaves the default layout`() = runTest {
        notifier().post("adhan", notification(), NotificationOptions(mediaStyle = true))

        assertNull(shown.single().extras.getString(Notification.EXTRA_TEMPLATE))
    }

    @Test
    fun `an action icon is drawn for the action it is keyed to and no other`() = runTest {
        val retry = NotificationAction("retry", "Retry")
        notifier().post(
            "download",
            notification(actions = listOf(stop, retry)),
            NotificationOptions(
                actionIcons = mapOf("stop" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause)),
            ),
        )

        val actions: Array<Notification.Action> = shown.single().actions
        assertEquals(android.R.drawable.ic_media_pause, actions[0].getIcon()?.resId)
        assertNull(actions[1].getIcon())
    }

    // --- options are part of what coalescing compares ----------------------------------------

    @Test
    fun `a progress frame whose title changed is posted inside the same bucket`() = runTest {
        val coalescing: Notifier =
            createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

        coalescing.post("download", notification(title = "Page 1", progress = NotificationProgress.Determinate(40)))
        val result: NotificationResult =
            coalescing.post("download", notification(title = "Page 2", progress = NotificationProgress.Determinate(41)))

        assertEquals(NotificationResult.Posted, result)
        assertEquals("Page 2", shown.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `an unchanged progress frame inside the same bucket is still coalesced`() = runTest {
        val coalescing: Notifier =
            createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

        coalescing.post("download", notification(progress = NotificationProgress.Determinate(40)))
        val result: NotificationResult =
            coalescing.post("download", notification(progress = NotificationProgress.Determinate(41)))

        assertEquals(NotificationResult.Coalesced, result)
    }

    // --- the public helpers ------------------------------------------------------------------

    @Test
    fun `notificationIdOf is the id a post is shown under`() = runTest {
        notifier().post("playback", notification())

        assertNotNull(shadowOf(manager).getNotification(notificationIdOf("playback")))
    }

    @Test
    fun `notificationIdOf rejects a blank id`() {
        assertFailsWith<IllegalArgumentException> { notificationIdOf(" ") }
    }

    @Test
    fun `a foreground notification is ongoing and creates its channel without posting`() {
        val built: Notification = buildForegroundNotification(context, "playback", notification(), config)

        assertTrue((built.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull(manager.getNotificationChannel(CHANNEL_ID))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a foreground notification carries the same broadcasts a post would`() = runTest {
        val options = NotificationOptions(dismissAction = stop, mediaStyle = true)
        val built: Notification =
            buildForegroundNotification(context, "adhan", notification(actions = listOf(stop)), config, options)
        notifier().post("adhan", notification(actions = listOf(stop)), options)
        val posted: Notification = shown.single()

        assertEquals(
            shadowOf(posted.actions[0].actionIntent).requestCode,
            shadowOf(built.actions[0].actionIntent).requestCode,
        )
        assertEquals(
            shadowOf(requireNotNull(posted.deleteIntent)).requestCode,
            shadowOf(requireNotNull(built.deleteIntent)).requestCode,
        )
        assertEquals("adhan", NotificationActionIntent.notificationId(shadowOf(built.actions[0].actionIntent).savedIntent))
    }

    @Test
    fun `a foreground notification with an icon that does not resolve is rejected up front`() {
        val broken: LocalNotification = notification().copy(icon = NotificationIcon.AndroidDrawable(0x7f_ff_ff_ff))

        assertFailsWith<IllegalArgumentException> {
            buildForegroundNotification(context, "playback", broken, config)
        }
    }

    @Test
    fun `an action whose id looks like a dismissal key keeps a pending intent of its own`() = runTest {
        // "adhan:dismiss:stop" is the key a naive join would give both this button and the
        // dismissal of "stop" on "adhan".
        val lookalike = NotificationAction("dismiss:stop", "Lookalike")
        notifier().post(
            "adhan",
            notification(actions = listOf(lookalike)),
            NotificationOptions(dismissAction = stop),
        )

        val posted: Notification = shown.single()
        assertNotEquals(
            shadowOf(posted.actions[0].actionIntent).requestCode,
            shadowOf(requireNotNull(posted.deleteIntent)).requestCode,
        )
    }

    @Test
    fun `a foreground notification rejects a blank id`() {
        assertFailsWith<IllegalArgumentException> {
            buildForegroundNotification(context, "", notification(), config)
        }
    }

    @Test
    fun `channels can be created ahead of any post and deleted`() {
        val spec = NotificationChannelSpec(
            id = "reminders",
            name = "Reminders",
            importance = NotificationImportance.High,
        )

        NotificationChannels.ensure(context, spec)
        assertEquals(NotificationManager.IMPORTANCE_HIGH, manager.getNotificationChannel("reminders")?.importance)

        NotificationChannels.delete(context, "reminders")
        assertNull(manager.getNotificationChannel("reminders"))
    }

    @Test
    fun `deleting a channel that does not exist is a no-op`() {
        NotificationChannels.delete(context, "never-created")
    }

    private companion object {
        const val CHANNEL_ID = "playback"
    }
}
