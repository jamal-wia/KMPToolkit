package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
 * Mutation survivor hunt (round 13). Contract (scheduler docs 02 and 05, "Nothing is persisted" and cancel): after
 * `cancel(id)` the platform holds nothing for the alarm: no armed alarm AND no `PendingIntent` left to resolve
 * (a lingering one is what a later `FLAG_NO_CREATE` lookup, or the platform's 500-alarm budget, would still see).
 * That holds for an alarm armed by this version and for the lossy legacy form armed by 3.0.x.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerCancelReleasesPendingIntentsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
    }

    @After
    fun tearDown() {
        AlarmDispatch.reset()
    }

    private fun alarm(id: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = "t", body = "b", channelId = "c"),
    )

    private fun lookupSlot(id: String, slot: Int): PendingIntent? = PendingIntent.getBroadcast(
        context,
        AlarmIntents.requestCode(id, slot),
        AlarmIntents.cancelIntent(context, id, keys),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
    )

    @Test
    fun `cancel releases the pending intent of an alarm armed by this version`() {
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            scheduler.schedule(alarm("a"))
            assertNotNull(lookupSlot("a", 0), "the armed alarm has a pending intent")
            scheduler.cancel("a")
            for (slot: Int in 0 until AlarmIntents.SLOT_COUNT) assertNull(lookupSlot("a", slot), "slot $slot survived cancel")
            assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
        }
    }

    @Test
    fun `cancel releases the pending intent of a legacy alarm of an id with an unpaired surrogate`() {
        val id = "task\uD800"
        val legacyIntent = AlarmIntents.toIntent(context, alarm(id), keys)
        legacyIntent.data = Uri.Builder().scheme(keys.intentScheme).appendPath(id).build()
        val legacy = PendingIntent.getBroadcast(
            context, id.hashCode(), legacyIntent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 1_000L, legacy)
        val lookup = {
            PendingIntent.getBroadcast(
                context, id.hashCode(), checkNotNull(AlarmIntents.legacyCancelIntent(context, id, keys)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_NO_CREATE,
            )
        }
        assertNotNull(lookup(), "the legacy alarm is resolvable before the cancel")
        runBlocking { AndroidAlarmScheduler(context, keys).cancel(id) }
        assertEquals(0, shadowOf(alarmManager).scheduledAlarms.size)
        assertNull(lookup(), "the legacy pending intent survived cancel")
    }
}
