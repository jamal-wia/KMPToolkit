package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Round 15 (contract lawyer): "`cancel` - A no-op for an id that is not showing, including one this
 * instance never posted." The ids a call site passes are not always ones `post` would accept: a blank
 * id is rejected by `post` only. `cancel` has no such rule, so it must be silent for it and must leave
 * every other notification, tagged ones and the unrelated untagged ones of other code, alone.
 */
@RunWith(AndroidJUnit4::class)
class NotifierCancelOfUnpostedIdsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "c", name = "C")

    private fun titles(): List<String> =
        shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }.sorted()

    @Test
    fun `cancel of a blank or awkward id that was never posted is silent and removes nothing`() {
        val notifier: Notifier = createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted))
        runBlocking {
            notifier.post("kept-1", LocalNotification("one", "b", channel))
            notifier.post("kept-2", LocalNotification("two", "b", channel))
        }

        for (id in listOf("", " ", "\t", "\n", "\u0000", "%", "?#", "\uD83D", "never-posted", "x".repeat(200_000))) {
            val failure: Throwable? = runCatching { notifier.cancel(id) }.exceptionOrNull()
            assertNull(failure, "cancel('${id.take(12)}') threw $failure")
        }

        assertEquals(listOf("one", "two"), titles())
    }

    @Test
    fun `a blank id is rejected by post and a cancel of it afterwards is still silent`() {
        val notifier: Notifier = createNotifier(context, FakePermissionController(defaultStatus = PermissionStatus.Granted))

        val rejected: Throwable? = runCatching { runBlocking { notifier.post(" ", LocalNotification("t", "b", channel)) } }.exceptionOrNull()
        val silent: Throwable? = runCatching { notifier.cancel(" ") }.exceptionOrNull()

        assertEquals(IllegalArgumentException::class, rejected?.let { it::class })
        assertNull(silent)
        assertEquals(emptyList(), titles())
    }
}
