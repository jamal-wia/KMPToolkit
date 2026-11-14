package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
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
 * Round 18 (lifecycle): "Last writer wins. Creating a second scheduler with different handlers replaces the first
 * registration rather than merging" (the registration is process wide). Schedulers are re-created from three threads, with
 * alternating handler sets and alternating provider and list overloads, while alarms of two types are delivered to the
 * receiver: whatever registration the receiver reads, a handler must only ever see an alarm of its own type, nothing may
 * throw on any thread, and once the writers stop the last registration is the only one that answers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverRegistrationRaceAdversarialTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val uncaught: CopyOnWriteArrayList<Throwable> = CopyOnWriteArrayList()
    private var previousHandler: Thread.UncaughtExceptionHandler? = null

    private class Tagged(override val type: String, val seen: AtomicInteger, val wrong: CopyOnWriteArrayList<String>) : AlarmHandler {
        override suspend fun onFire(alarm: ScheduledAlarm) {
            if (alarm.type != type) wrong += "handler $type got ${alarm.type}"
            seen.incrementAndGet()
        }
    }

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, failure -> uncaught += failure }
    }

    @After
    fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
        AlarmDispatch.reset()
    }

    private fun savedIntent(scheduler: AlarmScheduler, id: String, type: String): Intent {
        runBlocking { scheduler.schedule(ScheduledAlarm(id, type, 1_900_000_000_000L, AlarmNotification("t", "b", "c"))) }
        return shadowOf(requireNotNull(shadowOf(alarmManager).scheduledAlarms.map { it.operation }.last { op ->
            @Suppress("DEPRECATION") shadowOf(op).savedIntent.getStringExtra(AlarmSchedulerConfig.DEFAULT_ALARM_ID_KEY) == id
        })).savedIntent
    }

    @Test
    fun `handlers only ever see their own type while the registration is replaced from other threads and the last writer wins`() {
        val seenA = AtomicInteger()
        val seenB = AtomicInteger()
        val wrong: CopyOnWriteArrayList<String> = CopyOnWriteArrayList()
        val handlerA = Tagged("A", seenA, wrong)
        val handlerB = Tagged("B", seenB, wrong)
        val scheduler: AlarmScheduler = createAlarmScheduler(app, listOf(handlerA, handlerB))
        val intentA: Intent = savedIntent(scheduler, "ida", "A")
        val intentB: Intent = savedIntent(scheduler, "idb", "B")

        val stop = CountDownLatch(1)
        val writers = List(3) { writer: Int ->
            thread(name = "registration-writer-$writer") {
                var i = 0
                while (stop.count > 0L) {
                    when ((i++ + writer) % 4) {
                        0 -> createAlarmScheduler(app, listOf(handlerA))
                        1 -> createAlarmScheduler(app, listOf(handlerB))
                        2 -> createAlarmScheduler(app, handlerProvider = { listOf(handlerA, handlerB) })
                        else -> createAlarmScheduler(app, emptyList())
                    }
                }
            }
        }
        repeat(1_500) { i: Int ->
            @Suppress("DEPRECATION")
            AlarmReceiver().onReceive(app, if (i % 2 == 0) intentA else intentB)
        }
        stop.countDown()
        writers.forEach { it.join(10_000) }

        // Everything delivered has been dispatched or dropped: wait for the handlers that did run.
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(20)
        var last: Int = -1
        while (System.nanoTime() < deadline) {
            Thread.sleep(150)
            val now: Int = seenA.get() + seenB.get()
            if (now == last) break
            last = now
        }
        assertTrue(wrong.isEmpty(), "a handler was given an alarm of another type: ${wrong.take(5)}")
        assertTrue(uncaught.isEmpty(), "an exception reached a thread's uncaught handler: ${uncaught.take(3)}")
        assertTrue(seenA.get() + seenB.get() <= 1_500, "more handler calls than delivered alarms")

        // The last writer wins: only handlerA is registered now.
        createAlarmScheduler(app, listOf(handlerA))
        val beforeA: Int = seenA.get()
        val beforeB: Int = seenB.get()
        @Suppress("DEPRECATION")
        AlarmReceiver().onReceive(app, intentB)
        @Suppress("DEPRECATION")
        AlarmReceiver().onReceive(app, intentA)
        val settle: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (seenA.get() == beforeA && System.nanoTime() < settle) Thread.sleep(10)
        Thread.sleep(100)
        assertEquals(beforeA + 1, seenA.get(), "the registered handler answers its own type")
        assertEquals(beforeB, seenB.get(), "a replaced registration still answers")
    }
}
