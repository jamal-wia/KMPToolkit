package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * A slot's request code is `id.hashCode() + slot`, which wraps around the `Int` range for an id whose
 * hash sits at the top: the slot-1 code of the id hashing to `Int.MAX_VALUE` is the slot-0 code of the
 * id hashing to `Int.MIN_VALUE`, and its slot-2 code is the slot-0 code of the next one. Distinctness
 * rests on the per-id data URI alone there, and a replacement or cancel must never reach across.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerHashWrapAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, at: Long, title: String): ScheduledAlarm = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = at, notification = AlarmNotification(title, "b", "c"),
    )

    private fun armed(): Map<String, ScheduledAlarm> = shadowAlarmManager.scheduledAlarms
        .map { requireNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)) }
        .associateBy { it.id }

    private fun occupiedSlots(id: String): List<Int> = (0 until AlarmIntents.SLOT_COUNT).filter { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `the three ids really sit at the wrap-around`() {
        assertEquals(Int.MAX_VALUE, TOP.hashCode())
        assertEquals(Int.MIN_VALUE, WRAPPED.hashCode())
        assertEquals(Int.MIN_VALUE + 1, WRAPPED_NEXT.hashCode())
        assertEquals(AlarmIntents.requestCode(TOP, 1), AlarmIntents.requestCode(WRAPPED, 0))
        assertEquals(AlarmIntents.requestCode(TOP, 2), AlarmIntents.requestCode(WRAPPED_NEXT, 0))
        assertEquals(AlarmIntents.requestCode(WRAPPED, 1), AlarmIntents.requestCode(WRAPPED_NEXT, 0))
    }

    @Test
    fun `replacing and cancelling ids across the wrap never reaches a neighbour`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val ids: List<String> = listOf(TOP, WRAPPED, WRAPPED_NEXT)
        ids.forEachIndexed { index: Int, id: String ->
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(id, 100L + index, "first-$id")) })
        }
        assertEquals(ids.toSet(), armed().keys)

        for (round in 1..6) {
            for (id: String in ids.reversed() + ids) {
                assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(id, 1_000L * round, "r$round-$id")) })
                assertEquals(ids.toSet(), armed().keys, "round $round after $id")
                assertEquals("r$round-$id", armed().getValue(id).notification.title)
            }
        }
        val titlesBefore: Map<String, String> = armed().mapValues { it.value.notification.title }

        runBlocking { scheduler.cancel(TOP) }
        assertEquals(setOf(WRAPPED, WRAPPED_NEXT), armed().keys)
        assertEquals(titlesBefore.filterKeys { it != TOP }, armed().mapValues { it.value.notification.title })
        runBlocking { scheduler.cancel(WRAPPED) }
        assertEquals(setOf(WRAPPED_NEXT), armed().keys)
        runBlocking { scheduler.cancel(WRAPPED_NEXT) }
        assertEquals(emptySet(), armed().keys)
        for (id: String in ids) assertEquals(emptyList(), occupiedSlots(id), "a PendingIntent of $id is left behind")
    }

    @Test
    fun `a cancelAll over the wrap leaves no alarm and no pending intent`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val ids: List<String> = listOf(WRAPPED_NEXT, TOP, WRAPPED)
        ids.forEach { id: String -> runBlocking { scheduler.schedule(alarm(id, 5L, id)) } }
        ids.forEach { id: String -> runBlocking { scheduler.schedule(alarm(id, 6L, id)) } }

        runBlocking { scheduler.cancelAll(ids) }

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        for (id: String in ids) assertEquals(emptyList(), occupiedSlots(id), id)
        assertNull(armed()[TOP])
    }

    private companion object {
        /** `hashCode() == Int.MAX_VALUE`. */
        const val TOP: String = "hmqcwsq\u001D"

        /** `hashCode() == Int.MIN_VALUE`. */
        const val WRAPPED: String = "loktory\n"

        /** `hashCode() == Int.MIN_VALUE + 1`. */
        const val WRAPPED_NEXT: String = "hrldmSk\u0004"
    }
}
