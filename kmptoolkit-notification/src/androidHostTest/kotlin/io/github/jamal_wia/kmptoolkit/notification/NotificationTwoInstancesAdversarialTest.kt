package io.github.jamal_wia.kmptoolkit.notification

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Round 20 (consumer setups): two [Notifier] instances in one process, which the guide neither
 * promises nor forbids (a foreground service that builds its own notifier next to the one the UI
 * got from DI is the realistic shape). What the contract does say, and what these tests pin:
 * the tray is one per process and is keyed by the id, `cancelAll` removes every notification this
 * app shows "not only the ones this instance posted", and the ordering rules are "per Notifier
 * instance". Instances created off the main thread are legal as well (`createNotifier` only keeps
 * the application context).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class NotificationTwoInstancesAdversarialTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = context.getSystemService(NotificationManager::class.java)
    private val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
    private val channel = NotificationChannelSpec(id = "two", name = "Two")
    private val noInterval = NotificationConfig(minProgressInterval = Duration.ZERO)

    private fun frame(title: String, percent: Int? = null): LocalNotification = LocalNotification(
        title = title,
        body = "b",
        channel = channel,
        progress = percent?.let { NotificationProgress.Determinate(it) },
    )

    private fun post(n: Notifier, id: String, notification: LocalNotification): NotificationResult =
        runBlocking { n.post(id, notification) }

    private fun shownTitle(id: String): String? =
        shadowOf(manager).getNotification(id, 1)?.extras?.getString(Notification.EXTRA_TITLE)

    @Test
    fun `two notifiers share one tray - the later post replaces the earlier one and either can cancel it`() {
        val a: Notifier = createNotifier(context, permissions)
        val b: Notifier = createNotifier(context, permissions)

        assertEquals(NotificationResult.Posted, post(a, "x", frame("from a")))
        assertEquals(NotificationResult.Posted, post(b, "x", frame("from b")))

        assertEquals(1, shadowOf(manager).allNotifications.size, "one id is one notification, whoever posted it")
        assertEquals("from b", shownTitle("x"))

        a.cancel("x")
        assertNull(shownTitle("x"), "cancel by id reaches a notification the other instance posted")

        assertEquals(NotificationResult.Posted, post(a, "y", frame("a y")))
        assertEquals(NotificationResult.Posted, post(b, "z", frame("b z")))
        b.cancelAll()
        assertEquals(0, shadowOf(manager).allNotifications.size, "cancelAll removes what the other instance posted, as documented")
    }

    @Test
    fun `each instance has its own progress baseline - the second instance posts a bucket the first already showed`() {
        val a: Notifier = createNotifier(context, permissions, noInterval)
        val b: Notifier = createNotifier(context, permissions, noInterval)

        assertEquals(NotificationResult.Posted, post(a, "dl", frame("a", 20)))
        assertEquals(NotificationResult.Coalesced, post(a, "dl", frame("a", 21)), "control: same bucket, same instance")
        assertEquals(NotificationResult.Posted, post(b, "dl", frame("b", 21)), "B never showed this id, so it has nothing to coalesce against")
        assertEquals("b", shownTitle("dl"))
    }

    @Test
    fun `two instances with different button actions each stamp their own on the buttons they post`() {
        val a: Notifier = createNotifier(context, permissions, NotificationConfig(actionBroadcastAction = "app.A"))
        val b: Notifier = createNotifier(context, permissions, NotificationConfig(actionBroadcastAction = "app.B"))
        val withButton: LocalNotification = LocalNotification(
            title = "t",
            body = "b",
            channel = channel,
            actions = listOf(NotificationAction(id = "go", label = "Go")),
        )

        assertEquals(NotificationResult.Posted, post(a, "a-id", withButton))
        assertEquals(NotificationResult.Posted, post(b, "b-id", withButton))

        val actionOf: (String) -> String? = { id: String ->
            val button: Notification.Action? = shadowOf(manager).getNotification(id, 1).actions?.firstOrNull()
            shadowOf(button?.actionIntent).savedIntent?.action
        }
        assertEquals("app.A", actionOf("a-id"))
        assertEquals("app.B", actionOf("b-id"))
    }

    @Test
    fun `a notifier created on a background thread and one on the main thread run side by side without exception`() {
        val created: AtomicReference<Notifier> = AtomicReference()
        val failure: AtomicReference<Throwable> = AtomicReference()
        val done = CountDownLatch(1)
        Thread {
            try {
                created.set(createNotifier(context, permissions))
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS), "creation must not block")
        assertNull(failure.get(), "createNotifier is legal off the main thread")
        val background: Notifier = assertNotNull(created.get())
        val main: Notifier = createNotifier(context, permissions)

        val workers: List<Thread> = (0 until 8).map { index: Int ->
            Thread {
                val notifier: Notifier = if (index % 2 == 0) background else main
                repeat(40) { round: Int ->
                    try {
                        post(notifier, "t$index", frame("t$index r$round"))
                        if (round % 7 == 0) notifier.cancel("t$index")
                    } catch (t: Throwable) {
                        failure.compareAndSet(null, t)
                    }
                }
            }
        }
        workers.forEach { it.start() }
        workers.forEach { it.join(30_000) }

        assertNull(failure.get(), "no call may throw: ${failure.get()}")
        for (index in 0 until 8) {
            assertEquals("t$index r39", shownTitle("t$index"), "the last frame of every id is what the tray shows")
        }
        assertEquals(8, shadowOf(manager).allNotifications.size)
    }
}
