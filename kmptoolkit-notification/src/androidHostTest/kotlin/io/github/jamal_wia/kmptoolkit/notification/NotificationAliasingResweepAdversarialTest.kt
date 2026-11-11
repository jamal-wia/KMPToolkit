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
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A re-sweep of the in-place-edit fix (round 8, H1) with the shapes the first test did not use: lists
 * and maps that are *views* of something the caller keeps editing (reversed, sub-list, unmodifiable
 * wrapper), one mutable list or map shared by several ids and several posts, a shared options object,
 * and edits that go back and forth. Whatever the reference, the frame a post carries is its content at
 * the call, so the next post is judged against what was shown, not against a live object.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationAliasingResweepAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "resweep", name = "Resweep")

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

    private fun notifier(): Notifier = createNotifier(context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO))

    private fun frame(percent: Int, actions: List<NotificationAction> = emptyList(), extras: Map<String, String>? = null): LocalNotification =
        LocalNotification(
            title = "t", body = "b", channel = channel, progress = NotificationProgress.Determinate(percent),
            actions = actions, contentExtras = extras,
        )

    private fun post(n: Notifier, id: String, notification: LocalNotification, options: NotificationOptions = NotificationOptions.DEFAULT): NotificationResult =
        runBlocking { n.post(id, notification, options) }

    private fun shown(id: String): Notification = shadowOf(manager).getNotification(id, 1)

    private val pause = NotificationAction("pause", "Pause")
    private val resume = NotificationAction("resume", "Resume")

    @Test
    fun `a reversed view of a list the caller edits is read at the call`() {
        val n: Notifier = notifier()
        val backing: MutableList<NotificationAction> = mutableListOf(pause, NotificationAction("c", "Cancel"))
        assertEquals(NotificationResult.Posted, post(n, "d", frame(20, backing.asReversed())))
        assertEquals(listOf("Cancel", "Pause"), shown("d").actions.map { it.title.toString() })
        backing[0] = resume

        assertEquals(NotificationResult.Posted, post(n, "d", frame(21, backing.asReversed())), "the view's content changed")
        assertEquals(listOf("Cancel", "Resume"), shown("d").actions.map { it.title.toString() })
    }

    @Test
    fun `a sub-list view and an unmodifiable wrapper are read at the call`() {
        val n: Notifier = notifier()
        val backing: MutableList<NotificationAction> = mutableListOf(pause, NotificationAction("c", "Cancel"))
        val wrapper: List<NotificationAction> = Collections.unmodifiableList(backing)
        assertEquals(NotificationResult.Posted, post(n, "w", frame(20, wrapper)))
        backing.removeAt(1)
        assertEquals(NotificationResult.Posted, post(n, "w", frame(21, wrapper)), "a button was removed behind the wrapper")
        assertEquals(listOf("Pause"), shown("w").actions.map { it.title.toString() })

        val sub: List<NotificationAction> = backing.subList(0, 1)
        assertEquals(NotificationResult.Coalesced, post(n, "w", frame(22, sub)), "equal content through another view is redundant")
        backing[0] = resume
        assertEquals(NotificationResult.Posted, post(n, "w", frame(23, sub)), "the sub-list view's element changed")
        assertEquals(listOf("Resume"), shown("w").actions.map { it.title.toString() })
    }

    @Test
    fun `an unmodifiable map view of tap-target extras that changes behind it is changed content`() {
        val n: Notifier = notifier()
        val backing: MutableMap<String, String> = mutableMapOf("screen" to "downloads")
        val view: Map<String, String> = Collections.unmodifiableMap(backing)
        assertEquals(NotificationResult.Posted, post(n, "m", frame(20, extras = view)))
        backing["screen"] = "settings"
        backing["extra"] = "1"

        assertEquals(NotificationResult.Posted, post(n, "m", frame(21, extras = view)))
        val intent: Intent = shadowOf(shown("m").contentIntent).savedIntent
        assertEquals("settings", intent.getStringExtra("screen"))
        assertEquals("1", intent.getStringExtra("extra"))
    }

    @Test
    fun `one mutable list shared by two ids is its own baseline for neither`() {
        val n: Notifier = notifier()
        val shared: MutableList<NotificationAction> = mutableListOf(pause)
        assertEquals(NotificationResult.Posted, post(n, "one", frame(20, shared)))
        assertEquals(NotificationResult.Posted, post(n, "two", frame(20, shared)))
        shared[0] = resume

        assertEquals(NotificationResult.Posted, post(n, "one", frame(21, shared)))
        assertEquals(NotificationResult.Posted, post(n, "two", frame(21, shared)), "id two's baseline was id one's edit")
        assertEquals(listOf("Resume"), shown("one").actions.map { it.title.toString() })
        assertEquals(listOf("Resume"), shown("two").actions.map { it.title.toString() })
    }

    @Test
    fun `a toggle that goes back and forth in one list is posted every time and coalesced when nothing changed`() {
        val n: Notifier = notifier()
        val buttons: MutableList<NotificationAction> = mutableListOf(pause)
        val seen: MutableList<NotificationResult> = mutableListOf()
        var percent = 20
        repeat(6) { round: Int ->
            buttons[0] = if (round % 2 == 0) pause else resume
            seen += post(n, "t", frame(percent++, buttons))
            // The same content again, still inside the bucket: redundant.
            seen += post(n, "t", frame(percent, buttons))
        }
        // Round 0 is the first frame of the id; every later round changes the button, every repeat is redundant.
        assertEquals(List(6) { listOf(NotificationResult.Posted, NotificationResult.Coalesced) }.flatten(), seen)
        assertEquals(listOf("Resume"), shown("t").actions.map { it.title.toString() })
    }

    @Test
    fun `one options object whose icon map is edited between posts of several ids changes each id's options`() {
        val n: Notifier = notifier()
        val icons: MutableMap<String, NotificationIcon> = mutableMapOf("pause" to NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_pause))
        val options = NotificationOptions(actionIcons = icons)
        val buttons: List<NotificationAction> = listOf(pause)
        assertEquals(NotificationResult.Posted, post(n, "o1", frame(20, buttons), options))
        assertEquals(NotificationResult.Posted, post(n, "o2", frame(20, buttons), options))
        icons["pause"] = NotificationIcon.AndroidDrawable(android.R.drawable.ic_media_play)

        assertEquals(NotificationResult.Posted, post(n, "o1", frame(21, buttons), options))
        assertEquals(NotificationResult.Posted, post(n, "o2", frame(21, buttons), options))
        assertEquals(android.R.drawable.ic_media_play, shown("o1").actions.single().icon)
        assertEquals(android.R.drawable.ic_media_play, shown("o2").actions.single().icon)
    }

    @Test
    fun `an in-place edit then a post of an unchanged frame is redundant only against the edited content`() {
        val n: Notifier = notifier()
        val buttons: MutableList<NotificationAction> = mutableListOf(pause)
        assertEquals(NotificationResult.Posted, post(n, "e", frame(20, buttons)))
        buttons[0] = resume
        assertEquals(NotificationResult.Posted, post(n, "e", frame(21, buttons)))
        // Back to the first content: that is a change against what the tray shows now.
        buttons[0] = pause
        assertEquals(NotificationResult.Posted, post(n, "e", frame(22, buttons)))
        assertEquals(listOf("Pause"), shown("e").actions.map { it.title.toString() })
        assertEquals(NotificationResult.Coalesced, post(n, "e", frame(23, buttons)))
    }
}
