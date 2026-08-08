package io.github.jamal_wia.kmptoolkit.scheduler

import android.content.Context
import android.content.Intent
import org.junit.After
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the fire-time half of the Android side: the intent round trip and the dispatch decision
 * the receiver makes. Both are silent failures in production — an alarm that does not reach its
 * handler produces no error anywhere — so they are asserted here rather than trusted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AlarmReceiverTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    private class RecordingHandler(override val type: String) : AlarmHandler {
        val latch = CountDownLatch(1)
        var fired: ScheduledAlarm? = null

        override suspend fun onFire(alarm: ScheduledAlarm) {
            fired = alarm
            latch.countDown()
        }
    }

    private fun alarm(
        id: String = "a",
        type: String = "REMINDER",
        payload: Map<String, String> = emptyMap(),
    ): ScheduledAlarm = ScheduledAlarm(
        id = id,
        type = type,
        fireAtEpochMillis = 10_000L,
        notification = AlarmNotification(title = "Title", body = "Body", channelId = "chan"),
        payload = payload,
    )

    private fun keys(config: AlarmSchedulerConfig = AlarmSchedulerConfig()): AlarmIntentKeys =
        AlarmIntentKeys.from(config, context.packageName)

    @After
    fun clearRegistration() {
        AlarmDispatch.reset()
    }

    @Test
    fun `an alarm survives the round trip through intent extras`() {
        val original: ScheduledAlarm = alarm(payload = mapOf("deeplink" to "app://x", "n" to "7"))

        val intent: Intent = AlarmIntents.toIntent(context, original, keys())

        assertEquals(original, AlarmIntents.fromIntent(intent, keys()))
    }

    @Test
    fun `an empty payload round-trips as an empty map`() {
        val intent: Intent = AlarmIntents.toIntent(context, alarm(), keys())

        assertEquals(emptyMap(), AlarmIntents.fromIntent(intent, keys())?.payload)
    }

    @Test
    fun `an intent carrying no alarm extras is not an alarm`() {
        assertNull(AlarmIntents.fromIntent(Intent(context, AlarmReceiver::class.java), keys()))
    }

    @Test
    fun `configured keys carry the id and type`() {
        val config = AlarmSchedulerConfig(alarmIdKey = "x_id", alarmTypeKey = "x_type")

        val intent: Intent = AlarmIntents.toIntent(context, alarm(), keys(config))

        assertEquals("a", intent.getStringExtra("x_id"))
        assertEquals("REMINDER", intent.getStringExtra("x_type"))
        // Read back with the default keys the alarm is unrecognizable — the keys are not implicit.
        assertNull(AlarmIntents.fromIntent(intent, keys()))
        assertEquals(alarm(), AlarmIntents.fromIntent(intent, keys(config)))
    }

    @Test
    fun `a fired alarm reaches the handler registered for its type`() {
        val reminder = RecordingHandler("REMINDER")
        val digest = RecordingHandler("DIGEST")
        AlarmDispatch.install(keys(), listOf(reminder, digest))
        val fired: ScheduledAlarm = alarm(payload = mapOf("k" to "v"))

        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, fired, keys()))

        assertTrue(reminder.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "handler was never called")
        assertEquals(fired, reminder.fired)
        assertNull(digest.fired)
    }

    @Test
    fun `an alarm whose type no handler claims is dropped`() {
        val reminder = RecordingHandler("REMINDER")
        AlarmDispatch.install(keys(), listOf(reminder))

        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(type = "UNKNOWN"), keys()))

        assertFalse(reminder.latch.await(SETTLE_MILLIS, TimeUnit.MILLISECONDS), "handler must not run")
        assertNull(reminder.fired)
    }

    @Test
    fun `an alarm arriving before any scheduler was created is dropped`() {
        // Nothing installed: the process was recreated by the alarm itself and the app never got
        // around to calling createAlarmScheduler. Must not crash the receiver.
        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(), keys()))
    }

    @Test
    fun `an unrelated intent is ignored`() {
        val reminder = RecordingHandler("REMINDER")
        AlarmDispatch.install(keys(), listOf(reminder))

        AlarmReceiver().onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertFalse(reminder.latch.await(SETTLE_MILLIS, TimeUnit.MILLISECONDS), "handler must not run")
    }

    @Test
    fun `the last installed registration wins`() {
        val first = RecordingHandler("REMINDER")
        val second = RecordingHandler("REMINDER")
        AlarmDispatch.install(keys(), listOf(first))
        AlarmDispatch.install(keys(), listOf(second))

        AlarmReceiver().onReceive(context, AlarmIntents.toIntent(context, alarm(), keys()))

        assertTrue(second.latch.await(AWAIT_SECONDS, TimeUnit.SECONDS), "handler was never called")
        assertNull(first.fired)
    }

    private companion object {
        const val AWAIT_SECONDS = 5L

        /** Long enough for a dispatch that should not happen to have happened. */
        const val SETTLE_MILLIS = 300L
    }
}
