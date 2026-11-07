package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlin.random.Random
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Real threads, each owning its ids, schedule, replace and cancel while the platform refuses every
 * alarm whose fire time is a multiple of four (the alarm limit of a busy app). A refused schedule
 * is reported as `Failed` and changes nothing: the alarm that was armed keeps its time and content,
 * and no `PendingIntent` slot is left behind for an alarm that was never armed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerRefusalStormAdversarialTest.RefusingShadowAlarmManager::class])
class AndroidAlarmSchedulerRefusalStormAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `refused replacements under a thread storm leave the armed alarms and the slots as the model says`() {
        val owned: List<List<String>> = List(WORKERS) { index: Int -> listOf("r$index-a", "r$index-b") }
        val models: List<MutableMap<String, ScheduledAlarm>> = List(WORKERS) { mutableMapOf() }
        val barrier = CyclicBarrier(WORKERS)
        val failures: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val workers: List<Thread> = List(WORKERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-refusal-$index") {
                try {
                    val random = Random(index)
                    val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
                    val model: MutableMap<String, ScheduledAlarm> = models[index]
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(STEPS) { step: Int ->
                        val id: String = owned[index][random.nextInt(2)]
                        if (random.nextInt(10) < 7) {
                            val fireAt: Long = 1_000L + index * 10_000L + step
                            val a = ScheduledAlarm(
                                id = id,
                                type = "T",
                                fireAtEpochMillis = fireAt,
                                notification = AlarmNotification(title = "t$fireAt", body = "b", channelId = "c"),
                                payload = mapOf("k" to "v$fireAt"),
                            )
                            val result: AlarmScheduleResult = runBlocking { scheduler.schedule(a) }
                            if (fireAt % 4 == 0L) {
                                if (result !is AlarmScheduleResult.Failed) failures += "fire at $fireAt should be refused but was $result"
                            } else {
                                if (result !is AlarmScheduleResult.Exact) failures += "fire at $fireAt should arm but was $result"
                                model[id] = a
                            }
                        } else {
                            runBlocking { scheduler.cancel(id) }
                            model.remove(id)
                        }
                    }
                } catch (e: Throwable) {
                    failures += "worker $index: ${e.javaClass.simpleName}: ${e.message}"
                }
            }
        }
        workers.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
        assertTrue(workers.none { it.isAlive }, "a worker is stuck")
        assertEquals(emptyList(), failures.toList())

        val armed: Map<String, List<ScheduledAlarm>> = shadowAlarmManager.scheduledAlarms
            .map { armed: ShadowAlarmManager.ScheduledAlarm ->
                assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys), "unreadable alarm")
            }
            .groupBy { it.id }
        val expected: Map<String, ScheduledAlarm> = models.flatMap { it.entries }.associate { it.key to it.value }
        assertEquals(expected.keys, armed.keys, "armed ids differ from the model")
        expected.forEach { (id: String, alarm: ScheduledAlarm) ->
            assertEquals(listOf(alarm), armed.getValue(id), "'$id': a refused replacement changed the armed alarm, or a second one is armed")
        }
        owned.flatten().forEach { id: String ->
            assertEquals(if (id in expected) 1 else 0, occupiedSlots(id), "'$id' slots in use differ from the alarms armed")
        }
    }

    /** Refuses every exact alarm due at a multiple of four, the way a platform at its alarm limit does. */
    @Implements(AlarmManager::class)
    class RefusingShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (triggerAtTime % 4 == 0L) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
        }
    }

    private companion object {
        const val WORKERS: Int = 8
        const val STEPS: Int = 200
        const val JOIN_SECONDS: Long = 45L
    }
}
