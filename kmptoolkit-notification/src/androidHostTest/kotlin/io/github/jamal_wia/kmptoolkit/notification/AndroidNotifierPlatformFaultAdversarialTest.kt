package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowNotificationManager

/**
 * The notifier contract says nothing throws for a runtime condition. These tests make the platform
 * refuse the removal calls (a dead binder, a revoked right) and check that the contract still holds
 * and that a frame that did reach the tray is not reported as a failure.
 */
@RunWith(AndroidJUnit4::class)
@Config(shadows = [AndroidNotifierPlatformFaultAdversarialTest.RefusingShadowNotificationManager::class])
class AndroidNotifierPlatformFaultAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "fault", name = "Fault")

    @After
    fun heal() {
        RefusingShadowNotificationManager.refuseTaggedCancel.set(false)
        RefusingShadowNotificationManager.refuseUntaggedCancel.set(false)
        RefusingShadowNotificationManager.refuseCancelAll.set(false)
    }

    private fun frame(title: String): LocalNotification = LocalNotification(title = title, body = "body", channel = channel)

    private fun notifier(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"), ProgressCoalescer(minInterval = Duration.ZERO),
    )

    private fun titles(): List<String> =
        shadowOf(manager).allNotifications.map { it.extras.getString(Notification.EXTRA_TITLE).orEmpty() }.sorted()

    @Test
    fun `a platform refusal of cancel does not escape cancel`() {
        val notifier: Notifier = notifier()
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("a")) })
        RefusingShadowNotificationManager.refuseTaggedCancel.set(true)

        val escaped: Throwable? = runCatching { notifier.cancel("a") }.exceptionOrNull()

        assertEquals(null, escaped, "cancel threw ${escaped?.javaClass?.simpleName}, but nothing may throw for a runtime condition")
    }

    @Test
    fun `a platform refusal of cancelAll does not escape cancelAll`() {
        val notifier: Notifier = notifier()
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("a", frame("a")) })
        RefusingShadowNotificationManager.refuseCancelAll.set(true)

        val escaped: Throwable? = runCatching { notifier.cancelAll() }.exceptionOrNull()

        assertEquals(null, escaped, "cancelAll threw ${escaped?.javaClass?.simpleName}, but nothing may throw for a runtime condition")
    }

    @Test
    fun `a refused legacy sweep after the frame reached the tray does not turn the post into a failure`() {
        val notifier: Notifier = notifier()
        RefusingShadowNotificationManager.refuseUntaggedCancel.set(true)

        val result: NotificationResult = runBlocking { notifier.post("a", frame("a")) }

        assertEquals(listOf("a"), titles(), "the frame must be on the tray")
        assertEquals(NotificationResult.Posted, result, "the frame is showing, yet the post reported ${result}")
    }

    @Implements(NotificationManager::class)
    class RefusingShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun cancel(tag: String?, id: Int) {
            if (tag != null && refuseTaggedCancel.get()) throw SecurityException("cancel refused")
            if (tag == null && refuseUntaggedCancel.get()) throw SecurityException("legacy cancel refused")
            super.cancel(tag, id)
        }

        @Implementation
        public override fun cancelAll() {
            if (refuseCancelAll.get()) throw SecurityException("cancelAll refused")
            super.cancelAll()
        }

        companion object {
            val refuseTaggedCancel: AtomicBoolean = AtomicBoolean(false)
            val refuseUntaggedCancel: AtomicBoolean = AtomicBoolean(false)
            val refuseCancelAll: AtomicBoolean = AtomicBoolean(false)
        }
    }
}
