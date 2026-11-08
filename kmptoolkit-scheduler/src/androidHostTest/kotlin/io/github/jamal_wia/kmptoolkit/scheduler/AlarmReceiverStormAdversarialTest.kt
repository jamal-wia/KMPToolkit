package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Many alarms fire at once while the registration is replaced underneath them and the provider fails
 * now and then: every alarm that reaches a handler is delivered exactly once, an alarm is dropped
 * only when the provider threw for it, and nothing is delivered to the wrong handler.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverStormAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @After
    fun clearRegistration() {
        AlarmDispatch.reset()
    }

    private class CountingHandler(override val type: String, val seen: ConcurrentHashMap<String, AtomicInteger>, val done: CountDownLatch) : AlarmHandler {
        override suspend fun onFire(alarm: ScheduledAlarm) {
            check(alarm.type == type) { "alarm of type ${alarm.type} reached the $type handler" }
            seen.computeIfAbsent(alarm.id) { AtomicInteger() }.incrementAndGet()
            done.countDown()
        }
    }

    @Test
    fun `a storm of fired alarms is delivered exactly once each while the registration is replaced`() {
        val seen = ConcurrentHashMap<String, AtomicInteger>()
        val total: Int = FIRERS * PER_FIRER
        val providerCalls = AtomicInteger()
        val providerFailures = AtomicInteger()
        val done = CountDownLatch(total)
        val handlers: List<AlarmHandler> = listOf(CountingHandler("A", seen, done), CountingHandler("B", seen, done))
        val provider: () -> Collection<AlarmHandler> = {
            if (providerCalls.incrementAndGet() % 7 == 0) {
                providerFailures.incrementAndGet()
                // An alarm dropped by a failing provider never reaches a handler: count it as done.
                done.countDown()
                throw IllegalStateException("container not started")
            }
            handlers
        }
        AlarmDispatch.install(keys, provider)
        val barrier = CyclicBarrier(FIRERS + 1)
        val stop = java.util.concurrent.atomic.AtomicBoolean(false)
        val reinstaller: Thread = thread(name = "kmptoolkit-adversarial-reinstall") {
            barrier.await(10, TimeUnit.SECONDS)
            while (!stop.get()) AlarmDispatch.install(keys, provider)
        }
        val firers: List<Thread> = List(FIRERS) { index: Int ->
            thread(name = "kmptoolkit-adversarial-fire-$index") {
                barrier.await(10, TimeUnit.SECONDS)
                repeat(PER_FIRER) { n: Int ->
                    val alarm = ScheduledAlarm(
                        id = "f$index-$n",
                        type = if (n % 2 == 0) "A" else "B",
                        fireAtEpochMillis = 1L,
                        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
                    )
                    AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm, keys))
                }
            }
        }
        firers.forEach { it.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS)) }
        val allDone: Boolean = done.await(JOIN_SECONDS, TimeUnit.SECONDS)
        stop.set(true)
        reinstaller.join(TimeUnit.SECONDS.toMillis(JOIN_SECONDS))
        assertTrue(firers.none { it.isAlive } && !reinstaller.isAlive, "a thread is stuck")
        assertTrue(allDone, "alarms went missing: ${seen.values.sumOf { it.get() }} delivered, ${providerFailures.get()} dropped by the provider, $total fired")
        assertEquals(emptyList(), seen.filterValues { it.get() != 1 }.keys.toList(), "an alarm was delivered more than once")
        assertEquals(total - providerFailures.get(), seen.size, "delivered plus dropped must account for every alarm")
    }

    private companion object {
        const val FIRERS: Int = 8
        const val PER_FIRER: Int = 100
        const val JOIN_SECONDS: Long = 30L
    }
}
