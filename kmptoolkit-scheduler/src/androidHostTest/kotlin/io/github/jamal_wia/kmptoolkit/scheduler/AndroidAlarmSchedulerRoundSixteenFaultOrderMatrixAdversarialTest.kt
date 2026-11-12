package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 16 (regression hunt on the round 15 scheduler fixes): every one of the four platform calls a
 * `schedule` can make (the exact-right check, the exact set, the inexact set, the cancel of the retired
 * alarm) either works or throws one of three kinds (RuntimeException, SecurityException, Error), in every
 * combination, over two faulty replacements of an armed alarm followed by a healthy platform. Contract
 * (04-api-reference): "Never throws for a permission or platform refusal", "Failed: nothing was armed", a
 * failed replacement leaves the armed alarm unchanged, slots are saturated only by "a retirement the platform
 * refused", and a healthy `schedule` and `cancel` always recover the id.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerRoundSixteenFaultOrderMatrixAdversarialTest.ScriptedShadowAlarmManager::class])
class AndroidAlarmSchedulerRoundSixteenFaultOrderMatrixAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    enum class Fault { NONE, RUNTIME, SECURITY, ERROR }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ScriptedShadowAlarmManager.script.set(Script())
    }

    @After
    fun tearDown() {
        ScriptedShadowAlarmManager.script.set(Script())
        AlarmDispatch.reset()
    }

    data class Script(
        val right: Fault = Fault.NONE,
        val exact: Fault = Fault.NONE,
        val inexact: Fault = Fault.NONE,
        val cancel: Fault = Fault.NONE,
    )

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun alarm(id: String, at: Long): ScheduledAlarm =
        ScheduledAlarm(id, "T", at, AlarmNotification("t$at", "b", "c"), mapOf("k" to "v$at"))

    private fun armedTimes(): List<Long> = shadowAlarmManager.scheduledAlarms.map { it.triggerAtMs }

    /** The content of every armed alarm as the receiver would read it: a refused replacement must not change it. */
    private fun armedContent(): List<ScheduledAlarm> = shadowAlarmManager.scheduledAlarms.map { armed ->
        assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys), "unreadable armed alarm")
    }

    @Test
    fun `every combination of faults of the four platform calls keeps the contract and the id recovers`() {
        val faults: List<Fault> = Fault.values().toList()
        var combos = 0
        for (right in faults) for (exact in faults) for (inexact in faults) for (cancel in faults) {
            combos++
            val script = Script(right, exact, inexact, cancel)
            val label = "script $script"
            shadowAlarmManager.scheduledAlarms.toList().forEach { shadowAlarmManager.scheduledAlarms.remove(it) }
            ScriptedShadowAlarmManager.script.set(Script())
            val id = "m-$combos"
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(id, 1_000L)) }, "$label: healthy first schedule")

            ScriptedShadowAlarmManager.script.set(script)
            for (round in 1..2) {
                val before: List<Long> = armedTimes()
                val beforeContent: List<ScheduledAlarm> = armedContent()
                val outcome: Result<AlarmScheduleResult> =
                    runCatching { runBlocking { withTimeout(20_000) { scheduler.schedule(alarm(id, 1_000L + round)) } } }
                val thrown: Throwable? = outcome.exceptionOrNull()
                assertTrue(thrown == null || thrown is OutOfMemoryError, "$label round $round: only an Error may escape, got $thrown")
                val result: AlarmScheduleResult? = outcome.getOrNull()
                // The oracle, written from the contract: a refusal is a Failed, a revoked exact right with a
                // working inexact call is the documented downgrade, an Error is not swallowed, and a refused
                // retirement is logged and never turns an armed alarm into a failure (an Error there still escapes).
                val expected: String = when {
                    right == Fault.ERROR -> "error"
                    right != Fault.NONE -> "failed"
                    exact == Fault.NONE -> if (cancel == Fault.ERROR) "error" else "exact"
                    exact == Fault.RUNTIME -> "failed"
                    exact == Fault.ERROR -> "error"
                    inexact == Fault.NONE -> if (cancel == Fault.ERROR) "error" else "downgraded"
                    inexact == Fault.ERROR -> "error"
                    else -> "failed"
                }
                val actual: String = when {
                    thrown != null -> "error"
                    result is AlarmScheduleResult.Exact -> "exact"
                    result is AlarmScheduleResult.Inexact && result.reason == InexactReason.EXACT_ALARM_PERMISSION_REVOKED -> "downgraded"
                    result is AlarmScheduleResult.Failed -> "failed"
                    else -> "other: $result"
                }
                assertEquals(expected, actual, "$label round $round: result $result thrown $thrown")
                if (result is AlarmScheduleResult.Failed) {
                    assertTrue(result.reason is AlarmFailure.PlatformError, "$label round $round: $result")
                    assertEquals(before, armedTimes(), "$label round $round: Failed means nothing was armed and nothing was disturbed")
                    assertEquals(beforeContent, armedContent(), "$label round $round: a refused replacement keeps content too")
                }
                if (cancel == Fault.NONE) {
                    assertEquals(1, occupiedSlots(id), "$label round $round: without a refused retirement exactly one slot is in use")
                }
            }

            ScriptedShadowAlarmManager.script.set(Script())
            val healthy: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm(id, 5_000L)) }
            if (cancel == Fault.NONE) {
                assertEquals(AlarmScheduleResult.Exact, healthy, "$label: a healthy platform after faulty sets must schedule")
                assertEquals(listOf(5_000L), armedTimes(), "$label: only the final alarm remains")
                assertEquals(1, occupiedSlots(id), "$label: one slot")
            } else {
                assertTrue(healthy is AlarmScheduleResult.Exact || healthy is AlarmScheduleResult.Failed, "$label: $healthy")
            }
            runBlocking { scheduler.cancel(id) }
            assertEquals(emptyList(), armedTimes(), "$label: a healthy cancel leaves nothing armed")
            assertEquals(0, occupiedSlots(id), "$label: a healthy cancel leaves no slot")
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(id, 9_000L)) }, "$label: id usable after cancel")
            runBlocking { scheduler.cancel(id) }
        }
        assertEquals(256, combos)
    }

    @Implements(AlarmManager::class)
    class ScriptedShadowAlarmManager : ShadowAlarmManager() {

        private fun throwFor(fault: Fault) {
            when (fault) {
                Fault.NONE -> Unit
                Fault.RUNTIME -> throw IllegalStateException("scripted refusal")
                Fault.SECURITY -> throw SecurityException("scripted security")
                Fault.ERROR -> throw OutOfMemoryError("scripted")
            }
        }

        @Implementation
        public override fun canScheduleExactAlarms(): Boolean {
            throwFor(script.get().right)
            return super.canScheduleExactAlarms()
        }

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            throwFor(script.get().exact)
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            throwFor(script.get().inexact)
            insideSet.set(true)
            try { super.setAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            // Robolectric's own set calls cancel() to replace an alarm; the real platform does not refuse that.
            if (!insideSet.get()) throwFor(script.get().cancel)
            super.cancel(operation)
        }

        companion object {
            val script: AtomicReference<Script> = AtomicReference(Script())
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }
}
