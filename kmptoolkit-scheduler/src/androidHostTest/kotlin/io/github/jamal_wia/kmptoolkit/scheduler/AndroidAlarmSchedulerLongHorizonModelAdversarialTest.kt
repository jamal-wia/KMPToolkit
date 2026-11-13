package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.random.Random
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
 * A long random history against a reference model: tens of thousands of schedule, replace, cancel and cancelAll
 * calls over a handful of ids (including colliding hash codes), with the scheduler instance thrown away and
 * recreated at random (a process restart: the platform keeps the alarms) and fire times spread over weeks and
 * across year and daylight-saving boundaries (the scheduler must only pass the instant through). The model is the
 * documented contract: an id owns at most one alarm, the last schedule wins, cancel removes it. Every few hundred
 * operations the alarms in the platform are compared with the model in full.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerLongHorizonModelAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun armed(): Map<String, Pair<String, Long>> = shadowAlarmManager.scheduledAlarms.associate {
        val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys))
        alarm.id to (alarm.notification.title to alarm.fireAtEpochMillis)
    }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `a hundred thousand operations with restarts leave exactly what the last schedule of each id left`() {
        val ids: List<String> = listOf("a", "b", "Aa", "BB", "reminder-7", "")
            .filter { it.isNotEmpty() } + listOf("c")
        val random = Random(2121)
        val model: MutableMap<String, Pair<String, Long>> = HashMap()
        var scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val dstEdges: List<Long> = listOf(
            1_711_846_800_000L, 1_729_990_800_000L, 1_735_689_599_000L, 1_735_689_600_000L, 4_102_444_800_000L,
        )
        repeat(OPERATIONS) { step: Int ->
            val id: String = ids[random.nextInt(ids.size)]
            when (random.nextInt(100)) {
                in 0..59 -> {
                    val at: Long = if (random.nextInt(4) == 0) dstEdges[random.nextInt(dstEdges.size)] + random.nextInt(-3, 4) * 3_600_000L
                    else 1_700_000_000_000L + random.nextLong(0L, 21L * 24 * 3_600_000L)
                    val title = "s$step"
                    val result: AlarmScheduleResult = runBlocking {
                        scheduler.schedule(
                            ScheduledAlarm(
                                id = id, type = "T", fireAtEpochMillis = at,
                                notification = AlarmNotification(title = title, body = "b", channelId = "c"),
                            ),
                        )
                    }
                    assertEquals(AlarmScheduleResult.Exact, result, "step $step: schedule of '$id'")
                    model[id] = title to at
                }
                in 60..84 -> {
                    runBlocking { scheduler.cancel(id) }
                    model.remove(id)
                }
                in 85..86 -> {
                    runBlocking { scheduler.cancelAll(ids.shuffled(random).take(random.nextInt(1, ids.size + 1)).also { cancelled: List<String> -> cancelled.forEach { model.remove(it) } }) }
                }
                else -> scheduler = AndroidAlarmScheduler(context, keys)
            }
            if (step % 250 == 0) {
                assertEquals(model.toMap(), armed(), "step $step: the platform differs from the model")
                ids.forEach { assertEquals(if (it in model) 1 else 0, slots(it), "step $step: slots of '$it'") }
            }
        }
        assertEquals(model.toMap(), armed())
        runBlocking { scheduler.cancelAll(ids) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        ids.forEach { assertEquals(0, slots(it), "slots of '$it' after cancelAll") }
    }

    private companion object {
        const val OPERATIONS: Int = 100_000
    }
}
