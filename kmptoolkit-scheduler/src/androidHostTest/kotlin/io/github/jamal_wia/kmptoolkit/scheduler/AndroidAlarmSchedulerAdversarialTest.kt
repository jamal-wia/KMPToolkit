package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicIntegerArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * Process death, many callers and robustness of the Android scheduler and its receiver.
 *
 * `ShadowAlarmManager` stands in for the OS, which keeps its alarms when the process dies; the
 * process-wide handler registration is what a new process would not have, so it is reset to model the
 * death. Every wait is bounded and every thread is joined with a timeout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun clearRegistration() {
        AlarmDispatch.reset()
    }

    private class CountingHandler(override val type: String) : AlarmHandler {
        val fired: AtomicInteger = AtomicInteger()
        val last: AtomicReference<ScheduledAlarm?> = AtomicReference(null)
        val latch: CountDownLatch = CountDownLatch(1)

        override suspend fun onFire(alarm: ScheduledAlarm) {
            last.set(alarm)
            fired.incrementAndGet()
            latch.countDown()
        }
    }

    private fun alarm(
        id: String = "a",
        type: String = "REMINDER",
        fireAt: Long = 10_000L,
        title: String = "t",
        payload: Map<String, String> = emptyMap(),
    ): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = type,
        fireAtEpochMillis = fireAt,
        notification = AlarmNotification(title = title, body = "b", channelId = "chan"),
        payload = payload,
    )

    private fun armedAlarms(): List<ScheduledAlarm> = shadowAlarmManager.scheduledAlarms.map { armed: ShadowAlarmManager.ScheduledAlarm ->
        assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys))
    }

    private fun Thread.joinBounded() {
        join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertTrue(!isAlive, "thread $name is stuck:\n" + threadDump())
    }

    private fun threadDump(): String = Thread.getAllStackTraces().entries
        .filter { (thread: Thread, _) -> thread.name.startsWith("kmptoolkit-") || thread.name == "main" || thread.name.startsWith("DefaultDispatcher") }
        .joinToString("\n") { (thread: Thread, stack: Array<StackTraceElement>) -> "${thread.name}: ${stack.take(8).joinToString(" <- ")}" }

    /** Fires the operation the OS holds for the first armed alarm, as the OS would after a restart. */
    private fun fireArmed(index: Int = 0) {
        val operation = shadowAlarmManager.scheduledAlarms[index].operation
        AlarmReceiver().onReceive(context, shadowOf(operation).savedIntent)
    }

    // --- process death ---------------------------------------------------------------------------

    @Test
    fun `an alarm armed by a dead process is dropped before the new scheduler exists and delivered to the new handler after`() {
        val deadHandler = CountingHandler("REMINDER")
        val dead: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(deadHandler))
        runBlocking { dead.schedule(alarm(payload = mapOf("k" to "v"))) }
        AlarmDispatch.reset()

        fireArmed()
        assertTrue(!deadHandler.latch.await(SETTLE_MILLIS, TimeUnit.MILLISECONDS), "a handler of the dead process was invoked")

        val rebornHandler = CountingHandler("REMINDER")
        createAlarmScheduler(context, handlers = listOf(rebornHandler))
        fireArmed()

        assertTrue(rebornHandler.latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "the new handler never received the alarm the OS kept")
        assertEquals(alarm(payload = mapOf("k" to "v")), rebornHandler.last.get())
        assertEquals(0, deadHandler.fired.get())
        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "the OS keeps the alarm until it is cancelled or replaced")
    }

    @Test
    fun `a new scheduler can cancel and replace the alarm the dead one armed`() {
        val dead: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { dead.schedule(alarm(title = "old")) }
        AlarmDispatch.reset()
        val reborn: AlarmScheduler = createAlarmScheduler(context)

        runBlocking { reborn.schedule(alarm(title = "new", fireAt = 20_000L)) }
        assertEquals(listOf("new"), armedAlarms().map { it.notification.title }, "replacement must not stack")

        runBlocking { reborn.cancel("a") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size)
    }

    // --- many callers --------------------------------------------------------------------------

    @Test
    fun `sixteen threads scheduling and cancelling one id across recreated schedulers end with one alarm carrying the last schedule`() {
        val barrier = CyclicBarrier(THREADS)
        val failures: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())
        val workers: List<Thread> = List(THREADS) { worker: Int ->
            thread(name = "kmptoolkit-adversarial-sched-$worker") {
                runCatching {
                    val random = Random(SEED + worker)
                    barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                    var scheduler: AlarmScheduler = createAlarmScheduler(context)
                    repeat(OPS) { op: Int ->
                        if (random.nextInt(6) == 0) scheduler = createAlarmScheduler(context)
                        if (random.nextInt(4) == 0) {
                            runBlocking { scheduler.cancel("one") }
                        } else {
                            runBlocking { scheduler.schedule(alarm(id = "one", fireAt = 1_000L + worker, title = "w$worker-op$op")) }
                        }
                    }
                }.onFailure { failures += "worker=$worker seed=${SEED + worker}: $it" }
            }
        }
        workers.forEach { it.joinBounded() }
        assertEquals(emptyList(), failures.toList())
        assertTrue(shadowAlarmManager.scheduledAlarms.size <= 1, "one id armed ${shadowAlarmManager.scheduledAlarms.size} alarms")

        runBlocking { createAlarmScheduler(context).schedule(alarm(id = "one", fireAt = 99_999L, title = "last")) }

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size, "one id, one alarm")
        val armed: ShadowAlarmManager.ScheduledAlarm = assertNotNull(shadowAlarmManager.peekNextScheduledAlarm())
        assertEquals(99_999L, armed.triggerAtTime)
        assertEquals("last", AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys)?.notification?.title)

        runBlocking { createAlarmScheduler(context).cancel("one") }
        assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "a final cancel must leave nothing armed")
    }

    @Test
    fun `eight threads scheduling distinct ids while others are cancelled leave exactly the uncancelled ones`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        val barrier = CyclicBarrier(8)
        val workers: List<Thread> = List(8) { worker: Int ->
            thread(name = "kmptoolkit-adversarial-ids-$worker") {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                repeat(10) { index: Int ->
                    val id = "w$worker-$index"
                    runBlocking { scheduler.schedule(alarm(id = id, title = id)) }
                    if (index % 2 == 1) runBlocking { scheduler.cancel(id) }
                }
            }
        }
        workers.forEach { it.joinBounded() }

        val expected: Set<String> = (0 until 8).flatMap { worker: Int -> (0 until 10 step 2).map { "w$worker-$it" } }.toSet()
        assertEquals(expected, armedAlarms().map { it.id }.toSet())
        assertEquals(expected.size, shadowAlarmManager.scheduledAlarms.size)
    }

    @Test
    fun `two hundred alarms fired from eight threads reach their handler exactly once each`() {
        val counts = AtomicIntegerArray(BURST)
        val done = CountDownLatch(BURST)
        AlarmDispatch.install(keys, listOf(object : AlarmHandler {
            override val type: String = "BURST"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                counts.incrementAndGet(alarm.id.removePrefix("n-").toInt())
                done.countDown()
            }
        }))
        val barrier = CyclicBarrier(8)
        val workers: List<Thread> = List(8) { worker: Int ->
            thread(name = "kmptoolkit-adversarial-fire-$worker") {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                repeat(BURST / 8) { index: Int ->
                    val id = "n-${worker * (BURST / 8) + index}"
                    AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(id = id, type = "BURST"), keys))
                }
            }
        }
        workers.forEach { it.joinBounded() }

        assertTrue(done.await(WAIT_SECONDS * 2, TimeUnit.SECONDS), "only ${BURST - done.count} of $BURST alarms were handled\n" + threadDump())
        Thread.sleep(SETTLE_MILLIS)
        val wrong: List<String> = (0 until BURST).filter { counts.get(it) != 1 }.map { "n-$it fired ${counts.get(it)} times" }
        assertEquals(emptyList(), wrong.take(5))
    }

    // --- robustness ----------------------------------------------------------------------------

    @Test
    fun `a handler that throws reaches the process-crash path and does not stop a later alarm`() {
        val crashed = CountDownLatch(1)
        val cause: AtomicReference<Throwable?> = AtomicReference(null)
        val good = CountingHandler("GOOD")
        AlarmDispatch.install(keys, listOf(
            object : AlarmHandler {
                override val type: String = "BAD"
                override suspend fun onFire(alarm: ScheduledAlarm) = throw IllegalStateException("handler failure")
            },
            good,
        ))
        val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error: Throwable -> cause.set(error); crashed.countDown() }
        try {
            AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(type = "BAD"), keys))
            assertTrue(crashed.await(WAIT_SECONDS, TimeUnit.SECONDS), "an exception escaping onFire did not reach the uncaught handler")
            assertEquals("handler failure", cause.get()?.message)

            AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(type = "GOOD"), keys))
            assertTrue(good.latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "a later alarm was not delivered")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `ids that look alike stay distinct alarms and each cancels alone`() {
        val ids: List<String> = listOf(
            "a/b", "a%2Fb", "a%252Fb", "a b", "a+b", "A", "a", "a?b#c", "a:b", "..", ".", "a/../b", "日本語",
            "😀", " lead", "trail ", "x".repeat(300), "a\nb", "a\u0000b",
        )
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        ids.forEach { id: String -> runBlocking { scheduler.schedule(alarm(id = id, title = id)) } }

        assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size, "two of the ids collapsed into one alarm")
        assertEquals(ids.toSet(), armedAlarms().map { it.id }.toSet())
        val remaining: MutableList<String> = ids.toMutableList()
        ids.forEach { id: String ->
            runBlocking { scheduler.cancel(id) }
            remaining -= id
            assertEquals(remaining.toSet(), armedAlarms().map { it.id }.toSet(), "cancelling '$id' removed the wrong alarm")
        }
    }

    @Test
    fun `a large unicode payload survives the intent round trip`() {
        val payload: Map<String, String> = buildMap {
            repeat(300) { index: Int -> put("k$index é😀", "v".repeat(index % 7) + "日") }
            put("", "empty key")
            put("empty value", "")
            put("kmptoolkit_alarm_title", "a payload key shaped like an extra")
        }
        val original: ScheduledAlarm = alarm(title = "😀 title", payload = payload)

        val roundTrip: ScheduledAlarm? = AlarmIntents.fromIntent(AlarmIntents.toIntent(context, original, keys), keys)

        assertEquals(original, roundTrip)
        assertNull(AlarmIntents.fromIntent(android.content.Intent(), keys))
    }

    @Test
    fun `a hundred slow handlers do not stop a later alarm from being delivered`() {
        val slowStarted = AtomicInteger()
        val late = CountingHandler("LATE")
        AlarmDispatch.install(keys, listOf(
            object : AlarmHandler {
                override val type: String = "SLOW"
                override suspend fun onFire(alarm: ScheduledAlarm) {
                    slowStarted.incrementAndGet()
                    Thread.sleep(SLOW_HANDLER_MILLIS)
                }
            },
            late,
        ))
        repeat(100) { index: Int ->
            AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(id = "slow-$index", type = "SLOW"), keys))
        }

        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(id = "late", type = "LATE"), keys))

        assertTrue(late.latch.await(WAIT_SECONDS * 2, TimeUnit.SECONDS), "the later alarm starved behind ${slowStarted.get()} slow handlers\n" + threadDump())
    }

    @Test
    fun `scheduler recreation and registry resets racing a burst of fires lose and duplicate nothing`() {
        val total = AtomicInteger()
        val perId = AtomicIntegerArray(BURST)
        val handlers: List<AlarmHandler> = listOf(object : AlarmHandler {
            override val type: String = "BURST"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                perId.incrementAndGet(alarm.id.removePrefix("n-").toInt())
                total.incrementAndGet()
            }
        })
        AlarmDispatch.install(keys, handlers)
        val barrier = CyclicBarrier(8 + 4)
        val workers: List<Thread> = List(8) { worker: Int ->
            thread(name = "kmptoolkit-adversarial-burst-$worker") {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                repeat(BURST / 8) { index: Int ->
                    val id = "n-${worker * (BURST / 8) + index}"
                    AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(id = id, type = "BURST"), keys))
                }
            }
        }
        val reinstallers: List<Thread> = List(4) { worker: Int ->
            thread(name = "kmptoolkit-adversarial-reinstall-$worker") {
                barrier.await(WAIT_SECONDS, TimeUnit.SECONDS)
                repeat(50) {
                    createAlarmScheduler(context, handlers = handlers)
                    Thread.yield()
                }
            }
        }
        (workers + reinstallers).forEach { it.joinBounded() }

        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS * 2)
        while (total.get() < BURST && System.nanoTime() < deadline) Thread.sleep(20)
        Thread.sleep(SETTLE_MILLIS)
        val wrong: List<String> = (0 until BURST).filter { perId.get(it) != 1 }.map { "n-$it fired ${perId.get(it)} times" }
        assertEquals(emptyList(), wrong.take(5), "total=${total.get()}")
    }

    private companion object {
        const val WAIT_SECONDS: Long = 5L
        const val SETTLE_MILLIS: Long = 300L
        const val THREADS: Int = 16
        const val OPS: Int = 25
        const val SEED: Int = 20_261_107
        const val BURST: Int = 200
        const val SLOW_HANDLER_MILLIS: Long = 150L
    }
}
