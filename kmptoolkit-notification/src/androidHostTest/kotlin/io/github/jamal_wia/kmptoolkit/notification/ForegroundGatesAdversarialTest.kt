package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * `03-guide.md`, "Foreground services": building the service's notification "runs none of the post
 * gates: a service has to call `startForeground` either way. The later `post` does run them: on API
 * 33+ without `POST_NOTIFICATIONS` it returns `PermissionDenied` and the service's notification keeps
 * showing what it started with."
 *
 * The same holds for the other two gates (notifications switched off, channel muted): the later post
 * answers with the typed result, leaves what the service started with untouched, and a cancel still
 * removes it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33])
class ForegroundGatesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
    private val config = NotificationConfig(minProgressInterval = Duration.ZERO)
    private val foreground = ForegroundNotificationId(id = "playback", platformId = 1001)
    private val channel = NotificationChannelSpec(id = "playback-channel", name = "Playback")

    private fun note(title: String): LocalNotification = LocalNotification(title = title, body = "B", channel = channel)

    private fun notifier(status: PermissionStatus): Notifier = createNotifier(
        context,
        FakePermissionController(defaultStatus = status),
        config,
        foregroundIds = setOf(foreground),
    )

    private fun startService() {
        manager.notify(foreground.platformId, buildForegroundNotification(context, foreground, note("started"), config))
    }

    private fun titleOnTray(): String {
        val shown: Notification = shadowOf(manager).allNotifications.single()
        return shown.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
    }

    @Test
    fun `building runs no gate and a denied permission answers PermissionDenied without touching the tray`() = runTest {
        startService()
        assertEquals("started", titleOnTray())

        val result: NotificationResult = notifier(PermissionStatus.Denied()).post("playback", note("updated"))

        assertEquals(NotificationResult.PermissionDenied, result)
        assertEquals("started", titleOnTray())
    }

    @Test
    fun `switched off notifications answer NotificationsDisabled and leave the service notification as it was`() = runTest {
        startService()
        shadowOf(manager).setNotificationsEnabled(false)

        val result: NotificationResult = notifier(PermissionStatus.Granted).post("playback", note("updated"))

        assertEquals(NotificationResult.NotificationsDisabled, result)
        shadowOf(manager).setNotificationsEnabled(true)
        assertEquals("started", titleOnTray())
    }

    @Test
    fun `a muted channel is built anyway and the later post answers ChannelBlocked`() = runTest {
        manager.createNotificationChannel(NotificationChannel(channel.id, channel.name, NotificationManager.IMPORTANCE_NONE))
        // The service must still be able to call startForeground with a notification on a muted channel.
        startService()
        assertEquals("started", titleOnTray())

        val result: NotificationResult = notifier(PermissionStatus.Granted).post("playback", note("updated"))

        assertEquals(NotificationResult.ChannelBlocked(channel.id), result)
        assertEquals("started", titleOnTray())
    }

    @Test
    fun `a cancel of the foreground id still removes the notification whatever the gates say`() = runTest {
        startService()
        val notifier: Notifier = notifier(PermissionStatus.Denied())

        notifier.cancel("playback")

        assertTrue(shadowOf(manager).allNotifications.isEmpty())
    }
}
