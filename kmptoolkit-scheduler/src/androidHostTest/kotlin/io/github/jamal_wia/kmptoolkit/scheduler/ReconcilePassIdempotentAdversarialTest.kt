package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 17: the guide's reconcile pass ("on every app start, cancel everything the table says is stale and
 * re-schedule everything still in the future ... idempotent and cheap") run as an app does it: a new
 * scheduler per process start, a table that changes between starts, a start after the platform lost
 * every alarm AND every PendingIntent (force-stop or reboot), and a final clean slate that must leave no
 * PendingIntent of any slot behind.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReconcilePassIdempotentAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadow: ShadowAlarmManager = shadowOf(alarmManager)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String, at: Long): ScheduledAlarm = ScheduledAlarm(id, "T", at, AlarmNotification("t-$id-$at", "b", "c"), mapOf("at" to "$at"))

    private fun liveSlots(id: String): List<Int> {
        val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
        return (0 until AlarmIntents.SLOT_COUNT).filter { slot ->
            PendingIntent.getBroadcast(
                context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            ) != null
        }
    }

    /** The reconcile pass of the guide: cancel what the table dropped, re-schedule what it still wants. */
    private fun reconcile(scheduler: AlarmScheduler, previous: Set<String>, table: Map<String, Long>) {
        runBlocking {
            scheduler.cancelAll(previous - table.keys)
            table.forEach { (id, at) -> assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(id, at)), "reconcile of $id") }
        }
    }

    @Test
    fun `twenty app starts with a changing table leave exactly the table armed and one live slot per id`() {
        var previous: Set<String> = emptySet()
        val universe: List<String> = (0 until 40).map { "reminder-$it" }
        for (start in 0 until 20) {
            // A new process: a new scheduler object, the same AlarmManager.
            val scheduler: AlarmScheduler = createAlarmScheduler(context)
            // The table drifts: a sliding window of 25 ids, every time-of-day edited on every start.
            val table: Map<String, Long> = universe.drop(start % 15).take(25).associateWith { 9_000_000_000_000L + start * 1_000L + it.length }
            reconcile(scheduler, previous, table)
            // The same pass again changes nothing: idempotent.
            reconcile(scheduler, previous, table)
            previous = table.keys
            assertEquals(table.size, shadow.scheduledAlarms.size, "start $start: exactly the table is armed")
            val armedTimes: Set<Long> = shadow.scheduledAlarms.map { it.triggerAtMs }.toSet()
            assertEquals(table.values.toSet(), armedTimes, "start $start: the newest time of every id")
            table.keys.forEach { id -> assertEquals(1, liveSlots(id).size, "start $start: one live slot for $id") }
            (universe - table.keys).forEach { id -> assertTrue(liveSlots(id).isEmpty(), "start $start: a dropped id $id holds no slot") }
        }
        runBlocking { createAlarmScheduler(context).cancelAll(universe) }
        assertEquals(0, shadow.scheduledAlarms.size)
        universe.forEach { assertTrue(liveSlots(it).isEmpty(), "clean slate: $it") }
    }

    @Test
    fun `a start after the platform lost every alarm and every pending intent re-arms the table without a trace of the old process`() {
        val ids: List<String> = (0 until 12).map { "boot-$it" }
        val table: Map<String, Long> = ids.associateWith { 9_000_000_000_000L }
        repeat(5) { boot ->
            val scheduler: AlarmScheduler = createAlarmScheduler(context)
            reconcile(scheduler, emptySet(), table)
            assertEquals(ids.size, shadow.scheduledAlarms.size, "boot $boot")
            // Force-stop or reboot: the OS drops the alarms and invalidates the package's PendingIntents.
            shadow.scheduledAlarms.toList().forEach { fired ->
                val operation: PendingIntent = requireNotNull(fired.operation)
                alarmManager.cancel(operation)
                operation.cancel()
            }
            ids.forEach { assertTrue(liveSlots(it).isEmpty(), "boot $boot: $it") }
            assertEquals(0, shadow.scheduledAlarms.size)
        }
    }
}
