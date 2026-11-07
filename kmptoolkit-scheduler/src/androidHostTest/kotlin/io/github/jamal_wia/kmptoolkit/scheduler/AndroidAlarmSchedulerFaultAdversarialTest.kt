package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
 * Cancellation and platform faults against the process-wide lock the Android scheduler serialises
 * every arm and cancel behind: a caller cancelled while queued, a platform call that throws inside the
 * critical section. Neither may leave the lock held or an alarm armed that nobody asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerFaultAdversarialTest.FaultyShadowAlarmManager::class])
class AndroidAlarmSchedulerFaultAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager =
        shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grantExactAlarms() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun reset() {
        Faults.reset()
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String = id): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = "TEST",
        fireAtEpochMillis = 10_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "chan"),
    )

    private fun armedIds(): Set<String> = shadowAlarmManager.scheduledAlarms.map { armed: ShadowAlarmManager.ScheduledAlarm ->
        assertNotNull(AlarmIntents.fromIntent(shadowOf(armed.operation).savedIntent, keys)).id
    }.toSet()

    @Test
    fun `a schedule cancelled while queued behind another leaves no alarm and the lock usable`() {
        val first: AlarmScheduler = createAlarmScheduler(context)
        val second: AlarmScheduler = createAlarmScheduler(context)
        val pause: Faults.Pause = Faults.pauseNextExactCheck()
        val holder: Thread = thread(name = "kmptoolkit-adversarial-holder") { runBlocking { first.schedule(alarm("holder")) } }
        assertTrue(pause.entered.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first schedule never reached the platform")

        val scope = CoroutineScope(Dispatchers.Default)
        val queued = scope.launch { second.schedule(alarm("queued")) }
        Thread.sleep(SETTLE_MILLIS)
        runBlocking { withTimeout(WAIT_SECONDS * 1000) { queued.cancelAndJoin() } }
        pause.release.countDown()
        holder.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS))
        assertTrue(!holder.isAlive, "the holder is stuck")

        val fresh: AlarmScheduleResult = runBlocking { withTimeout(WAIT_SECONDS * 1000) { second.schedule(alarm("fresh")) } }

        assertEquals(AlarmScheduleResult.Exact, fresh)
        assertEquals(setOf("holder", "fresh"), armedIds(), "the cancelled schedule was armed anyway, or the lock stayed held")
    }

    @Test
    fun `a platform cancel that throws does not leave the lock held`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { scheduler.schedule(alarm("x")) }
        Faults.failNextCancel(IllegalStateException("binder died"))

        runCatching { runBlocking { withTimeout(WAIT_SECONDS * 1000) { scheduler.cancel("x") } } }

        val next: AlarmScheduleResult = runBlocking { withTimeout(WAIT_SECONDS * 1000) { scheduler.schedule(alarm("y")) } }
        assertIs<AlarmScheduleResult.Exact>(next)
    }

    @Test
    fun `a platform cancel that throws is not allowed to escape cancel`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { scheduler.schedule(alarm("x")) }
        Faults.failNextCancel(SecurityException("revoked"))

        val escaped: Throwable? = runCatching { runBlocking { withTimeout(WAIT_SECONDS * 1000) { scheduler.cancel("x") } } }.exceptionOrNull()

        assertEquals(null, escaped, "cancel threw ${escaped?.javaClass?.simpleName}")
    }

    @Test
    fun `a platform cancel that throws IllegalStateException is not allowed to escape cancel`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking { scheduler.schedule(alarm("x")) }
        Faults.failNextCancel(IllegalStateException("binder died"))

        val escaped: Throwable? = runCatching { runBlocking { withTimeout(WAIT_SECONDS * 1000) { scheduler.cancel("x") } } }.exceptionOrNull()

        assertEquals(null, escaped, "cancel threw ${escaped?.javaClass?.simpleName}")
    }

    @Test
    fun `cancelAll goes on with the remaining ids after one platform cancel throws`() {
        val scheduler: AlarmScheduler = createAlarmScheduler(context)
        runBlocking {
            scheduler.schedule(alarm("a"))
            scheduler.schedule(alarm("b"))
        }
        Faults.failNextCancel(SecurityException("revoked"))

        val escaped: Throwable? = runCatching {
            runBlocking { withTimeout(WAIT_SECONDS * 1000) { scheduler.cancelAll(listOf("a", "b")) } }
        }.exceptionOrNull()

        assertEquals(null, escaped, "cancelAll threw ${escaped?.javaClass?.simpleName}")
        assertEquals(1, armedIds().size, "the id after the failing one was not cancelled")
    }

    /** Parks the exact-permission check, and can make the next platform cancel throw. */
    @Implements(AlarmManager::class)
    class FaultyShadowAlarmManager : ShadowAlarmManager() {

        @Implementation
        public override fun canScheduleExactAlarms(): Boolean {
            Faults.awaitIfPaused()
            return super.canScheduleExactAlarms()
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            Faults.cancelFailure.getAndSet(null)?.let { throw it }
            super.cancel(operation)
        }
    }

    private object Faults {

        class Pause {
            val entered: CountDownLatch = CountDownLatch(1)
            val release: CountDownLatch = CountDownLatch(1)
        }

        private val next: AtomicReference<Pause?> = AtomicReference(null)
        val cancelFailure: AtomicReference<RuntimeException?> = AtomicReference(null)

        fun pauseNextExactCheck(): Pause = Pause().also { next.set(it) }

        fun failNextCancel(failure: RuntimeException) {
            cancelFailure.set(failure)
        }

        fun awaitIfPaused() {
            val pause: Pause = next.getAndSet(null) ?: return
            pause.entered.countDown()
            pause.release.await(WAIT_SECONDS, TimeUnit.SECONDS)
        }

        fun reset() {
            next.set(null)
            cancelFailure.set(null)
        }
    }

    private companion object {
        const val WAIT_SECONDS: Long = 5L
        const val SETTLE_MILLIS: Long = 300L
    }
}
