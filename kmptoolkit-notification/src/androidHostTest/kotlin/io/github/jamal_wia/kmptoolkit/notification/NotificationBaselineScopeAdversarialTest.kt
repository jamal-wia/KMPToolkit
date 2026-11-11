package io.github.jamal_wia.kmptoolkit.notification

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
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Scope of the progress baseline, read from `Notifier.cancel` ("forgets its progress-coalescing state
 * so a later run under the same id starts fresh") and `cancelAll` ("removes every notification"): a
 * baseline that outlives its notification would answer `Coalesced` for a frame the tray no longer
 * shows, and one that is dropped too widely would let redundant frames through. Also that the
 * `foregroundIds` set handed to `createNotifier` is read once, not kept as a live view.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationBaselineScopeAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "scope", name = "Scope")

    private fun frame(percent: Int): LocalNotification =
        LocalNotification("t", "b", channel, progress = NotificationProgress.Determinate(percent))

    private fun post(n: Notifier, id: String, percent: Int): NotificationResult = runBlocking { n.post(id, frame(percent)) }

    @Test
    fun `cancel of one id drops that baseline only and cancelAll drops every baseline, foreground ids included`() {
        val n: Notifier = createNotifier(
            context, permissions, NotificationConfig(minProgressInterval = Duration.ZERO),
            setOf(ForegroundNotificationId("svc", 4242)),
        )
        listOf("a", "b", "svc").forEach { id: String ->
            assertEquals(NotificationResult.Posted, post(n, id, 20), id)
            assertEquals(NotificationResult.Coalesced, post(n, id, 21), "control $id")
        }

        n.cancel("a")
        assertEquals(NotificationResult.Posted, post(n, "a", 21), "a run after cancel starts fresh")
        assertEquals(NotificationResult.Coalesced, post(n, "b", 21), "cancel of 'a' must not touch the baseline of 'b'")
        assertEquals(NotificationResult.Coalesced, post(n, "svc", 21), "cancel of 'a' must not touch the baseline of the service id")

        n.cancelAll()
        assertEquals(0, shadowOf(manager).allNotifications.size)
        listOf("a", "b", "svc").forEach { id: String ->
            assertEquals(NotificationResult.Posted, post(n, id, 21), "after cancelAll '$id' must post: its notification is gone")
        }
        assertNotNull(shadowOf(manager).getNotification("a", 1))
        assertNotNull(shadowOf(manager).getNotification(null, 4242))
    }

    @Test
    fun `the foreground set is read when the notifier is created, not kept as a live view`() {
        val set: MutableSet<ForegroundNotificationId> = mutableSetOf(ForegroundNotificationId("svc", 4242))
        val n: Notifier = createNotifier(context, permissions, NotificationConfig(), set)
        set.clear()
        set += ForegroundNotificationId("other", 77)

        assertEquals(NotificationResult.Posted, post(n, "svc", 10))
        assertEquals(NotificationResult.Posted, post(n, "other", 10))

        assertNotNull(shadowOf(manager).getNotification(null, 4242), "svc was bound at creation")
        assertNull(shadowOf(manager).getNotification(null, 77), "a binding added to the set afterwards must not apply")
        assertNotNull(shadowOf(manager).getNotification("other", 1))
        n.cancel("svc")
        assertNull(shadowOf(manager).getNotification(null, 4242))
    }
}
