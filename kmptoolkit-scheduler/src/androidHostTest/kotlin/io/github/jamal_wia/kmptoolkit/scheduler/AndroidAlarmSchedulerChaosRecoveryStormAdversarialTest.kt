package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
 * Real threads, a platform whose set and cancel calls are refused at random moments by a chaos thread, process
 * deaths (new schedulers, lost dispatch registration), look-alike ids and every id saturated at some point:
 * nothing may escape as an exception, no armed alarm may carry another id's content or URI, and once the platform
 * is healthy again `cancelAll` must always bring every id back to zero alarms and a fresh schedule must arm
 * exactly one.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerChaosRecoveryStormAdversarialTest.ChaosShadowAlarmManager::class])
class AndroidAlarmSchedulerChaosRecoveryStormAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() { ShadowAlarmManager.setCanScheduleExactAlarms(true); ChaosShadowAlarmManager.mode.set(Mode.HEALTHY) }

    @After
    fun tearDown() { ChaosShadowAlarmManager.mode.set(Mode.HEALTHY); AlarmDispatch.reset() }

    private fun alarm(id: String, n: Int) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L + n,
        notification = AlarmNotification(title = "$n|${id.map { it.code }}", body = "b", channelId = "c"),
    )

    private val saturatedSeen = java.util.concurrent.atomic.AtomicInteger()

    @Test
    fun `chaos leaves every id recoverable and no alarm under another id`() {
        for (round in 0 until ROUNDS) {
            val escaped: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue()
            val barrier = CyclicBarrier(WORKERS + 1)
            val stop = java.util.concurrent.atomic.AtomicBoolean(false)
            val chaos = thread(name = "kmptoolkit-adversarial-alarm-chaos-$round") {
                val random = Random(round)
                barrier.await(10, TimeUnit.SECONDS)
                while (!stop.get()) {
                    ChaosShadowAlarmManager.mode.set(Mode.values()[random.nextInt(Mode.values().size)])
                    ShadowAlarmManager.setCanScheduleExactAlarms(random.nextInt(3) != 0)
                    Thread.sleep(0, 200_000)
                }
            }
            val workers = List(WORKERS) { index ->
                thread(name = "kmptoolkit-adversarial-alarm-worker-$round-$index") {
                    val random = Random(round * 31 + index)
                    var scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
                    barrier.await(10, TimeUnit.SECONDS)
                    repeat(OPS) { op ->
                        val id = IDS[random.nextInt(IDS.size)]
                        try {
                            when (random.nextInt(10)) {
                                in 0..5 -> {
                                    val result = runBlocking { scheduler.schedule(alarm(id, op)) }
                                    if (result is AlarmScheduleResult.Failed && (result.reason as? AlarmFailure.PlatformError)?.message?.startsWith("Every PendingIntent slot") == true) saturatedSeen.incrementAndGet()
                                }
                                in 6..7 -> runBlocking { scheduler.cancel(id) }
                                8 -> runBlocking { scheduler.cancelAll(IDS.shuffled(random).take(3)) }
                                else -> { AlarmDispatch.reset(); scheduler = AndroidAlarmScheduler(context, keys) }
                            }
                        } catch (e: Throwable) {
                            escaped += "${e.javaClass.simpleName}: ${e.message}"
                        }
                    }
                }
            }
            workers.forEach { it.join(TimeUnit.SECONDS.toMillis(60)) }
            stop.set(true)
            chaos.join(10_000)
            ChaosShadowAlarmManager.mode.set(Mode.HEALTHY)
            ShadowAlarmManager.setCanScheduleExactAlarms(true)
            assertTrue(workers.none { it.isAlive } && !chaos.isAlive, "round $round: a thread is stuck")
            assertEquals(emptyList(), escaped.toList(), "round $round: a platform refusal escaped")

            for (armed in shadowAlarmManager.scheduledAlarms) {
                val intent = shadowOf(armed.operation).savedIntent
                val a = assertNotNull(AlarmIntents.fromIntent(intent, keys))
                assertEquals(a.id.map { it.code }.toString(), a.notification.title.substringAfter('|'), "round $round: title of another id")
                assertEquals(AlarmIntents.cancelIntent(context, a.id, keys).data, intent.data, "round $round: URI of another id")
            }
            val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
            runBlocking {
                scheduler.cancelAll(IDS)
                assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "round $round: cancelAll could not recover every id")
                for (id in IDS) {
                    assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, 9_000)), "round $round: ${id.map { it.code }} cannot schedule after recovery")
                }
                assertEquals(IDS.size, shadowAlarmManager.scheduledAlarms.size)
                scheduler.cancelAll(IDS)
            }
            assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
        }
        assertTrue(saturatedSeen.get() > 0, "the storm never saturated an id, so it did not exercise the saturated rule")
    }

    enum class Mode { HEALTHY, REFUSE_SET, REFUSE_CANCEL, REFUSE_BOTH, REVOKED }

    @Implements(AlarmManager::class)
    class ChaosShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            val m = mode.get()
            if (m == Mode.REFUSE_SET || m == Mode.REFUSE_BOTH) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            if (m == Mode.REVOKED) throw SecurityException("exact alarms revoked")
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun setAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            val m = mode.get()
            if (m == Mode.REFUSE_SET || m == Mode.REFUSE_BOTH) throw IllegalStateException("Maximum limit of concurrent alarms 500 reached.")
            insideSet.set(true)
            try { super.setAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            val m = mode.get()
            if ((m == Mode.REFUSE_CANCEL || m == Mode.REFUSE_BOTH) && !insideSet.get()) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val mode: AtomicReference<Mode> = AtomicReference(Mode.HEALTHY)
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }

    private companion object {
        const val ROUNDS: Int = 30
        const val WORKERS: Int = 6
        const val OPS: Int = 150
        val IDS: List<String> = listOf("a", "\uD800", "?", "\uD801\uD800", "\uD800\uD81F", "%uD800")
    }
}
