package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
 * r6-F1. docs 04 and the `AlarmFailure.PlatformError` KDoc: `message` is a diagnostic for logs, either the platform's
 * text or a fixed English description from this library (the iOS cap, or an Android id with all four slots held).
 * A saturated id must answer `PlatformError` with such a library diagnostic, never display text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerSaturationMessageAdversarialTest.RefusingShadow::class])
class AndroidAlarmSchedulerSaturationMessageAdversarialTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before fun setUp() { ShadowAlarmManager.setCanScheduleExactAlarms(true); RefusingShadow.refuseCancel.set(false) }
    @After fun tearDown() { RefusingShadow.refuseCancel.set(false); AlarmDispatch.reset() }

    private fun alarm(title: String) = ScheduledAlarm(
        id = "a", type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    @Test
    fun `a saturated id answers PlatformError with a library diagnostic message naming cancel`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            RefusingShadow.refuseCancel.set(true)
            for (n in 1..4) assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm("s$n")))
            RefusingShadow.refuseCancel.set(false)
            val failed = assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm("healthy")))
            val error = assertIs<AlarmFailure.PlatformError>(failed.reason)
            assertNotNull(error.message, "the library's own diagnostic is documented for a saturated id")
            assertTrue(error.message!!.contains("cancel"), "the diagnostic points at the remedy: ${error.message}")
        }
    }

    @Implements(AlarmManager::class)
    class RefusingShadow : ShadowAlarmManager() {
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
