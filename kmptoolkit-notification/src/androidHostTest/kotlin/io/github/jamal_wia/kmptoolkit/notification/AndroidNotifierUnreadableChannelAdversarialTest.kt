package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * Mutation survivor hunt (round 13). Contract (docs 04, "Ordering" and `ChannelBlocked`): the channel gate answers
 * `ChannelBlocked` only for a channel (or group) it could read and found muted; in every other case the post goes on.
 * A platform that cannot read the channel back (an OEM manager that answers null for it) is not "blocked" and must
 * not turn a post into a `Failed`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], shadows = [AndroidNotifierUnreadableChannelAdversarialTest.UnreadableChannelShadow::class])
class AndroidNotifierUnreadableChannelAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun `a channel the platform cannot read back does not fail the post`() = runBlocking {
        val notifier: Notifier = createNotifier(
            context,
            FakePermissionController(defaultStatus = PermissionStatus.Granted),
            NotificationConfig(),
        )
        val result: NotificationResult = notifier.post(
            "n",
            LocalNotification(
                title = "t",
                body = "b",
                channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
            ),
        )
        assertEquals(NotificationResult.Posted, result)
        val manager: NotificationManager = context.getSystemService(NotificationManager::class.java)
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    /** A manager whose channel reads always come back empty. */
    @Implements(NotificationManager::class)
    class UnreadableChannelShadow : ShadowNotificationManager() {

        @Implementation
        public override fun getNotificationChannel(channelId: String?): NotificationChannel? = null
    }
}
