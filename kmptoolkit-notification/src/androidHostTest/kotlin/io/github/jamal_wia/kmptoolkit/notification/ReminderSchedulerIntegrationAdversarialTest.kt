package io.github.jamal_wia.kmptoolkit.notification

import android.app.AlarmManager
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
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
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 17 (consumer-shaped integration): the two real Android implementations used together the way an
 * app wires them. A daily reminder is an alarm whose handler posts a notification and arms the next day's
 * alarm; the receiver is the library's own, reached through the intent the platform would deliver.
 * Nothing here is faked except the clock the platform owns and the permission answer.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReminderSchedulerIntegrationAdversarialTest {

    private val app: Application = ApplicationProvider.getApplicationContext()
    private val notificationManager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val alarmManager: AlarmManager get() = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarms: ShadowAlarmManager get() = shadowOf(alarmManager)
    private val tray: List<Notification> get() = shadowOf(notificationManager).allNotifications
    private val channel = NotificationChannelSpec(id = "reminders", name = "Reminders")

    private fun registerLauncherActivity() {
        val component = android.content.ComponentName(app.packageName, "com.example.IntegrationLauncher")
        val packageManager = shadowOf(app.packageManager)
        packageManager.addActivityIfNotPresent(component)
        packageManager.addIntentFilterForActivity(
            component,
            IntentFilter(Intent.ACTION_MAIN).apply { addCategory(Intent.CATEGORY_LAUNCHER) },
        )
    }

    private fun title(n: Notification): String = n.extras.getString(Notification.EXTRA_TITLE).orEmpty()

    private fun newNotifier(): Notifier =
        createNotifier(context = app, permissions = FakePermissionController(defaultStatus = PermissionStatus.Granted))

    private fun receiver(): android.content.BroadcastReceiver {
        // The library's receiver is internal to its module; the platform builds it by class name, so does this.
        val type: Class<*> = Class.forName("io.github.jamal_wia.kmptoolkit.scheduler.AlarmReceiver")
        return type.getDeclaredConstructor().apply { isAccessible = true }.newInstance() as android.content.BroadcastReceiver
    }

    /** What AlarmManager does at the armed instant: deliver the PendingIntent's broadcast to the receiver. */
    private fun fireOnlyArmedAlarm() {
        val armed: List<ShadowAlarmManager.ScheduledAlarm> = shadowAlarms.scheduledAlarms
        assertEquals(1, armed.size, "exactly one alarm must be armed before it fires")
        receiver().onReceive(app, shadowOf(requireNotNull(armed.single().operation)).savedIntent)
    }

    /** The reminder a consumer writes: post at fire time, then arm the next occurrence from the same handler. */
    private class DailyReminderHandler(
        private val notifier: Notifier,
        private val schedulerOf: () -> AlarmScheduler,
        private val zone: ZoneId,
        private val channel: NotificationChannelSpec,
        private val log: MutableList<ScheduledAlarm>,
        private val done: () -> Unit,
    ) : AlarmHandler {
        override val type: String = "DAILY"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            log += alarm
            notifier.post(
                alarm.id,
                LocalNotification(alarm.notification.title, alarm.notification.body, channel, contentExtras = alarm.payload),
            )
            val firedDay: LocalDate = java.time.Instant.ofEpochMilli(alarm.fireAtEpochMillis).atZone(zone).toLocalDate()
            val next: Long = nextNine(firedDay.plusDays(1), zone)
            val result: AlarmScheduleResult = schedulerOf().schedule(
                alarm.copy(
                    fireAtEpochMillis = next,
                    notification = alarm.notification.copy(title = "Day ${firedDay.plusDays(1)}"),
                ),
            )
            check(result == AlarmScheduleResult.Exact) { "re-arm answered $result" }
            done()
        }
    }

    /** Runs [days] consecutive fires of a daily 09:00 reminder, optionally as a brand new process each day. */
    private fun runDailyReminder(zone: ZoneId, start: LocalDate, days: Int, restartEachDay: Boolean) {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val log: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        var latch = CountDownLatch(1)
        var scheduler: AlarmScheduler? = null

        fun boot() {
            val notifier: Notifier = newNotifier()
            val handler = DailyReminderHandler(notifier, { requireNotNull(scheduler) }, zone, channel, log) { latch.countDown() }
            scheduler = createAlarmScheduler(app, handlerProvider = { listOf(handler) })
        }
        boot()
        val first: Long = nextNine(start, zone)
        assertEquals(
            AlarmScheduleResult.Exact,
            runBlocking { requireNotNull(scheduler).schedule(ScheduledAlarm("daily", "DAILY", first, AlarmNotification("Day $start", "Drink water", "reminders"), mapOf("k" to "v"))) },
        )
        var expected: Long = first
        for (day in 0 until days) {
            assertEquals(expected, shadowAlarms.scheduledAlarms.single().triggerAtMs, "armed instant of day $day in $zone")
            latch = CountDownLatch(1)
            if (restartEachDay && day > 0) boot()
            fireOnlyArmedAlarm()
            assertTrue(latch.await(10, TimeUnit.SECONDS), "handler of day $day did not finish")
            val fired: ScheduledAlarm = log.last()
            assertEquals(expected, fired.fireAtEpochMillis, "the handler is told the instant it was armed for")
            assertEquals("daily", fired.id)
            assertEquals(mapOf("k" to "v"), fired.payload)
            // One notification, replaced in place each day, showing the title the alarm carried.
            assertEquals(1, tray.size, "the same id replaces yesterday's notification")
            expected = nextNine(start.plusDays(day + 1L), zone)
        }
        assertEquals(1, shadowAlarms.scheduledAlarms.size, "re-arming from the handler never stacks a second alarm")
        assertEquals(days, log.size)
    }

    @Test
    fun `a daily 0900 reminder across the New York DST end and Europe London DST start fires at local nine every day with one alarm armed`() {
        runDailyReminder(ZoneId.of("America/New_York"), LocalDate.of(2026, 10, 28), 8, restartEachDay = false)
        runDailyReminder(ZoneId.of("Europe/London"), LocalDate.of(2027, 3, 25), 6, restartEachDay = true)
    }

    @Test
    fun `a daily reminder across a leap day a month end and a year end keeps its instants and its single alarm`() {
        runDailyReminder(ZoneId.of("UTC"), LocalDate.of(2028, 2, 27), 5, restartEachDay = false)
        runDailyReminder(ZoneId.of("Asia/Kolkata"), LocalDate.of(2026, 12, 29), 5, restartEachDay = true)
        runDailyReminder(ZoneId.of("Australia/Lord_Howe"), LocalDate.of(2026, 10, 2), 5, restartEachDay = false)
    }

    @Test
    fun `an alarm id and a notification id that are equal stay independent on Android in every order`() {
        registerLauncherActivity()
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val notifier: Notifier = newNotifier()
        val scheduler: AlarmScheduler = createAlarmScheduler(app)
        val alarm = ScheduledAlarm("shared", "X", 9_000_000_000_000L, AlarmNotification("alarm", "b", "reminders"))
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm) })
        val posted = runBlocking { notifier.post("shared", LocalNotification("posted", "b", channel, contentExtras = mapOf("a" to "b"))) }
        assertEquals(NotificationResult.Posted, posted)
        val tapTarget = requireNotNull(tray.single().contentIntent)
        val alarmOperation = requireNotNull(shadowAlarms.scheduledAlarms.single().operation)

        notifier.cancel("shared")
        assertEquals(1, shadowAlarms.scheduledAlarms.size, "Notifier.cancel does not disarm an alarm of the same id on Android")
        assertFalse(shadowOf(alarmOperation).isCanceled)
        assertTrue(tray.isEmpty())

        runBlocking { notifier.post("shared", LocalNotification("again", "b", channel, contentExtras = mapOf("a" to "b"))) }
        val secondTap = requireNotNull(tray.single().contentIntent)
        runBlocking { scheduler.cancel("shared") }
        assertTrue(shadowAlarms.scheduledAlarms.isEmpty())
        assertEquals(listOf("again"), tray.map(::title), "AlarmScheduler.cancel does not remove a notification of the same id on Android")
        assertFalse(shadowOf(secondTap).isCanceled, "and does not invalidate its tap target")
        assertNotNull(tapTarget)

        // Re-arm and let it fire: the handler-less receiver drops it, the notifier's notification is untouched.
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm) })
        notifier.cancelAll()
        assertEquals(1, shadowAlarms.scheduledAlarms.size, "Notifier.cancelAll never touches an alarm")
        assertFalse(shadowOf(requireNotNull(shadowAlarms.scheduledAlarms.single().operation)).isCanceled)
    }

    @Test
    fun `losing every alarm without telling the library and re-arming from the consumer table never saturates an id over many reboots`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler: AlarmScheduler = createAlarmScheduler(app)
        val table: List<ScheduledAlarm> = (0 until 6).map {
            ScheduledAlarm("r$it", "T", 9_000_000_000_000L + it, AlarmNotification("t$it", "b", "c"))
        }
        repeat(12) { boot: Int ->
            // The reboot: AlarmManager forgets the alarms; the library is never told (it stores nothing).
            shadowAlarms.scheduledAlarms.toList().forEach { alarmManager.cancel(requireNotNull(it.operation)) }
            assertEquals(0, shadowAlarms.scheduledAlarms.size)
            // The consumer's boot receiver: re-schedule the whole table.
            table.forEach { alarm ->
                assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm) }, "boot $boot, ${alarm.id}")
            }
            assertEquals(table.size, shadowAlarms.scheduledAlarms.size)
        }
        // And the user's own edit after all that still replaces by id.
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(table[0].copy(fireAtEpochMillis = 9_100_000_000_000L)) })
        assertEquals(table.size, shadowAlarms.scheduledAlarms.size)
        assertTrue(shadowAlarms.scheduledAlarms.any { it.triggerAtMs == 9_100_000_000_000L })
    }

    @Test
    fun `a tapped Cancel button reaches the consumer receiver with the ids and a following frame starts a fresh run`() {
        val received: MutableList<Pair<String?, String?>> = CopyOnWriteArrayList()
        val action: String = NotificationActionIntent.defaultAction(app)
        app.registerReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                received += NotificationActionIntent.actionId(intent) to NotificationActionIntent.notificationId(intent)
                // The consumer's response to its own Cancel button: stop the task, take the notification down.
                notifier.cancel(requireNotNull(NotificationActionIntent.notificationId(intent)))
            }
        }, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
        val cancelButton = NotificationAction(id = "cancel-download", label = "Cancel")
        fun frame(percent: Int): LocalNotification = LocalNotification(
            "Downloading", "$percent %", channel,
            progress = NotificationProgress.Determinate(percent), ongoing = true, actions = listOf(cancelButton),
        )
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("download", frame(10)) })
        val button = requireNotNull(tray.single().actions.single().actionIntent)
        button.send()
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(listOf<Pair<String?, String?>>("cancel-download" to "download"), received)
        assertTrue(tray.isEmpty(), "the consumer took the notification down from its receiver")
        // A new run under the same id is not judged against the cancelled run's 10 %.
        assertEquals(NotificationResult.Posted, runBlocking { notifier.post("download", frame(10)) })
        assertEquals(1, tray.size)
    }

    private val notifier: Notifier by lazy { newNotifier() }

    @Test
    fun `a progress notification the user swiped away stays away inside its bucket and comes back with the next bucket and the terminal frame`() {
        val time = kotlin.time.TestTimeSource()
        val coalescingNotifier: Notifier = AndroidNotifier(
            app, FakePermissionController(defaultStatus = PermissionStatus.Granted),
            NotificationRenderer(app, "test.ACTION"),
            ProgressCoalescer(bucketPercent = 10, minInterval = kotlin.time.Duration.ZERO, timeSource = time),
        )
        fun frame(percent: Int): LocalNotification =
            LocalNotification("Upload", "uploading", channel, progress = NotificationProgress.Determinate(percent))
        assertEquals(NotificationResult.Posted, runBlocking { coalescingNotifier.post("up", frame(10)) })
        val shown = notificationManager.activeNotifications.single { it.tag == "up" }
        notificationManager.cancel(shown.tag, shown.id) // the system, not the library: a swipe
        assertTrue(tray.isEmpty())
        // The library is never told about a swipe (documented): same bucket is suppressed, no resurrection.
        time.plusAssign(kotlin.time.Duration.parse("1s"))
        assertEquals(NotificationResult.Coalesced, runBlocking { coalescingNotifier.post("up", frame(12)) })
        assertTrue(tray.isEmpty())
        // The next bucket is a new frame and shows again, and the terminal frame always does.
        assertEquals(NotificationResult.Posted, runBlocking { coalescingNotifier.post("up", frame(20)) })
        assertEquals(1, tray.size)
        notificationManager.cancel(shown.tag, shown.id)
        assertEquals(NotificationResult.Posted, runBlocking { coalescingNotifier.post("up", frame(100)) })
        assertEquals(listOf("uploading"), tray.map { it.extras.getString(Notification.EXTRA_TEXT) })
    }
}

private fun nextNine(day: LocalDate, zone: ZoneId): Long =
    ZonedDateTime.of(day, LocalTime.of(9, 0), zone).toInstant().toEpochMilli()
