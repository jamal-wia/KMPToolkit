package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * An alarm is an absolute instant (`RTC_WAKEUP`, epoch milliseconds): the zone the process runs in
 * when it is scheduled, when it is re-armed, when it fires and when it is cancelled has no say. Swept
 * over the first daylight-saving transitions of 2026 and 2027 in zones whose shifts are an hour, half
 * an hour, a day (Apia), and a gap that never reappears: the instant one millisecond before, at and
 * after every transition is armed under that zone, the zone is then switched to another one (and the
 * locale to one with other digits) while the alarm is armed, re-armed, fired and cancelled, and the
 * platform must hold, and the handler receive, exactly the instant that was asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerZoneAndLocaleAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()
    private val delivered = LinkedBlockingQueue<ScheduledAlarm>()

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun restore() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
        AlarmDispatch.reset()
    }

    private val handler = object : AlarmHandler {
        override val type: String = "T"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            delivered.put(alarm)
        }
    }

    private fun transitions(zone: String): List<Long> {
        val rules = ZoneId.of(zone).rules
        val out: MutableList<Long> = mutableListOf()
        var at: java.time.Instant = java.time.Instant.parse("2026-01-01T00:00:00Z")
        repeat(4) {
            val next = rules.nextTransition(at) ?: return@repeat
            out += next.instant.toEpochMilli()
            at = next.instant
        }
        return out
    }

    private fun alarm(id: String, at: Long, title: String): ScheduledAlarm =
        ScheduledAlarm(id, "T", at, AlarmNotification(title, "b", "c"), mapOf("zone" to TimeZone.getDefault().id))

    private fun armed(): List<Pair<ScheduledAlarm, Long>> = shadowAlarmManager.scheduledAlarms.map { a: ShadowAlarmManager.ScheduledAlarm ->
        assertNotNull(AlarmIntents.fromIntent(shadowOf(a.operation).savedIntent, keys), "unreadable armed alarm") to a.triggerAtTime
    }

    @Test
    fun `an instant around a daylight-saving transition is armed fired and cancelled as itself whatever zone or locale the process switches to`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(handler))
        val switchTo: List<String> = listOf("Pacific/Kiritimati", "America/St_Johns", "UTC", "Pacific/Pago_Pago")
        val locales: List<Locale> = listOf(Locale.forLanguageTag("ar-EG-u-nu-arab"), Locale.forLanguageTag("hi-IN-u-nu-deva"), Locale.US)
        var counter = 0
        for (zone: String in ZONES) {
            for (transition: Long in transitions(zone)) {
                for (delta: Long in listOf(-1L, 0L, 1L)) {
                    val at: Long = transition + delta
                    val id = "dst-${counter++}"
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    val first: ScheduledAlarm = alarm(id, at, "first")
                    assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(first) }, "$zone $at")
                    assertEquals(listOf(first to at), armed().filter { it.first.id == id }, "$zone $at: armed under its own zone")

                    TimeZone.setDefault(TimeZone.getTimeZone(switchTo[counter % switchTo.size]))
                    Locale.setDefault(locales[counter % locales.size])
                    assertEquals(listOf(first to at), armed().filter { it.first.id == id }, "$zone $at: after the zone and locale changed")

                    // Re-arm under the other zone: the same id replaces itself, the instant is still the asked one.
                    val second: ScheduledAlarm = alarm(id, at, "second")
                    assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(second) })
                    assertEquals(listOf(second to at), armed().filter { it.first.id == id }, "$zone $at: replaced under another zone")

                    // Fire under yet another zone.
                    TimeZone.setDefault(TimeZone.getTimeZone(zone))
                    // The OS pops the earliest alarm: the only one armed here.
                    val popped: ShadowAlarmManager.ScheduledAlarm = assertNotNull(shadowAlarmManager.nextScheduledAlarm)
                    assertEquals(at, popped.triggerAtTime, "$zone: the OS held another instant")
                    @Suppress("DEPRECATION")
                    AlarmReceiver().onReceive(context, shadowOf(popped.operation).savedIntent)
                    assertEquals(second, delivered.poll(10, TimeUnit.SECONDS), "$zone $at: delivered")

                    runBlocking { scheduler.cancel(id) }
                    assertEquals(emptyList(), armed(), "$zone $at: nothing left")
                }
            }
        }
    }

    @Test
    fun `instants at both ends of the Long range survive a zone change while armed`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(handler))
        for ((index: Int, at: Long) in listOf(Long.MIN_VALUE, Long.MIN_VALUE + 1, -1L, 0L, Long.MAX_VALUE - 1, Long.MAX_VALUE).withIndex()) {
            val id = "range-$index"
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
            val wanted: ScheduledAlarm = alarm(id, at, "x")
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(wanted) })
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Pago_Pago"))
            assertEquals(listOf(wanted to at), armed().filter { it.first.id == id })
            runBlocking { scheduler.cancel(id) }
        }
        assertEquals(emptyList(), armed())
    }

    private companion object {
        val ZONES: List<String> = listOf(
            "America/New_York", "Europe/London", "Australia/Lord_Howe", "Pacific/Apia", "Asia/Tehran", "America/Sao_Paulo", "Africa/Casablanca",
        )
    }
}
