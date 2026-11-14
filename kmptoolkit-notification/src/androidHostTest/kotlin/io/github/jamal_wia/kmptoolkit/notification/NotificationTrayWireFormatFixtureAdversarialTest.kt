package io.github.jamal_wia.kmptoolkit.notification

import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 17 (upgrade compatibility): the notification tray and its PendingIntents belong to the system and
 * outlive the process, so what a 3.0.0 or 4.0.0 notifier left there is a wire format: the platform tag (the
 * caller's id) with the platform id 1, the button broadcast (action, package, two extra names) and the
 * identifier of each PendingIntent (`kmptoolkit-notification://<kind>/<id>[/<action>]`, request code 0).
 * The shape is written here from literals taken from the 3.0.0 and 4.0.0 sources, without the production
 * code, and the current notifier must replace it in place, remove it, and keep every earlier button alive
 * and equal to the one it builds now.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationTrayWireFormatFixtureAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val tray: List<Notification> get() = shadowOf(manager).allNotifications
    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")
    private val actionName: String = app.packageName + ".KMPTOOLKIT_NOTIFICATION_ACTION"

    private fun launcher() {
        val component = ComponentName(app.packageName, "com.example.WireLauncher")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun earlierButton(id: String, actionId: String): PendingIntent = PendingIntent.getBroadcast(
        app, 0,
        Intent(actionName).apply {
            setPackage(app.packageName)
            putExtra("io.github.jamal_wia.kmptoolkit.notification.EXTRA_ACTION_ID", actionId)
            putExtra("io.github.jamal_wia.kmptoolkit.notification.EXTRA_NOTIFICATION_ID", id)
            identifier = "kmptoolkit-notification://action/$id/$actionId"
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun earlierDismiss(id: String, actionId: String): PendingIntent = PendingIntent.getBroadcast(
        app, 0,
        Intent(actionName).apply {
            setPackage(app.packageName)
            putExtra("io.github.jamal_wia.kmptoolkit.notification.EXTRA_ACTION_ID", actionId)
            putExtra("io.github.jamal_wia.kmptoolkit.notification.EXTRA_NOTIFICATION_ID", id)
            identifier = "kmptoolkit-notification://dismiss/$id/$actionId"
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** What a 3.0.0 / 4.0.0 notifier left on the tray: tagged under the caller's id, platform id 1. */
    private fun postAsEarlierVersion(id: String, title: String, button: PendingIntent, dismiss: PendingIntent? = null) {
        manager.createNotificationChannel(NotificationChannel(channel.id, channel.name, NotificationManager.IMPORTANCE_DEFAULT))
        val built: Notification = NotificationCompat.Builder(app, channel.id)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .addAction(0, "Cancel", button)
            .apply { if (dismiss != null) setDeleteIntent(dismiss) }
            .build()
        NotificationManagerCompat.from(app).notify(id, 1, built)
    }

    private fun notifier(): Notifier =
        createNotifier(context = app, permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted))

    @Test
    fun `a notification left by an earlier version is replaced in place and its buttons stay the very PendingIntents the current version builds`() {
        launcher()
        val old: PendingIntent = earlierButton("download", "cancel")
        val oldDismiss: PendingIntent = earlierDismiss("download", "cancel")
        postAsEarlierVersion("download", "old frame", old, oldDismiss)
        assertEquals(1, tray.size)

        val cancel = NotificationAction("cancel", "Cancel")
        val result = runBlocking {
            notifier().post(
                "download",
                LocalNotification("new frame", "b", channel, actions = listOf(cancel)),
                NotificationOptions(dismissAction = cancel),
            )
        }
        assertEquals(NotificationResult.Posted, result)
        assertEquals(1, tray.size, "the same tag and platform id: replaced, not stacked")
        assertEquals("new frame", tray.single().extras.getString(Notification.EXTRA_TITLE))
        // Same identity as the earlier version's: FLAG_UPDATE_CURRENT reused the existing PendingIntents.
        assertEquals(old, tray.single().actions.single().actionIntent, "button identity is unchanged across the update")
        assertEquals(oldDismiss, tray.single().deleteIntent, "dismiss identity is unchanged across the update")
    }

    @Test
    fun `cancel and cancelAll of the current version remove a notification an earlier version showed`() {
        postAsEarlierVersion("a", "A", earlierButton("a", "x"))
        postAsEarlierVersion("b", "B", earlierButton("b", "x"))
        assertEquals(2, tray.size)
        notifier().cancel("a")
        assertEquals(listOf("B"), tray.map { it.extras.getString(Notification.EXTRA_TITLE) })
        notifier().cancelAll()
        assertTrue(tray.isEmpty())
    }

    @Test
    fun `a button tapped on an earlier notification reaches the receiver with the ids the current accessors read`() {
        val old: PendingIntent = earlierButton("download", "cancel")
        val sent: Intent = shadowOf(old).savedIntent
        assertEquals(actionName, sent.action)
        assertEquals("cancel", NotificationActionIntent.actionId(sent))
        assertEquals("download", NotificationActionIntent.notificationId(sent))
        assertEquals(actionName, NotificationActionIntent.defaultAction(app))
        // The two extra names are public constants, pinned here to the strings of 3.0.0 and 4.0.0.
        assertEquals("io.github.jamal_wia.kmptoolkit.notification.EXTRA_ACTION_ID", NotificationActionIntent.EXTRA_ACTION_ID)
        assertEquals("io.github.jamal_wia.kmptoolkit.notification.EXTRA_NOTIFICATION_ID", NotificationActionIntent.EXTRA_NOTIFICATION_ID)
        assertNotNull(old)
    }
}
