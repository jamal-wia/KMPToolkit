package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Bundle
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBroadcastPendingResult

/**
 * Resource accounting of the broadcast window: the receiver goes async for an alarm it will handle
 * and must give the window back (`PendingResult.finish`) on every way out of the handler's
 * coroutine, and only once the handler is done. A window never finished holds the process (and its
 * wake lock) up for the ten seconds the system allows; one finished early lets the process die under
 * a handler that is still running. The docs promise "inside a broadcast window of roughly ten
 * seconds; do the minimum and return".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverPendingResultAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun clean() { AlarmDispatch.reset() }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(type: String = "T"): ScheduledAlarm = ScheduledAlarm(
        id = "a", type = type, fireAtEpochMillis = 1L,
        notification = AlarmNotification("t", "b", "c"),
    )

    /** A receiver that has been given a pending result, the way the framework hands one over. */
    private fun asyncReceiver(): Pair<AlarmReceiver, BroadcastReceiver.PendingResult> {
        val receiver = AlarmReceiver()
        // `create` is package-private in Robolectric: the one way in that survives its versions.
        val create = ShadowBroadcastPendingResult::class.java.getDeclaredMethod(
            "create", Int::class.javaPrimitiveType, String::class.java, Bundle::class.java, Boolean::class.javaPrimitiveType,
        )
        create.isAccessible = true
        val result: BroadcastReceiver.PendingResult = create.invoke(null, 0, null, null, false) as BroadcastReceiver.PendingResult
        BroadcastReceiver::class.java.getMethod("setPendingResult", BroadcastReceiver.PendingResult::class.java)
            .invoke(receiver, result)
        return receiver to result
    }

    private fun finished(result: BroadcastReceiver.PendingResult): Boolean {
        // Robolectric records `finish()` in a future on the result's shadow.
        val shadow: Any = Shadow.extract(result)
        val future = shadow.javaClass.getMethod("getFuture").invoke(shadow) as Future<*>
        return future.isDone
    }

    private fun awaitFinished(result: BroadcastReceiver.PendingResult, what: String) {
        val deadline: Long = System.nanoTime() + TimeUnit.SECONDS.toNanos(10)
        while (!finished(result) && System.nanoTime() < deadline) Thread.sleep(5)
        assertTrue(finished(result), "the broadcast window was never given back: $what")
    }

    @Test
    fun `the window is held while the handler runs and given back when it returns`() {
        val inside = CountDownLatch(1)
        val release = CountDownLatch(1)
        AlarmDispatch.install(keys, listOf(object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { inside.countDown(); release.await(10, TimeUnit.SECONDS) }
        }))
        val (receiver, result) = asyncReceiver()

        receiver.onReceive(context, AlarmIntents.toIntent(context, alarm(), keys))

        assertTrue(inside.await(10, TimeUnit.SECONDS), "handler never ran")
        assertFalse(finished(result), "the window was given back while the handler was still running")
        release.countDown()
        awaitFinished(result, "handler returned")
    }

    @Test
    fun `a provider that throws still gives the window back`() {
        AlarmDispatch.install(keys) { throw IllegalStateException("container not started") }
        val (receiver, result) = asyncReceiver()
        receiver.onReceive(context, AlarmIntents.toIntent(context, alarm(), keys))
        awaitFinished(result, "provider threw")
    }

    @Test
    fun `an alarm no handler claims still gives the window back`() {
        AlarmDispatch.install(keys, emptyList())
        val (receiver, result) = asyncReceiver()
        receiver.onReceive(context, AlarmIntents.toIntent(context, alarm(), keys))
        awaitFinished(result, "no handler")
    }

    @Test
    fun `a handler that throws gives the window back before the process would die`() {
        val crashed = CountDownLatch(1)
        val cause: AtomicReference<Throwable?> = AtomicReference(null)
        AlarmDispatch.install(keys, listOf(object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) = throw IllegalStateException("boom")
        }))
        val previous: Thread.UncaughtExceptionHandler? = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { _, error: Throwable -> cause.set(error); crashed.countDown() }
        try {
            val (receiver, result) = asyncReceiver()
            receiver.onReceive(context, AlarmIntents.toIntent(context, alarm(), keys))
            assertTrue(crashed.await(10, TimeUnit.SECONDS), "the failure did not escape")
            assertEquals("boom", cause.get()?.message)
            awaitFinished(result, "handler threw")
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }

    @Test
    fun `an intent that is not an alarm never takes the window`() {
        AlarmDispatch.install(keys, emptyList())
        val (receiver, result) = asyncReceiver()
        receiver.onReceive(context, Intent("unrelated"))
        // Not async: nothing was started, so nothing may have been finished on the system's behalf.
        Thread.sleep(50)
        assertFalse(finished(result))
    }
}
