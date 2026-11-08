package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowAlarmManager

/**
 * The legacy lookup for an alarm armed by 3.0.x under the lossy data URI (id with an unpaired surrogate):
 * "Failed changes nothing", a refused retirement is retried by the next schedule or a cancel, the legacy
 * alarm is not a slot (saturation count is unchanged), `cancelAll` goes through `cancel`, and an alarm of
 * a look-alike id is never touched.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], shadows = [AndroidAlarmSchedulerLegacyLookupAdversarialTest.RefusingShadowAlarmManager::class])
class AndroidAlarmSchedulerLegacyLookupAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val alarmManager: AlarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(alarmManager)
    private val keys: AlarmIntentKeys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
    private val id: String = "task\uD800"

    @Before
    fun setUp() {
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        RefusingShadowAlarmManager.refuseCancel.set(false)
        RefusingShadowAlarmManager.refuseSet.set(false)
    }

    @After
    fun tearDown() {
        RefusingShadowAlarmManager.refuseCancel.set(false)
        RefusingShadowAlarmManager.refuseSet.set(false)
        AlarmDispatch.reset()
    }

    private fun alarm(id: String, title: String) = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = 1_000L,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"),
    )

    /** What 3.0.x armed: slot 0 request code, the URI built by plain `appendPath`. */
    private fun armAsEarlierVersion(id: String, title: String) {
        val intent = AlarmIntents.toIntent(context, alarm(id, title), keys)
        intent.data = Uri.Builder().scheme(keys.intentScheme).appendPath(id).build()
        val pending = PendingIntent.getBroadcast(context, id.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, 1_000L, pending)
    }

    private fun titles(): List<String> = shadowAlarmManager.scheduledAlarms
        .map { assertNotNull(AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys)).notification.title }.sorted()

    @Test
    fun `a refused replacement leaves the legacy alarm armed and a later healthy schedule retires it`() {
        armAsEarlierVersion(id, "old")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            RefusingShadowAlarmManager.refuseSet.set(true)
            assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm(id, "new")))
            RefusingShadowAlarmManager.refuseSet.set(false)
            assertEquals(listOf("old"), titles())
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "new")))
            assertEquals(listOf("new"), titles())
        }
    }

    @Test
    fun `a refused legacy retirement keeps it armed and the next schedule retries it`() {
        armAsEarlierVersion(id, "old")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            RefusingShadowAlarmManager.refuseCancel.set(true)
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "new")))
            assertEquals(listOf("new", "old"), titles())
            RefusingShadowAlarmManager.refuseCancel.set(false)
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "newer")))
            assertEquals(listOf("newer"), titles())
        }
    }

    @Test
    fun `a legacy alarm does not count toward saturation and cancel clears slots and legacy alarm`() {
        armAsEarlierVersion(id, "old")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            RefusingShadowAlarmManager.refuseCancel.set(true)
            for (n in 1..4) assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "s$n")), "schedule $n")
            RefusingShadowAlarmManager.refuseCancel.set(false)
            assertEquals(listOf("old", "s1", "s2", "s3", "s4"), titles())
            // The first schedule found a free slot, three refused replacements followed: now all four slots are held.
            assertIs<AlarmScheduleResult.Failed>(scheduler.schedule(alarm(id, "healthy")))
            assertEquals(listOf("old", "s1", "s2", "s3", "s4"), titles())
            scheduler.cancel(id)
            assertEquals(emptyList(), titles())
        }
    }

    @Test
    fun `cancelAll removes the legacy alarm`() {
        armAsEarlierVersion(id, "old")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking { scheduler.cancelAll(listOf(id)) }
        assertEquals(emptyList(), titles())
    }

    @Test
    fun `an alarm of a look-alike id with a literal question mark is never touched`() {
        armAsEarlierVersion("task?", "lookalike")
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        runBlocking {
            assertIs<AlarmScheduleResult.Exact>(scheduler.schedule(alarm(id, "mine")))
            assertEquals(listOf("lookalike", "mine"), titles())
            scheduler.cancel(id)
            assertEquals(listOf("lookalike"), titles())
        }
    }

    @Implements(AlarmManager::class)
    class RefusingShadowAlarmManager : ShadowAlarmManager() {
        @Implementation
        public override fun setExactAndAllowWhileIdle(type: Int, triggerAtTime: Long, operation: PendingIntent?) {
            if (refuseSet.get()) throw RuntimeException("system server is restarting")
            insideSet.set(true)
            try { super.setExactAndAllowWhileIdle(type, triggerAtTime, operation) } finally { insideSet.set(false) }
        }

        @Implementation
        public override fun cancel(operation: PendingIntent?) {
            if (refuseCancel.get() && !insideSet.get()) throw RuntimeException("system server is restarting")
            super.cancel(operation)
        }

        companion object {
            val refuseCancel: AtomicReference<Boolean> = AtomicReference(false)
            val refuseSet: AtomicReference<Boolean> = AtomicReference(false)
            val insideSet: ThreadLocal<Boolean> = ThreadLocal.withInitial { false }
        }
    }
}
