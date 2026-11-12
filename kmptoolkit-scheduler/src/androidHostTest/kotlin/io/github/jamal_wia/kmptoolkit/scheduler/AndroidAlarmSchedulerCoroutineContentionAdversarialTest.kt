package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.ConcurrentLinkedQueue
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * High contention on one scheduler from coroutines: many coroutines on a multi-threaded dispatcher schedule, cancel
 * and cancelAll a handful of ids while their jobs are cancelled at random moments (a hard cancel, a short timeout).
 * Invariants once the storm has ended: no job is stuck (a hard time limit), only cancellation escapes, an id holds at
 * most one alarm and at most one slot (one alarm per id), no alarm belongs to an id outside the set, and a final
 * healthy cancelAll leaves neither alarms nor slots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26, 34])
class AndroidAlarmSchedulerCoroutineContentionAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun armedIds(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).id }

    private fun slots(id: String): Int = (0 until AlarmIntents.SLOT_COUNT).count { slot: Int ->
        PendingIntent.getBroadcast(
            context, AlarmIntents.requestCode(id, slot), AlarmIntents.cancelIntent(context, id, keys),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
        ) != null
    }

    @Test
    fun `cancelled scheduling coroutines never leave more than one alarm or slot for an id`() {
        val ids: List<String> = listOf("a", "b", "Aa", "BB", "c")
        repeat(ROUNDS) { round: Int ->
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            runBlocking {
                withTimeout(JOIN_MILLIS) {
                    val scope = CoroutineScope(Dispatchers.Default)
                    val jobs: List<Job> = List(COROUTINES) { index: Int ->
                        scope.launch {
                            val random = Random(round * 1000 + index)
                            val children: MutableList<Job> = mutableListOf()
                            repeat(OPS) { op: Int ->
                                val id: String = ids[random.nextInt(ids.size)]
                                val alarm = ScheduledAlarm(
                                    id = id, type = "T", fireAtEpochMillis = 1_000L + op + index,
                                    notification = AlarmNotification(title = "r$round-c$index-o$op", body = "b", channelId = "c"),
                                )
                                try {
                                    when (random.nextInt(100)) {
                                        in 0..14 -> scheduler.cancel(id)
                                        in 15..16 -> scheduler.cancelAll(ids)
                                        in 17..49 -> {
                                            val job: Job = launch {
                                                try {
                                                    scheduler.schedule(alarm)
                                                } catch (e: CancellationException) {
                                                    throw e
                                                } catch (e: Throwable) {
                                                    escaped += "${e.javaClass.simpleName}: ${e.message}"
                                                }
                                            }
                                            children += job
                                            if (random.nextBoolean()) job.cancel()
                                        }
                                        in 50..64 -> withTimeoutOrNull(random.nextLong(1, 3)) { scheduler.schedule(alarm) }
                                        else -> if (children.isNotEmpty()) children[random.nextInt(children.size)].cancel()
                                    }
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Throwable) {
                                    escaped += "${e.javaClass.simpleName}: ${e.message}"
                                }
                                if (random.nextInt(8) == 0) delay(1)
                            }
                            children.joinAll()
                        }
                    }
                    jobs.joinAll()
                }
            }
            assertEquals(emptyList(), escaped.toList(), "round $round: an exception other than cancellation escaped")
            val armed: List<String> = armedIds()
            assertTrue(armed.all { it in ids }, "round $round: an alarm under a foreign id: $armed")
            ids.forEach { id: String ->
                assertTrue(armed.count { it == id } <= 1, "round $round: '$id' holds ${armed.count { it == id }} alarms")
                assertTrue(slots(id) <= 1, "round $round: '$id' holds ${slots(id)} slots")
            }
            runBlocking { scheduler.cancelAll(ids) }
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "round $round: cancelAll left alarms")
            ids.forEach { assertEquals(0, slots(it), "round $round: cancelAll left slots of $it") }
        }
    }

    private companion object {
        const val ROUNDS: Int = 6
        const val COROUTINES: Int = 24
        const val OPS: Int = 150
        const val JOIN_MILLIS: Long = 60_000L
    }
}
