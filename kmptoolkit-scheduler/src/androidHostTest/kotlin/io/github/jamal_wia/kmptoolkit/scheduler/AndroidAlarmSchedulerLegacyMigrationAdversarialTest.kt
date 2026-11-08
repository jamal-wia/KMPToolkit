package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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
 * An app updated from a version that armed alarms with the plain `Uri.Builder.appendPath(id)` data URI: the
 * alarm armed before the update must still be replaced by `schedule(id)` and removed by `cancel(id)` ("Re-scheduling
 * the same id replaces the pending alarm", "cancel takes this value"), for an ordinary id (ids with an unpaired surrogate changed their URI, see the suspect copy).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerLegacyMigrationAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String, title: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    /** What the previous version armed: slot 0, the URI built by plain `appendPath`. */
    private fun armAsEarlierVersion(id: String, title: String) {
        val intent = AlarmIntents.toIntent(context, alarm(id, title), keys)
        intent.data = Uri.Builder().scheme(keys.intentScheme).appendPath(id).build()
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 1_000L, pending)
    }

    private fun titles(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }.sorted()

    @Test
    fun `an ordinary id armed by the earlier version is replaced and cancelled`() {
        armAsEarlierVersion("reminder-1", "old")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        assertIs<AlarmScheduleResult.Exact>(runBlocking { scheduler.schedule(alarm("reminder-1", "new")) })
        assertEquals(listOf("new"), titles())
        runBlocking { scheduler.cancel("reminder-1") }
        assertEquals(emptyList(), titles())
    }
}
