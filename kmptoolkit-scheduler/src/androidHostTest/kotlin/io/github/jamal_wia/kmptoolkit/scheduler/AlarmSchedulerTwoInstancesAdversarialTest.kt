package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
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
 * Round 20 (consumer setups): two schedulers in one process, and a scheduler created off the main
 * thread. The reference says "Calling it twice replaces the previous registration; the earlier
 * handler list stops receiving alarms. Pass every handler to one call." These tests pin what that
 * sentence means in practice: which handler list, which keys and which alarms survive a second
 * `createAlarmScheduler`, and that the alarms themselves stay in the platform either way.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmSchedulerTwoInstancesAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private class Recorder(override val type: String, val seen: MutableList<String>) : AlarmHandler {
        val fired = CountDownLatch(1)
        override suspend fun onFire(alarm: ScheduledAlarm) {
            seen += "${type}:${alarm.id}"
            fired.countDown()
        }
    }

    private fun alarm(id: String, type: String): ScheduledAlarm =
        ScheduledAlarm(id, type, 1_900_000_000_000L, AlarmNotification("t", "b", "c"), mapOf("k" to "v"))

    private fun fireAll() {
        shadowAlarmManager.scheduledAlarms.toList().forEach { armed: ShadowAlarmManager.ScheduledAlarm ->
            @Suppress("DEPRECATION")
            AlarmReceiver().onReceive(context, shadowOf(requireNotNull(armed.operation)).savedIntent)
        }
    }

    @Test
    fun `the later scheduler owns the handlers - an alarm armed by the first reaches the second one's handler only`() {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val first = Recorder("T", log)
        val second = Recorder("T", log)
        val a: AlarmScheduler = createAlarmScheduler(context, listOf(first))
        runBlocking { a.schedule(alarm("one", "T")) }
        createAlarmScheduler(context, listOf(second))

        fireAll()

        assertTrue(second.fired.await(10, TimeUnit.SECONDS), "the registration of the latest call must run the alarm")
        assertEquals(listOf("T:one"), log.toList())
        assertEquals(1L, first.fired.count, "the earlier handler list stops receiving alarms, as documented")
    }

    @Test
    fun `an alarm of a type only the replaced list knew is dropped without an exception and the next one still runs`() {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val onlyFirst = Recorder("FIRST", log)
        val second = Recorder("SECOND", log)
        val a: AlarmScheduler = createAlarmScheduler(context, listOf(onlyFirst))
        val b: AlarmScheduler = createAlarmScheduler(context, listOf(second))
        runBlocking {
            a.schedule(alarm("lost", "FIRST"))
            b.schedule(alarm("kept", "SECOND"))
        }

        fireAll()

        assertTrue(second.fired.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("SECOND:kept"), log.toList(), "FIRST has no handler any more: dropped, as for any unknown type")
        assertEquals(1L, onlyFirst.fired.count)
    }

    @Test
    fun `two schedulers with the same configuration cancel each other's alarms by id`() {
        val a: AlarmScheduler = createAlarmScheduler(context, emptyList())
        val b: AlarmScheduler = createAlarmScheduler(context, emptyList())
        runBlocking {
            a.schedule(alarm("x", "T"))
            b.schedule(alarm("y", "T"))
        }
        assertEquals(2, shadowAlarmManager.scheduledAlarms.size)

        runBlocking {
            b.cancel("x")
            a.cancel("y")
        }

        assertEquals(0, shadowAlarmManager.scheduledAlarms.size, "one alarm identity per id: either instance cancels it")
    }

    @Test
    fun `a replace by the second scheduler leaves one alarm for the id`() {
        val a: AlarmScheduler = createAlarmScheduler(context, emptyList())
        val b: AlarmScheduler = createAlarmScheduler(context, emptyList())
        runBlocking {
            a.schedule(alarm("same", "T"))
            b.schedule(alarm("same", "T").copy(fireAtEpochMillis = 1_950_000_000_000L))
        }

        assertEquals(1, shadowAlarmManager.scheduledAlarms.size)
        assertEquals(1_950_000_000_000L, shadowAlarmManager.scheduledAlarms.single().triggerAtMs)
    }

    @Test
    fun `a scheduler created on a background thread arms and delivers like one created on the main thread`() {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val handler = Recorder("T", log)
        val created: AtomicReference<AlarmScheduler> = AtomicReference()
        val failure: AtomicReference<Throwable> = AtomicReference()
        val done = CountDownLatch(1)
        Thread {
            try {
                created.set(createAlarmScheduler(context, listOf(handler)))
            } catch (t: Throwable) {
                failure.set(t)
            } finally {
                done.countDown()
            }
        }.start()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        assertNull(failure.get(), "createAlarmScheduler is legal off the main thread")

        val scheduler: AlarmScheduler = assertNotNull(created.get())
        assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("bg", "T")) })
        fireAll()

        assertTrue(handler.fired.await(10, TimeUnit.SECONDS))
        assertEquals(listOf("T:bg"), log.toList())
    }

    @Test
    fun `an alarm armed under custom keys is not readable once a scheduler with other keys registered`() {
        val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val custom = Recorder("T", log)
        val plain = Recorder("T", log)
        val a: AlarmScheduler = createAlarmScheduler(context, listOf(custom), AlarmSchedulerConfig(alarmIdKey = "my_id", alarmTypeKey = "my_type"))
        runBlocking { a.schedule(alarm("keys", "T")) }
        createAlarmScheduler(context, listOf(plain))

        var failure: Throwable? = null
        try {
            fireAll()
        } catch (t: Throwable) {
            failure = t
        }

        assertNull(failure, "an alarm the registration cannot read must be dropped, not thrown")
        assertEquals(1L, custom.fired.count)
        assertEquals(1L, plain.fired.count, "characterization: the keys come from the latest registration")
        assertTrue(log.isEmpty())
    }
}
