package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
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
 * While `AlarmManager.cancel` refuses every third call (a restarting system server), threads
 * schedule, replace and cancel over their own ids, leaving stale alarms and occupied slots behind,
 * which the scheduler documents as "cleared by the next schedule or cancel of the id". Once the
 * platform is healthy again, one successful schedule of an id must leave exactly that one alarm
 * armed with the final content and a single slot, and one cancel must leave nothing at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerRetireFaultStormAdversarialTest.FlakyCancelShadowAlarmManager::class])
class AndroidAlarmSchedulerRetireFaultStormAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        FlakyCancelShadowAlarmManager.faulty.set(false)
    }

    @After
    fun reset() {
        FlakyCancelShadowAlarmManager.faulty.set(false)
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

    private fun armedFor(id: String): List<ScheduledAlarm> = shadowAlarmManager.scheduledAlarms
        .map { armed: ShadowAlarmManager.ScheduledAlarm ->
            assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys), "unreadable alarm")
        }
        .filter { it.id == id }

    @Test
    fun `stale alarms left by refused retirements are cleared by the next successful schedule or cancel`() {
        val owned: List<List<String>> = List(WORKERS) { index: Int -> listOf("f$index-a", "f$index-b") }
        val barrier = CyclicBarrier(WORKERS)
        val failures: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        FlakyCancelShadowAlarmManager.faulty.set(true)
        val workers: List<Thread> = List(WORKERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-retire-$index") {
                try {
                    val random = Random(index)
                    val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(STEPS) { step: Int ->
                        val id: String = owned[index][random.nextInt(2)]
                        if (random.nextInt(10) < 7) {
                            runBlocking { scheduler.schedule(alarm(id, index * 10_000 + step)) }
                        } else {
                            runBlocking { scheduler.cancel(id) }
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

        FlakyCancelShadowAlarmManager.faulty.set(false)
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        owned.flatten().forEachIndexed { n: Int, id: String ->
            val final: ScheduledAlarm = alarm(id, 900_000 + n)
            assertTrue(runBlocking { scheduler.schedule(final) } is AlarmScheduleResult.Exact)
            assertEquals(listOf(final), armedFor(id), "'$id': a stale alarm survived a successful schedule")
            assertEquals(1, occupiedSlots(id), "'$id': slots in use after a successful schedule")
            if (n % 2 == 0) {
                runBlocking { scheduler.cancel(id) }
                assertEquals(emptyList(), armedFor(id), "'$id': an alarm survived a successful cancel")
                assertEquals(0, occupiedSlots(id), "'$id': a slot survived a successful cancel")
            }
        }
    }

    private fun alarm(id: String, step: Int): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 1_000L + step,
        notification = AlarmNotification(title = "t$step", body = "b", channelId = "c"),
        payload = mapOf("k" to "v$step"),
    )

    /** While [faulty], every third `cancel` throws the way a restarting system server does. */
    @Implements(AlarmManager::class)
    class FlakyCancelShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (faulty.get() && calls.incrementAndGet() % 3 == 0) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val faulty: AtomicBoolean = AtomicBoolean(false)
            val calls: AtomicInteger = AtomicInteger(0)
        }
    }

    private companion object {
        const val WORKERS: Int = 8
        const val STEPS: Int = 200
        const val JOIN_SECONDS: Long = 45L
    }
}
