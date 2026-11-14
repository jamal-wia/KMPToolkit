package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The repeating-reminder pattern of the guide: a handler that, when its alarm fires, re-arms the next
 * occurrence under the same id from inside `onFire` (on the receiver's background thread). The fired
 * alarm leaves AlarmManager but its `PendingIntent` stays; the re-arm has to find that slot occupied,
 * use another one and retire the fired one, forever. After any number of cycles exactly one alarm is
 * armed, it carries the newest content, and the id owns one `PendingIntent` slot, not a growing set.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerRepeatingReminderAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun occupiedSlots(id: String): List<Int> = (0 until AlarmIntents.SLOT_COUNT).filter { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private class Reminder(private val scheduler: AlarmScheduler, override val type: String, private val id: String) : AlarmHandler {
        val fires = AtomicInteger()
        @Volatile var done = CountDownLatch(1)
        @Volatile var failure: Throwable? = null

        override suspend fun onFire(alarm: ScheduledAlarm) {
            try {
                val n: Int = fires.incrementAndGet()
                val result: AlarmScheduleResult = scheduler.schedule(
                    ScheduledAlarm(id, type, alarm.fireAtEpochMillis + 60_000L, AlarmNotification("occurrence $n", "b", "c"), mapOf("n" to "$n")),
                )
                check(result == AlarmScheduleResult.Exact) { "re-arm answered $result" }
            } catch (e: Throwable) {
                failure = e
            } finally {
                done.countDown()
            }
        }
    }

    @Test
    fun `an alarm that re-arms itself from onFire stays one alarm in one slot for hundreds of cycles`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val reminder = Reminder(scheduler, "T", "daily")
        AlarmDispatch.install(keys, listOf(reminder))
        assertEquals(
            AlarmScheduleResult.Exact,
            kotlinx.coroutines.runBlocking { scheduler.schedule(ScheduledAlarm("daily", "T", 1_000L, AlarmNotification("occurrence 0", "b", "c"))) },
        )

        for (cycle in 1..CYCLES) {
            val fired: ShadowAlarmManager.ScheduledAlarm = requireNotNull(shadowAlarmManager.nextScheduledAlarm) { "nothing armed before cycle $cycle" }
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "the fired alarm must have left the manager")
            reminder.done = CountDownLatch(1)
            @Suppress("DEPRECATION")
            AlarmReceiver().onReceive(context, shadowOf(requireNotNull(fired.operation)).savedIntent)
            assertTrue(reminder.done.await(10, TimeUnit.SECONDS), "the handler of cycle $cycle never finished")
            assertEquals(null, reminder.failure?.toString(), "cycle $cycle")

            assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "cycle $cycle: exactly one alarm")
            val armed: ScheduledAlarm = requireNotNull(
                AlarmIntents.fromIntent(shadowOf(shadowAlarmManager.scheduledAlarms.single().operation).savedIntent, keys),
            )
            assertEquals("occurrence $cycle", armed.notification.title, "cycle $cycle")
            assertEquals(mapOf("n" to "$cycle"), armed.payload)
            assertEquals(1_000L + 60_000L * cycle, armed.fireAtEpochMillis)
            assertEquals(1, occupiedSlots("daily").size, "cycle $cycle: slots are leaking")
        }
        assertEquals(CYCLES, reminder.fires.get())
    }

    private companion object {
        const val CYCLES: Int = 300
    }
}
