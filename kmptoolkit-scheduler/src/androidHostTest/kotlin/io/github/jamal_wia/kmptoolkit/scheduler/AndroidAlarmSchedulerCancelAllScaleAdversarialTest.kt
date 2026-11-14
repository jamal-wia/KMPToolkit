package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
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
 * Round 15 (resource starvation): `cancelAll` is the call a logout makes with "whatever the store says",
 * which may be thousands of ids of which most were never armed on this device. "`cancelAll` cancels
 * exactly the ids passed" - and it must finish, cancelling the armed ones and only those.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerCancelAllScaleAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(id, "T", 1_900_000_000_000L + id.hashCode().toLong().mod(1000L), AlarmNotification("t", "b", "c"))

    @Test
    fun `cancelAll of twenty thousand ids of which four hundred are armed removes exactly those four hundred`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        val armedIds: List<String> = (0 until 400).map { "armed-$it" }
        val spare: List<String> = (0 until 40).map { "spare-$it" }
        runBlocking { (armedIds + spare).forEach { scheduler.schedule(alarm(it)) } }
        val all: List<String> = (0 until 19_600).map { "ghost-$it" } + armedIds

        runBlocking { withTimeout(240_000) { scheduler.cancelAll(all) } }

        assertEquals(spare.size, shadowAlarmManager.scheduledAlarms.size, "only the ids that were not passed may remain armed")
    }
}
