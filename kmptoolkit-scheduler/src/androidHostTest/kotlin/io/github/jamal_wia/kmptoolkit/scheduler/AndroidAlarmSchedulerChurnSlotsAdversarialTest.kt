package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Thousands of replacements of one id and of fresh ids: one alarm and one slot per live id, nothing left after the last cancel. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerChurnSlotsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun alarm(id: String, n: Int) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = n.toLong(), notification = AlarmNotification(title = "t$n", body = "b", channelId = "c"),
    )

    @Test
    fun `ten thousand replacements of one id keep one alarm and one slot`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        repeat(10_000) { n: Int -> runBlocking { scheduler.schedule(alarm("one", n)) } }
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(1, slots("one"))
        runBlocking { scheduler.cancel("one") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, slots("one"))
    }

    @Test
    fun `four thousand fresh ids that are scheduled and cancelled in a sliding window leave nothing`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val total = 4_000
        val window = 50
        repeat(total) { n: Int ->
            runBlocking { scheduler.schedule(alarm("id-$n", n)) }
            if (n >= window) runBlocking { scheduler.cancel("id-${n - window}") }
        }
        assertEquals(window, shadowAlarmManager.scheduledAlarms.size)
        runBlocking { scheduler.cancelAll((total - window until total).map { "id-$it" }) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        (0 until total).forEach { assertEquals(0, slots("id-$it"), "slots of id-$it") }
    }
}
