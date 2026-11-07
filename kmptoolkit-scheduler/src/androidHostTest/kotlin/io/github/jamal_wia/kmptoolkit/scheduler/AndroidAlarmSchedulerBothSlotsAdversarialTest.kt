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
import java.util.concurrent.atomic.AtomicReference

/**
 * A refused retirement leaves both PendingIntent slots occupied (and both alarms armed). A replacement
 * the platform then refuses is reported as Failed ("nothing was armed") yet rewrites the extras of the
 * slot it targets in place, so an alarm that stays armed now delivers the refused replacement's content.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerBothSlotsAdversarialTest.ModalShadowAlarmManager::class])
class AndroidAlarmSchedulerBothSlotsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

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

    private fun alarm(title: String, fireAt: Long) = ScheduledAlarm(
        id = "a", type = "T", fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    @Test
    fun `a refused replacement does not rewrite an alarm that stays armed even when a retirement was refused before`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        assertIs<AlarmScheduleResult.Exact>(runBlocking { scheduler.schedule(alarm("one", 1_000L)) })
        // The replacement arms, but the platform refuses to cancel the old slot: both slots stay occupied.
        ModalShadowAlarmManager.mode.set(Mode.REFUSE_CANCEL)
        assertIs<AlarmScheduleResult.Exact>(runBlocking { scheduler.schedule(alarm("two", 2_000L)) })
        val titlesBefore: List<String> = titles()

        ModalShadowAlarmManager.mode.set(Mode.REFUSE_SET)
        val refused: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("three", 3_000L)) }
        ModalShadowAlarmManager.mode.set(Mode.HEALTHY)

        assertIs<AlarmScheduleResult.Failed>(refused)
        assertEquals(titlesBefore, titles(), "a refused replacement ('Nothing was armed') rewrote the content of an alarm that stays armed")
    }

    private fun titles(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }
        .sorted()

    enum class Mode { HEALTHY, REFUSE_SET, REFUSE_CANCEL }

    @Implements(AlarmManager::class)
    class ModalShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (mode.get() == Mode.REFUSE_SET) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
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
}
