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
 * A frame is rendered before the platform accepts it, and its tap target is a `PendingIntent` that
 * `FLAG_UPDATE_CURRENT` rewrites in place. When the platform then refuses the frame, the frame that
 * stays on the tray must still open the app with its own extras, not the refused frame's.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierTapExtrasAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "tap", name = "Tap")

    /** Renders like the real one, then refuses the frame the way a failing `notify` would. */
    private class RefusingAfterBuildRenderer(context: Context) : NotificationRenderer(context, "test.ACTION") {
        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            val built: Notification = super.build(id, notification, options, iconResId)
            if (notification.title == "refused") throw IllegalStateException("the platform refused the frame")
            return built
        }
    }

    private fun frame(title: String, file: String, percent: Int): LocalNotification = LocalNotification(
        title = title,
        body = "body",
        channel = channel,
        progress = NotificationProgress.Determinate(percent),
        contentExtras = mapOf("file" to file),
    )

    @Test
    fun `a refused frame does not rewrite where the frame on the tray opens`() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        val notifier: Notifier = AndroidNotifier(
            context, permissions, RefusingAfterBuildRenderer(context),
            ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
        )
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("d", frame("shown", file = "first.bin", percent = 10)) })

        val refused: NotificationResult = runBlocking { notifier.post("d", frame("refused", file = "second.bin", percent = 50)) }

        assertEquals(true, refused is NotificationResult.Failed)
        val onTray: Notification = shadowOf(manager).allNotifications.single()
        assertEquals("shown", onTray.extras.getString(Notification.EXTRA_TITLE))
        val tapped: Intent = shadowOf(assertNotNull(onTray.contentIntent)).savedIntent
        assertEquals("first.bin", tapped.getStringExtra("file"), "the notification on the tray now opens the app with the refused frame's extras")
    }
}
