package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Lifecycle chaos for the Android scheduler: a host activity that goes away while the scheduler lives on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerLifecycleAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String, fireAt: Long): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = title, body = "b", channelId = "chan"),
    )

    @Test
    fun `a scheduler made from an activity that is then destroyed keeps working and holds no activity`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val scheduler: AlarmScheduler = createAlarmScheduler(controller.get())
        controller.pause().stop().destroy()

        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("a", "t", 1_000L)) })
        runBlocking { scheduler.cancel("a") }
        assertTrue(shadowAlarmManager.scheduledAlarms.isEmpty())
        val held: Any? = scheduler.javaClass.getDeclaredField("context").also { it.isAccessible = true }.get(scheduler)
        assertFalse(held is Activity, "the scheduler retained the host activity")
    }
}
