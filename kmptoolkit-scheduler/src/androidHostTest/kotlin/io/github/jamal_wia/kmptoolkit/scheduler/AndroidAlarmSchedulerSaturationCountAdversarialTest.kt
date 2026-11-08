package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicReference
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
 * Published contract (docs 04 `schedule` and `Failed` rows, KDoc of `AlarmScheduler.schedule`): an id owns four
 * slots; each replacement whose cancel is refused leaves the replaced alarm in one, so after three such
 * replacements in a row every slot is held and `schedule` answers `Failed` untouched until `cancel(id)`.
 * Pins the published number to the code on both sides of the boundary.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerSaturationCountAdversarialTest.CountShadowAlarmManager::class])
class AndroidAlarmSchedulerSaturationCountAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() { ShadowAlarmManager.setCanScheduleExactAlarms(true); CountShadowAlarmManager.refuseCancel.set(false) }

    @After
    fun tearDown() { CountShadowAlarmManager.refuseCancel.set(false); AlarmDispatch.reset() }

    private fun alarm(title: String) = ScheduledAlarm(
        id = "a", type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    private fun titles(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }.sorted()

    private fun slotsHeld(): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode("a", slot),
            AlarmIntents.cancelIntent(context, "a", keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `three refused replacements saturate the id, a healthy schedule then fails untouched, cancel recovers`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm("one")))
            assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
            assertEquals(1, slotsHeld())

            CountShadowAlarmManager.refuseCancel.set(true)
            for (n in 2..4) {
                // The third refused replacement itself still succeeds: the boundary is not reached before it.
                assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm("refused$n")))
                assertEquals(n, shadowAlarmManager.scheduledAlarms.size, "alarms after refused replacement $n")
                assertEquals(n, slotsHeld(), "slots after refused replacement $n")
            }
            CountShadowAlarmManager.refuseCancel.set(false)

            val healthy = scheduler.schedule(alarm("healthy"))
            assertIs<AlarmScheduleResult.Failed>(healthy)
            assertIs<AlarmFailure.PlatformError>(healthy.reason)
            assertEquals(listOf("one", "refused2", "refused3", "refused4"), titles())
            assertEquals(4, slotsHeld())

            scheduler.cancel("a")
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
            assertEquals(0, slotsHeld())
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm("healthy")))
            assertEquals(listOf("healthy"), titles())
            assertEquals(1, slotsHeld())
        }
    }

    @Implements(AlarmManager::class)
    class CountShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (refuseCancel.get() && !insideSet.get()) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val refuseCancel: AtomicReference<Boolean> = AtomicReference(false)
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }
}
