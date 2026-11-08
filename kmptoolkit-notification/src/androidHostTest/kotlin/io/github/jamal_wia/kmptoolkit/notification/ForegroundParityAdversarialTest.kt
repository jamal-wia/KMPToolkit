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
import kotlin.test.assertNotNull
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Published contract of `buildForegroundNotification`: it "renders `notification` exactly as `post` would under
 * `foreground.id` (channel mapping, buttons, dismissal, tap target, options)", forcing only `ongoing`. A service
 * that starts with the built notification and an app that later updates it through the notifier must agree on every
 * intent, or the update quietly changes what a button does.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [24, 34])
class ForegroundParityAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val foreground = ForegroundNotificationId("svc", 1001)

    private fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        val pm = shadowOf(context.packageManager)
        pm.addActivityIfNotPresent(component)
        pm.addIntentFilterForActivity(component, IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) })
    }

    private fun describe(pending: PendingIntent?): String {
        if (pending == null) return "none"
        val intent: Intent = shadowOf(pending).savedIntent
        val extras = intent.extras?.keySet().orEmpty().sorted().joinToString { "$it=${intent.extras?.get(it)}" }
        return "action=${intent.action} component=${intent.component} package=${intent.`package`} " +
            "identifier=${if (android.os.Build.VERSION.SDK_INT >= 29) intent.identifier else "-"} " +
            "categories=${intent.categories?.sorted()} data=${intent.data} extras=[$extras] flags=${intent.flags} " +
            "requestCode=${shadowOf(pending).requestCode}"
    }

    private fun describe(shown: Notification): List<String> = buildList {
        add("title=${shown.extras.getCharSequence(Notification.EXTRA_TITLE)}")
        add("text=${shown.extras.getCharSequence(Notification.EXTRA_TEXT)}")
        add("progress=${shown.extras.getInt(Notification.EXTRA_PROGRESS)}/${shown.extras.getInt(Notification.EXTRA_PROGRESS_MAX)}/${shown.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE)}")
        add("template=${shown.extras.getString(Notification.EXTRA_TEMPLATE)}")
        add("compactActions=${shown.extras.getIntArray("android.compactActions")?.toList()}")
        add("alertOnce=${shown.flags and Notification.FLAG_ONLY_ALERT_ONCE}")
        add("autoCancel=${shown.flags and Notification.FLAG_AUTO_CANCEL}")
        add("channel=${if (android.os.Build.VERSION.SDK_INT >= 26) shown.channelId else "-"}")
        @Suppress("DEPRECATION")
        add("priority=${shown.priority}")
        add("content=${describe(shown.contentIntent)}")
        add("delete=${describe(shown.deleteIntent)}")
        shown.actions?.forEachIndexed { index, action -> add("action$index=${action.title} ${describe(action.actionIntent)} icon=${if (android.os.Build.VERSION.SDK_INT >= 23) action.getIcon()?.resId else "-"}") }
    }

    @Test
    fun `the service's notification and the notifier's frame agree on everything but ongoing`() {
        registerLauncher()
        val config = NotificationConfig(minProgressInterval = Duration.ZERO)
        val options = NotificationOptions(
            alertOnce = false,
            dismissAction = NotificationAction("dismiss", "Dismiss"),
            mediaStyle = true,
            actionIcons = mapOf("pause" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause)),
        )
        for (autoCancel in listOf(true, false)) for (progress in listOf(null, NotificationProgress.Indeterminate, NotificationProgress.Determinate(40))) {
            manager.cancelAll()
            val notification = LocalNotification(
                title = "Playing", body = "Track", channel = NotificationChannelSpec(id = "media", name = "Media", importance = NotificationImportance.High),
                progress = progress, autoCancel = autoCancel,
                actions = listOf(NotificationAction("pause", "Pause"), NotificationAction("next", "Next")),
                contentExtras = mapOf("screen" to "player", "n" to "1"),
            )
            val built: Notification = buildForegroundNotification(context, foreground, notification, config, options)
            val notifier: Notifier = createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted), config, setOf(foreground))
            runBlocking { notifier.post("svc", notification.copy(ongoing = true), options) }
            val posted: Notification = assertNotNull(shadowOf(manager).getNotification(null, 1001))
            assertEquals(describe(posted), describe(built), "autoCancel=$autoCancel progress=$progress")
        }
    }
}
