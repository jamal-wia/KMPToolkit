package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 12 (soak): a seeded workload over 150 ids against the real Android scheduler and the real receiver:
 * schedule and replace, cancel, cancelAll of random subsets, and alarms that fire in the order the platform
 * would fire them and re-arm themselves from the handler. A reference model decides what is armed. Every
 * 250 steps the armed set, the content of every alarm and the order of the next fire must equal the model;
 * at the end a cancelAll leaves no alarm and no PendingIntent slot of any id behind, and the run has not
 * grown the thread count by more than the receiver's dispatcher pool. The default is bounded; set
 * KMPTOOLKIT_SOAK_STEPS (environment) or kmptoolkit.soak.steps (system property) for the long form.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerSoakAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private data class Armed(val title: String, val at: Long, val payload: Map<String, String>)

    private class Recorder(override val type: String) : AlarmHandler {
        val last = AtomicReference<ScheduledAlarm?>()
        @Volatile var latch = CountDownLatch(1)
        override suspend fun onFire(alarm: ScheduledAlarm) {
            last.set(alarm)
            latch.countDown()
        }
    }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun armedNow(): Map<String, Armed> = shadowAlarmManager.scheduledAlarms.associate {
        val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys))
        alarm.id to Armed(alarm.notification.title, it.triggerAtMs, alarm.payload)
    }

    @Test
    fun `a seeded soak of schedules, cancels, cancelAlls and fires matches the model and leaves nothing behind`() {
        val steps: Int = System.getProperty("kmptoolkit.soak.steps")?.toIntOrNull()
            ?: System.getenv("KMPTOOLKIT_SOAK_STEPS")?.toIntOrNull()
            ?: DEFAULT_STEPS
        println("soak steps=$steps")
        val threadsBefore: Int = Thread.activeCount()
        val recorder = Recorder("T")
        val scheduler: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(recorder))
        val ids: List<String> = (0 until IDS).map { "soak-$it" } + listOf("Aa", "BB", "a/b", "a%2Fb", "😀", "x".repeat(2_000))
        val random = Random(2026)
        val model: MutableMap<String, Armed> = mutableMapOf()
        var fires = 0
        runBlocking {
            repeat(steps) { step: Int ->
                when (random.nextInt(100)) {
                    in 0 until 60 -> {
                        val id: String = ids[random.nextInt(ids.size)]
                        val armed = Armed(
                            title = "t$step", at = random.nextInt(1_000_000) * 10_000_000_000L + step,
                            payload = if (random.nextBoolean()) emptyMap() else mapOf("n" to "$step", "id" to id),
                        )
                        val result: AlarmScheduleResult = scheduler.schedule(
                            ScheduledAlarm(id, "T", armed.at, AlarmNotification(armed.title, "b", "c"), armed.payload),
                        )
                        assertEquals(AlarmScheduleResult.Exact, result, "step $step")
                        model[id] = armed
                    }
                    in 60 until 75 -> {
                        val id: String = ids[random.nextInt(ids.size)]
                        scheduler.cancel(id)
                        model.remove(id)
                    }
                    in 75 until 80 -> {
                        val subset: List<String> = List(random.nextInt(0, 12)) { ids[random.nextInt(ids.size)] }
                        scheduler.cancelAll(subset)
                        subset.forEach { model.remove(it) }
                    }
                    else -> {
                        if (model.isNotEmpty()) {
                            val expectedId: String = model.minByOrNull { it.value.at }!!.key
                            val fired: ShadowAlarmManager.ScheduledAlarm = assertNotNull(shadowAlarmManager.nextScheduledAlarm)
                            val firedAlarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(fired.operation).savedIntent, keys))
                            assertEquals(expectedId, firedAlarm.id, "step $step: the platform fired a different alarm than the model's earliest")
                            recorder.latch = CountDownLatch(1)
                            @Suppress("DEPRECATION")
                            AlarmReceiver().onReceive(context, shadowOf(fired.operation).savedIntent)
                            assertTrue(recorder.latch.await(10, TimeUnit.SECONDS), "step $step: the handler never ran")
                            val seen: ScheduledAlarm = assertNotNull(recorder.last.get())
                            val expected: Armed = model.getValue(expectedId)
                            assertEquals(expected.title, seen.notification.title, "step $step")
                            assertEquals(expected.payload, seen.payload, "step $step")
                            assertEquals(expected.at, seen.fireAtEpochMillis, "step $step")
                            model.remove(expectedId)
                            fires++
                        }
                    }
                }
                if (step % 250 == 0) {
                    assertEquals(model, armedNow(), "step $step: armed alarms differ from the model")
                    ids.take(IDS).forEachIndexed { index: Int, id: String ->
                        if (index % 15 == 0) assertTrue(slots(id) <= 1, "step $step: '$id' holds ${slots(id)} slots")
                    }
                }
            }
            assertEquals(model, armedNow(), "final armed alarms differ from the model")
            assertTrue(fires > steps / 20, "the workload fired only $fires alarms; it does not exercise the fire path")
            scheduler.cancelAll(ids)
        }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        ids.forEach { assertEquals(0, slots(it), "'$it' kept a PendingIntent slot after cancelAll of everything") }
        val grown: Int = Thread.activeCount() - threadsBefore
        assertTrue(grown <= 80, "the soak left $grown more threads alive than it started with")
    }

    private companion object {
        const val IDS: Int = 150
        const val DEFAULT_STEPS: Int = 6_000
    }
}
