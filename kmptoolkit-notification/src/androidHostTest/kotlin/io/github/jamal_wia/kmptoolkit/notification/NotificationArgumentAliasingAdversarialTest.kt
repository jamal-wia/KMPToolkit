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
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * "A changed title, body, button set or options bypasses [the bucket]" (docs 04, Coalescing) is a
 * promise about values, and a caller may legitimately keep one mutable list or map and edit it in
 * place between posts (a pause/resume button toggle on a long-lived list is the classic case). The
 * baseline a later frame is judged against must therefore hold what was *shown*, not a reference the
 * caller can still change; otherwise the edited frame is compared with itself, found redundant and
 * dropped while the tray still shows the old buttons.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationArgumentAliasingAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "alias", name = "Alias")

    @Before
    fun registerLauncher() {
        // Without a launcher activity the app has no tap target at all.
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    // Rate limit off: only the bucket and the content comparison decide.
    private fun notifier(): Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

    private fun frame(
        percent: Int,
        actions: List<NotificationAction> = emptyList(),
        extras: Map<String, String>? = null,
    ): LocalNotification = LocalNotification(
        title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent),
        actions = actions, contentExtras = extras,
    )

    private fun post(n: Notifier, notification: LocalNotification, options: NotificationOptions = NotificationOptions.DEFAULT): NotificationResult =
        runBlocking { n.post("d", notification, options) }

    private fun shown(): Notification = shadowOf(manager).getNotification("d", 1)

    @Test
    fun `control - the same buttons in a fresh list inside one bucket are redundant`() {
        val n: Notifier = notifier()
        assertEquals(NotificationResult.Posted, post(n, frame(20, listOf(NotificationAction("pause", "Pause")))))
        assertEquals(NotificationResult.Coalesced, post(n, frame(21, listOf(NotificationAction("pause", "Pause")))))
    }

    @Test
    fun `a button list edited in place between two posts is a changed button set`() {
        val n: Notifier = notifier()
        val buttons: MutableList<NotificationAction> = mutableListOf(NotificationAction("pause", "Pause"))
        assertEquals(NotificationResult.Posted, post(n, frame(20, buttons)))
        buttons[0] = NotificationAction("resume", "Resume")

        val result: NotificationResult = post(n, frame(21, buttons))

        assertEquals(NotificationResult.Posted, result, "the tray still shows the old button: ${shown().actions?.map { it.title }}")
        assertEquals(listOf("Resume"), shown().actions.map { it.title.toString() })
    }

    @Test
    fun `tap-target extras edited in place between two posts are changed content`() {
        val n: Notifier = notifier()
        val extras: MutableMap<String, String> = mutableMapOf("screen" to "downloads")
        assertEquals(NotificationResult.Posted, post(n, frame(20, extras = extras)))
        extras["screen"] = "settings"

        val result: NotificationResult = post(n, frame(21, extras = extras))

        assertEquals(NotificationResult.Posted, result, "the tap target still opens the old screen")
        assertEquals("settings", shadowOf(shown().contentIntent).savedIntent.getStringExtra("screen"))
    }

    @Test
    fun `action icons edited in place between two posts are changed options`() {
        val n: Notifier = notifier()
        val icons: MutableMap<String, NotificationIcon> = mutableMapOf("pause" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause))
        val buttons: List<NotificationAction> = listOf(NotificationAction("pause", "Pause"))
        assertEquals(NotificationResult.Posted, post(n, frame(20, buttons), NotificationOptions(actionIcons = icons)))
        icons["pause"] = NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_play)

        val result: NotificationResult = post(n, frame(21, buttons), NotificationOptions(actionIcons = icons))

        assertEquals(NotificationResult.Posted, result, "the button still carries the old icon")
        assertEquals(android.R.drawable.ic_media_play, shown().actions.single().icon)
    }
}
