package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * A replacement the platform refuses must leave the alarm that is armed exactly as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerRefusalAdversarialTest.RefusingShadowAlarmManager::class])
class AndroidAlarmSchedulerRefusalAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        RefusingShadowAlarmManager.refusals.set(0)
    }

    @After
    fun reset() {
        RefusingShadowAlarmManager.refusals.set(0)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String, fireAt: Long): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = title, body = "b", channelId = "chan"),
    )

    @Test
    fun `a replacement the platform refuses leaves the alarm that was armed exactly as it was`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
        assertIs<AlarmScheduleResult.Exact>(runBlocking { scheduler.schedule(alarm("a", title = "original", fireAt = 1_000L)) })

        RefusingShadowAlarmManager.refusals.set(1)
        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("a", title = "replacement", fireAt = 9_000L)) }

        assertIs<AlarmScheduleResult.Failed>(result)
        // The OS still holds the original alarm, due at 1_000. Whatever it delivers must be the original.
        val armed: ShadowAlarmManager.ScheduledAlarm = shadowAlarmManager.scheduledAlarms.single()
        val delivered: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys))
        assertEquals(1_000L, armed.triggerAtTime)
        assertEquals(
            "original",
            delivered.notification.title,
            "a refused replacement rewrote the content of the alarm that stays armed (Failed promises nothing was armed)",
        )
        assertEquals(1_000L, delivered.fireAtEpochMillis)
    }

    /** Makes the next N exact-alarm calls throw the way a platform at its alarm limit does. */
    @Implements(AlarmManager::class)
    class RefusingShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (refusals.getAndUpdate { if (it > 0) it - 1 else it } > 0) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        companion object {
            val refusals: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
        }
    }
}
