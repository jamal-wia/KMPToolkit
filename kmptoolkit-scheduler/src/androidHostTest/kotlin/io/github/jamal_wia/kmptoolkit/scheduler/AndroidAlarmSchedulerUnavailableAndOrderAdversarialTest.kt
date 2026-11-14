package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.ContextWrapper
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
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
 * Two literal readings of the contract that no earlier class exercised:
 *
 * - `AlarmFailure.SchedulerUnavailable` ("`ALARM_SERVICE` returned nothing", "Nothing was armed"):
 *   the platform service is missing, `schedule` says so, `cancel` and `cancelAll` stay silent, and
 *   none of them leaves a `PendingIntent` behind or touches an alarm another instance armed.
 * - `cancelAll` "cancels exactly the ids passed, in order": the platform hears the cancellations in
 *   the order of the ids, an id passed twice is cancelled once (the second is the documented no-op),
 *   and ids that are not armed produce no platform call at all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerUnavailableAndOrderAdversarialTest.OrderRecordingShadowAlarmManager::class])
class AndroidAlarmSchedulerUnavailableAndOrderAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        OrderRecordingShadowAlarmManager.cancelled.clear()
    }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String): ScheduledAlarm =
        ScheduledAlarm(id, "T", 1_000L, AlarmNotification("t", "b", "c"))

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private class NoAlarmServiceContext(base: Context) : ContextWrapper(base) {
        override fun getSystemService(name: String): Any? = if (name == Context.ALARM_SERVICE) null else super.getSystemService(name)
        override fun getApplicationContext(): Context = this
    }

    @Test
    fun `without an alarm service schedule reports SchedulerUnavailable and nothing is armed, cancel is silent, nothing is left behind`() {
        val unavailable: AlarmScheduler = AndroidAlarmScheduler(NoAlarmServiceContext(context), keys)
        val real: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking { real.schedule(alarm("armed-before")) }

        assertEquals(AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable), runBlocking { unavailable.schedule(alarm("fresh")) })
        assertEquals(AlarmScheduleResult.Failed(AlarmFailure.SchedulerUnavailable), runBlocking { unavailable.schedule(alarm("armed-before")) })
        runBlocking { unavailable.cancel("armed-before") }
        runBlocking { unavailable.cancelAll(listOf("armed-before", "fresh", "never")) }

        // "Nothing was armed": no alarm for the fresh id, no PendingIntent made for it, and the alarm
        // another instance armed is exactly as it was (a failed replacement does not touch it).
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, occupiedSlots("fresh"))
        assertEquals(1, occupiedSlots("armed-before"))
        assertEquals("armed-before", AlarmIntents.fromIntent(shadowOf(shadowAlarmManager.scheduledAlarms.single().operation).savedIntent, keys)!!.id)

        // The service coming back needs no repair: the ids work at once.
        assertEquals(AlarmScheduleResult.Exact, runBlocking { real.schedule(alarm("fresh")) })
        runBlocking { real.cancelAll(listOf("fresh", "armed-before")) }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, occupiedSlots("fresh"))
        assertEquals(0, occupiedSlots("armed-before"))
    }

    @Test
    fun `cancelAll reaches the platform in the order of the ids, once per armed id, and not at all for ids that are not armed`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        listOf("a", "b", "c", "d").forEach { id: String -> runBlocking { scheduler.schedule(alarm(id)) } }
        OrderRecordingShadowAlarmManager.cancelled.clear()

        runBlocking { scheduler.cancelAll(listOf("d", "never-armed", "b", "d", "a", "also-not-armed", "b")) }

        // Three armed ids cancelled, each once: the repeats and the unarmed ids are no platform calls.
        assertEquals(listOf("d", "b", "a"), OrderRecordingShadowAlarmManager.cancelled.toList())
        assertEquals(listOf("c"), shadowAlarmManager.scheduledAlarms.map { AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)!!.id })
        listOf("a", "b", "d").forEach { id: String -> assertEquals(0, occupiedSlots(id), id) }
        assertEquals(1, occupiedSlots("c"))
    }

    @Test
    fun `cancelAll over an empty collection and over unarmed ids makes no platform call`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking { scheduler.cancelAll(emptyList()) }
        runBlocking { scheduler.cancelAll(setOf("x", "y")) }
        assertNull(OrderRecordingShadowAlarmManager.cancelled.firstOrNull())
    }

    @Implements(AlarmManager::class)
    class OrderRecordingShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (operation != null) cancelled += shadowOf(operation).savedIntent.getStringExtra("alarm_id").orEmpty()
            super.cancel(operation)
        }

        companion object {
            val cancelled: MutableList<String> = CopyOnWriteArrayList()
        }
    }
}
