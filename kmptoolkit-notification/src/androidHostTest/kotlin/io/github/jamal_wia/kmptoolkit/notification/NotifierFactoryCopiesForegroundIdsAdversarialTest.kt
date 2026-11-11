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
 * Copy semantics at an entry point that takes a collection at construction: `createNotifier(...,
 * foregroundIds)`. The binding of ids to the service's untagged `Int` is fixed when the notifier is
 * created, whatever the caller does later with the set it passed (a mutable set it keeps for its own
 * bookkeeping), exactly as a created notifier validated the set once.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotifierFactoryCopiesForegroundIdsAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val channel = NotificationChannelSpec(id = "fg", name = "Foreground")
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val config = NotificationConfig(minProgressInterval = Duration.ZERO)

    private fun frame(): LocalNotification = LocalNotification(title = "T", body = "B", channel = channel)

    private fun notifier(foregroundIds: Set<ForegroundNotificationId>): Notifier =
        createNotifier(context, permissions, config, foregroundIds)

    @Test
    fun `a foreground id removed from the caller's set after creation is still posted untagged`() {
        val ids: MutableSet<ForegroundNotificationId> = mutableSetOf(ForegroundNotificationId("a", 1001))
        val notifier: Notifier = notifier(ids)
        ids.clear()

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame()) })

        assertNotNull(shadowOf(manager).getNotification(null, 1001))
        assertNull(shadowOf(manager).getNotification("a", 1))
    }

    @Test
    fun `an id added to the caller's set after creation is posted tagged not untagged`() {
        val ids: MutableSet<ForegroundNotificationId> = mutableSetOf(ForegroundNotificationId("a", 1001))
        val notifier: Notifier = notifier(ids)
        ids += ForegroundNotificationId("b", 1002)

        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("b", frame()) })

        assertNotNull(shadowOf(manager).getNotification("b", 1))
        assertNull(shadowOf(manager).getNotification(null, 1002))
    }

    @Test
    fun `a set that fails validation is rejected at creation and the failure names the duplicate`() {
        val ids: Set<ForegroundNotificationId> = setOf(ForegroundNotificationId("a", 7), ForegroundNotificationId("b", 7))

        val failure: IllegalArgumentException = kotlin.test.assertFailsWith { notifier(ids) }

        assertEquals(true, failure.message.orEmpty().contains("platformId"))
    }
}
