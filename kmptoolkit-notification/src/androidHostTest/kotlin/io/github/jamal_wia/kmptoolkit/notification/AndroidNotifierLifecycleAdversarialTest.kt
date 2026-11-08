package io.github.jamal_wia.kmptoolkit.notification

import android.app.Activity
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf

/**
 * Host activity lifecycle: a notifier built from an activity must outlive it, hold nothing of it, and
 * keep the tray coherent when every rotation builds a fresh one.
 */
@RunWith(AndroidJUnit4::class)
class AndroidNotifierLifecycleAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "life", name = "Life")

    private fun frame(title: String): LocalNotification = LocalNotification(title = title, body = "body", channel = channel)

    private fun titles(): List<String> =
        shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }.sorted()

    private fun heldContexts(owner: Any): List<Context> = owner.javaClass.declaredFields
        .onEach { it.isAccessible = true }
        .mapNotNull { it.get(owner) as? Context }

    @Test
    fun `a notifier made from an activity outlives it and holds only the application context`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val notifier: Notifier = createNotifier(controller.get(), permissions)
        controller.pause().stop().destroy()

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("a")) })
        notifier.cancel("a")

        assertEquals(emptyList(), titles())
        assertFalse(heldContexts(notifier).any { it is Activity }, "the notifier retained the host activity")
    }

    @Test
    fun `a notifier built by every rotation shows one notification per id and cancel clears it for all`() {
        val notifiers: MutableList<Notifier> = mutableListOf()
        repeat(5) { index: Int ->
            val activity: Activity = Robolectric.buildActivity(Activity::class.java).setup().get()
            val notifier: Notifier = createNotifier(activity, permissions)
            notifiers += notifier
            assertEquals(NotificationResult.Posted, runBlocking { notifier.post("download", frame("frame $index")) })
        }

        assertEquals(listOf("frame 4"), titles(), "re-posting one id from rebuilt notifiers must replace, not stack")
        notifiers.last().cancel("download")
        assertEquals(emptyList(), titles())
    }
}
