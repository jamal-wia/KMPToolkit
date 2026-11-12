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

/**
 * Round 13 mutation survivor M094. An id that holds an unpaired surrogate takes the lossless key path,
 * and there a well-formed pair (an emoji) must still be written into the key. Ids that differ only in
 * such a pair, next to an unpaired surrogate, are different ids: their buttons and taps may not merge
 * (docs: "ids that differ only in unpaired surrogates stay distinct", and any two distinct ids stay
 * distinct notifications).
 */
@RunWith(AndroidJUnit4::class)
class NotificationKeyPairedNextToUnpairedSurrogateAdversarialTest {

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
    fun `buttons whose ids differ only in a well formed pair next to an unpaired surrogate report their own action`() = runTest {
        val ids: List<String> = listOf("😀\uD800", "\uD800", "a😀\uD800", "a\uD800", "\uD800😀", "\uD800😁")
        val actions: List<NotificationAction> = ids.map { NotificationAction(it, "L") }
        notifier().post("n", LocalNotification(title = "n", body = "b", channel = channel, actions = actions))
        val shown: Notification = shadowOf(manager).allNotifications.single()
        val reported: List<String?> = shown.actions.map { NotificationActionIntent.actionId(shadowOf(it.actionIntent).savedIntent) }
        assertEquals(ids, reported)
    }

    @Test
    fun `foreground ids that differ only in a well formed pair next to an unpaired surrogate keep their own taps`() = runTest {
        registerLauncher()
        val first = "😀\uD800"
        val second = "\uD800"
        val foreground: Set<ForegroundNotificationId> = setOf(ForegroundNotificationId(first, 1001), ForegroundNotificationId(second, 1002))
        val notifier: Notifier = notifier(foreground)
        notifier.post(first, LocalNotification(title = "first", body = "b", channel = channel, contentExtras = mapOf("which" to "first")))
        notifier.post(second, LocalNotification(title = "second", body = "b", channel = channel, contentExtras = mapOf("which" to "second")))
        val byTitle: Map<String, Notification> = shadowOf(manager).allNotifications.associateBy {
            it.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }
        for (title: String in listOf("first", "second")) {
            val tap: PendingIntent = requireNotNull(byTitle.getValue(title).contentIntent)
            assertEquals(title, shadowOf(tap).savedIntent.getStringExtra("which"), "tap of $title")
        }
    }
}
