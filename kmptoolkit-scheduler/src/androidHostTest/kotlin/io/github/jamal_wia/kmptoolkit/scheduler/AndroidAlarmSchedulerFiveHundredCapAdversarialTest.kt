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
 * Round 12 (scale): the documented platform cap of 500 concurrent alarms per app, modelled by count (the
 * platform refuses every set call, inexact ones and replacements included, once 500 alarms are armed) instead
 * of by a mood switch. What the docs promise at and around the cap: the refusal comes back as
 * Failed(PlatformError), never as an exception; nothing is armed for the refused id; a refused replacement
 * leaves the armed alarm exactly as it was; a PendingIntent the refused call created is dropped; one cancel
 * makes room again; and a replacement just below the cap succeeds although it briefly holds two alarms.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerFiveHundredCapAdversarialTest.CappedShadowAlarmManager::class])
class AndroidAlarmSchedulerFiveHundredCapAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String): ScheduledAlarm =
        ScheduledAlarm(id, "T", 5_000L, AlarmNotification(title, "b", "c"))

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun titles(): Map<String, String> = shadowAlarmManager.scheduledAlarms.associate {
        val armed: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys))
        armed.id to armed.notification.title
    }

    @Test
    fun `at the cap a new id and a replacement both come back Failed, change nothing and leak no slot, and a cancel makes room`() = runBlocking {
        val ids: List<String> = (0 until CAP).map { "cap-$it" }
        ids.forEach { assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(it, "v1-$it"))) }
        assertEquals(CAP, shadowAlarmManager.scheduledAlarms.size)

        // A new id is refused as a value.
        val refusedNew: AlarmScheduleResult = scheduler.schedule(alarm("one-too-many", "x"))
        assertIs<AlarmFailure.PlatformError>(assertIs<AlarmScheduleResult.Failed>(refusedNew).reason)
        assertEquals(CAP, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(0, slots("one-too-many"), "the PendingIntent of a refused call must be dropped")

        // A replacement of an armed id is refused too, and the armed alarm keeps its content and its one slot.
        val refusedReplacement: AlarmScheduleResult = scheduler.schedule(alarm("cap-0", "v2-cap-0"))
        assertIs<AlarmScheduleResult.Failed>(refusedReplacement)
        assertEquals("v1-cap-0", titles().getValue("cap-0"), "a refused replacement must leave the armed alarm as it was")
        assertEquals(1, slots("cap-0"))
        repeat(10) { assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm("cap-1", "again-$it"))) }
        assertEquals(1, slots("cap-1"), "repeated refusals of a replacement must not pile up slots or saturate the id")
        assertEquals(ids.associateWith { "v1-$it" }, titles())

        // One cancel makes room for exactly one more.
        scheduler.cancel("cap-7")
        assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("one-too-many", "now")))
        assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm("another", "no room")))
        assertEquals(CAP, shadowAlarmManager.scheduledAlarms.size)

        // Cancelling two frees room for a replacement, which briefly holds two alarms of the id and still succeeds.
        scheduler.cancelAll(listOf("cap-8", "cap-9"))
        assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm("cap-0", "v2-cap-0")))
        assertEquals("v2-cap-0", titles().getValue("cap-0"))
        assertEquals(1, slots("cap-0"), "the replaced slot must be retired")
        assertEquals(CAP - 2, shadowAlarmManager.scheduledAlarms.size, "two cancelled, one replaced: 498 alarms")

        scheduler.cancelAll(ids + "one-too-many")
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        (ids + "one-too-many" + "another").forEach { assertEquals(0, slots(it), "'$it'") }
    }

    @Implements(AlarmManager::class)
    class CappedShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            refuseAtCap()
            super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            refuseAtCap()
            super.setAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        private fun refuseAtCap() {
            if (scheduledAlarms.size >= CAP) throw IllegalStateException("Maximum limit of concurrent alarms $CAP reached.")
        }
    }

    private companion object {
        const val CAP: Int = 500
    }
}
