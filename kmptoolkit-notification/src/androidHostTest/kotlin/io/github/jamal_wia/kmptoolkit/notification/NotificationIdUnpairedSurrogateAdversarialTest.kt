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

/**
 * The documented contract of the PendingIntent key: "no choice of ids can produce another
 * notification's key". Ids are arbitrary Strings, and two Strings that differ only in unpaired
 * surrogates are two different ids (two notifications: the tag is the String itself).
 */
@RunWith(AndroidJUnit4::class)
class NotificationIdUnpairedSurrogateAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val shownByTitle: Map<String, Notification>
        get() = org.robolectric.Shadows.shadowOf(manager).allNotifications.associateBy {
            it.extras.getCharSequence(Notification.EXTRA_TITLE).toString()
        }

    private fun notifier(): Notifier = createNotifier(
        context, FakePermissionController(defaultStatus = PermissionStatus.Granted),
        NotificationConfig(minProgressInterval = Duration.ZERO),
    )

    private fun notificationFor(id: String, actions: List<NotificationAction> = emptyList(), extras: Map<String, String>? = null) =
        LocalNotification(
            title = id, body = "b", channel = NotificationChannelSpec(id = "downloads", name = "Downloads"),
            actions = actions, contentExtras = extras,
        )

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = org.robolectric.Shadows.shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @Test
    fun `ids that differ only in unpaired surrogates are two notifications`() = runTest {
        val notifier: Notifier = notifier()
        notifier.post(FIRST, notificationFor(FIRST))
        notifier.post(SECOND, notificationFor(SECOND))
        assertEquals(setOf(FIRST, SECOND), shownByTitle.keys)
    }

    @Test
    fun `taps on ids that differ only in unpaired surrogates carry their own extras`() = runTest {
        registerLauncher()
        val notifier: Notifier = notifier()
        notifier.post(FIRST, notificationFor(FIRST, extras = mapOf("which" to "first")))
        notifier.post(SECOND, notificationFor(SECOND, extras = mapOf("which" to "second")))
        val tapFirst: PendingIntent = requireNotNull(shownByTitle.getValue(FIRST).contentIntent)
        val tapSecond: PendingIntent = requireNotNull(shownByTitle.getValue(SECOND).contentIntent)
        assertEquals("first", org.robolectric.Shadows.shadowOf(tapFirst).savedIntent.getStringExtra("which"))
        assertEquals("second", org.robolectric.Shadows.shadowOf(tapSecond).savedIntent.getStringExtra("which"))
    }

    @Test
    fun `the same button on ids that differ only in unpaired surrogates reports its own notification`() = runTest {
        val notifier: Notifier = notifier()
        val stop: List<NotificationAction> = listOf(NotificationAction("stop", "Stop"))
        notifier.post(FIRST, notificationFor(FIRST, actions = stop))
        notifier.post(SECOND, notificationFor(SECOND, actions = stop))
        for (id: String in listOf(FIRST, SECOND)) {
            val button: PendingIntent = shownByTitle.getValue(id).actions.single().actionIntent
            val intent: Intent = org.robolectric.Shadows.shadowOf(button).savedIntent
            assertEquals(id, NotificationActionIntent.notificationId(intent), "button of ${id.map { it.code }}")
        }
    }

    private companion object {
        const val FIRST: String = "\uD800"
        const val SECOND: String = "\uD801"
    }
}
