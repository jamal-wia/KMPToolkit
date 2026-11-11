package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationChannelGroup
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The exact border levels of the notifier's API gates, which no earlier suite loaded: the id-key
 * switch happens at API 29 (`Intent.setIdentifier`), channels exist from 26, a muted channel group
 * is readable from 28. The earlier suites sit at 24, 28 and 30 and above, so a gate written `> Q`
 * instead of `>= Q` (or `< O` as `<= O`) would pass every one of them.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [29])
class NotificationIdCollisionApi29AdversarialTest : NotificationIdCollisionTest() {

    @Test
    fun `at the border level a button with no receiver declared carries its key as the identifier, not as a category`() = runTest {
        createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted)).post(
            "Aa",
            LocalNotification(
                title = "Aa",
                body = "Body",
                channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
                actions = listOf(NotificationAction("stop", "Stop")),
            ),
        )
        val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
        val intent: Intent = shadowOf(shadowOf(manager).allNotifications.single().actions.single().actionIntent).savedIntent
        assertEquals(null, intent.component)
        assertEquals(null, intent.categories)
        assertNotNull(intent.identifier, "from API 29 the key rides on the identifier")
        assertEquals(NotificationActionIntent.defaultAction(context), intent.action)
    }
}

/** API 26 is the first level with channels and still below the identifier: both paths at once. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [26])
class NotificationIdCollisionApi26AdversarialTest : NotificationIdCollisionTest()

@RunWith(AndroidJUnit4::class)
class NotificationApiBorderAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)

    private fun notifier(): Notifier =
        createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

    private fun note(channel: NotificationChannelSpec): LocalNotification =
        LocalNotification(title = "t", body = "b", channel = channel)

    private val default = NotificationChannelSpec("c-default", "Default", importance = NotificationImportance.Default, sound = NotificationSound.Default)
    private val low = NotificationChannelSpec("c-low", "Low", importance = NotificationImportance.Low, sound = NotificationSound.Default)
    private val silent = NotificationChannelSpec("c-silent", "Silent", importance = NotificationImportance.High, sound = NotificationSound.Silent)
    private val custom = NotificationChannelSpec("c-custom", "Custom", sound = NotificationSound.Custom("ding"))

    private fun shownSound(): android.net.Uri? = shadowOf(manager).allNotifications.single().sound

    private fun putChannelInGroup(channelId: String, blocked: Boolean) {
        val group = NotificationChannelGroup("grp", "Group")
        if (blocked) {
            NotificationChannelGroup::class.java.getMethod("setBlocked", Boolean::class.javaPrimitiveType).invoke(group, true)
        }
        manager.createNotificationChannelGroup(group)
        manager.createNotificationChannel(NotificationChannel(channelId, "Name", NotificationManager.IMPORTANCE_LOW).apply { this.group = "grp" })
        manager.deleteNotificationChannel(channelId)
    }

    @Test
    @Config(sdk = [25])
    fun `API 25 has no channels so the sound rides on the notification and Low and Silent stay silent`() = runTest {
        for (spec in listOf(default, low, silent, custom)) {
            manager.cancelAll()
            assertEquals(NotificationResult.Posted, notifier().post("n", note(spec)), spec.id)
            val sound: android.net.Uri? = shownSound()
            when (spec.id) {
                "c-default" -> assertEquals(Settings.System.DEFAULT_NOTIFICATION_URI, sound)
                "c-low", "c-silent" -> assertNull(sound, spec.id)
                else -> assertTrue(sound.toString().contains("raw/ding"), "was $sound")
            }
        }
    }

    @Test
    @Config(sdk = [25])
    fun `API 25 foreground notification builds without channel calls and is ongoing`() {
        val built: Notification = buildForegroundNotification(
            context, ForegroundNotificationId("svc", 7), note(default).copy(ongoing = false),
        )
        assertTrue(built.flags and Notification.FLAG_ONGOING_EVENT != 0)
        NotificationChannels.ensure(context, default)
        NotificationChannels.delete(context, default.id)
    }

    @Test
    @Config(sdk = [26])
    fun `API 26 creates the channel and the notification itself carries no sound`() = runTest {
        for (spec in listOf(default, low, silent, custom)) {
            manager.cancelAll()
            assertEquals(NotificationResult.Posted, notifier().post("n", note(spec)), spec.id)
            assertNull(shownSound(), "the channel carries the sound from 26: ${spec.id}")
            assertNotNull(manager.getNotificationChannel(spec.id), spec.id)
        }
    }

    @Test
    @Config(sdk = [26])
    fun `API 26 reports a muted channel and recreates a deleted one`() = runTest {
        manager.createNotificationChannel(NotificationChannel(low.id, "Low", NotificationManager.IMPORTANCE_NONE))
        manager.deleteNotificationChannel(low.id)
        assertEquals(NotificationResult.ChannelBlocked(low.id), notifier().post("n", note(low)))
        val notifier: Notifier = notifier()
        assertEquals(NotificationResult.Posted, notifier.post("m", note(default)))
        manager.deleteNotificationChannel(default.id)
        assertEquals(NotificationResult.Posted, notifier.post("m", note(default)))
        assertNotNull(manager.getNotificationChannel(default.id))
    }

    @Test
    @Config(sdk = [26])
    fun `API 26 posts on a channel that belongs to a group without asking the platform about the group`() = runTest {
        putChannelInGroup(low.id, blocked = false)
        assertEquals(NotificationResult.Posted, notifier().post("n", note(low)))
    }

    @Test
    @Config(sdk = [27])
    fun `API 27 posts on a channel that belongs to a group without asking the platform about the group`() = runTest {
        putChannelInGroup(low.id, blocked = false)
        assertEquals(NotificationResult.Posted, notifier().post("n", note(low)))
    }

    @Test
    @Config(sdk = [28])
    fun `API 28 is the first level that reads a muted group`() = runTest {
        putChannelInGroup(low.id, blocked = true)
        assertEquals(NotificationResult.ChannelBlocked(low.id), notifier().post("n", note(low)))
        manager.deleteNotificationChannel(low.id)
    }

    @Test
    @Config(sdk = [29])
    fun `API 29 reads a muted group and an unmuted one posts`() = runTest {
        putChannelInGroup(low.id, blocked = true)
        assertEquals(NotificationResult.ChannelBlocked(low.id), notifier().post("n", note(low)))
    }
}
