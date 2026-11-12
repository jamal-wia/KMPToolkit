package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 15 (contract lawyer, resource starvation): the platform scheduling service cannot be reached
 * because the *lookup itself* throws. Only the recovery half is committed here: that a scheduler is
 * usable again once the lookup works. The "never throws" half is the SUSPECT r15-S1. (a system server that is restarting), not because it answers
 * null (that case is round 8).
 *
 * Contract sentences under test (04-api-reference, `AlarmScheduler` table):
 * - `schedule`: "Never throws for a permission or platform refusal; those come back in the result."
 * - `cancel`: "Never throws for a platform refusal: on Android any `RuntimeException` ... (a refusal, a
 *   revoked right, a restarting system server) is caught and logged".
 * - `cancelAll`: "Like `cancel`, it never throws for a platform refusal, and a refusal on one id does not
 *   stop the ids after it."
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerServiceLookupRecoveryAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private val dead: AtomicBoolean = AtomicBoolean(false)

    private inner class FlakyServiceContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? {
            if (name == Context.ALARM_SERVICE && dead.get()) throw IllegalStateException("system server is restarting")
            return super.getSystemService(name)
        }

        override fun getApplicationContext(): Context = this
    }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        dead.set(false)
    }

    private fun alarm(id: String, fireAt: Long = 1_900_000_000_000L): ScheduledAlarm =
        ScheduledAlarm(id, "T", fireAt, AlarmNotification("title", "body", "channel"), mapOf("k" to "v"))

    private fun scheduler(): AlarmScheduler = AndroidAlarmScheduler(FlakyServiceContext(context), keys)

    @Test
    fun `the scheduler recovers once the service is reachable again - the refused cancel is retried by the next cancel and a schedule arms`() {
        val scheduler: AlarmScheduler = scheduler()
        runBlocking { scheduler.schedule(alarm("a")) }
        dead.set(true)
        runCatching { runBlocking { scheduler.cancel("a") } }
        runCatching { runBlocking { scheduler.schedule(alarm("b")) } }
        dead.set(false)

        runBlocking { scheduler.cancel("a") }
        val result: AlarmScheduleResult = runBlocking { scheduler.schedule(alarm("b")) }

        assertEquals(AlarmScheduleResult.Exact, result)
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "only b is armed; a was cancelled after recovery")
    }
}
