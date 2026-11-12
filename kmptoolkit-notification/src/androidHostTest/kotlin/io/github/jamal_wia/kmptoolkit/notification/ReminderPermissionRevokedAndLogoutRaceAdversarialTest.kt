package io.github.jamal_wia.kmptoolkit.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.jamal_wia.kmptoolkit.permission.PermissionStatus
import io.github.jamal_wia.kmptoolkit.permission.testing.FakePermissionController
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmHandler
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmNotification
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduleResult
import io.github.jamal_wia.kmptoolkit.scheduler.AlarmScheduler
import io.github.jamal_wia.kmptoolkit.scheduler.ScheduledAlarm
import io.github.jamal_wia.kmptoolkit.scheduler.createAlarmScheduler
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 17: a reminder fires after the user revoked the notification permission (the handler gets a typed
 * result, the alarm is not retried, the next grant works), and a logout (notifier.cancelAll plus
 * scheduler.cancelAll) racing alarms that are being delivered.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderPermissionRevokedAndLogoutRaceAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val shadowAlarms: ShadowAlarmManager get() = shadowOf(app.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val tray: List<Notification> get() = shadowOf(manager).allNotifications
    private val channel = NotificationChannelSpec("reminders", "Reminders")

    private fun receiver(): BroadcastReceiver =
        Class.forName("io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver").getDeclaredConstructor()
            .apply { isAccessible = true }.newInstance() as BroadcastReceiver

    @Suppress("DEPRECATION")
    private fun deliver(a: ShadowAlarmManager.ScheduledAlarm) = receiver().onReceive(app, shadowOf(requireNotNull(a.operation)).savedIntent)

    @Test
    fun `a reminder fired after the permission was revoked reports PermissionDenied once and the next fire after a grant posts`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted)
        val notifier: Notifier = createNotifier(app, permissions)
        val results: MutableList<NotificationResult> = CopyOnWriteArrayList()
        var latch = CountDownLatch(1)
        val scheduler: AlarmScheduler = createAlarmScheduler(app, handlerProvider = {
            listOf(object : AlarmHandler {
                override val type: String = "R"
                override suspend fun onFire(alarm: ScheduledAlarm) {
                    results += notifier.post(alarm.id, LocalNotification(alarm.notification.title, "b", channel))
                    latch.countDown()
                }
            })
        })
        val alarm = ScheduledAlarm("rem", "R", 9_000_000_000_000L, AlarmNotification("t", "b", "c"))
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm) })
        permissions.setStatus(io.github.jamal_wia.kmptoolkit.permission.CommonPermission.Notifications, PermissionStatus.Denied())
        deliver(shadowAlarms.scheduledAlarms.single())
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(listOf<NotificationResult>(NotificationResult.PermissionDenied), results)
        assertTrue(tray.isEmpty())
        assertEquals(1, shadowAlarms.scheduledAlarms.size, "the library neither retries nor drops the armed alarm")
        permissions.setStatus(io.github.jamal_wia.kmptoolkit.permission.CommonPermission.Notifications, PermissionStatus.Granted)
        latch = CountDownLatch(1)
        deliver(shadowAlarms.scheduledAlarms.single())
        assertTrue(latch.await(10, TimeUnit.SECONDS))
        assertEquals(NotificationResult.Posted, results.last())
        assertEquals(1, tray.size)
    }

    @Test
    fun `logout cancelAll of both modules while thirty alarms are delivered leaves no exception and nothing armed after the final cancel`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val notifier: Notifier = createNotifier(app, FakePermissionController(defaultStatus = PermissionStatus.Granted))
        val failures: MutableList<Throwable> = CopyOnWriteArrayList()
        val done = CountDownLatch(30)
        val scheduler: AlarmScheduler = createAlarmScheduler(app, handlerProvider = {
            listOf(object : AlarmHandler {
                override val type: String = "R"
                override suspend fun onFire(alarm: ScheduledAlarm) {
                    try { notifier.post(alarm.id, LocalNotification("t", "b", channel)) } catch (t: Throwable) { failures += t } finally { done.countDown() }
                }
            })
        })
        val ids: List<String> = (0 until 30).map { "lo-$it" }
        ids.forEach { runBlocking { scheduler.schedule(ScheduledAlarm(it, "R", 9_000_000_000_000L, AlarmNotification("t", "b", "c"))) } }
        val armed = shadowAlarms.scheduledAlarms.toList()
        val deliverer = Thread { armed.forEach { deliver(it) } }
        val logout = Thread { repeat(20) { notifier.cancelAll(); runBlocking { scheduler.cancelAll(ids) } } }
        deliverer.start(); logout.start(); deliverer.join(20_000); logout.join(20_000)
        assertTrue(done.await(20, TimeUnit.SECONDS))
        assertTrue(failures.isEmpty(), "$failures")
        runBlocking { scheduler.cancelAll(ids) }
        assertEquals(0, shadowAlarms.scheduledAlarms.size)
        notifier.cancelAll()
        assertTrue(tray.isEmpty())
    }
}
