package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Edges of the 3.0.x legacy lookup (D-65): unpaired surrogates at every position and next to pairs, equal hash codes,
 * adjacent hash codes, a custom intent scheme, alarms of both URI forms present at once, and a storm of schedule /
 * cancel calls over two scheduler instances with a legacy alarm armed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 34])
class AndroidAlarmSchedulerLegacyEdgesAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String, title: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    private fun plant(id: String, title: String, k: AlarmIntentKeys = keys) {
        val intent = AlarmIntents.toIntent(context, alarm(id, title), k)
        intent.data = Uri.Builder().scheme(k.intentScheme).appendPath(id).build()
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 1_000L, pending)
    }

    private fun titles(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }.sorted()

    private val awkwardIds: List<String> = listOf(
        "\uD800", "\uDC00", "\uDC00\uD800", "a\uDC00b", "\uD800𐀀", "\uD83D😀", "\uDE00😀",
        "😀\uD83D", "\uD83D😀\uDE00", "x".repeat(1500) + "\uD800" + "y".repeat(1500), "\uD800 \uD800", "a/b\uD800/c", "%uD800\uD800",
    )

    @Test
    fun `an alarm of every awkward unpaired-surrogate id armed by 3 0 x is replaced and cancelled`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        for (id in awkwardIds) {
            plant(id, "old")
            assertIs<AlarmScheduleResult.Exact>(runBlocking { scheduler.schedule(alarm(id, "new")) }, id)
            assertEquals(listOf("new"), titles(), "replace: ${id.take(20)}")
            plant(id, "old2")
            runBlocking { scheduler.cancel(id) }
            assertEquals(emptyList(), titles(), "cancel: ${id.take(20)}")
        }
    }

    @Test
    fun `an alarm armed by 3 0 x and one armed by 3 1 0 for the same id are both retired by a schedule`() {
        val id = "task\uD800"
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking { scheduler.schedule(alarm(id, "current")) }
        plant(id, "legacy")
        assertEquals(listOf("current", "legacy"), titles())
        assertIs<AlarmScheduleResult.Exact>(runBlocking { scheduler.schedule(alarm(id, "new")) })
        assertEquals(listOf("new"), titles())
        plant(id, "legacy2")
        runBlocking { scheduler.cancel(id) }
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `ids with equal hash codes and equal lossy URIs stay two alarms in 3 1 0 and neither retires the other`() {
        val a = "\uD800\uD820"
        val b = "\uD801\uD801"
        assertEquals(a.hashCode(), b.hashCode())
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            scheduler.schedule(alarm(a, "a1"))
            scheduler.schedule(alarm(b, "b1"))
            assertEquals(listOf("a1", "b1"), titles())
            scheduler.schedule(alarm(a, "a2"))
            assertEquals(listOf("a2", "b1"), titles())
            scheduler.cancel(a)
            assertEquals(listOf("b1"), titles())
            scheduler.schedule(alarm(a, "a3"))
            scheduler.cancel(b)
            assertEquals(listOf("a3"), titles())
        }
    }

    @Test
    fun `3 0 x alarms of ids with adjacent hash codes and one lossy URI are separate alarms the lookup keeps apart`() {
        val a = "\uD800"
        val b = "\uD801"
        plant(a, "oldA")
        plant(b, "oldB")
        assertEquals(listOf("oldA", "oldB"), titles(), "fixture: 3.0.x had two alarms (request codes differ)")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            scheduler.schedule(alarm(a, "newA"))
            assertEquals(listOf("newA", "oldB"), titles())
            scheduler.cancel(b)
            assertEquals(listOf("newA"), titles())
        }
    }

    @Test
    fun `a 3 1 0 alarm of the literal question-mark id survives schedule and cancel of the surrogate id in both orders`() {
        val lone = "task\uD800"
        val literal = "task?"
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            scheduler.schedule(alarm(literal, "lit"))
            scheduler.schedule(alarm(lone, "lone"))
            assertEquals(listOf("lit", "lone"), titles())
            scheduler.cancel(lone)
            assertEquals(listOf("lit"), titles())
            scheduler.schedule(alarm(lone, "lone2"))
            scheduler.cancel(literal)
            assertEquals(listOf("lone2"), titles())
        }
    }

    @Test
    fun `a custom intent scheme is used for the legacy lookup too`() {
        val custom: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(alarmIntentScheme = "myapp-alarm"), context.packageName)
        val id = "task\uD800"
        plant(id, "old", custom)
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, custom)
        runBlocking {
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "new")))
            assertEquals(listOf("new"), titles())
            plant(id, "old2", custom)
            scheduler.cancel(id)
            assertEquals(emptyList(), titles())
        }
    }

    @Test
    fun `a storm of schedule and cancel over two instances with a legacy alarm armed ends with exactly the last schedule`() {
        val id = "task\uD800"
        for (round in 0 until 15) {
            plant(id, "old")
            val schedulers = listOf<AlarmScheduler>(AndroidAlarmScheduler(context, keys), AndroidAlarmScheduler(context, keys))
            val barrier = CyclicBarrier(4)
            val failure = AtomicReference<Throwable?>(null)
            val threads = (0 until 4).map { t ->
                Thread {
                    try {
                        barrier.await(20, TimeUnit.SECONDS)
                        repeat(12) { n ->
                            val s = schedulers[(t + n) % 2]
                            if ((t + n) % 3 == 0) runBlocking { s.cancel(id) }
                            else assertIs<AlarmScheduleResult.Exact>(runBlocking { s.schedule(alarm(id, "t$t-$n")) })
                        }
                    } catch (e: Throwable) { failure.compareAndSet(null, e) }
                }.also { it.start() }
            }
            threads.forEach { it.join(60_000) }
            assertFalse(threads.any { it.isAlive }, "a storm thread hung")
            failure.get()?.let { throw it }
            assertEquals(true, titles().size <= 1, "round $round: more than one alarm armed for one id: ${titles()}")
            assertIs<AlarmScheduleResult.Exact>(runBlocking { schedulers[0].schedule(alarm(id, "final")) })
            assertEquals(listOf("final"), titles(), "round $round")
            runBlocking { schedulers[1].cancel(id) }
            assertEquals(emptyList(), titles(), "round $round")
        }
    }
}
