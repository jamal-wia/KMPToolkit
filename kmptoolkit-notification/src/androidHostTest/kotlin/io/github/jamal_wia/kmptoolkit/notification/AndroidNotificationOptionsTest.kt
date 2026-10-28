package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * [NotificationOptions] and the public Android helpers that let a foreground service and an app's
 * startup code share the notifier's rendering: [buildForegroundNotification] with
 * [ForegroundNotificationId], and [NotificationChannels]. Plus the 2.x migration sweep.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotificationOptionsTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val shown: List<Notification>
        get() = shadowOf(manager).allNotifications

    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

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
        assertFalse(samePendingIntent(posted.actions[0].actionIntent, requireNotNull(posted.deleteIntent)))
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
    fun `a post under a foreground id updates the notification its service started with`() = runTest {
        // What the service does: startForeground(PLAYBACK.platformId, built) shows it untagged.
        manager.notify(PLAYBACK.platformId, buildForegroundNotification(context, PLAYBACK, notification(), config))

        foregroundNotifier().post("playback", notification(title = "Updated"))

        val updated: Notification = shown.single()
        assertEquals(updated, shadowOf(manager).getNotification(PLAYBACK.platformId))
        assertEquals("Updated", updated.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `cancelling a foreground id removes its service's notification`() = runTest {
        manager.notify(PLAYBACK.platformId, buildForegroundNotification(context, PLAYBACK, notification(), config))

        foregroundNotifier().cancel("playback")

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `an id that is not a foreground id never touches a foreground notification`() = runTest {
        manager.notify(PLAYBACK.platformId, buildForegroundNotification(context, PLAYBACK, notification(), config))
        val notifier: Notifier = foregroundNotifier()

        notifier.post("download", notification(title = "Download"))
        notifier.cancel("download")

        assertNotNull(shadowOf(manager).getNotification(PLAYBACK.platformId))
        assertEquals(1, shown.size)
    }

    @Test
    fun `a foreground id rejects a blank id and a zero platform id`() {
        assertFailsWith<IllegalArgumentException> { ForegroundNotificationId(" ", 1001) }
        assertFailsWith<IllegalArgumentException> { ForegroundNotificationId("playback", 0) }
    }

    @Test
    fun `foreground ids that share an id or a platform id are rejected`() {
        assertFailsWith<IllegalArgumentException> {
            createNotifier(
                context,
                permissions,
                config,
                setOf(PLAYBACK, ForegroundNotificationId("playback", PLAYBACK.platformId + 1)),
            )
        }
        assertFailsWith<IllegalArgumentException> {
            createNotifier(context, permissions, config, setOf(PLAYBACK, ForegroundNotificationId("radio", PLAYBACK.platformId)))
        }
    }

    @Test
    fun `a foreground notification is ongoing and creates its channel without posting`() {
        val built: Notification = buildForegroundNotification(context, PLAYBACK, notification(), config)

        assertTrue((built.flags and Notification.FLAG_ONGOING_EVENT) != 0)
        assertNotNull(manager.getNotificationChannel(CHANNEL_ID))
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a foreground notification carries the same broadcasts a post would`() = runTest {
        val options = NotificationOptions(dismissAction = stop, mediaStyle = true)
        val adhan = ForegroundNotificationId(id = "adhan", platformId = 2002)
        val built: Notification =
            buildForegroundNotification(context, adhan, notification(actions = listOf(stop)), config, options)
        notifier().post("adhan", notification(actions = listOf(stop)), options)
        val posted: Notification = shown.single()

        assertTrue(samePendingIntent(posted.actions[0].actionIntent, built.actions[0].actionIntent))
        assertTrue(samePendingIntent(requireNotNull(posted.deleteIntent), requireNotNull(built.deleteIntent)))
        assertEquals("adhan", NotificationActionIntent.notificationId(shadowOf(built.actions[0].actionIntent).savedIntent))
    }

    @Test
    fun `a foreground notification with an icon that does not resolve is rejected up front`() {
        val broken: LocalNotification = notification().copy(icon = NotificationIcon.AndroidDrawable(0x7f_ff_ff_ff))

        assertFailsWith<IllegalArgumentException> {
            buildForegroundNotification(context, PLAYBACK, broken, config)
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
        assertFalse(samePendingIntent(posted.actions[0].actionIntent, requireNotNull(posted.deleteIntent)))
    }

    // --- migrating from 2.x ------------------------------------------------------------------

    @Test
    fun `cancel removes what 2_x showed for the id under its hashed platform id`() = runTest {
        manager.notify(legacyPlatformNotificationId("download"), legacyNotification())

        notifier().cancel("download")

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `the first post of an id replaces what 2_x showed for it instead of stacking`() = runTest {
        manager.notify(legacyPlatformNotificationId("download"), legacyNotification())

        notifier().post("download", notification(title = "Download"))

        assertEquals("Download", shown.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `the sweep spares a foreground notification whose platform id equals the old hashed one`() = runTest {
        // An app that kept startForeground's old number by choosing notificationIdOf's value.
        val kept = ForegroundNotificationId("playback", legacyPlatformNotificationId("playback"))
        manager.notify(kept.platformId, buildForegroundNotification(context, kept, notification(), config))

        createNotifier(context, permissions, config, setOf(kept)).post("playback", notification(title = "Updated"))

        assertEquals("Updated", shown.single().extras.getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    @Test
    fun `the hashed 2_x platform id is still computed exactly as 2_x did`() {
        // "" hashes to 0, which 2.x moved to 1; this one hashes negative, and 2.x cleared the sign bit.
        val negativeHash = "chat_thread_id_from_push"
        assertTrue(negativeHash.hashCode() < 0)

        assertEquals(negativeHash.hashCode() and 0x7FFFFFFF, legacyPlatformNotificationId(negativeHash))
        assertEquals(1, legacyPlatformNotificationId(""))
        assertEquals("download".hashCode(), legacyPlatformNotificationId("download"))
    }

    /** A notification as 2.x left it in the tray: untagged, under the hashed id. */
    private fun legacyNotification(): Notification {
        NotificationChannels.ensure(context, NotificationChannelSpec(id = CHANNEL_ID, name = "Playback"))
        return androidx.core.app.NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("From 2.x")
            .build()
    }

    private fun foregroundNotifier(): Notifier = createNotifier(context, permissions, config, setOf(PLAYBACK))

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
        val PLAYBACK = ForegroundNotificationId(id = "playback", platformId = 1001)
        const val CHANNEL_ID = "playback"
    }
}

/**
 * Whether [first] and [second] are one `PendingIntent` as the platform decides it: the same request
 * code and intents that are `filterEquals` — extras do not count. Two `PendingIntent`s this module
 * creates for different purposes must never be one, or `FLAG_UPDATE_CURRENT` makes one rewrite the
 * other's extras.
 */
internal fun samePendingIntent(first: PendingIntent, second: PendingIntent): Boolean =
    shadowOf(first).requestCode == shadowOf(second).requestCode &&
        shadowOf(first).savedIntent.filterEquals(shadowOf(second).savedIntent)
