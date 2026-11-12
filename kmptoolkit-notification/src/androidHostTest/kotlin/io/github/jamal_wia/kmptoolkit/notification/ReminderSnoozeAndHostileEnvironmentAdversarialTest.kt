package io.github.jamal_wia.kmptoolkit.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
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
import org.robolectric.shadows.ShadowLooper

/**
 * Round 17, second integration class: a reminder with a Snooze button (the tap re-arms the same alarm
 * through the scheduler and takes the notification down), a storm of alarms firing at once into the real
 * notifier, and the whole daily-reminder flow under a hostile default locale and time zone that changes
 * while alarms are armed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderSnoozeAndHostileEnvironmentAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val alarmManager: AlarmManager get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarms: ShadowAlarmManager get() = shadowOf(alarmManager)
    private val tray: List<Notification> get() = shadowOf(notificationManager).allNotifications
    private val channel = NotificationChannelSpec(id = "reminders", name = "Reminders")

    private fun newNotifier(): Notifier =
        createNotifier(context = app, permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted))

    private fun receiver(): BroadcastReceiver {
        val type: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver")
        return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as BroadcastReceiver
    }

    @Suppress("DEPRECATION")
    private fun deliver(alarm: ShadowAlarmManager.ScheduledAlarm) {
        receiver().onReceive(app, shadowOf(requireNotNull(alarm.operation)).savedIntent)
    }

    @Test
    fun `a Snooze button re-arms the fired reminder ten minutes later through the scheduler and clears the notification`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val notifier: Notifier = newNotifier()
        val scheduler: AlarmScheduler = createAlarmScheduler(app, handlerProvider = {
            listOf(object : AlarmHandler {
                override val type: String = "MED"
                override suspend fun onFire(alarm: ScheduledAlarm) {
                    notifier.post(
                        alarm.id,
                        LocalNotification(
                            alarm.notification.title, alarm.notification.body, channel,
                            actions = listOf(NotificationAction("snooze", "Snooze")),
                        ),
                        NotificationOptions(alertOnce = false),
                    )
                }
            })
        })
        val first = 9_000_000_000_000L
        val medication = ScheduledAlarm("med-1", "MED", first, AlarmNotification("Take pill", "2 mg", "reminders"), mapOf("dose" to "2"))
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(medication) })
        // The consumer's receiver for the Snooze button.
        val snoozed: MutableList<Long> = CopyOnWriteArrayList()
        app.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (NotificationActionIntent.actionId(intent) != "snooze") return
                val id: String = requireNotNull(NotificationActionIntent.notificationId(intent))
                val at: Long = (snoozed.lastOrNull() ?: first) + 10 * 60_000L
                runBlocking { scheduler.schedule(medication.copy(id = id, fireAtEpochMillis = at)) }
                notifier.cancel(id)
                snoozed += at
            }
        }, IntentFilter(NotificationActionIntent.defaultAction(app)), Context.RECEIVER_NOT_EXPORTED)

        repeat(3) { round: Int ->
            // The alarm fires; the handler posts the reminder (always audible, as documented for reminders).
            val fired = shadowAlarms.scheduledAlarms.single()
            assertEquals(first + round * 10 * 60_000L, fired.triggerAtMs)
            deliver(fired)
            val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
            while (tray.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
            assertEquals(1, tray.size, "round $round: the reminder is showing")
            assertEquals(0, tray.single().flags and Notification.FLAG_ONLY_ALERT_ONCE, "a reminder sounds every time")
            // The user taps Snooze.
            requireNotNull(tray.single().actions.single().actionIntent).send()
            ShadowLooper.idleMainLooper()
            assertEquals(round + 1, snoozed.size)
            assertTrue(tray.isEmpty(), "the Snooze handler took the notification down")
            assertEquals(1, shadowAlarms.scheduledAlarms.size, "snoozing replaces the alarm, never stacks a second one")
        }
        assertEquals(first + 30 * 60_000L, shadowAlarms.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun `sixty alarms firing at once into the real notifier all post and one id fired many times shows one notification`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val notifier: Notifier = newNotifier()
        val results: MutableList<NotificationResult> = CopyOnWriteArrayList()
        val done = CountDownLatch(60 + 40)
        createAlarmScheduler(app, handlerProvider = {
            listOf(object : AlarmHandler {
                override val type: String = "R"
                override suspend fun onFire(alarm: ScheduledAlarm) {
                    results += notifier.post(alarm.id, LocalNotification(alarm.notification.title, alarm.notification.body, channel))
                    done.countDown()
                }
            })
        }).let { scheduler: AlarmScheduler ->
            (0 until 60).forEach { index: Int ->
                runBlocking { scheduler.schedule(ScheduledAlarm("storm-$index", "R", 9_000_000_000_000L + index, AlarmNotification("t$index", "b", "c"))) }
            }
            runBlocking { scheduler.schedule(ScheduledAlarm("same", "R", 9_100_000_000_000L, AlarmNotification("same", "b", "c"))) }
        }
        val all: List<ShadowAlarmManager.ScheduledAlarm> = shadowAlarms.scheduledAlarms.toList()
        assertEquals(61, all.size)
        val sameAlarm: ShadowAlarmManager.ScheduledAlarm = all.single { it.triggerAtMs == 9_100_000_000_000L }
        val others: List<ShadowAlarmManager.ScheduledAlarm> = all - sameAlarm
        // The platform delivers them together from several threads; the same id is delivered 40 times.
        val threads: List<Thread> = (others + List(40) { sameAlarm }).chunked(10).map { chunk ->
            Thread { chunk.forEach { deliver(it) } }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join(10_000) }
        assertTrue(done.await(30, TimeUnit.SECONDS), "every fired alarm reached its handler (${done.count} left)")
        assertTrue(results.all { it == NotificationResult.Posted || it == NotificationResult.Coalesced }, "$results")
        assertEquals(61, tray.size, "60 distinct ids and one replaced-in-place id")
    }

    private fun nine(day: LocalDate, zone: ZoneId): Long = ZonedDateTime.of(day, LocalTime.of(9, 0), zone).toInstant().toEpochMilli()

    @Test
    fun `the daily reminder flow is unchanged under Turkish Arabic and Thai defaults and a time zone that changes while alarms are armed`() {
        val savedLocale: Locale = Locale.getDefault()
        val savedZone: TimeZone = TimeZone.getDefault()
        try {
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            val zone: ZoneId = ZoneId.of("Europe/Istanbul")
            val titles: MutableList<String> = CopyOnWriteArrayList()
            val latch = arrayOf(CountDownLatch(1))
            val notifier: Notifier = newNotifier()
            lateinit var scheduler: AlarmScheduler
            scheduler = createAlarmScheduler(app, handlerProvider = {
                listOf(object : AlarmHandler {
                    override val type: String = "DAILY"
                    override suspend fun onFire(alarm: ScheduledAlarm) {
                        titles += alarm.notification.title
                        notifier.post(alarm.id, LocalNotification(alarm.notification.title, alarm.notification.body, channel))
                        val next: Long = alarm.fireAtEpochMillis + 24 * 3_600_000L
                        check(scheduler.schedule(alarm.copy(fireAtEpochMillis = next)) == AlarmScheduleResult.Exact)
                        latch[0].countDown()
                    }
                })
            })
            val start: Long = nine(LocalDate.of(2026, 10, 1), zone)
            // An id with the dotted and dotless i, a Turkish upper-case trap, and a title in Thai digits.
            val id = "iİıI-reminder"
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            runBlocking { scheduler.schedule(ScheduledAlarm(id, "DAILY", start, AlarmNotification("başlık ๑๒๓", "gövde", "reminders"))) }
            val locales: List<Locale> = listOf(
                Locale.forLanguageTag("ar-SA-u-nu-arab"), Locale.forLanguageTag("th-TH-u-ca-buddhist-nu-thai"), Locale.forLanguageTag("tr-TR"), Locale.ROOT,
            )
            val zones: List<String> = listOf("Pacific/Kiritimati", "America/St_Johns", "Australia/Lord_Howe", "UTC")
            repeat(4) { day: Int ->
                Locale.setDefault(locales[day]); TimeZone.setDefault(TimeZone.getTimeZone(zones[day]))
                assertEquals(start + day * 24 * 3_600_000L, shadowAlarms.scheduledAlarms.single().triggerAtMs, "day $day")
                latch[0] = CountDownLatch(1)
                deliver(shadowAlarms.scheduledAlarms.single())
                assertTrue(latch[0].await(10, TimeUnit.SECONDS))
                assertEquals(1, tray.size)
                assertEquals(1, shadowAlarms.scheduledAlarms.size)
            }
            assertEquals(List(4) { "başlık ๑๒๓" }, titles)
            // Cancelling under yet another default finds the same alarm and the same notification.
            Locale.setDefault(Locale.forLanguageTag("lt-LT")); TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Apia"))
            runBlocking { scheduler.cancel(id) }
            notifier.cancel(id)
            assertEquals(0, shadowAlarms.scheduledAlarms.size)
            assertEquals(0, tray.size)
        } finally {
            Locale.setDefault(savedLocale)
            TimeZone.setDefault(savedZone)
        }
    }
}
