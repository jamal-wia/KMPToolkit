package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.Shadows.shadowOf

/** Action ids are as free as notification ids: look-alike ids of one notification must stay separate buttons. */
@RunWith(AndroidJUnit4::class)
open class NotificationActionIdsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")

    private fun notifier(): Notifier = createNotifier(
        context, FakePermissionController(defaultStatus = PermissionStatus.Granted),
        NotificationConfig(minProgressInterval = Duration.ZERO),
    )

    @Test
    fun `two buttons whose ids look alike but are paired or ordinary report their own action`() = runTest {
        val ids: List<String> = listOf("a/b", "a%2Fb", "a", "a/", "😀", "\u0000", "é", "é", "dismiss/stop", "x".repeat(40_000))
        val actions: List<NotificationAction> = ids.map { NotificationAction(it, "L") }
        notifier().post("n", LocalNotification(title = "n", body = "b", channel = channel, actions = actions))
        val shown: Notification = shadowOf(manager).allNotifications.single()
        val reported: List<String?> = shown.actions.map { NotificationActionIntent.actionId(shadowOf(it.actionIntent).savedIntent) }
        assertEquals(ids, reported)
    }
}

/** The same checks below API 29, where the PendingIntent key rides on a category (or, with no single receiver, on a hash). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [28])
class NotificationActionIdsLegacyApiAdversarialTest : NotificationActionIdsAdversarialTest()
