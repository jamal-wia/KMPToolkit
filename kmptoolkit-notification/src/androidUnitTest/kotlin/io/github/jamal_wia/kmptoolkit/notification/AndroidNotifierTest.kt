package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.Permission
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.RecordingPermissionHandler
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * The Android notifier against a real `NotificationManager`.
 *
 * The cases here are the ones the platform answers with silence — a missing permission,
 * notifications switched off, a channel the user muted, an icon that does not resolve — because
 * turning that silence into a [NotificationResult] is the module's reason to exist. The rest pin
 * down the identity rules (one id, one notification) and the action/tap wiring.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val manager: NotificationManager
        get() = context.getSystemService(NotificationManager::class.java)

    private val shown: List<Notification>
        get() = shadowOf(manager).allNotifications

    private val permissions = RecordingPermissionHandler(defaultStatus = PermissionStatus.Granted)

    private fun notifier(
        config: NotificationConfig = NotificationConfig(minProgressInterval = Duration.ZERO),
    ): Notifier = createNotifier(context, permissions, config)

    private fun notification(
        title: String = "Title",
        body: String = "Body",
        channel: NotificationChannelSpec = NotificationChannelSpec(
            id = CHANNEL_ID,
            name = "Downloads",
            description = "Files being downloaded",
            importance = NotificationImportance.Low,
            sound = NotificationSound.Silent,
        ),
    ): LocalNotification = LocalNotification(title = title, body = body, channel = channel)

    // --- the happy path ----------------------------------------------------------------------

    @Test
    fun `posting shows one notification and reports Posted`() = runTest {
        val result: NotificationResult = notifier().post("download", notification())

        assertEquals(NotificationResult.Posted, result)
        assertEquals(1, shown.size)
    }

    @Test
    fun `the channel is created from the spec on first post`() = runTest {
        notifier().post("download", notification())

        val channel: NotificationChannel = manager.getNotificationChannel(CHANNEL_ID)
        assertEquals("Downloads", channel.name.toString())
        assertEquals("Files being downloaded", channel.description)
        assertEquals(NotificationManager.IMPORTANCE_LOW, channel.importance)
        assertNull(channel.sound, "a Silent channel must not carry a sound")
    }

    @Test
    fun `a custom sound is registered on the channel as a raw resource`() = runTest {
        notifier().post(
            "chat",
            notification(
                channel = NotificationChannelSpec(
                    id = "chat",
                    name = "Chat",
                    sound = NotificationSound.Custom("chat_message"),
                ),
            ),
        )

        val channel: NotificationChannel = manager.getNotificationChannel("chat")
        assertTrue(
            channel.sound.toString().contains("raw/chat_message"),
            "expected a res/raw uri, was ${channel.sound}",
        )
    }

    @Test
    fun `re-posting the same id replaces rather than stacks`() = runTest {
        val notifier: Notifier = notifier()

        notifier.post("download", notification(body = "10%"))
        notifier.post("download", notification(body = "20%"))

        assertEquals(1, shown.size)
    }

    @Test
    fun `different ids produce different notifications`() = runTest {
        val notifier: Notifier = notifier()

        notifier.post("a", notification())
        notifier.post("b", notification())

        assertEquals(2, shown.size)
    }

    // --- the ways a post fails ---------------------------------------------------------------

    @Test
    fun `a missing permission is reported and nothing is posted`() = runTest {
        permissions.setStatus(Permission.NOTIFICATIONS, PermissionStatus.Denied())

        val result: NotificationResult = notifier().post("download", notification())

        assertEquals(NotificationResult.PermissionDenied, result)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a permanently denied permission is reported the same way`() = runTest {
        permissions.setStatus(Permission.NOTIFICATIONS, PermissionStatus.PermanentlyDenied)

        assertEquals(NotificationResult.PermissionDenied, notifier().post("d", notification()))
    }

    @Test
    fun `the permission is checked rather than requested`() = runTest {
        notifier().post("download", notification())

        assertEquals(listOf(Permission.NOTIFICATIONS), permissions.checks)
        assertEquals(emptyList(), permissions.requests, "the module must never show a prompt")
    }

    @Test
    fun `notifications switched off app-wide are reported and nothing is posted`() = runTest {
        shadowOf(manager).setNotificationsEnabled(false)

        val result: NotificationResult = notifier().post("download", notification())

        assertEquals(NotificationResult.NotificationsDisabled, result)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a blocked channel is reported by id and nothing is posted`() = runTest {
        blockChannel(CHANNEL_ID)

        val result: NotificationResult = notifier().post("download", notification())

        assertEquals(NotificationResult.ChannelBlocked(CHANNEL_ID), result)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a blocked channel does not block a different one`() = runTest {
        blockChannel(CHANNEL_ID)

        val result: NotificationResult = notifier().post(
            "chat",
            notification(channel = NotificationChannelSpec(id = "chat", name = "Chat")),
        )

        assertEquals(NotificationResult.Posted, result)
    }

    @Test
    fun `an icon that does not resolve is reported as a failure instead of crashing`() = runTest {
        val result: NotificationResult = notifier().post(
            "download",
            notification().copy(icon = NotificationIcon.AndroidDrawable(UNRESOLVABLE_RES_ID)),
        )

        assertIs<NotificationResult.Failed>(result)
        assertTrue(shown.isEmpty())
    }

    @Test
    fun `a resolvable custom icon is applied to the notification`() = runTest {
        notifier().post(
            "download",
            notification().copy(
                icon = NotificationIcon.AndroidDrawable(android.R.drawable.stat_sys_download),
            ),
        )

        assertEquals(android.R.drawable.stat_sys_download, shadowOf(shown.single().smallIcon).resId)
    }

    // --- cancelling --------------------------------------------------------------------------

    @Test
    fun `cancelling removes exactly that notification`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("a", notification())
        notifier.post("b", notification())

        notifier.cancel("a")

        assertEquals(1, shown.size)
    }

    @Test
    fun `cancelling an id that is not showing changes nothing`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("a", notification())

        notifier.cancel("never-posted")

        assertEquals(1, shown.size, "an unknown id must not take another notification down")
    }

    @Test
    fun `cancelling on a notifier that has posted nothing does not throw`() {
        notifier().cancel("never-posted")

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `cancelAll clears notifications this instance never posted`() = runTest {
        notifier().post("from-a-previous-process", notification())

        // A fresh instance, with empty coalescing state, still clears the tray.
        notifier().cancelAll()

        assertTrue(shown.isEmpty())
    }

    @Test
    fun `cancelAll on an empty tray does not throw`() {
        notifier().cancelAll()

        assertTrue(shown.isEmpty())
    }

    // --- progress ----------------------------------------------------------------------------

    @Test
    fun `a progress update inside the same bucket is coalesced and leaves the tray untouched`() =
        runTest {
            val notifier: Notifier = notifier()
            notifier.post("d", notification().copy(progress = NotificationProgress.Determinate(40)))

            val result: NotificationResult = notifier.post(
                "d",
                notification().copy(progress = NotificationProgress.Determinate(41)),
            )

            assertEquals(NotificationResult.Coalesced, result)
            assertEquals(1, shown.size)
        }

    @Test
    fun `the terminal frame is never coalesced`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("d", notification().copy(progress = NotificationProgress.Determinate(95)))

        val result: NotificationResult = notifier.post("d", notification(body = "Done"))

        assertEquals(NotificationResult.Posted, result)
    }

    @Test
    fun `cancelling forgets the coalescing state so the next run starts fresh`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("d", notification().copy(progress = NotificationProgress.Determinate(40)))

        notifier.cancel("d")
        val result: NotificationResult = notifier.post(
            "d",
            notification().copy(progress = NotificationProgress.Determinate(40)),
        )

        assertEquals(NotificationResult.Posted, result)
    }

    @Test
    fun `cancelAll forgets the coalescing state of every id`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post("d", notification().copy(progress = NotificationProgress.Determinate(40)))

        notifier.cancelAll()
        val result: NotificationResult = notifier.post(
            "d",
            notification().copy(progress = NotificationProgress.Determinate(40)),
        )

        assertEquals(NotificationResult.Posted, result)
    }

    @Test
    fun `coalescing never runs ahead of the gates`() = runTest {
        // A suppressed post must not consume the decision: with the permission missing the caller
        // has to learn that, not "Coalesced", however often they posted before.
        val notifier: Notifier = notifier()
        notifier.post("d", notification().copy(progress = NotificationProgress.Determinate(40)))
        permissions.setStatus(Permission.NOTIFICATIONS, PermissionStatus.Denied())

        val result: NotificationResult = notifier.post(
            "d",
            notification().copy(progress = NotificationProgress.Determinate(41)),
        )

        assertEquals(NotificationResult.PermissionDenied, result)
    }

    // --- action buttons ----------------------------------------------------------------------

    @Test
    fun `an action renders a button broadcasting the app-scoped action with the action id`() =
        runTest {
            notifier().post(
                "download",
                notification().copy(actions = listOf(NotificationAction("cancel", "Cancel"))),
            )

            val posted: Notification = shown.single()
            assertEquals(1, posted.actions.size)
            assertEquals("Cancel", posted.actions[0].title.toString())
            val intent: Intent = shadowOf(posted.actions[0].actionIntent).savedIntent
            assertEquals(context.packageName + ".KMPTOOLKIT_NOTIFICATION_ACTION", intent.action)
            assertEquals("cancel", NotificationActionIntent.actionId(intent))
            assertEquals("download", NotificationActionIntent.notificationId(intent))
        }

    @Test
    fun `a configured broadcast action replaces the derived one`() = runTest {
        notifier(NotificationConfig(actionBroadcastAction = "com.example.MY_ACTION")).post(
            "download",
            notification().copy(actions = listOf(NotificationAction("cancel", "Cancel"))),
        )

        val intent: Intent = shadowOf(shown.single().actions[0].actionIntent).savedIntent
        assertEquals("com.example.MY_ACTION", intent.action)
    }

    @Test
    fun `the same action on two notifications keeps two distinct pending intents`() = runTest {
        val notifier: Notifier = notifier()
        val actions: List<NotificationAction> = listOf(NotificationAction("cancel", "Cancel"))

        notifier.post("a", notification().copy(actions = actions))
        notifier.post("b", notification().copy(actions = actions))

        // Sharing a request code would let FLAG_UPDATE_CURRENT rewrite the first button's extras.
        assertNotEquals(
            shadowOf(shown[0].actions[0].actionIntent).requestCode,
            shadowOf(shown[1].actions[0].actionIntent).requestCode,
        )
    }

    @Test
    fun `a notification without actions renders no buttons`() = runTest {
        notifier().post("download", notification())

        val actions: Array<Notification.Action>? = shown.single().actions
        assertTrue(actions == null || actions.isEmpty())
    }

    @Test
    fun `the default broadcast action matches what the notifier actually fires`() {
        assertEquals(
            context.packageName + ".KMPTOOLKIT_NOTIFICATION_ACTION",
            NotificationActionIntent.defaultAction(context),
        )
    }

    @Test
    fun `reading action extras from an unrelated intent yields null`() {
        assertNull(NotificationActionIntent.actionId(Intent("com.example.OTHER")))
        assertNull(NotificationActionIntent.notificationId(Intent("com.example.OTHER")))
    }

    // --- the tap target ----------------------------------------------------------------------

    @Test
    fun `a notification without contentExtras is not tappable`() = runTest {
        notifier().post("download", notification())

        assertNull(shown.single().contentIntent)
    }

    @Test
    fun `contentExtras open the launcher activity carrying those extras`() = runTest {
        registerLauncherActivity()

        notifier().post(
            "download",
            notification().copy(contentExtras = mapOf("screen" to "downloads", "id" to "42")),
        )

        val contentIntent: PendingIntent = requireNotNull(shown.single().contentIntent)
        val intent: Intent = shadowOf(contentIntent).savedIntent
        assertEquals("downloads", intent.getStringExtra("screen"))
        assertEquals("42", intent.getStringExtra("id"))
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP) != 0)
        assertTrue((intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP) != 0)
    }

    @Test
    fun `an empty contentExtras map still opens the app`() = runTest {
        registerLauncherActivity()

        notifier().post("download", notification().copy(contentExtras = emptyMap()))

        assertNotNull(shown.single().contentIntent, "an empty map still means tappable-to-open")
    }

    // --- id mapping --------------------------------------------------------------------------

    @Test
    fun `the platform id is stable and non-negative and never zero`() {
        // "" hashes to 0, which startForeground rejects; this tag hashes negative.
        val negativeHash = "chat_thread_id_from_push"
        assertTrue(negativeHash.hashCode() < 0)

        assertTrue(AndroidNotifier.notificationId(negativeHash) > 0)
        assertNotEquals(0, AndroidNotifier.notificationId(""))
        assertEquals(
            AndroidNotifier.notificationId(negativeHash),
            AndroidNotifier.notificationId(negativeHash),
        )
    }

    /**
     * Puts a channel into the state a user blocking it leaves behind.
     *
     * Creating it muted and then deleting it is what makes the block *stick*: both the platform and
     * Robolectric restore a deleted channel's original settings when it is re-created, which is
     * exactly the rule that stops an app from raising its own channel's importance.
     */
    private fun blockChannel(channelId: String) {
        manager.createNotificationChannel(
            NotificationChannel(channelId, "Downloads", NotificationManager.IMPORTANCE_NONE),
        )
        manager.deleteNotificationChannel(channelId)
    }

    /** This module has no launcher activity of its own; a real app resolves one. */
    private fun registerLauncherActivity() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private companion object {
        const val CHANNEL_ID: String = "downloads"

        /** An id in the app resource range that no resource in this module occupies. */
        const val UNRESOLVABLE_RES_ID: Int = 0x7F123456
    }
}
