package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * `system_server` restarting under the app: `AlarmManager` calls fail with a `DeadSystemRuntimeException`
 * (a hidden API, modelled here as a plain `RuntimeException`), which is neither of the two refusals the scheduler converts. The
 * contract says schedule never throws for a platform refusal and cancel never throws for one either.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerSystemDeathAdversarialTest.DyingShadowAlarmManager::class])
class AndroidAlarmSchedulerSystemDeathAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        DyingShadowAlarmManager.dying.set(null)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "chan"),
    )

    @Test
    fun `a schedule that meets a dying system server is reported as a failure and does not throw`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        DyingShadowAlarmManager.dying.set(IllegalArgumentException("android.os.DeadSystemRuntimeException: The system died"))

        val outcome: Result<AlarmScheduleResult> = runCatching { runBlocking { scheduler.schedule(alarm("a")) } }

        assertEquals(null, outcome.exceptionOrNull(), "schedule threw")
        assertIs<AlarmScheduleResult.Failed>(outcome.getOrNull())
    }

    @Test
    fun `a cancel that meets a dying system server does not throw`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { scheduler.schedule(alarm("a")) }
        DyingShadowAlarmManager.dying.set(IllegalArgumentException("android.os.DeadSystemRuntimeException: The system died"))

        val outcome: Result<Unit> = runCatching { runBlocking { scheduler.cancel("a") } }

        assertEquals(null, outcome.exceptionOrNull(), "cancel threw")
    }

    @Implements(AlarmManager::class)
    class DyingShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            dying.get()?.let { throw it }
            super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            dying.get()?.let { throw it }
            super.cancel(operation)
        }

        companion object {
            val dying: AtomicReference<RuntimeException?> = AtomicReference(null)
        }
    }
}
