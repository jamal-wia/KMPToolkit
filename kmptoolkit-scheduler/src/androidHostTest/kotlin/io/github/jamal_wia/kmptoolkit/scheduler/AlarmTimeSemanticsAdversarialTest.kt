package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.os.SystemClock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.util.Locale
import java.util.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
 * Round 23 (time and calendar semantics). The documented sentences about time are few and all of one
 * kind: `fireAtEpochMillis` is "milliseconds since the Unix epoch, UTC", a past value "is legal - Android
 * fires it immediately, iOS clamps the trigger to one second out", the Android time base is "wall-clock
 * (RTC_WAKEUP)" so the OS, not the library, moves an alarm when the clock or zone changes, and the library
 * itself keeps no calendar. These tests give each of them a table instead of a few samples: DST gaps and
 * overlaps (whole and half hour), leap days, month and year ends, the 32-bit second boundaries, a
 * default zone and locale switched before every call, and a system clock stepped backwards and forwards.
 * The clock is injected (the shadow's), nothing sleeps.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmTimeSemanticsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private val originalZone: TimeZone = TimeZone.getDefault()
    private val originalLocale: Locale = Locale.getDefault()

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        TimeZone.setDefault(originalZone)
        Locale.setDefault(originalLocale)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, at: Long): ScheduledAlarm = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = at, notification = AlarmNotification("t", "b", "c"), payload = mapOf("k" to "v"),
    )

    private fun local(zone: String, year: Int, month: Int, day: Int, hour: Int, minute: Int, later: Boolean = false): Long {
        val zoned: ZonedDateTime = ZonedDateTime.of(LocalDateTime.of(year, month, day, hour, minute), ZoneId.of(zone))
        return (if (later) zoned.withLaterOffsetAtOverlap() else zoned.withEarlierOffsetAtOverlap()).toInstant().toEpochMilli()
    }

    private fun utc(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, millis: Int = 0): Long =
        LocalDateTime.of(year, month, day, hour, minute, second, millis * 1_000_000).toInstant(ZoneOffset.UTC).toEpochMilli()

    /** The instants a calendar can make awkward, plus the arithmetic boundaries of seconds and milliseconds. */
    private fun instants(): List<Pair<String, Long>> = listOf(
        "NY spring gap 02:30 (does not exist)" to local("America/New_York", 2027, 3, 14, 2, 30),
        "NY fall overlap 01:30 first" to local("America/New_York", 2026, 11, 1, 1, 30),
        "NY fall overlap 01:30 second" to local("America/New_York", 2026, 11, 1, 1, 30, later = true),
        "London spring gap 01:30" to local("Europe/London", 2027, 3, 28, 1, 30),
        "London fall overlap 01:30 first" to local("Europe/London", 2026, 10, 25, 1, 30),
        "London fall overlap 01:30 second" to local("Europe/London", 2026, 10, 25, 1, 30, later = true),
        "Lord Howe half-hour gap" to local("Australia/Lord_Howe", 2026, 10, 4, 2, 15),
        "Lord Howe half-hour overlap second" to local("Australia/Lord_Howe", 2027, 4, 4, 1, 45, later = true),
        "Kolkata no DST" to local("Asia/Kolkata", 2026, 6, 15, 9, 0),
        "Kiritimati +14" to local("Pacific/Kiritimati", 2026, 12, 31, 23, 59),
        "leap day start" to utc(2028, 2, 29),
        "leap day last millisecond" to utc(2028, 2, 29, 23, 59, 59, 999),
        "day before leap day end" to utc(2028, 2, 28, 23, 59, 59, 999),
        "2100 is not a leap year: last ms of Feb 28" to utc(2100, 2, 28, 23, 59, 59, 999),
        "2100 March 1" to utc(2100, 3, 1),
        "month end Jan" to utc(2027, 1, 31, 23, 59, 59, 999),
        "month end Apr" to utc(2027, 4, 30, 23, 59, 59, 999),
        "month end Feb non-leap" to utc(2027, 2, 28, 23, 59, 59, 999),
        "year end" to utc(2026, 12, 31, 23, 59, 59, 999),
        "year start" to utc(2027, 1, 1),
        "signed 32-bit seconds max" to utc(2038, 1, 19, 3, 14, 7),
        "signed 32-bit seconds max plus 1 s" to utc(2038, 1, 19, 3, 14, 8),
        "unsigned 32-bit seconds max plus 1 s" to utc(2106, 2, 7, 6, 28, 16),
        "epoch" to 0L,
        "one ms before the epoch" to -1L,
        "1969 last ms" to utc(1969, 12, 31, 23, 59, 59, 999),
        "far future 9e12" to 9_000_000_000_000L,
        "about 292 million years" to Long.MAX_VALUE / 2,
        "Long max" to Long.MAX_VALUE,
        "Long min" to Long.MIN_VALUE,
    )

    @Test
    fun `an alarm is armed for exactly the epoch instant it was given whatever the default zone and locale are`() {
        val zones: List<String> = listOf("UTC", "America/New_York", "Asia/Kolkata", "Australia/Lord_Howe", "Pacific/Kiritimati", "Pacific/Pago_Pago")
        val locales: List<Locale> = listOf(Locale.ROOT, Locale.forLanguageTag("tr-TR"), Locale.forLanguageTag("ar-EG"), Locale.forLanguageTag("th-TH-u-ca-buddhist"))
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        var checks = 0
        for ((index: Int, zone: String) in zones.withIndex()) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            Locale.setDefault(locales[index % locales.size])
            for ((label: String, at: Long) in instants()) {
                val armed: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("tz", at)) }
                assertEquals(AlarmScheduleResult.Exact, armed, "$label in $zone")
                val scheduled: ShadowAlarmManager.ScheduledAlarm = shadowAlarmManager.scheduledAlarms.single()
                assertEquals(at, scheduled.triggerAtMs, "$label in default zone $zone: the instant is passed through unchanged")
                assertEquals(AlarmManager.RTC_WAKEUP, scheduled.type, "$label in $zone: wall-clock time base")
                // What the receiver will hand the handler: the same instant, from the intent extras.
                val delivered: ScheduledAlarm? = AlarmIntents.fromIntent(shadowOf(requireNotNull(scheduled.operation)).savedIntent, keys)
                assertEquals(alarm("tz", at), assertNotNull(delivered), "$label in $zone: the handler is told the instant it was armed for")
                runBlocking { scheduler.cancel("tz") }
                assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "$label in $zone: cancel disarms it whatever the zone")
                checks++
            }
        }
        assertEquals(zones.size * instants().size, checks)
    }

    @Test
    fun `a system clock stepped backwards and forwards and a zone switched while armed move nothing the library did`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val at: Long = utc(2028, 2, 29, 9, 0)
        val clockSteps: List<Long> = listOf(
            utc(2026, 11, 13), // roughly now
            utc(2026, 10, 13), // a month backwards
            utc(2036, 11, 13), // ten years forwards
            at - 1, // one millisecond before the alarm
            at + 1, // one millisecond after it
            0L,
        )
        for (step: Long in clockSteps) {
            TimeZone.setDefault(TimeZone.getTimeZone(if (step % 2L == 0L) "Pacific/Kiritimati" else "America/Los_Angeles"))
            SystemClock.setCurrentTimeMillis(step)
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("clock", at)) }, "clock at $step")
            assertEquals(at, shadowAlarmManager.scheduledAlarms.single().triggerAtMs, "the alarm does not depend on the clock when it is armed (clock at $step)")
        }
        // An alarm armed earlier is not touched by a later clock step or zone switch, and still cancels.
        SystemClock.setCurrentTimeMillis(utc(2040, 1, 1))
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
        assertEquals(at, shadowAlarmManager.scheduledAlarms.single().triggerAtMs)
        runBlocking { scheduler.cancel("clock") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `a fire time in the past is armed unchanged on Android, however far back or after any clock step`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val now: Long = utc(2026, 11, 13, 12, 0)
        SystemClock.setCurrentTimeMillis(now)
        for ((label: String, past: Long) in listOf(
            "one millisecond ago" to now - 1,
            "a day ago" to now - 86_400_000L,
            "a month ago across a DST end" to local("America/New_York", 2026, 10, 13, 9, 0),
            "before the epoch" to -86_400_000L,
            "Long min" to Long.MIN_VALUE,
        )) {
            val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("past", past)) }
            assertEquals(AlarmScheduleResult.Exact, result, label)
            assertEquals(past, shadowAlarmManager.scheduledAlarms.single().triggerAtMs, "$label: Android is handed the past instant, it fires at once")
        }
        runBlocking { scheduler.cancel("past") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `the iOS trigger interval is real elapsed seconds across DST leap days and clock steps and never below one second`() {
        data class Row(val label: String, val fireAt: Long, val now: Long, val expectedSeconds: Double)
        val nyBefore: Long = local("America/New_York", 2026, 11, 1, 0, 0)
        val nySpring: Long = local("America/New_York", 2027, 3, 14, 0, 0)
        val lordHowe: Long = local("Australia/Lord_Howe", 2026, 10, 3, 12, 0)
        val rows: List<Row> = listOf(
            Row("NY fall back day is 25 hours", local("America/New_York", 2026, 11, 2, 0, 0), nyBefore, 90_000.0),
            Row("NY spring forward day is 23 hours", local("America/New_York", 2027, 3, 15, 0, 0), nySpring, 82_800.0),
            Row("London spring forward 23 hours", local("Europe/London", 2027, 3, 29, 0, 0), local("Europe/London", 2027, 3, 28, 0, 0), 82_800.0),
            Row("Lord Howe 30 minute shift: next local noon is 23.5 hours later", local("Australia/Lord_Howe", 2026, 10, 4, 12, 0), lordHowe, 84_600.0),
            Row("leap day is a whole day", utc(2028, 3, 1, 12), utc(2028, 2, 29, 12), 86_400.0),
            Row("two days over a leap day", utc(2028, 3, 1, 12), utc(2028, 2, 28, 12), 172_800.0),
            Row("2100 has no Feb 29: a day is a day", utc(2100, 3, 1, 12), utc(2100, 2, 28, 12), 86_400.0),
            Row("month end to month start, one millisecond apart is clamped", utc(2027, 2, 1), utc(2027, 1, 31, 23, 59, 59, 999), 1.0),
            Row("exactly one second out", 1_001_000L, 1_000_000L, 1.0),
            Row("one second and one millisecond out", 1_001_001L, 1_000_000L, 1.001),
            Row("now", 5_000L, 5_000L, 1.0),
            Row("one millisecond ago", 4_999L, 5_000L, 1.0),
            Row("a day ago", 5_000L - 86_400_000L, 5_000L, 1.0),
            Row("clock stepped back a month: the same fire time is a month further", utc(2026, 12, 1), utc(2026, 11, 1), 30.0 * 86_400.0),
            Row("clock stepped forward past the fire time", utc(2026, 12, 1), utc(2036, 11, 1), 1.0),
            Row("the 2038 boundary is only a number", utc(2038, 1, 19, 3, 14, 8), utc(2038, 1, 19, 3, 14, 7), 1.0),
            Row("the 2038 boundary an hour apart", utc(2038, 1, 19, 4, 14, 7), utc(2038, 1, 19, 3, 14, 7), 3_600.0),
            Row("negative now, positive fire", 1_000_000L, -1_000_000L, 2_000.0),
            Row("negative now, fire at the epoch", 0L, -3_000L, 3.0),
            Row("far future minus far past does not wrap into the past", Long.MAX_VALUE, Long.MIN_VALUE, Long.MAX_VALUE.toDouble() / 1000.0 + Long.MIN_VALUE.toDouble() / -1000.0),
            Row("far future from now", Long.MAX_VALUE, 1_000L, (Long.MAX_VALUE.toDouble() - 1_000.0) / 1000.0),
            Row("Long min fire time", Long.MIN_VALUE, 0L, 1.0),
        )
        for (row: Row in rows) {
            assertEquals(row.expectedSeconds, triggerIntervalSeconds(row.fireAt, row.now), maxOf(1e-6, row.expectedSeconds * 1e-12), row.label)
        }
        assertTrue(rows.size >= 20, "the table has at least 20 rows")
        // The default zone and the locale are not inputs of the arithmetic.
        TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Kiritimati"))
        Locale.setDefault(Locale.forLanguageTag("ar-EG"))
        for (row: Row in rows) {
            assertEquals(row.expectedSeconds, triggerIntervalSeconds(row.fireAt, row.now), maxOf(1e-6, row.expectedSeconds * 1e-12), "${row.label} (zone and locale switched)")
        }
        assertEquals(LocalDate.of(2028, 2, 29), Instant.ofEpochMilli(utc(2028, 2, 29, 23, 59)).atZone(ZoneOffset.UTC).toLocalDate())
    }
}
