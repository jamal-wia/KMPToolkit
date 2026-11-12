package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 15 (resource starvation): "very long ... payloads, thousands of items" for the content a caller
 * hands to one post, and a caller thread that is interrupted when it tears the tray down.
 *
 * Contract: every runtime failure is a result, never an exception; the tap target carries "each entry as
 * a string extra"; `cancel` / `cancelAll` never throw and are "safe to call from any thread".
 */
@RunWith(AndroidJUnit4::class)
class NotificationHugeContentAndInterruptAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "huge", name = "Huge")

    private fun notifier(): Notifier =
        createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted), NotificationConfig(minProgressInterval = kotlin.time.Duration.ZERO))

    private fun registerLauncherActivity() {
        val component = ComponentName(context.packageName, "com.example.HugeLauncher")
        val packageManager = shadowOf(context.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    @Test
    fun `twenty thousand tap target extras and three hundred buttons post and reach the tray intact`() {
        registerLauncherActivity()
        val extras: Map<String, String> = (0 until 20_000).associate { "key-$it" to "value-".repeat(8) + it }
        val actions: List<NotificationAction> = (0 until 300).map { NotificationAction("action-$it", "Label $it") }
        val icons: Map<String, NotificationIcon> = actions.associate { it.id to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause) }
        val notifier: Notifier = notifier()

        val result: NotificationResult = runBlocking {
            withTimeout(120_000) {
                notifier.post(
                    "huge",
                    LocalNotification("t", "b", channel, actions = actions, contentExtras = extras),
                    NotificationOptions(actionIcons = icons, dismissAction = actions.first()),
                )
            }
        }

        assertEquals(NotificationResult.Posted, result)
        val shown: Notification = shadowOf(manager).allNotifications.single()
        assertEquals(300, shown.actions.size, "every button reaches the platform notification")
        val tap = assertNotNull(shown.contentIntent)
        val delivered: Intent = shadowOf(tap).savedIntent
        assertEquals(20_000, delivered.extras?.keySet()?.count { it.startsWith("key-") }, "each entry is a string extra of the tap target")
        assertEquals("value-".repeat(8) + 19_999, delivered.getStringExtra("key-19999"))
    }

    @Test
    fun `cancel and cancelAll from an interrupted thread never throw keep the flag and still remove the notification`() {
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("a", LocalNotification("t", "b", channel)) }
        runBlocking { notifier.post("b", LocalNotification("t", "b", channel)) }
        var failure: Throwable? = null
        var stillInterrupted = false

        val worker = Thread {
            Thread.currentThread().interrupt()
            failure = runCatching {
                notifier.cancel("a")
                notifier.cancelAll()
            }.exceptionOrNull()
            stillInterrupted = Thread.currentThread().isInterrupted
        }
        worker.start()
        worker.join(20_000)

        assertTrue(!worker.isAlive, "the interrupted caller hung")
        assertEquals(null, failure, "an interrupted thread made cancel throw $failure")
        assertTrue(stillInterrupted, "the notifier swallowed the caller's interrupt")
        assertEquals(0, shadowOf(manager).allNotifications.size)
    }
}
