package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
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
 * A seeded operation-sequence fuzzer against a plain reference model of the scheduler contract
 * (docs 03 to 05): schedule (replacing an id's alarm, with times from the far past to `Long.MAX_VALUE`),
 * cancel, cancelAll over known, unknown and repeated ids, the exact-alarm right granted and revoked
 * while alarms are armed, a restart (a second scheduler over the same OS state, with or without the
 * process-wide handler state surviving) and the OS firing the earliest alarm into the receiver.
 *
 * The model is a map from id to the alarm the user asked for last, and what has been delivered. After
 * every step the OS must hold exactly the model's alarms (one per id, last content, requested instant),
 * no id may hold more than one `PendingIntent` slot, a cancelled id none, every answer must name the
 * exactness the right allowed at that moment, and a fired alarm must reach the handler once, with the
 * content of the last schedule of its id. The seed is in every failure message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SchedulerContractModelFuzzAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmDispatch.reset()
    }

    private val delivered = LinkedBlockingQueue<ScheduledAlarm>()

    private val handler = object : AlarmHandler {
        override val type: String = "T"
        override suspend fun onFire(alarm: ScheduledAlarm) {
            delivered.put(alarm)
        }
    }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun armedNow(seed: Int, log: CharSequence): Map<String, ScheduledAlarm> {
        val seen: MutableMap<String, ScheduledAlarm> = mutableMapOf()
        shadowAlarmManager.scheduledAlarms.forEach { armed: ShadowAlarmManager.ScheduledAlarm ->
            val alarm: ScheduledAlarm = assertNotNull(
                AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys),
                "seed $seed: unreadable armed alarm. Steps: $log",
            )
            assertEquals(alarm.fireAtEpochMillis, armed.triggerAtTime, "seed $seed: the OS holds another instant than the alarm carries. Steps: $log")
            assertEquals(null, seen.put(alarm.id, alarm), "seed $seed: two alarms armed for '${alarm.id}'. Steps: $log")
        }
        return seen
    }

    @Test
    fun `no sequence of schedules cancels revocations restarts and fires makes the OS or an answer disagree with the contract`() {
        repeat(SEEDS) { seed: Int -> runSeed(seed) }
    }

    private fun runSeed(seed: Int) {
        val random = Random(seed)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        shadowAlarmManager.scheduledAlarms.toList().forEach { alarmManager.cancel(requireNotNull(it.operation)) }
        // A previous seed may have left fired slots behind: the model starts from nothing.
        IDS.forEach { id: String -> runBlocking { AndroidAlarmScheduler(context, keys).cancel(id) } }
        delivered.clear()
        AlarmDispatch.reset()
        var scheduler: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(handler))

        val armed: MutableMap<String, ScheduledAlarm> = mutableMapOf()
        // Ids that hold a slot: scheduled (even if it has fired since) and not cancelled after.
        val holding: MutableSet<String> = mutableSetOf()
        var exact = true
        var counter = 0
        val log = StringBuilder()

        repeat(STEPS) { step: Int ->
            when (random.nextInt(12)) {
                0, 1, 2, 3, 4 -> {
                    val id: String = IDS.random(random)
                    val alarm = ScheduledAlarm(
                        id = id,
                        type = "T",
                        fireAtEpochMillis = TIMES.random(random),
                        notification = AlarmNotification(title = "t${counter++}", body = "b", channelId = "c"),
                        payload = mapOf("n" to "$counter", "seed" to "$seed"),
                    )
                    val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm) }
                    log.append("schedule($id@${alarm.fireAtEpochMillis},exact=$exact)=${result::class.simpleName}; ")
                    val expected: AlarmScheduleResult =
                        if (exact) AlarmScheduleResult.Exact else AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING)
                    assertEquals(expected, result, "seed $seed step $step: answer. Steps: $log")
                    armed[id] = alarm
                    holding += id
                    assertEquals(1, slots(id), "seed $seed step $step: slots of a freshly scheduled '$id'. Steps: $log")
                }

                5 -> {
                    val id: String = IDS.random(random)
                    runBlocking { scheduler.cancel(id) }
                    armed.remove(id)
                    holding.remove(id)
                    log.append("cancel($id); ")
                    assertEquals(0, slots(id), "seed $seed step $step: slots of a cancelled '$id'. Steps: $log")
                }

                6 -> {
                    val some: List<String> = List(random.nextInt(0, 5)) { (IDS + "never-scheduled").random(random) }
                    runBlocking { scheduler.cancelAll(some) }
                    some.forEach { armed.remove(it); holding.remove(it) }
                    log.append("cancelAll($some); ")
                    some.forEach { assertEquals(0, slots(it), "seed $seed step $step: slots of '$it'. Steps: $log") }
                }

                7 -> {
                    exact = !exact
                    ShadowAlarmManager.setCanScheduleExactAlarms(exact)
                    log.append("exact=$exact; ")
                }

                8 -> {
                    if (random.nextBoolean()) AlarmDispatch.reset()
                    scheduler = createAlarmScheduler(context, handlers = listOf(handler))
                    log.append("restart; ")
                }

                else -> {
                    // The OS fires the earliest alarm it holds (any of equal instants).
                    val earliest: Long? = armed.values.minOfOrNull { it.fireAtEpochMillis }
                    val fired: ShadowAlarmManager.ScheduledAlarm? = shadowAlarmManager.nextScheduledAlarm
                    if (earliest == null) {
                        assertEquals(null, fired, "seed $seed step $step: the OS fired an alarm the model does not hold. Steps: $log")
                        log.append("fire(none); ")
                    } else {
                        val operation: PendingIntent = assertNotNull(fired?.operation, "seed $seed step $step: nothing to fire. Steps: $log")
                        assertEquals(earliest, fired?.triggerAtTime, "seed $seed step $step: the OS did not fire the earliest. Steps: $log")
                        val intent = shadowOf(operation).savedIntent
                        val expected: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(intent, keys), "seed $seed: unreadable. Steps: $log")
                        assertEquals(armed[expected.id], expected, "seed $seed step $step: the fired alarm is not the model's for '${expected.id}'. Steps: $log")
                        @Suppress("DEPRECATION")
                        AlarmReceiver().onReceive(context, intent)
                        val got: ScheduledAlarm? = delivered.poll(10, TimeUnit.SECONDS)
                        assertEquals(expected, got, "seed $seed step $step: the handler got another alarm. Steps: $log")
                        assertEquals(null, delivered.poll(20, TimeUnit.MILLISECONDS), "seed $seed step $step: delivered twice. Steps: $log")
                        armed.remove(expected.id)
                        log.append("fire(${expected.id}); ")
                    }
                }
            }
            assertEquals(armed.toMap(), armedNow(seed, log), "seed $seed step $step: the OS and the model disagree. Steps: $log")
            IDS.forEach { id: String ->
                val held: Int = slots(id)
                assertTrue(held <= 1, "seed $seed step $step: '$id' holds $held slots. Steps: $log")
                if (id !in holding) assertEquals(0, held, "seed $seed step $step: '$id' holds a slot nobody scheduled. Steps: $log")
            }
        }
        // Whatever is left, cancelling everything clears every slot.
        runBlocking { scheduler.cancelAll(IDS) }
        assertEquals(emptyMap(), armedNow(seed, log), "seed $seed: armed after the final cancelAll. Steps: $log")
        IDS.forEach { assertEquals(0, slots(it), "seed $seed: '$it' holds a slot after the final cancelAll. Steps: $log") }
    }

    private companion object {
        const val SEEDS: Int = 60
        const val STEPS: Int = 50
        val IDS: List<String> = listOf("a", "b", "c", "d/e", "d%2Fe")
        val TIMES: List<Long> = listOf(
            Long.MIN_VALUE, -1L, 0L, 1L, 1_000L, 1_000L, 1_700_000_000_000L, 4_102_444_800_000L, Long.MAX_VALUE - 1, Long.MAX_VALUE,
        )
    }
}
