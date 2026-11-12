package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
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
 * Round 15 (contract lawyer): 04-api-reference, `AlarmHandler`: "Called at fire time on a background
 * dispatcher, inside a broadcast window of roughly ten seconds; do the minimum and return." and the
 * `handlerProvider` sentence "called on a background thread". A handler that blocks (a slow database
 * read, a network call it should not make) must not freeze the main thread the receiver was invoked on.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverThreadingContractAdversarialTest {

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

    private fun arm(scheduler: AlarmScheduler) {
        runBlocking {
            scheduler.schedule(ScheduledAlarm("a", "T", 1_900_000_000_000L, AlarmNotification("t", "b", "c")))
        }
    }

    private fun deliver() {
        @Suppress("DEPRECATION")
        AlarmReceiver().onReceive(context, shadowOf(requireNotNull(shadowAlarmManager.nextScheduledAlarm?.operation)).savedIntent)
    }

    @Test
    fun `a blocking handler runs on another thread and onReceive returns without waiting for it`() {
        val release = CountDownLatch(1)
        val started = CountDownLatch(1)
        val handlerThread: AtomicReference<Thread?> = AtomicReference(null)
        val handlerOnMainLooper: AtomicReference<Boolean?> = AtomicReference(null)
        val finished = CountDownLatch(1)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                handlerThread.set(Thread.currentThread())
                handlerOnMainLooper.set(Looper.getMainLooper().thread === Thread.currentThread())
                started.countDown()
                check(release.await(20, TimeUnit.SECONDS)) { "never released" }
                finished.countDown()
            }
        }
        arm(createAlarmScheduler(context, listOf(handler)))
        val caller: Thread = Thread.currentThread()

        deliver()

        // onReceive returned although the handler is still parked: the receiver did not block its caller.
        assertTrue(started.await(10, TimeUnit.SECONDS), "the handler never started")
        assertEquals(1L, finished.count, "onReceive must not wait for the handler to finish")
        assertNotEquals(caller, handlerThread.get(), "the handler ran on the thread that delivered the broadcast")
        assertEquals(false, handlerOnMainLooper.get(), "the handler ran on the main looper thread")
        release.countDown()
        assertTrue(finished.await(10, TimeUnit.SECONDS))
    }

    @Test
    fun `the handler provider is also asked off the delivering thread and a blocking provider does not block onReceive`() {
        val release = CountDownLatch(1)
        val asked = CountDownLatch(1)
        val providerThread: AtomicReference<Thread?> = AtomicReference(null)
        val fired = CountDownLatch(1)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) {
                fired.countDown()
            }
        }
        arm(
            createAlarmScheduler(
                context,
                handlerProvider = {
                    providerThread.set(Thread.currentThread())
                    asked.countDown()
                    release.await(20, TimeUnit.SECONDS)
                    listOf(handler)
                },
            ),
        )
        val caller: Thread = Thread.currentThread()

        deliver()

        assertTrue(asked.await(10, TimeUnit.SECONDS), "the provider was never asked")
        assertEquals(1L, fired.count, "onReceive returned while the provider still blocks")
        assertNotEquals(caller, providerThread.get())
        release.countDown()
        assertTrue(fired.await(10, TimeUnit.SECONDS), "the handler the provider returned must still run")
    }
}
