package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationManager
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * `04-api-reference.md`, `createNotifier`: "Only the application context is retained on Android, so
 * passing an `Activity` leaks nothing."
 *
 * The context handed in plays an activity that has been destroyed after `createNotifier` returned:
 * any later use of it throws. A notifier that kept the argument (rather than its application
 * context) for the buttons, the tap target, the channel gate or the tray would fail on the first
 * post, cancel or sweep.
 */
@RunWith(AndroidJUnit4::class)
class NotifierRetainsOnlyApplicationContextAdversarialTest {

    private val app: Context = ApplicationProvider.getApplicationContext()

    private class DestroyedActivity(private val application: Context) : ContextWrapper(application) {
        @Volatile var destroyed: Boolean = false

        private fun alive(what: String) {
            check(!destroyed) { "the activity context was used after it was destroyed: $what" }
        }

        override fun getApplicationContext(): Context = application
        override fun getSystemService(name: String): Any? { alive("getSystemService"); return super.getSystemService(name) }
        override fun getPackageName(): String { alive("getPackageName"); return super.getPackageName() }
        override fun getPackageManager(): android.content.pm.PackageManager { alive("getPackageManager"); return super.getPackageManager() }
        override fun getResources(): android.content.res.Resources { alive("getResources"); return super.getResources() }
        override fun getContentResolver(): android.content.ContentResolver { alive("getContentResolver"); return super.getContentResolver() }
    }

    @Test
    fun `a notifier built from an activity keeps working after the activity is destroyed`() {
        val activity = DestroyedActivity(app)
        val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val notifier: Notifier = createNotifier(
            activity,
            permissions,
            NotificationConfig(minProgressInterval = Duration.ZERO),
            foregroundIds = setOf(ForegroundNotificationId(id = "playback", platformId = 1001)),
        )
        activity.destroyed = true
        val channel = NotificationChannelSpec(id = "c", name = "C")
        val action = NotificationAction(id = "stop", label = "Stop")
        val manager: NotificationManager = app.getSystemService(NotificationManager::class.java)

        val results: List<NotificationResult> = runBlocking {
            listOf(
                notifier.post(
                    "a",
                    LocalNotification(
                        title = "T", body = "B", channel = channel,
                        actions = listOf(action), contentExtras = mapOf("screen" to "x"),
                    ),
                    NotificationOptions(dismissAction = action),
                ),
                notifier.post("playback", LocalNotification(title = "T", body = "B", channel = channel)),
            )
        }
        assertEquals(listOf(NotificationResult.Posted, NotificationResult.Posted), results)
        assertEquals(2, shadowOf(manager).allNotifications.size)

        notifier.cancel("a")
        notifier.cancel("playback")
        assertEquals(0, shadowOf(manager).allNotifications.size)
        notifier.cancelAll()
    }
}
