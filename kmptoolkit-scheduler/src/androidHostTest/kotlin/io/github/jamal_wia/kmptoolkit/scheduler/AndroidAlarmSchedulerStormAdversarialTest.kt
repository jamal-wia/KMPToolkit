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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Storms of schedule, replace, cancel and cancelAll from real threads, over several scheduler
 * instances. Per id whose operations come from one thread the final state is fully determined, so it
 * is checked against a model; per id that every thread hits, whatever order the lock produced, the
 * OS may hold at most one alarm whose time and content belong together. Neither may leave a
 * `PendingIntent` slot behind that no alarm uses.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerStormAdversarialTest {

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

    private fun alarm(id: String, step: Int): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 1_000L + step,
        notification = AlarmNotification(title = "t$step", body = "b", channelId = "c"),
        payload = mapOf("k" to "v$step"),
    )

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun armed(): Map<String, List<ScheduledAlarm>> = shadowAlarmManager.scheduledAlarms
        .map { armed: ShadowAlarmManager.ScheduledAlarm ->
            assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys), "unreadable alarm")
        }
        .groupBy { it.id }

    @Test
    fun `threads that own their ids leave exactly the model and no stray slot behind`() {
        val owned: List<List<String>> = List(WORKERS) { index: Int -> listOf("w$index-a", "w$index-b", "w$index/c") }
        val models: List<MutableMap<String, ScheduledAlarm>> = List(WORKERS) { mutableMapOf() }
        val barrier = CyclicBarrier(WORKERS)
        val failures: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val workers: List<Thread> = List(WORKERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-sched-$index") {
                try {
                    val random = Random(index)
                    // A scheduler instance of its own per thread: the lock is process-wide.
                    val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
                    val ids: List<String> = owned[index]
                    val model: MutableMap<String, ScheduledAlarm> = models[index]
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(STEPS) { step: Int ->
                        val id: String = ids[random.nextInt(ids.size)]
                        when (random.nextInt(10)) {
                            in 0..5 -> {
                                val a: ScheduledAlarm = alarm(id, index * 10_000 + step)
                                runBlocking { scheduler.schedule(a) }
                                model[id] = a
                            }
                            in 6..7 -> {
                                runBlocking { scheduler.cancel(id) }
                                model.remove(id)
                            }
                            else -> {
                                val some: List<String> = ids.filter { random.nextBoolean() }
                                runBlocking { scheduler.cancelAll(some) }
                                some.forEach { model.remove(it) }
                            }
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

        val armed: Map<String, List<ScheduledAlarm>> = armed()
        val expected: Map<String, ScheduledAlarm> = models.flatMap { it.entries }.associate { it.key to it.value }
        assertEquals(expected.keys, armed.keys, "armed ids differ from the model")
        expected.forEach { (id: String, alarm: ScheduledAlarm) ->
            assertEquals(listOf(alarm), armed.getValue(id), "'$id' holds more than one alarm or the wrong content")
        }
        owned.flatten().forEach { id: String ->
            assertEquals(if (id in expected) 1 else 0, occupiedSlots(id), "'$id' leaves a PendingIntent slot that no alarm uses (or none for the armed one)")
        }
    }

    @Test
    fun `every thread hitting one id leaves at most one alarm whose time and content belong together`() {
        val barrier = CyclicBarrier(WORKERS)
        val failures: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
        val workers: List<Thread> = List(WORKERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-sched-one-$index") {
                try {
                    val random = Random(100 + index)
                    val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(STEPS) { step: Int ->
                        when (random.nextInt(10)) {
                            in 0..6 -> runBlocking { scheduler.schedule(alarm("shared", index * 10_000 + step)) }
                            in 7..8 -> runBlocking { scheduler.cancel("shared") }
                            else -> runBlocking { scheduler.cancelAll(listOf("shared", "other")) }
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

        val held: List<ScheduledAlarm> = armed()["shared"].orEmpty()
        assertTrue(held.size <= 1, "more than one alarm armed for one id: $held")
        held.forEach { a: ScheduledAlarm ->
            val step: Int = a.notification.title.removePrefix("t").toInt()
            assertEquals(1_000L + step, a.fireAtEpochMillis, "time and content of the armed alarm come from different schedule calls")
            assertEquals("v$step", a.payload["k"], "payload and content come from different schedule calls")
        }
        assertEquals(held.size, occupiedSlots("shared"), "slots in use differ from alarms armed")
    }

    @Test
    fun `callers cancelled while waiting for or holding the lock leave the lock free and the slots consistent`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val ids: List<String> = listOf("c-a", "c-b")
        runBlocking {
            withTimeout(60_000L) {
                coroutineScope {
                    repeat(CALLERS) { n: Int ->
                        launch(Dispatchers.Default) {
                            val random = Random(n)
                            val id: String = ids[random.nextInt(ids.size)]
                            withTimeoutOrNull(random.nextLong(1, 6)) {
                                when (random.nextInt(4)) {
                                    0 -> scheduler.cancel(id)
                                    1 -> scheduler.cancelAll(ids)
                                    else -> scheduler.schedule(alarm(id, n))
                                }
                            }
                        }
                    }
                }
            }
        }
        // The lock must be free and the scheduler usable, whichever calls were cut short.
        val result: AlarmScheduleResult = runBlocking { withTimeout(10_000L) { scheduler.schedule(alarm("c-a", 9_999)) } }
        assertTrue(result is AlarmScheduleResult.Exact, "scheduling after the storm gave $result")
        val held: Map<String, List<ScheduledAlarm>> = armed()
        ids.forEach { id: String ->
            assertTrue(held[id].orEmpty().size <= 1, "'$id' holds more than one alarm: ${held[id]}")
            assertEquals(held[id].orEmpty().size, occupiedSlots(id), "'$id': slots in use differ from alarms armed")
        }
        runBlocking { withTimeout(10_000L) { scheduler.cancelAll(ids) } }
        ids.forEach { id: String -> assertEquals(0, occupiedSlots(id), "'$id' still holds a slot after cancel") }
        assertEquals(emptyMap(), armed().filterKeys { it in ids })
    }

    private companion object {
        const val CALLERS: Int = 400
        const val WORKERS: Int = 8
        const val STEPS: Int = 200
        const val JOIN_SECONDS: Long = 45L
    }
}
