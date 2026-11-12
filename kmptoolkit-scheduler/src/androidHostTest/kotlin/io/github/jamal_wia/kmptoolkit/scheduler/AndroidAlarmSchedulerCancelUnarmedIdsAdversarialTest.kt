package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * Round 15 (contract lawyer): "Cancelling an id that is not armed is a no-op, not an error" and
 * "`cancelAll` cancels exactly the ids passed ... a refusal on one id does not stop the ids after it".
 * An id that cannot be armed at all (blank: `ScheduledAlarm` rejects it at construction; or absurdly
 * long; or carrying characters a URI cannot hold) is the sharpest "not armed" id there is, and the
 * call sites that pass whatever a store returns do pass them.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerCancelUnarmedIdsAdversarialTest {

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

    private fun alarm(id: String, fireAt: Long = 1_900_000_000_000L): ScheduledAlarm =
        ScheduledAlarm(id, "T", fireAt, AlarmNotification("t", "b", "c"))

    private val awkward: List<String> = listOf("", " ", "\t", "\n", "\u0000", "a b", "%", "%zz", "?#", "/", "../x", "\uD83D", "x".repeat(200_000))

    @Test
    fun `cancel of an id that was never armed - blank or awkward - returns silently and touches no armed alarm`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { scheduler.schedule(alarm("kept")) }

        for (id in awkward) {
            val failure: Throwable? = runCatching { runBlocking { withTimeout(20_000) { scheduler.cancel(id) } } }.exceptionOrNull()
            assertNull(failure, "cancel('${id.take(12)}') threw $failure")
        }

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "the armed alarm must survive cancels of ids that were never armed")
    }

    @Test
    fun `cancelAll with unarmed ids in the middle still cancels every armed id after them`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking {
            scheduler.schedule(alarm("first"))
            scheduler.schedule(alarm("last"))
            scheduler.schedule(alarm("spare", 1_900_000_000_001L))
        }

        val failure: Throwable? = runCatching {
            runBlocking { withTimeout(20_000) { scheduler.cancelAll(listOf("first") + awkward + listOf("last")) } }
        }.exceptionOrNull()

        assertNull(failure, "cancelAll threw $failure")
        assertEquals(listOf(1_900_000_000_001L), shadowAlarmManager.scheduledAlarms.map { it.triggerAtMs }, "only the spare alarm remains")
    }
}
