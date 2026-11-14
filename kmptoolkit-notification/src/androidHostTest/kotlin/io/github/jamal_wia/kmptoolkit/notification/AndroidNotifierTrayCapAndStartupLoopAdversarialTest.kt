package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * Round 19 (docs as an executable contract, OS-state flips): two normative passages of the notification docs.
 *
 * 05-platform-notes, "Both platforms": "Android caps the notifications an app may show at once ... A new
 * notification posted while the app already has that many active is dropped by the system without an
 * exception or a return value, so `notify` returns normally and the notifier answers `Posted` ... An update of a
 * notification that is already showing is not affected." The platform's cap is modelled by a shadow that drops a
 * new `(tag, id)` pair when the tray is full.
 *
 * 03-guide, "Creating channels up front, and retiring one": the `App.onCreate` sample (delete the retired channel,
 * ensure every current one) is run for many process launches in a row, each with a new notifier: the channel set
 * is the same after every launch, the retired channel never comes back, and every launch can post.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [33], shadows = [AndroidNotifierTrayCapAndStartupLoopAdversarialTest.CappedShadowNotificationManager::class])
class AndroidNotifierTrayCapAndStartupLoopAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "cap", name = "Cap")

    @After
    fun heal() {
        CappedShadowNotificationManager.cap.set(Int.MAX_VALUE)
        CappedShadowNotificationManager.dropped.set(0)
    }

    private fun notifier(): Notifier = AndroidNotifier(
        context, permissions, NotificationRenderer(context, "test.ACTION"),
        ProgressCoalescer(bucketPercent = 10, minInterval = Duration.ZERO),
    )

    private fun frame(title: String, percent: Int? = null): LocalNotification = LocalNotification(
        title = title,
        body = "b",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun post(notifier: Notifier, id: String, notification: LocalNotification): NotificationResult =
        runBlocking { notifier.post(id, notification) }

    private fun titleOf(id: String): String? =
        shadowOf(manager).getNotification(id, 1)?.extras?.getString(Notification.EXTRA_TITLE)

    @Test
    fun `a new notification past the platform cap is dropped silently and answered Posted while an update of a showing one is not affected`() {
        CappedShadowNotificationManager.cap.set(3)
        val notifier: Notifier = notifier()
        for (n: Int in 0 until 3) assertEquals(NotificationResult.Posted, post(notifier, "n$n", frame("first-$n")))

        val overflow: NotificationResult = post(notifier, "n3", frame("overflow"))
        assertEquals(NotificationResult.Posted, overflow, "the notifier cannot know and says Posted, as documented")
        assertNull(titleOf("n3"), "the platform dropped it")
        assertEquals(1, CappedShadowNotificationManager.dropped.get())

        // An update of a notification that is already showing is not affected by the cap.
        assertEquals(NotificationResult.Posted, post(notifier, "n1", frame("updated-1")))
        assertEquals("updated-1", titleOf("n1"))
        assertEquals(3, shadowOf(manager).allNotifications.size)

        // A cancel makes room: the next distinct id shows.
        notifier.cancel("n0")
        assertEquals(NotificationResult.Posted, post(notifier, "n3", frame("room-now")))
        assertEquals("room-now", titleOf("n3"))

        // A refused-by-cap frame never turns into an exception or a failure of the calls after it.
        notifier.cancelAll()
        assertEquals(0, shadowOf(manager).allNotifications.size)
        assertEquals(NotificationResult.Posted, post(notifier, "n0", frame("again")))
    }

    @Test
    fun `the on create sample of the guide run for many launches keeps the channel set stable and never brings the retired channel back`() {
        val remindersV1 = NotificationChannelSpec(id = "reminders", name = "Reminders", sound = NotificationSound.Silent)
        val remindersV2 = NotificationChannelSpec(id = "reminders-v2", name = "Reminders", sound = NotificationSound.Default)
        val downloads = NotificationChannelSpec(id = "downloads", name = "Downloads", importance = NotificationImportance.Low)
        // The shipped, wrong channel that the guide's sample retires.
        NotificationChannels.ensure(context, remindersV1)
        assertEquals(1, shadowOf(manager).notificationChannels.size)

        repeat(40) { launch: Int ->
            // Application.onCreate of the guide.
            NotificationChannels.delete(context, "reminders")
            listOf(remindersV2, downloads).forEach { NotificationChannels.ensure(context, it) }

            val ids: Set<String> = shadowOf(manager).notificationChannels.map { (it as NotificationChannel).id }.toSet()
            assertEquals(setOf("reminders-v2", "downloads"), ids, "launch $launch")

            val notifier: Notifier = notifier()
            assertEquals(
                NotificationResult.Posted,
                post(notifier, "r", LocalNotification(title = "t$launch", body = "b", channel = remindersV2)),
                "launch $launch",
            )
            assertEquals(
                NotificationResult.Posted,
                post(notifier, "d", LocalNotification(title = "t$launch", body = "b", channel = downloads, progress = NotificationProgress.Determinate(launch % 90))),
                "launch $launch",
            )
            assertEquals(setOf("reminders-v2", "downloads"), shadowOf(manager).notificationChannels.map { (it as NotificationChannel).id }.toSet(), "launch $launch after posting")
            assertEquals(2, shadowOf(manager).allNotifications.size, "launch $launch")
            assertNotNull(shadowOf(manager).getNotification("r", 1))
        }
    }

    /** Drops a new `(tag, id)` pair when [cap] notifications are showing, the way the platform does. */
    @Implements(NotificationManager::class)
    class CappedShadowNotificationManager : ShadowNotificationManager() {

        @Implementation
        public override fun notify(tag: String?, id: Int, notification: Notification?) {
            val showing: Boolean = getNotification(tag, id) != null
            if (!showing && allNotifications.size >= cap.get()) {
                dropped.incrementAndGet()
                return
            }
            super.notify(tag, id, notification)
        }

        companion object {
            val cap: AtomicInteger = AtomicInteger(Int.MAX_VALUE)
            val dropped: AtomicInteger = AtomicInteger(0)
        }
    }
}
