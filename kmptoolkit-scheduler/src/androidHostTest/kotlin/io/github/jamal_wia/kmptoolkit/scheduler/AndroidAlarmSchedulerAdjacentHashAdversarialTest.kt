package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * An id owns two request codes, `hash` and `hash + 1`, so the second code of one id is the first code of
 * another whose hash is one higher ("Ab" and "Ac"), and two ids with equal hashes ("Aa" and "BB") share
 * both. Only the per-id data URI keeps their `PendingIntent`s apart: neighbours must never touch each
 * other's alarm, whatever the order of schedules, replacements and cancels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerAdjacentHashAdversarialTest {

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

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `ids whose hashes are equal or adjacent never disturb each other's alarm`() {
        val ids: List<String> = listOf("Ab", "Ac", "Ad", "Aa", "BB", "AaAa", "BBBB", "AaBB")
        assertEquals(ids.map { it.hashCode() }.distinct().size < ids.size, true, "the ids must include equal hashes")
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val model: MutableMap<String, ScheduledAlarm> = mutableMapOf()
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                val id: String = ids[random.nextInt(ids.size)]
                when (random.nextInt(6)) {
                    in 0..3 -> {
                        val alarm = ScheduledAlarm(
                            id = id, type = "T", fireAtEpochMillis = 1_000L + step,
                            notification = AlarmNotification(title = "t$seed-$step", body = "b", channelId = "c"),
                        )
                        runBlocking { scheduler.schedule(alarm) }
                        model[id] = alarm
                        log.append("S($id,$step) ")
                    }
                    4 -> { runBlocking { scheduler.cancel(id) }; model.remove(id); log.append("C($id) ") }
                    else -> {
                        val some: List<String> = ids.filter { random.nextInt(3) == 0 }
                        runBlocking { scheduler.cancelAll(some) }
                        some.forEach { model.remove(it) }
                        log.append("CA($some) ")
                    }
                }
            }
            val armed: Map<String, List<ScheduledAlarm>> = shadowAlarmManager.scheduledAlarms
                .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)) }
                .groupBy { it.id }
            assertEquals(model.keys, armed.keys, "seed $seed: armed ids differ. Steps: $log")
            model.forEach { (id: String, expected: ScheduledAlarm) ->
                assertEquals(listOf(expected), armed.getValue(id), "seed $seed: '$id'. Steps: $log")
            }
            ids.forEach { id: String ->
                assertEquals(if (id in model) 1 else 0, slots(id), "seed $seed: slots of '$id'. Steps: $log")
            }
            scheduler.let { runBlocking { it.cancelAll(ids) } }
        }
    }

    private companion object {
        const val SEEDS: Int = 150
        const val STEPS: Int = 50
    }
}
