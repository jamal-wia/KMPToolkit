package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * R19-S1, pinned behaviour (ruling D-53): three process deaths, each between the arm of a replacement and the
 * retirement of the old alarm (the first retirement call of the scheduler never takes effect), leave the old alarms
 * armed and the four slots of the id occupied. A healthy process then answers Failed with the saturation message,
 * which names a process death as a second cause, and `cancel(id)` frees the id.
 *
 * CHANGED EXPECTATION against the breaker's probe: the probe asserted that the healthy `schedule` returns Exact
 * (it failed red). The ruling keeps the stateless design, so the ghost alarms and the saturation stay and are pinned;
 * what changed is the message text and the docs (04-api-reference, 05-platform-notes "A refused or interrupted
 * cancel"). The probe's ghost and saturation observations are asserted, plus recovery by `cancel(id)`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerDeathInReplaceWindowAdversarialTest.KillAtRetireShadowAlarmManager::class])
class AndroidAlarmSchedulerDeathInReplaceWindowAdversarialTest {
    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    class ProcessKilled : Error("killed at retire")

    @Before fun setUp() { ShadowAlarmManager.setCanScheduleExactAlarms(true); KillAtRetireShadowAlarmManager.dieAtRetire.set(false) }
    @After fun tearDown() { KillAtRetireShadowAlarmManager.dieAtRetire.set(false); AlarmDispatch.reset() }

    private fun alarm(version: Int): ScheduledAlarm = ScheduledAlarm("a", "T", 5_000_000L + version, AlarmNotification("t$version", "b", "c"))

    private fun slots(): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot ->
        PendingIntent.getBroadcast(context, AlarmIntents.requestCode("a", slot), AlarmIntents.cancelIntent(context, "a", keys), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE) != null
    }

    private fun processDiesAtRetire(version: Int) {
        KillAtRetireShadowAlarmManager.dieAtRetire.set(true)
        val death = runCatching { runBlocking { AndroidAlarmScheduler(context, keys).schedule(alarm(version)) } }.exceptionOrNull()
        KillAtRetireShadowAlarmManager.dieAtRetire.set(false)
        AlarmDispatch.reset()
        assertTrue(death is ProcessKilled, "the death must leave the call: $death")
    }

    @Test
    fun `three deaths in the replace window saturate the id with a message that names the death and cancel frees it`() {
        assertTrue(runBlocking { AndroidAlarmScheduler(context, keys).schedule(alarm(1)) } is AlarmScheduleResult.Exact)
        processDiesAtRetire(2)
        processDiesAtRetire(3)
        processDiesAtRetire(4)
        assertEquals(4, slots(), "each death leaves one more slot occupied")
        assertEquals(4, shadowAlarmManager.scheduledAlarms.size, "each death leaves the old alarm armed")

        val healthy: AlarmScheduleResult = runBlocking { AndroidAlarmScheduler(context, keys).schedule(alarm(5)) }
        val failed: AlarmScheduleResult.Failed = assertIs<AlarmScheduleResult.Failed>(healthy)
        val message: String? = assertIs<AlarmFailure.PlatformError>(failed.reason).message
        assertTrue(message != null && "the process died before it could" in message && "platform refused to cancel it" in message, "message: $message")
        assertEquals(4, shadowAlarmManager.scheduledAlarms.size, "a Failed schedule changes nothing")

        runBlocking { AndroidAlarmScheduler(context, keys).cancel("a") }
        assertEquals(0, slots(), "cancel(id) frees every slot")
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "cancel(id) retires every ghost alarm")
        assertEquals(AlarmScheduleResult.Exact, runBlocking { AndroidAlarmScheduler(context, keys).schedule(alarm(6)) })
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
    }

    @Implements(AlarmManager::class)
    class KillAtRetireShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            val internal = Thread.currentThread().stackTrace.any { it.methodName.contains("internalSet") || it.methodName.contains("set") && it.className.contains("ShadowAlarmManager") }
            if (dieAtRetire.get() && !internal) throw ProcessKilled()
            super.cancel(operation)
        }
        companion object { val dieAtRetire: AtomicBoolean = AtomicBoolean(false) }
    }
}
