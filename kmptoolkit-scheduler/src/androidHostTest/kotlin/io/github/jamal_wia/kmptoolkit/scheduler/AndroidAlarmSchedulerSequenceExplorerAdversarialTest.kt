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
 * Every sequence of up to six operations over one id, from a healthy schedule of either of two
 * contents, a schedule the platform refuses, a schedule whose retirement of the old alarm is refused,
 * a healthy cancel and a cancel the platform refuses. After each operation the OS must hold what the
 * documented rules allow, and a healthy schedule or cancel must always leave exactly one alarm or none,
 * with a single `PendingIntent` slot or none, whatever stale state the refused operations left behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerSequenceExplorerAdversarialTest.ModalShadowAlarmManager::class])
class AndroidAlarmSchedulerSequenceExplorerAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    private enum class Op { SCHEDULE_X, SCHEDULE_Y, SCHEDULE_REFUSED, SCHEDULE_RETIRE_REFUSED, CANCEL, CANCEL_REFUSED }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
    }

    @After
    fun reset() {
        ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, which: String, fireAt: Long): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = which, body = "b", channelId = "c"),
        payload = mapOf("which" to which),
    )

    private fun armed(id: String): List<ScheduledAlarm> = shadowAlarmManager.scheduledAlarms
        .map { armed: ShadowAlarmManager.ScheduledAlarm ->
            assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys), "unreadable alarm")
        }
        .filter { it.id == id }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `every sequence of six operations leaves what the rules allow and is always repaired by a healthy call`() {
        val ops: List<Op> = Op.values().toList()
        var sequences = 0
        var counter = 0
        fun walk(prefix: List<Op>) {
            if (prefix.isNotEmpty()) {
                sequences++
                exercise("s${counter++}", prefix)
            }
            if (prefix.size == MAX_LENGTH) return
            ops.forEach { walk(prefix + it) }
        }
        walk(emptyList())
        assertTrue(sequences > 50_000, "explored only $sequences sequences")
    }

    private fun exercise(id: String, sequence: List<Op>) {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val x: ScheduledAlarm = alarm(id, "x", 1_000L)
        val y: ScheduledAlarm = alarm(id, "y", 2_000L)
        sequence.forEachIndexed { step: Int, op: Op ->
            val label = "${sequence.take(step + 1)}"
            val before: List<ScheduledAlarm> = armed(id)
            val slotsBefore: Int = slots(id)
            when (op) {
                Op.SCHEDULE_X, Op.SCHEDULE_Y -> {
                    val wanted: ScheduledAlarm = if (op == Op.SCHEDULE_X) x else y
                    assertTrue(runBlocking { scheduler.schedule(wanted) } is AlarmScheduleResult.Exact, label)
                    assertEquals(listOf(wanted), armed(id), "$label: a healthy schedule must leave exactly that alarm")
                    assertEquals(1, slots(id), "$label: slots after a healthy schedule")
                }
                Op.SCHEDULE_REFUSED -> {
                    ModalShadowAlarmManager.mode.set(Mode.REFUSE_SET)
                    val result: AlarmScheduleResult = runBlocking { scheduler.schedule(y) }
                    ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
                    assertTrue(result is AlarmScheduleResult.Failed, "$label: $result")
                    // With every slot occupied (the leftover of SLOT_COUNT - 1 refused retirements in a row) the
                    // first slot is rewritten in place: the one documented case the scheduler cannot keep.
                    if (slotsBefore < AlarmIntents.SLOT_COUNT) {
                        assertEquals(before.map { it.notification.title }, armed(id).map { it.notification.title }, "$label: a refused schedule changed what was armed")
                    }
                    assertEquals(slotsBefore, slots(id), "$label: a refused schedule changed the slots")
                }
                Op.SCHEDULE_RETIRE_REFUSED -> {
                    ModalShadowAlarmManager.mode.set(Mode.REFUSE_CANCEL)
                    val result: AlarmScheduleResult = runBlocking { scheduler.schedule(x) }
                    ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
                    assertTrue(result is AlarmScheduleResult.Exact, "$label: the new alarm is armed, so the result is $result")
                    assertTrue(armed(id).any { it == x }, "$label: the new alarm must be armed")
                }
                Op.CANCEL -> {
                    runBlocking { scheduler.cancel(id) }
                    assertEquals(emptyList(), armed(id), "$label: a healthy cancel must leave nothing armed")
                    assertEquals(0, slots(id), "$label: slots after a healthy cancel")
                }
                Op.CANCEL_REFUSED -> {
                    ModalShadowAlarmManager.mode.set(Mode.REFUSE_CANCEL)
                    runBlocking { scheduler.cancel(id) }
                    ModalShadowAlarmManager.mode.set(Mode.HEALTHY)
                    assertTrue(before.containsAll(armed(id)), "$label: a refused cancel armed something new")
                }
            }
        }
        // Whatever is left, one healthy cancel clears it.
        runBlocking { scheduler.cancel(id) }
        assertEquals(emptyList(), armed(id), "$sequence: stale alarm survived the final cancel")
        assertEquals(0, slots(id), "$sequence: stale slot survived the final cancel")
    }

    enum class Mode { HEALTHY, REFUSE_SET, REFUSE_CANCEL }

    /** Refuses `setExactAndAllowWhileIdle` or `cancel` while the mode says so. */
    @Implements(AlarmManager::class)
    class ModalShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (mode.get() == Mode.REFUSE_SET) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            // The shadow replaces an alarm of the same PendingIntent by cancelling it internally: that
            // is not a call of the scheduler and must not be refused.
            insideSet.set(true)
            try {
                super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
            } finally {
                insideSet.set(false)
            }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (mode.get() == Mode.REFUSE_CANCEL && !insideSet.get()) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val mode: AtomicReference<Mode> = AtomicReference(Mode.HEALTHY)
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }

    private companion object {
        const val MAX_LENGTH: Int = 6
    }
}
