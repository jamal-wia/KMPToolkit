package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.Activity
import android.app.AlarmManager
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/**
 * Round 18 (leaks): the retention claims of the Android scheduler, checked with weak references after
 * thousands of cycles. 04-api-reference: "Only `context.applicationContext` is retained, so passing an
 * `Activity` leaks nothing"; "Creating a second scheduler with different handlers replaces the first
 * registration" (the first handlers are not kept); a scheduler is collectable once the app drops it; and
 * the receiver leaves no thread or coroutine behind per delivered alarm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerLeaksAdversarialTest {

    private val app: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = app.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /** Stands for an Activity: not the application context, and nothing but this test may keep it. */
    private class UiContext(base: Context) : ContextWrapper(base)

    private class Counting(override val type: String, val fired: AtomicInteger = AtomicInteger()) : AlarmHandler {
        override suspend fun onFire(alarm: ScheduledAlarm) {
            fired.incrementAndGet()
        }
    }

    @Before
    fun grant() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private fun gone(reference: WeakReference<*>): Boolean {
        repeat(60) {
            System.gc()
            @Suppress("DEPRECATION")
            System.runFinalization()
            if (reference.get() == null) return true
            val ballast: Array<ByteArray> = Array(64) { ByteArray(256 * 1024) }
            assertNotNull(ballast)
            Thread.sleep(25)
        }
        return reference.get() == null
    }

    private fun alarm(i: Int, id: String = "id${i % 30}"): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "T",
        fireAtEpochMillis = 1_900_000_000_000L + i,
        notification = AlarmNotification("t$i", "b$i", "c"),
        payload = mapOf("n" to "$i"),
    )

    @Test
    fun `an activity style context is retained by neither factory overload nor by thousands of schedules and cancels`() {
        fun exercise(withProvider: Boolean): Pair<WeakReference<UiContext>, AlarmScheduler> {
            val ui = UiContext(app)
            val scheduler: AlarmScheduler =
                if (withProvider) createAlarmScheduler(ui, handlerProvider = { emptyList() }) else createAlarmScheduler(ui)
            runBlocking {
                repeat(2_000) { i: Int ->
                    scheduler.schedule(alarm(i))
                    if (i % 4 == 0) scheduler.cancel("id${i % 30}")
                    if (i % 501 == 0) scheduler.cancelAll(List(30) { "id$it" })
                }
            }
            return WeakReference(ui) to scheduler
        }
        for (withProvider: Boolean in listOf(false, true)) {
            val (reference, scheduler) = exercise(withProvider)
            assertTrue(gone(reference), "the activity style context is still reachable (provider overload: $withProvider)")
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(1, "after")) })
        }
    }

    @Test
    fun `a destroyed real activity given to the factory is not retained`() {
        fun open(): WeakReference<Activity> {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val reference = WeakReference(controller.get())
            controller.pause().stop().destroy()
            return reference
        }
        val controlCollectable: Boolean = gone(open())

        fun exercise(): Pair<WeakReference<Activity>, AlarmScheduler> {
            val controller = Robolectric.buildActivity(Activity::class.java).setup()
            val scheduler: AlarmScheduler = createAlarmScheduler(controller.get())
            runBlocking { repeat(300) { i: Int -> scheduler.schedule(alarm(i)) } }
            val reference = WeakReference(controller.get())
            controller.pause().stop().destroy()
            return reference to scheduler
        }
        val (reference, scheduler) = exercise()
        if (controlCollectable) assertTrue(gone(reference), "an activity given to createAlarmScheduler stays reachable")
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm(1, "after")) })
    }

    @Test
    fun `creating another scheduler releases the first registration and the handlers it held`() {
        fun first(): WeakReference<Counting> {
            val handler = Counting("T")
            createAlarmScheduler(app, listOf(handler))
            return WeakReference(handler)
        }
        fun firstProvided(): WeakReference<Counting> {
            val handler = Counting("T")
            val captured: List<AlarmHandler> = listOf(handler)
            createAlarmScheduler(app, handlerProvider = { captured })
            return WeakReference(handler)
        }
        val listed: WeakReference<Counting> = first()
        // Still registered: the process-wide registration is the one documented holder.
        assertNotNull(AlarmDispatch.current())
        createAlarmScheduler(app, listOf(Counting("OTHER")))
        assertTrue(gone(listed), "the handlers of a replaced registration are still reachable")

        val provided: WeakReference<Counting> = firstProvided()
        createAlarmScheduler(app, listOf(Counting("OTHER")))
        assertTrue(gone(provided), "the provider of a replaced registration is still reachable")
    }

    @Test
    fun `a scheduler is collectable once dropped even with alarms armed`() {
        fun use(): WeakReference<AlarmScheduler> {
            val scheduler: AlarmScheduler = createAlarmScheduler(app)
            runBlocking { repeat(2_000) { i: Int -> scheduler.schedule(alarm(i)) } }
            return WeakReference(scheduler)
        }
        val reference: WeakReference<AlarmScheduler> = use()
        assertTrue(gone(reference), "a dropped scheduler is held by something process wide")
        // The armed alarms outlive it, which is the point of an alarm: the next scheduler can still cancel them.
        val next: AlarmScheduler = createAlarmScheduler(app)
        runBlocking { next.cancelAll(List(30) { "id$it" }) }
        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
    }

    @Test
    fun `fifteen hundred delivered alarms leave no thread of their own and every handler runs`() {
        val handler = Counting("T")
        val scheduler: AlarmScheduler = createAlarmScheduler(app, listOf(handler))
        runBlocking { scheduler.schedule(alarm(0, "a")) }
        @Suppress("DEPRECATION")
        val intent = shadowOf(requireNotNull(shadowOf(alarmManager).nextScheduledAlarm?.operation)).savedIntent
        val before: Set<Thread> = Thread.getAllStackTraces().keys
        val workersBefore: Int = before.count { it.name.startsWith("DefaultDispatcher-worker") }
        repeat(1_500) { AlarmReceiver().onReceive(app, intent) }
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(30)
        while (handler.fired.get() < 1_500 && System.nanoTime() < deadline) Thread.sleep(10)
        assertEquals(1_500, handler.fired.get(), "every delivered alarm reaches its handler")
        val latch = CountDownLatch(1)
        Thread { latch.countDown() }.start()
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        Thread.sleep(200)
        val pool = Regex("DefaultDispatcher-worker-\\d+|Robolectric.*|Test worker.*|Finalizer|Reference Handler|Signal Dispatcher|Thread-\\d+|ForkJoinPool.*|process reaper|Attach Listener|Common-Cleaner|main|Monitor.*|pool-.*|kotlinx.*")
        val created: List<Thread> = Thread.getAllStackTraces().keys.filter { it !in before && it.isAlive && !pool.matches(it.name) }
        assertTrue(created.isEmpty(), "threads left behind: ${created.map { it.name }}")
        val workers: Int = Thread.getAllStackTraces().keys.count { it.name.startsWith("DefaultDispatcher-worker") }
        // The coroutine pools are shared with every other test of this JVM, so only the growth this test caused counts. Dispatchers.IO
        // may add up to 64 workers for a burst; a library that made a thread (or a pool) per delivered alarm would add about 1500.
        val grew: Int = workers - workersBefore
        assertTrue(grew <= 64, "the shared pool grew by $grew workers for fifteen hundred handlers that return at once")
    }
}
