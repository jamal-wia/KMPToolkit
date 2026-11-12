package io.github.jamal_wia.kmptoolkit.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 15 (contract lawyer): 05-platform-notes, "`cancelAll` clears the app's whole tray, including
 * notifications posted by a previous process or by another part of your app", and the contrast the
 * 04-api-reference draws for `cancel`: it removes "the notification under `id`", nothing else.
 */
@RunWith(AndroidJUnit4::class)
class NotificationCancelAllForeignTrayAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "mine", name = "Mine")

    private fun foreign(tag: String?, id: Int) {
        manager.createNotificationChannel(NotificationChannel("foreign", "Foreign", NotificationManager.IMPORTANCE_DEFAULT))
        val built = NotificationCompat.Builder(context, "foreign").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("foreign $tag $id").build()
        if (tag == null) NotificationManagerCompat.from(context).notify(id, built) else NotificationManagerCompat.from(context).notify(tag, id, built)
    }

    private fun notifier(): Notifier = createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted))

    @Test
    fun `cancelAll clears the tray including notifications this instance never posted`() {
        val first: Notifier = notifier()
        runBlocking { first.post("a", LocalNotification("t", "b", channel)) }
        foreign(null, 5)
        foreign("other-tag", 6)
        foreign(null, 1)
        val restarted: Notifier = notifier()

        restarted.cancelAll()

        assertEquals(0, shadowOf(manager).allNotifications.size, "the tray of the whole app must be empty")
    }

    @Test
    fun `cancel of one id leaves the notifications of every other tag and every untagged one alone`() {
        val notifier: Notifier = notifier()
        runBlocking {
            notifier.post("a", LocalNotification("mine-a", "b", channel))
            notifier.post("b", LocalNotification("mine-b", "b", channel))
        }
        foreign(null, 7_000_001)
        foreign("a-2", 1)

        notifier.cancel("a")

        val titles: List<String> = shadowOf(manager).allNotifications.map { it.extras.getString("android.title").orEmpty() }.sorted()
        assertEquals(listOf("foreign a-2 1", "foreign null 7000001", "mine-b"), titles)
    }
}
