package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Payload keys are the consumer's: whatever they are called, the payload round-trips and the alarm stays intact. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerPayloadKeysAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    @Test
    fun `payload keys named like the module's own extras and the configured keys round trip`() {
        val payload: Map<String, String> = mapOf(
            "kmptoolkit_alarm_title" to "p-title", "kmptoolkit_alarm_body" to "p-body", "kmptoolkit_alarm_fire_at" to "p-at",
            "kmptoolkit_alarm_payload" to "p-payload", "alarm_id" to "p-id", "alarm_type" to "p-type", "" to "empty-key",
            "key with spaces" to "v", "\u0000" to "nul", "k" to "",
        )
        val sent = ScheduledAlarm(
            id = "the-id", type = "T", fireAtEpochMillis = 5L,
            notification = AlarmNotification(title = "the-title", body = "the-body", channelId = "c"), payload = payload,
        )
        runBlocking { AndroidAlarmScheduler(context, keys).schedule(sent) }
        val back: ScheduledAlarm? = shadowAlarmManager.scheduledAlarms
            .map { AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys) }.singleOrNull()
        assertEquals(sent, back)
    }
}
