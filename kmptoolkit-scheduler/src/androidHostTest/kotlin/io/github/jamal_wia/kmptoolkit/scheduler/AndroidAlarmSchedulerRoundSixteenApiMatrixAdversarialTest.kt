package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Round 16 (platform edges, lean API matrix, two levels because a wider list exhausts the shared test JVM): the exact-alarm right exists from Android 12 (API 31) only; below
 * it the scheduler must not ask, and from 31 up a missing right is the documented `Inexact(MISSING)` downgrade,
 * a right revoked inside the check-then-act window is `Inexact(REVOKED)` on every level, and a platform refusal
 * (the 500 alarm cap is an `IllegalStateException` since Android 12, any `RuntimeException` before) is a `Failed`
 * that leaves the previous alarm and no slot behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [24, 31],
    shadows = [AndroidAlarmSchedulerRoundSixteenApiMatrixAdversarialTest.MatrixShadowAlarmManager::class],
)
class AndroidAlarmSchedulerRoundSixteenApiMatrixAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        MatrixShadowAlarmManager.mode.set(Mode.HEALTHY)
        MatrixShadowAlarmManager.rightQueries.set(0)
    }

    @After
    fun tearDown() {
        MatrixShadowAlarmManager.mode.set(Mode.HEALTHY)
        AlarmDispatch.reset()
    }

    enum class Mode { HEALTHY, RIGHT_MISSING, RACE_REVOKED, CAP_REACHED }

    private fun alarm(id: String, at: Long): ScheduledAlarm =
        ScheduledAlarm(id, "T", at, AlarmNotification("t", "b", "c"))

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private val hasRightApi: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    @Test
    fun `a missing exact right is a downgrade only where the right exists and the right is never asked below it`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        MatrixShadowAlarmManager.mode.set(Mode.RIGHT_MISSING)

        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("a", 1_900_000_000_000L)) }

        if (hasRightApi) {
            assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_MISSING), result, "sdk ${Build.VERSION.SDK_INT}")
            assertEquals(1, MatrixShadowAlarmManager.rightQueries.get(), "the right is asked once from API 31")
        } else {
            assertEquals(AlarmScheduleResult.Exact, result, "sdk ${Build.VERSION.SDK_INT}: no such right below API 31")
            assertEquals(0, MatrixShadowAlarmManager.rightQueries.get(), "the right does not exist below API 31 and must not be asked")
        }
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `a right revoked inside the window is the revoked downgrade on every level where it can happen`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        MatrixShadowAlarmManager.mode.set(Mode.RACE_REVOKED)

        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("a", 1_900_000_000_000L)) }

        assertEquals(AlarmScheduleResult.Inexact(InexactReason.EXACT_ALARM_PERMISSION_REVOKED), result, "sdk ${Build.VERSION.SDK_INT}")
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(1, occupiedSlots("a"))
        runBlocking { scheduler.cancel("a") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, occupiedSlots("a"))
    }

    @Test
    fun `the alarm cap refusal is a Failed on every level and leaves the armed alarm and no extra slot`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", 1_900_000_000_000L)) })
        MatrixShadowAlarmManager.mode.set(Mode.CAP_REACHED)

        repeat(6) {
            val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("a", 1_900_000_000_001L + it)) }
            assertTrue(result is AlarmScheduleResult.Failed && result.reason is AlarmFailure.PlatformError, "sdk ${Build.VERSION.SDK_INT}: $result")
        }

        assertEquals(listOf(1_900_000_000_000L), shadowAlarmManager.scheduledAlarms.map { it.triggerAtMs })
        assertEquals(1, occupiedSlots("a"))
        MatrixShadowAlarmManager.mode.set(Mode.HEALTHY)
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", 1_900_000_000_999L)) })
        assertEquals(listOf(1_900_000_000_999L), shadowAlarmManager.scheduledAlarms.map { it.triggerAtMs })
    }

    @Test
    fun `a fire time in the past is armed at once on every level and replace then cancel leaves nothing`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)

        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("p", -1L)) })
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("p", 0L)) })
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("p", Long.MAX_VALUE)) })
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "replacement keeps one alarm per id")
        assertEquals(1, occupiedSlots("p"))
        runBlocking { scheduler.cancel("p") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, occupiedSlots("p"))
    }

    @Implements(AlarmManager::class)
    class MatrixShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun canScheduleExactAlarms(): Boolean {
            rightQueries.incrementAndGet()
            return mode.get() != Mode.RIGHT_MISSING
        }

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            when (mode.get()) {
                Mode.RACE_REVOKED -> throw SecurityException("revoked in the window")
                Mode.CAP_REACHED -> throw IllegalStateException("Maximum limit of concurrent alarms 500 reached")
                else -> super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
            }
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (mode.get() == Mode.CAP_REACHED) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached")
            super.setAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        companion object {
            val mode: AtomicReference<Mode> = AtomicReference(Mode.HEALTHY)
            val rightQueries: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
        }
    }
}
