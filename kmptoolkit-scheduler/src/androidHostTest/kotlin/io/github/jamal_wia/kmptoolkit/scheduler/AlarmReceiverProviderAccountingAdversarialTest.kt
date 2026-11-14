package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import android.content.Intent
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Resource accounting of the handler provider, which the docs say is "called on a background thread
 * each time an alarm fires, never during this call" and "must be cheap to call repeatedly": exactly
 * once per alarm that fires, never for an intent that is not an alarm of this configuration (wrong
 * key, no id, no type) and never for an alarm that arrives before any scheduler exists. A provider
 * can be a DI lookup or worse, so a second call per fire, or a call for a dropped intent, is a cost
 * the contract does not announce.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverProviderAccountingAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun clean() { AlarmDispatch.reset() }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String): ScheduledAlarm = ScheduledAlarm(id, "T", 1L, AlarmNotification("t", "b", "c"))

    @Test
    fun `the provider is called once per fired alarm, the handler once, and never for what is not an alarm of this configuration`() {
        val providerCalls = AtomicInteger()
        val handled = AtomicInteger()
        val allHandled = CountDownLatch(FIRES)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { handled.incrementAndGet(); allHandled.countDown() }
        }
        val before = AtomicInteger()
        // No scheduler yet: dropped before any provider exists.
        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm("early"), keys))
        AlarmDispatch.install(keys) { providerCalls.incrementAndGet(); listOf(handler) }
        assertEquals(0, before.get())

        val otherKeys = AlarmIntentKeys(keys.intentScheme, "other_id", "other_type")
        val receiver = AlarmReceiver()
        repeat(FIRES) { index: Int ->
            receiver.onReceive(context, AlarmIntents.toIntent(context, alarm("a$index"), keys))
            // Not ours: another configuration's keys, an unrelated action, an empty intent.
            receiver.onReceive(context, AlarmIntents.toIntent(context, alarm("foreign$index"), otherKeys))
            receiver.onReceive(context, Intent("unrelated"))
            receiver.onReceive(context, Intent())
        }
        assertTrue(allHandled.await(30, TimeUnit.SECONDS), "not every alarm was delivered")
        Thread.sleep(100) // anything extra a stray call would have produced has had its chance

        assertEquals(FIRES, handled.get(), "handler calls")
        assertEquals(FIRES, providerCalls.get(), "provider calls: one per fired alarm, none for the rest")
    }

    private companion object {
        const val FIRES: Int = 40
    }
}
