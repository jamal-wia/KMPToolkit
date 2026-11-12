package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLog
import org.robolectric.shadows.ShadowNotificationManager

/**
 * `docs/kmptoolkit-notification/04-api-reference.md`, `cancel`: a platform refusal never throws, "on
 * Android it is logged under the tag `KmpToolkitNotification` and the notification may stay on the
 * tray". Nothing asserted the log line, so "logged" was a promise no test could break. `cancelAll`
 * says "a refusal is logged" as well, and each platform call of a `cancel` is guarded on its own, so a
 * refused tagged removal does not skip the legacy sweep that follows it.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [NotifierRefusalLogAdversarialTest.RefusingShadowNotificationManager::class])
class NotifierRefusalLogAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "log", name = "Log")

    @Before
    fun clearLog() {
        ShadowLog.clear()
    }

    @After
    fun heal() {
        RefusingShadowNotificationManager.refuseTaggedCancel.set(false)
        RefusingShadowNotificationManager.refuseCancelAll.set(false)
    }

    private fun frame(title: String): LocalNotification = LocalNotification(title = title, body = "body", channel = channel)

    private fun notifier(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"), ProgressCoalescer(minInterval = Duration.ZERO),
    )

    private fun warnings(): List<ShadowLog.LogItem> =
        ShadowLog.getLogsForTag("KmpToolkitNotification").filter { it.type == Log.WARN }

    @Test
    fun `a refused cancel is logged as a warning under the documented tag with its cause`() {
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("a", frame("a")) }
        RefusingShadowNotificationManager.refuseTaggedCancel.set(true)

        notifier.cancel("a")

        val logged: List<ShadowLog.LogItem> = warnings()
        assertTrue(logged.isNotEmpty(), "the refusal was swallowed without a log line")
        assertTrue(logged.any { it.throwable is SecurityException }, "the log line does not carry the cause")
    }

    @Test
    fun `a refused cancelAll is logged as a warning under the documented tag with its cause`() {
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("a", frame("a")) }
        RefusingShadowNotificationManager.refuseCancelAll.set(true)

        notifier.cancelAll()

        val logged: List<ShadowLog.LogItem> = warnings()
        assertTrue(logged.isNotEmpty(), "the refusal was swallowed without a log line")
        assertTrue(logged.any { it.throwable is SecurityException }, "the log line does not carry the cause")
    }

    @Test
    fun `a refused tagged removal does not skip the legacy sweep that follows it`() {
        val notifier: Notifier = notifier()
        val legacyId: Int = legacyPlatformNotificationId("a")
        manager.notify(
            legacyId,
            Notification.Builder(context, "log").setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("2.x").build(),
        )
        RefusingShadowNotificationManager.refuseTaggedCancel.set(true)

        notifier.cancel("a")

        assertEquals(emptyList(), shadowOf(manager).allNotifications.filter { it.extras.getString(Notification.EXTRA_TITLE) == "2.x" })
    }

    @Test
    fun `control - a healthy cancel logs no warning`() {
        val notifier: Notifier = notifier()
        runBlocking { notifier.post("a", frame("a")) }

        notifier.cancel("a")
        notifier.cancelAll()

        assertEquals(emptyList(), warnings())
    }

    @Implements(NotificationManager::class)
    class RefusingShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun cancel(tag: String?, id: Int) {
            if (tag != null && refuseTaggedCancel.get()) throw SecurityException("cancel refused")
            super.cancel(tag, id)
        }

        @Implementation
        public override fun cancelAll() {
            if (refuseCancelAll.get()) throw SecurityException("cancelAll refused")
            super.cancelAll()
        }

        companion object {
            val refuseTaggedCancel: AtomicBoolean = AtomicBoolean(false)
            val refuseCancelAll: AtomicBoolean = AtomicBoolean(false)
        }
    }
}
