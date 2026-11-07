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
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * A terminal frame resets the id's baseline when it is decided, so when the platform then refuses it the
 * coalescer no longer knows which frame the tray shows and cannot write that frame's tap target back:
 * the progress frame still on the tray opens the app with the refused terminal frame's extras.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTerminalRefusedTapTargetAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "term", name = "Term")

    private class RefusingTerminalRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            val built: Notification = super.build(id, notification, options, iconResId)
            if (notification.title == "done") throw IllegalStateException("the platform refused the frame")
            return built
        }
    }

    @Test
    fun `a refused terminal frame does not rewrite where the progress frame on the tray opens`() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        val notifier: Notifier = AndroidNotifier(
            context, permissions, RefusingTerminalRenderer(context),
            ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
        )
        assertEquals(
            NotificationResult.Posted,
            runBlocking {
                notifier.post("d", LocalNotification(title = "progress", body = "b", channel = channel, progress = NotificationProgress.Determinate(50), contentExtras = mapOf("file" to "progress.bin")))
            },
        )

        val done: NotificationResult = runBlocking {
            notifier.post("d", LocalNotification(title = "done", body = "b", channel = channel, progress = null, contentExtras = mapOf("file" to "done.bin")))
        }

        assertEquals(true, done is NotificationResult.Failed)
        val onTray: Notification = shadowOf(manager).allNotifications.single()
        assertEquals("progress", onTray.extras.getString(Notification.EXTRA_TITLE))
        val tapped: Intent = shadowOf(assertNotNull(onTray.contentIntent)).savedIntent
        assertEquals("progress.bin", tapped.getStringExtra("file"), "the progress frame on the tray opens the app with the extras of the refused terminal frame")
    }
}
