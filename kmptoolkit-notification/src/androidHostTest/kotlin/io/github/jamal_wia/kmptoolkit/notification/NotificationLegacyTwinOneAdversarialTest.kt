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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/**
 * Four different hash codes (0, 1, `Int.MIN_VALUE`, `Int.MIN_VALUE + 1`) all collapse to the 2.x
 * platform id 1 (`and 0x7FFFFFFF`, then `coerceAtLeast(1)`), and 1 is also the `Int` half of every tagged
 * notification's key and a natural choice for a foreground service's `startForeground` id. A service
 * on platform id 1 must survive the legacy sweep of every one of those ids, and a tagged
 * notification must never be mistaken for the untagged one with the same `Int`.
 */
@RunWith(AndroidJUnit4::class)
class NotificationLegacyTwinOneAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "twin", name = "Twin")
    private val service = ForegroundNotificationId("service", 1)

    private fun frame(title: String): LocalNotification = LocalNotification(title = title, body = "b", channel = channel)

    private fun notifier(): Notifier = createNotifier(
        context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO), setOf(service),
    )

    /** What `startForeground(1, ...)` leaves on the tray: the untagged notification. */
    private fun startService() {
        manager.notify(service.platformId, buildForegroundNotification(context, service, frame("service")))
    }

    private fun untagged(): Notification? = shadowOf(manager).getNotification(null, 1)

    private fun tagged(tag: String): Notification? = shadowOf(manager).getNotification(tag, 1)

    @Test
    fun `the four ids really share the legacy platform id 1`() {
        for (id: String in TWINS) assertEquals(1, legacyPlatformNotificationId(id), id)
    }

    @Test
    fun `a service on platform id 1 survives posting and cancelling every legacy twin of 1`() = runTest {
        startService()
        val notifier: Notifier = notifier()
        for (id: String in TWINS) {
            assertEquals(NotificationResult.Posted, notifier.post(id, frame(id)), id)
            assertNotNull(untagged(), "posting $id swept the service's notification")
            assertNotNull(tagged(id), "$id was not posted under its tag")
        }
        for (id: String in TWINS) {
            notifier.cancel(id)
            assertNotNull(untagged(), "cancelling $id removed the service's notification")
            assertNull(tagged(id), "$id was not cancelled")
        }
    }

    @Test
    fun `the service's own id removes the untagged notification and leaves the tagged twins`() = runTest {
        startService()
        val notifier: Notifier = notifier()
        TWINS.forEach { id: String -> notifier.post(id, frame(id)) }

        notifier.cancel("service")

        assertNull(untagged())
        for (id: String in TWINS) assertNotNull(tagged(id), "cancelling the service took $id with it")
    }

    @Test
    fun `posting under the service's id updates the untagged notification and no tagged one appears`() = runTest {
        startService()
        val notifier: Notifier = notifier()

        assertEquals(NotificationResult.Posted, notifier.post("service", frame("service v2")))

        assertEquals("service v2", untagged()?.extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString())
        assertNull(tagged("service"))
        assertEquals(1, shadowOf(manager).allNotifications.size)
    }

    @Test
    fun `cancelAll takes the service and every twin`() = runTest {
        startService()
        val notifier: Notifier = notifier()
        TWINS.forEach { id: String -> notifier.post(id, frame(id)) }

        notifier.cancelAll()

        assertEquals(0, shadowOf(manager).allNotifications.size)
    }

    private companion object {
        /** `hashCode()` 0, 1, `Int.MIN_VALUE` and `Int.MIN_VALUE + 1`. */
        val TWINS: List<String> = listOf("\u0000", "\u0001", "loktory\n", "hrldmSk\u0004")
    }
}
