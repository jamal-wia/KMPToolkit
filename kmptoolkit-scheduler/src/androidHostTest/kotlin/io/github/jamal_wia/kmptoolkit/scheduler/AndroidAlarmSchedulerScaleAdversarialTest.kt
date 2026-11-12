package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
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
 * Round 12 (scale): thousands of ids live in the real Android scheduler at once. What must hold at
 * scale and not only for the four ids earlier rounds used: every id keeps its own alarm and its own
 * slot, a replacement of all of them leaves exactly one alarm per id, a cancelAll of a subset leaves
 * the rest untouched, a full cancelAll leaves no alarm and no PendingIntent slot behind, ids churned
 * through (scheduled once, cancelled once, never seen again) leave nothing, and a huge id or a huge
 * payload is armed and delivered intact.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerScaleAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String, at: Long = 5_000L, payload: Map<String, String> = emptyMap()): ScheduledAlarm =
        ScheduledAlarm(
            id = id,
            type = "T",
            fireAtEpochMillis = at,
            notification = AlarmNotification(title = title, body = "b", channelId = "c"),
            payload = payload,
        )

    private fun occupiedSlots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    private fun armedByTitle(): Map<String, String> = shadowAlarmManager.scheduledAlarms.associate {
        val armed: ScheduledAlarm = assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys))
        armed.id to armed.notification.title
    }

    @Test
    fun `three thousand ids armed together are replaced and cancelled one by one without touching a neighbour`() = runBlocking {
        withTimeout(WALL_CLOCK_BOUND_MS) {
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val ids: List<String> = (0 until COUNT).map { "reminder-$it" }
            ids.forEach { id: String ->
                assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(id, "first-$id")))
            }
            assertEquals(COUNT, shadowAlarmManager.scheduledAlarms.size)
            assertEquals(ids.associateWith { "first-$it" }, armedByTitle())

            // The reconcile pass of the guide: re-scheduling every id replaces, never adds.
            ids.forEach { id: String -> scheduler.schedule(alarm(id, "second-$id", at = 9_000L)) }
            assertEquals(COUNT, shadowAlarmManager.scheduledAlarms.size, "a replacement of an armed id added an alarm")
            assertEquals(ids.associateWith { "second-$it" }, armedByTitle())
            assertTrue(shadowAlarmManager.scheduledAlarms.all { it.triggerAtMs == 9_000L })
            ids.forEachIndexed { index: Int, id: String ->
                if (index % 97 == 0) assertEquals(1, occupiedSlots(id), "'$id' holds more than one slot after a healthy replacement")
            }

            // A cancelAll of every third id, passed with duplicates and one id that was never armed.
            val victims: List<String> = ids.filterIndexed { index: Int, _ -> index % 3 == 0 }
            scheduler.cancelAll(victims + victims.take(50) + "never-armed")
            assertEquals(ids.size - victims.size, shadowAlarmManager.scheduledAlarms.size)
            assertEquals(ids.filter { it !in victims.toSet() }.associateWith { "second-$it" }, armedByTitle())
            victims.forEach { assertEquals(0, occupiedSlots(it), "a cancelled id kept a slot") }

            scheduler.cancelAll(ids)
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
            ids.forEach { assertEquals(0, occupiedSlots(it), "'$it' kept a slot after cancelAll of everything") }
        }
    }

    @Test
    fun `thirty thousand ids churned through one after another leave neither an alarm nor a slot`() = runBlocking {
        withTimeout(WALL_CLOCK_BOUND_MS) {
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            repeat(CHURN) { n: Int ->
                val id = "churn-$n"
                scheduler.schedule(alarm(id, "t$n"))
                if (n % 2 == 0) scheduler.cancel(id)
            }
            // The odd ones were never cancelled: exactly they are armed.
            assertEquals(CHURN / 2, shadowAlarmManager.scheduledAlarms.size)
            scheduler.cancelAll((0 until CHURN).map { "churn-$it" })
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
            (0 until CHURN step 311).forEach { assertEquals(0, occupiedSlots("churn-$it")) }
        }
    }

    @Test
    fun `an id of a hundred thousand characters and a payload of twenty thousand entries are armed and delivered intact`() = runBlocking {
        withTimeout(WALL_CLOCK_BOUND_MS) {
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val hugeId: String = "x".repeat(100_000) + "-end"
            val payload: Map<String, String> = (0 until 20_000).associate { "k$it" to "v".repeat(it % 7) + it }
            assertEquals(AlarmScheduleResult.Exact, scheduler.schedule(alarm(hugeId, "t".repeat(500_000), payload = payload)))

            val armed: ScheduledAlarm = assertNotNull(
                AlarmIntents.fromIntent(shadowOf(shadowAlarmManager.scheduledAlarms.single().operation).savedIntent, keys),
            )
            assertEquals(hugeId, armed.id)
            assertEquals(500_000, armed.notification.title.length)
            assertEquals(payload, armed.payload)

            // A replacement, then a cancel, with the same huge id: one alarm, then none.
            scheduler.schedule(alarm(hugeId, "second", payload = emptyMap()))
            assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
            scheduler.cancel(hugeId)
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
            assertEquals(0, occupiedSlots(hugeId))
        }
    }

    @Test
    fun `fire times at the extremes of Long are armed as given and never throw`() = runBlocking {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val times: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE, Long.MAX_VALUE - 1)
        times.forEachIndexed { index: Int, at: Long ->
            val result: AlarmScheduleResult = scheduler.schedule(alarm("t-$index", "x", at = at))
            assertEquals(AlarmScheduleResult.Exact, result, "fire time $at")
        }
        assertEquals(times.toSet(), shadowAlarmManager.scheduledAlarms.map { it.triggerAtMs }.toSet())
        scheduler.cancelAll(times.indices.map { "t-$it" })
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    private companion object {
        const val COUNT: Int = 3_000
        const val CHURN: Int = 30_000
        const val WALL_CLOCK_BOUND_MS: Long = 150_000L
    }
}
