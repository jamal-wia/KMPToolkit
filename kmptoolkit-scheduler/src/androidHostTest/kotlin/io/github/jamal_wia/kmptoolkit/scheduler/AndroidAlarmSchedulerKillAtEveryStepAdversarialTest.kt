package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
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
 * Round 19 (process death): the process is killed at every single `AlarmManager` call of a realistic
 * edit session (schedule three alarms, edit one, retire one, add one, cancel one) and a new process
 * (a new scheduler, the dispatch registration gone) runs the guide's reconcile pass over the desired
 * list: 03-guide, "Owning the desired state": "A reconcile pass is usually the simplest correct design:
 * on every app start, cancel everything the table says is stale and re-schedule everything still in
 * the future. Re-scheduling an id that is already armed replaces it, so a reconcile pass is idempotent."
 *
 * A kill before a `set` call also drops the `PendingIntent` the dying call had created and handed
 * over (the platform forgets one that no alarm holds once its process is gone). Whatever call the
 * kill landed on, after one reconcile pass each desired id holds exactly one armed alarm with the desired
 * content in exactly one slot, each retired id holds nothing, a second pass changes nothing, and no
 * schedule of the pass answered `Failed`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30, 34], shadows = [AndroidAlarmSchedulerKillAtEveryStepAdversarialTest.DyingShadowAlarmManager::class])
class AndroidAlarmSchedulerKillAtEveryStepAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    class ProcessKilled(call: Int) : Error("process killed at AlarmManager call $call")

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        DyingShadowAlarmManager.reset(killAt = 0)
    }

    @After
    fun tearDown() {
        DyingShadowAlarmManager.reset(killAt = 0)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, version: Int): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 5_000_000L + version,
        notification = AlarmNotification(title = "t$version", body = "b", channelId = "c"),
        payload = mapOf("v" to "$version"),
    )

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context,
            AlarmIntents.requestCode(id, slot),
            AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun armedFor(id: String): List<ScheduledAlarm> = shadowAlarmManager.scheduledAlarms
        .map { armed: ShadowAlarmManager.ScheduledAlarm ->
            assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys), "unreadable alarm")
        }
        .filter { it.id == id }

    /** The edit session, as one process runs it. Returns normally only when nothing killed it. */
    private fun session(scheduler: AlarmScheduler) = runBlocking {
        scheduler.schedule(alarm("a", 1))
        scheduler.schedule(alarm("b", 1))
        scheduler.schedule(alarm("c", 1))
        scheduler.schedule(alarm("a", 2))
        scheduler.cancel("b")
        scheduler.schedule(alarm("d", 1))
        scheduler.cancelAll(listOf("c", "never-armed"))
    }

    /** The desired state after the session, and the ids that must hold nothing. */
    private val desired: Map<String, ScheduledAlarm> = mapOf("a" to alarm("a", 2), "d" to alarm("d", 1))
    private val retired: List<String> = listOf("b", "c")

    private fun reconcile(scheduler: AlarmScheduler): List<AlarmScheduleResult> = runBlocking {
        retired.forEach { scheduler.cancel(it) }
        desired.values.map { scheduler.schedule(it) }
    }

    private fun assertDesiredState(label: String) {
        for ((id: String, alarm: ScheduledAlarm) in desired) {
            assertEquals(listOf(alarm), armedFor(id), "$label: '$id' must hold exactly the desired alarm")
            assertEquals(1, occupiedSlots(id), "$label: '$id' must hold one slot")
        }
        for (id: String in retired) {
            assertEquals(emptyList(), armedFor(id), "$label: '$id' is retired")
            assertEquals(0, occupiedSlots(id), "$label: '$id' is retired and must hold no slot")
        }
        assertEquals(desired.size, shadowAlarmManager.scheduledAlarms.size, "$label: no alarm of an unknown id")
    }

    @Test
    fun `a kill at any single AlarmManager call of an edit session is repaired by one reconcile pass`() {
        // How many calls a clean session makes: the number of kill positions to try.
        DyingShadowAlarmManager.reset(killAt = 0)
        session(AndroidAlarmScheduler(context, keys))
        val total: Int = DyingShadowAlarmManager.calls.get()
        assertTrue(total >= 10, "the session is expected to make many platform calls, made $total")

        for (kill: Int in 1..total) {
            // A fresh platform: every alarm and every PendingIntent gone (the previous kill position's leftovers).
            shadowAlarmManager.scheduledAlarms.toList().forEach { armed ->
                val operation: PendingIntent = assertNotNull(armed.operation)
                (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(operation)
                operation.cancel()
            }
            AlarmDispatch.reset()
            DyingShadowAlarmManager.reset(killAt = kill)

            val death: Throwable? = runCatching { session(AndroidAlarmScheduler(context, keys)) }.exceptionOrNull()
            assertTrue(death is ProcessKilled, "kill position $kill: the session was expected to die, got $death")

            // The new process.
            DyingShadowAlarmManager.reset(killAt = 0)
            AlarmDispatch.reset()
            val results: List<AlarmScheduleResult> = reconcile(AndroidAlarmScheduler(context, keys))
            assertTrue(results.all { it is AlarmScheduleResult.Exact }, "kill position $kill: the reconcile pass answered $results")
            assertDesiredState("kill position $kill, after one reconcile pass")

            // Idempotent.
            val again: List<AlarmScheduleResult> = reconcile(AndroidAlarmScheduler(context, keys))
            assertTrue(again.all { it is AlarmScheduleResult.Exact }, "kill position $kill: second pass answered $again")
            assertDesiredState("kill position $kill, after a second reconcile pass")

            // And a final teardown leaves nothing behind.
            runBlocking { AndroidAlarmScheduler(context, keys).cancelAll(desired.keys + retired) }
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "kill position $kill: teardown")
            for (id: String in desired.keys + retired) assertEquals(0, occupiedSlots(id), "kill position $kill: slots of '$id' after teardown")
        }
    }

    /**
     * Counts every `set` and `cancel` call and dies (an `Error`) at the configured one; a death before a
     * `set` first drops the `PendingIntent` the call was handed, as the platform does for one no alarm holds.
     */
    @Implements(AlarmManager::class)
    class DyingShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            val call: Int = calls.incrementAndGet()
            if (call == killAt.get()) {
                operation?.cancel()
                throw ProcessKilled(call)
            }
            super.setExactAndAllowWhileIdle(type, triggerAtTime, operation)
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            val call: Int = calls.incrementAndGet()
            if (call == killAt.get()) throw ProcessKilled(call)
            super.cancel(operation)
        }

        companion object {
            val calls: AtomicInteger = AtomicInteger(0)
            val killAt: AtomicInteger = AtomicInteger(0)

            fun reset(killAt: Int) {
                calls.set(0)
                this.killAt.set(killAt)
            }
        }
    }
}
