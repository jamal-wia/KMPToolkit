package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** Action ids are as free as notification ids: two actions of one notification must stay two buttons. */
@RunWith(AndroidJUnit4::class)
class NotificationActionIdsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "downloads", name = "Downloads")

    private fun notifier(foreground: Set<ForegroundNotificationId> = emptySet()): Notifier = createNotifier(
        context, FakePermissionController(defaultStatus = PermissionStatus.Granted),
        NotificationConfig(minProgressInterval = Duration.ZERO), foreground,
    )

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @Test
    fun `two buttons whose ids differ only in unpaired surrogates report their own action`() = runTest {
        val actions: List<NotificationAction> = listOf(NotificationAction("\uD800", "A"), NotificationAction("\uD801", "B"))
        notifier().post("n", LocalNotification(title = "n", body = "b", channel = channel, actions = actions))
        val shown: Notification = shadowOf(manager).allNotifications.single()
        val reported: List<String?> = shown.actions.map { NotificationActionIntent.actionId(shadowOf(it.actionIntent).savedIntent) }
        assertEquals(actions.map { it.id }, reported)
    }

    @Test
    fun `two buttons whose ids look alike but are paired or ordinary report their own action`() = runTest {
        val ids: List<String> = listOf("a/b", "a%2Fb", "a", "a/", "😀", "\u0000", "é", "é", "dismiss/stop", "x".repeat(40_000))
        val actions: List<NotificationAction> = ids.map { NotificationAction(it, "L") }
        notifier().post("n", LocalNotification(title = "n", body = "b", channel = channel, actions = actions))
        val shown: Notification = shadowOf(manager).allNotifications.single()
        val reported: List<String?> = shown.actions.map { NotificationActionIntent.actionId(shadowOf(it.actionIntent).savedIntent) }
        assertEquals(ids, reported)
    }

    @Test
    fun `foreground ids that differ only in unpaired surrogates keep their own taps`() = runTest {
        registerLauncher()
        val foreground: Set<ForegroundNotificationId> = setOf(ForegroundNotificationId("\uD800", 1001), ForegroundNotificationId("\uD801", 1002))
        val notifier: Notifier = notifier(foreground)
        notifier.post("\uD800", LocalNotification(title = "first", body = "b", channel = channel, contentExtras = mapOf("which" to "first")))
        notifier.post("\uD801", LocalNotification(title = "second", body = "b", channel = channel, contentExtras = mapOf("which" to "second")))
        val byTitle: Map<String, Notification> = shadowOf(manager).allNotifications.associateBy {
            it.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }
        for (title: String in listOf("first", "second")) {
            val tap: PendingIntent = requireNotNull(byTitle.getValue(title).contentIntent)
            assertEquals(title, shadowOf(tap).savedIntent.getStringExtra("which"), "tap of $title")
        }
    }
}
