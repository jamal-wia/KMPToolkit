package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The "Alarms and reminders" right in every state it can be in while an alarm is armed: held, missing,
 * revoked in the check-then-act window (the exact call throws `SecurityException`), and revoked so
 * thoroughly that the inexact call is refused with a `SecurityException` too. A seeded sequence flips
 * them between schedules of three ids: every answer names the state it met, the alarm the user asked for
 * last is the only one armed for its id, a refused schedule leaves the previous alarm and its content
 * exactly as they were, and a cancel always empties the id. The seed is in every failure message.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerExactRightStatesAdversarialTest.RightShadowAlarmManager::class])
class AndroidAlarmSchedulerExactRightStatesAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    enum class State { HELD, MISSING, REVOKED_RACE, INEXACT_REFUSED }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        RightShadowAlarmManager.state.set(State.HELD)
    }

    @After
    fun reset() {
        RightShadowAlarmManager.state.set(State.HELD)
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        AlarmDispatch.reset()
    }

    private fun apply(state: State) {
        RightShadowAlarmManager.state.set(state)
        // Missing and "inexact refused" are states in which the platform answers canScheduleExactAlarms() false.
        ShadowAlarmManager.setCanScheduleExactAlarms(state == State.HELD || state == State.REVOKED_RACE)
    }

    private fun armed(): Map<String, ScheduledAlarm> = shadowAlarmManager.scheduledAlarms.associate { a: ShadowAlarmManager.ScheduledAlarm ->
        val alarm: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(a.operation).savedIntent, keys))
        alarm.id to alarm
    }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `every state of the exact right gives the answer it names and never loses or doubles an armed alarm`() {
        val ids: List<String> = listOf("a", "b", "c")
        repeat(SEEDS) { seed: Int ->
            val random = Random(seed)
            apply(State.HELD)
            shadowAlarmManager.scheduledAlarms.toList().forEach { (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(requireNotNull(it.operation)) }
            ids.forEach { runBlocking { AndroidAlarmScheduler(context, keys).cancel(it) } }
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val model: MutableMap<String, ScheduledAlarm> = mutableMapOf()
            var state = State.HELD
            val log = StringBuilder()
            repeat(STEPS) { step: Int ->
                when (random.nextInt(6)) {
                    0 -> {
                        state = State.values().random(random)
                        apply(state)
                        log.append("state=$state; ")
                    }

                    1 -> {
                        val id: String = ids.random(random)
                        runBlocking { scheduler.cancel(id) }
                        model.remove(id)
                        log.append("cancel($id); ")
                        assertEquals(0, slots(id), "seed $seed step $step: slots after cancel. Steps: $log")
                    }

                    else -> {
                        val id: String = ids.random(random)
                        val alarm = ScheduledAlarm(id, "T", 1_000L + step, AlarmNotification("t$step", "b", "c"), mapOf("s" to "$seed"))
                        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm) }
                        log.append("schedule($id)@$state=${result::class.simpleName}; ")
                        when (state) {
                            State.HELD -> {
                                assertEquals(AlarmScheduleResult.Exact, result, "seed $seed step $step. Steps: $log")
                                model[id] = alarm
                            }

                            State.MISSING -> {
                                assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING), result, "seed $seed step $step. Steps: $log")
                                model[id] = alarm
                            }

                            State.REVOKED_RACE -> {
                                assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_REVOKED), result, "seed $seed step $step. Steps: $log")
                                model[id] = alarm
                            }

                            State.INEXACT_REFUSED -> {
                                assertTrue(result is AlarmScheduleResult.Failed, "seed $seed step $step: a refused schedule answered $result. Steps: $log")
                            }
                        }
                    }
                }
                assertEquals(model.toMap(), armed(), "seed $seed step $step: the OS and the model disagree. Steps: $log")
                ids.forEach { assertTrue(slots(it) <= 1, "seed $seed step $step: '$it' holds ${slots(it)} slots. Steps: $log") }
            }
            apply(State.HELD)
            runBlocking { scheduler.cancelAll(ids) }
            assertEquals(emptyMap(), armed(), "seed $seed: armed after the final cancelAll")
            ids.forEach { assertEquals(0, slots(it), "seed $seed: slot left after the final cancelAll") }
        }
    }

    /** Throws the `SecurityException` the state asks for from the exact call, or from the inexact one too. */
    @Implements(AlarmManager::class)
    class RightShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (state.get() == State.REVOKED_RACE) throw SecurityException("exact alarms revoked in the window")
            super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (state.get() == State.INEXACT_REFUSED) throw SecurityException("alarms refused")
            super.setAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        companion object {
            val state: AtomicReference<State> = AtomicReference(State.HELD)
        }
    }

    private companion object {
        const val SEEDS: Int = 80
        const val STEPS: Int = 40
    }
}
