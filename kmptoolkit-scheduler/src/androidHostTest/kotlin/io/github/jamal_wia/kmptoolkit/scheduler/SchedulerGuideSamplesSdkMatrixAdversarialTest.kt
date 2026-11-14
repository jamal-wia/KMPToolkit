package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.os.Build
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 20 (docs as a contract on every platform level): the getting-started flow of
 * docs/kmptoolkit-scheduler (create at startup, schedule, cancel, the `when` over the result, the
 * boot re-arm loop) at 24 26 29 31 33 35 (the levels the rest of the suite already loads, a sandbox per level stays in the test JVM). The exact-alarm check exists from API 31;
 * below it the reference promises an exact alarm without any permission, from 31 an exact one only
 * with it and an `Inexact` result naming the missing permission otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 26, 29, 31, 33, 35])
class SchedulerGuideSamplesSdkMatrixAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @After
    fun tearDown() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmDispatch.reset()
    }

    private class Handler(val seen: MutableList<ScheduledAlarm>, val fired: CountDownLatch) : AlarmHandler {
        override val type: String = "STUDY_REMINDER"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            seen += alarm
            fired.countDown()
        }
    }

    private fun reminder(lessonId: String, at: Long): ScheduledAlarm = ScheduledAlarm(
        id = "study-$lessonId",
        type = "STUDY_REMINDER",
        fireAtEpochMillis = at,
        notification = AlarmNotification("Time to study", "Lesson $lessonId", "reminders"),
        payload = mapOf("lessonId" to lessonId),
    )

    @Test
    fun `schedule cancel and fire answer as the reference says at every level when the exact permission is held`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val seen: MutableList<ScheduledAlarm> = Collections.synchronizedList(mutableListOf())
        val fired = CountDownLatch(1)
        val scheduler: AlarmScheduler = createAlarmScheduler(context = context, handlers = listOf(Handler(seen, fired)))

        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(reminder("7", 1_900_000_000_000L)) }
        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)

        @Suppress("DEPRECATION")
        AlarmReceiver().onReceive(context, shadowOf(requireNotNull(shadowAlarmManager.nextScheduledAlarm?.operation)).savedIntent)
        assertTrue(fired.await(10, TimeUnit.SECONDS))
        assertEquals(mapOf("lessonId" to "7"), seen.single().payload)

        runBlocking { scheduler.cancel("study-7") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        runBlocking { scheduler.schedule(reminder("8", 1_900_000_000_000L)) }
        runBlocking { scheduler.cancelAll(listOf("study-8", "never-armed")) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `without the exact permission the result is Inexact from API 31 and Exact below it`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(false)
        val scheduler: AlarmScheduler = createAlarmScheduler(context = context)

        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(reminder("1", 1_900_000_000_000L)) }

        val verdict: String = when (result) {
            AlarmScheduleResult.Exact -> "exact"
            is AlarmScheduleResult.Inexact -> "inexact ${result.reason}"
            is AlarmScheduleResult.Failed -> "failed ${result.reason}"
        }
        val expected: String =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "inexact EXACT_ALARM_PERMISSION_MISSING" else "exact"
        assertEquals(expected, verdict, "sdk ${Build.VERSION.SDK_INT}")
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "armed either way")
    }

    @Test
    fun `the boot re-arm loop of the guide re-arms every stored reminder once with no slot left over`() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val scheduler: AlarmScheduler = createAlarmScheduler(context = context)
        val stored: List<ScheduledAlarm> = (1..12).map { reminder("$it", 1_900_000_000_000L + it) }
        repeat(5) {
            // Five reboots: the platform forgot every alarm, the app schedules from its store each time.
            shadowAlarmManager.scheduledAlarms.toList().forEach { armed: ShadowAlarmManager.ScheduledAlarm ->
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(requireNotNull(armed.operation))
            }
            runBlocking { stored.forEach { scheduler.schedule(it) } }
            assertEquals(12, shadowAlarmManager.scheduledAlarms.size)
        }
        runBlocking { scheduler.cancelAll(stored.map { it.id }) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }
}
