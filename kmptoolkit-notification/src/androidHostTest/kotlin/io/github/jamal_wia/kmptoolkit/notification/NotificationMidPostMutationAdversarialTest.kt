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
 * A caller edits its mutable list or map *while a post is in flight* (another thread, a UI event
 * during the permission check or the build) and then edits it back. The coalescing baseline records
 * the content the frame had when the comparison key was taken; the tray shows what the renderer read
 * afterwards. If those two are read from the live object at different moments, the baseline and the
 * tray describe different frames, and a later post that equals the baseline (the content the caller
 * has now) is dropped as redundant while the tray still shows the content from the middle of the call.
 *
 * The in-flight edit is made deterministic by a renderer that performs it at the start of `build`,
 * which runs after the key is taken and before the platform sees the frame.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationMidPostMutationAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "midpost", name = "Midpost")

    @Before
    fun registerLauncher() {
        val component = ComponentName(context.packageName, "com.example.TestLauncher")
        shadowOf(context.packageManager).addActivityIfNotPresent(component)
        shadowOf(context.packageManager).addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
        manager.cancelAll()
    }

    /** Runs [edit] once, at the start of the first `build`: after the post took its key, before the frame is built. */
    private class EditingRenderer(context: Context, private var edit: (() -> Unit)?) : NotificationRenderer(context, "test.midpost.ACTION") {
        override fun build(id: String, notification: LocalNotification, options: NotificationOptions, iconResId: Int): Notification {
            edit?.invoke()
            edit = null
            return super.build(id, notification, options, iconResId)
        }
    }

    private fun notifier(edit: () -> Unit): Notifier = AndroidNotifier(
        context, permissions, EditingRenderer(context, edit), ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    private fun frame(percent: Int, actions: List<NotificationAction> = emptyList(), extras: Map<String, String>? = null): LocalNotification =
        LocalNotification(
            title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent),
            actions = actions, contentExtras = extras,
        )

    private fun shown(): Notification = shadowOf(manager).getNotification("d", 1)

    private val pause = NotificationAction("pause", "Pause")
    private val resume = NotificationAction("resume", "Resume")

    @Test
    fun `control - an edit before the post and an edit after it are both seen by the next post`() {
        val buttons: MutableList<NotificationAction> = mutableListOf(pause)
        val n: Notifier = notifier { }
        buttons[0] = resume
        assertEquals(NotificationResult.Posted, runBlocking { n.post("d", frame(20, buttons)) })
        buttons[0] = pause
        assertEquals(NotificationResult.Posted, runBlocking { n.post("d", frame(21, buttons)) })
        assertEquals(listOf("Pause"), shown().actions.map { it.title.toString() })
    }

    @Test
    fun `a button edited during the post and edited back leaves the tray and the baseline describing the same frame`() {
        val buttons: MutableList<NotificationAction> = mutableListOf(pause)
        val n: Notifier = notifier { buttons[0] = resume }
        assertEquals(NotificationResult.Posted, runBlocking { n.post("d", frame(20, buttons)) })
        val trayAfterFirst: List<String> = shown().actions.map { it.title.toString() }

        buttons[0] = pause
        val second: NotificationResult = runBlocking { n.post("d", frame(21, buttons)) }

        // Either the first frame showed what its key said (Pause), and the repeat is redundant, or the
        // tray shows something else (Resume), and a frame that says Pause must reach it.
        val trayNow: List<String> = shown().actions.map { it.title.toString() }
        assertEquals(
            listOf("Pause"), trayNow,
            "the caller's latest frame says Pause; the first post showed $trayAfterFirst and the second answered $second",
        )
    }

    @Test
    fun `tap-target extras edited during the post and edited back leave the tap target of the latest frame`() {
        val extras: MutableMap<String, String> = mutableMapOf("screen" to "downloads")
        val n: Notifier = notifier { extras["screen"] = "settings" }
        assertEquals(NotificationResult.Posted, runBlocking { n.post("d", frame(20, extras = extras)) })

        extras["screen"] = "downloads"
        val second: NotificationResult = runBlocking { n.post("d", frame(21, extras = extras)) }

        val opens: String? = shadowOf(shown().contentIntent).savedIntent.getStringExtra("screen")
        assertEquals("downloads", opens, "the caller's latest frame opens downloads; the second post answered $second")
    }

    @Test
    fun `action icons edited during the post and edited back leave the icon of the latest frame`() {
        val icons: MutableMap<String, NotificationIcon> = mutableMapOf("pause" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause))
        val buttons: List<NotificationAction> = listOf(pause)
        val n: Notifier = notifier { icons["pause"] = NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_play) }
        assertEquals(NotificationResult.Posted, runBlocking { n.post("d", frame(20, buttons), NotificationOptions(actionIcons = icons)) })

        icons["pause"] = NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause)
        val second: NotificationResult = runBlocking { n.post("d", frame(21, buttons), NotificationOptions(actionIcons = icons)) }

        assertEquals(
            android.R.drawable.ic_media_pause, shown().actions.single().icon,
            "the caller's latest frame carries the pause icon; the second post answered $second",
        )
    }
}
