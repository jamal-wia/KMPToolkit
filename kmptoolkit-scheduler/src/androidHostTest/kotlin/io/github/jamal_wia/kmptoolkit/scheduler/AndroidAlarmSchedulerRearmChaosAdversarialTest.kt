package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Repeating reminders re-arm themselves from `onFire` while the app cancels and re-schedules the same
 * ids from other threads (a user editing or deleting reminders as they fire), with a fresh scheduler
 * instance per thread (process-wide state is shared). Whatever order the platform calls interleave in,
 * a quiet scheduler holds at most one armed alarm per id, never more `PendingIntent` slots than the
 * documented bound, and `cancelAll` over the ids always leaves nothing behind, fired slots included.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerRearmChaosAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private val ids: List<String> = listOf("r0", "r1", "r2", "r3", "Aa", "BB")

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun armedIds(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { requireNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).id }

    private fun alarm(id: String, n: Int): ScheduledAlarm =
        ScheduledAlarm(id, "T", 1_000L + n, AlarmNotification("n$n", "b", "c"))

    @Test
    fun `fire and re-arm racing cancel and schedule leave at most one alarm per id and cancelAll leaves nothing`() {
        val handled = AtomicInteger()
        val failures = java.util.concurrent.CopyOnWriteArrayList<Throwable>()
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        AlarmDispatch.install(
            keys,
            listOf(object : AlarmHandler {
                override val type: String = "T"
                override suspend fun onFire(alarm: ScheduledAlarm) {
                    try {
                        scheduler.schedule(alarm(alarm.id, handled.incrementAndGet()))
                    } catch (e: Throwable) {
                        failures += e
                    }
                }
            }),
        )
        ids.forEach { id: String -> runBlocking { scheduler.schedule(alarm(id, 0)) } }

        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val chaos: List<Thread> = (0 until 3).map { index: Int ->
            thread(name = "kmptoolkit-adversarial-rearm-chaos-$index") {
                val random = Random(index)
                val own: AlarmScheduler = AndroidAlarmScheduler(context, keys)
                try {
                    while (!stop.get()) {
                        val id: String = ids[random.nextInt(ids.size)]
                        runBlocking {
                            withTimeout(JOIN_MS) {
                                if (random.nextInt(3) == 0) own.cancel(id) else own.schedule(alarm(id, 1_000 + random.nextInt(1000)))
                            }
                        }
                    }
                } catch (e: Throwable) {
                    failures += e
                }
            }
        }

        var delivered = 0
        val random = Random(7)
        val giveUp: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (delivered < TARGET_DELIVERED && System.nanoTime() < giveUp) {
            val fired = shadowAlarmManager.nextScheduledAlarm
            if (fired == null) {
                Thread.sleep(1)
                continue
            }
            @Suppress("DEPRECATION")
            val intent = shadowOf(requireNotNull(fired.operation)).savedIntent
            delivered++
            AlarmReceiver().onReceive(context, intent)
            if (random.nextInt(4) == 0) Thread.sleep(1)
        }
        stop.set(true)
        chaos.forEach { it.join(JOIN_MS) }
        assertTrue(chaos.none { it.isAlive }, "a chaos thread is still running")
        // Every delivered alarm's handler runs on the receiver's background thread: let them drain.
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        while (handled.get() < delivered && System.nanoTime() < deadline) Thread.sleep(5)
        Thread.sleep(200)

        assertEquals(emptyList(), failures.map { it.toString() })
        assertTrue(delivered >= MIN_DELIVERED, "the storm delivered only $delivered alarms")
        val armed: List<String> = armedIds()
        assertEquals(armed.toSet().size, armed.size, "two alarms armed for one id: $armed")
        for (id: String in ids) {
            assertTrue(occupiedSlots(id) <= AlarmIntents.SLOT_COUNT, "slots of $id")
        }

        runBlocking { scheduler.cancelAll(ids) }

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        for (id: String in ids) assertEquals(0, occupiedSlots(id), "$id keeps a PendingIntent after cancelAll")
        assertEquals(delivered, handled.get(), "a delivered alarm never reached its handler")
    }

    private companion object {
        const val TARGET_DELIVERED: Int = 300
        const val MIN_DELIVERED: Int = 100
        const val JOIN_MS: Long = 60_000L
    }
}
