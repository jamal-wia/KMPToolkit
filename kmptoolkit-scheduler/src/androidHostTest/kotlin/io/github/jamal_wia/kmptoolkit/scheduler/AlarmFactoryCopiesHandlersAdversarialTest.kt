package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Copy semantics at an entry point that takes a collection at construction:
 * `createAlarmScheduler(context, handlers)`. The list overload registers the handlers it was given
 * when it was called ("one call to get right"); a mutable list the caller keeps and edits afterwards
 * does not change who answers a fired alarm. Only the provider overload is a live lookup, by contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmFactoryCopiesHandlersAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @After
    fun reset() {
        AlarmDispatch.reset()
    }

    private class Recorder(override val type: String) : AlarmHandler {
        val fired = CountDownLatch(1)
        override suspend fun onFire(alarm: ScheduledAlarm) {
            fired.countDown()
        }
    }

    private fun fire(type: String) {
        val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
        val alarm = ScheduledAlarm(
            id = "x", type = type, fireAtEpochMillis = 1L,
            notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
        )
        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm, keys))
    }

    @Test
    fun `a handler removed from the caller's list after creation still receives its alarm`() {
        val reminder = Recorder("REMINDER")
        val handlers: MutableList<AlarmHandler> = mutableListOf(reminder)
        createAlarmScheduler(context, handlers)
        handlers.clear()

        fire("REMINDER")

        assertTrue(reminder.fired.await(5, TimeUnit.SECONDS), "the registered handler was dropped with the caller's list")
    }

    @Test
    fun `a handler added to the caller's list after creation is not registered`() {
        val late = Recorder("LATE")
        val handlers: MutableList<AlarmHandler> = mutableListOf(Recorder("REMINDER"))
        createAlarmScheduler(context, handlers)
        handlers += late

        fire("LATE")

        assertFalse(late.fired.await(1, TimeUnit.SECONDS), "a handler added after creation received an alarm")
        assertEquals(1, late.fired.count)
    }
}
