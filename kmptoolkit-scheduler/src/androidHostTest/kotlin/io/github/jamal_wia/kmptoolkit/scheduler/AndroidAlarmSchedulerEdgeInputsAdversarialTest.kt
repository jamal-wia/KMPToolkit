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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerEdgeInputsAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    private fun alarm(id: String, at: Long = 1_000L, payload: Map<String, String> = emptyMap(), title: String = "t") = ScheduledAlarm(
        id = id, type = "T", fireAtEpochMillis = at,
        notification = AlarmNotification(title = title, body = "b", channelId = "c"), payload = payload,
    )

    private fun fired(keys: AlarmIntentKeys): List<ScheduledAlarm?> = shadowAlarmManager.scheduledAlarms
        .map { AlarmIntents.fromIntent(shadowOf(it.operation).savedIntent, keys) }

    @Test
    fun `odd ids round trip, stay distinct and cancel one by one`() {
        val keys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val ids: List<String> = listOf(
            "a/b", "a%2Fb", "a%252Fb", "a", "a/", "/a", ".", "..", "a/../b", "a b", "a+b", "a?b", "a#b", "a:b",
            "\u0000", "\u0000\u0000", "a\u0000", "😀", "x".repeat(50_000), "é", "é", "%", "%%", "%00",
        )
        val results: List<AlarmScheduleResult> = ids.map { runBlocking { scheduler.schedule(alarm(it, title = "T-$it".take(20))) } }
        assertEquals(List(ids.size) { AlarmScheduleResult.Exact }, results)
        assertEquals(ids.toSet(), fired(keys).map { requireNotNull(it).id }.toSet())
        assertEquals(ids.size, shadowAlarmManager.scheduledAlarms.size)
        ids.forEachIndexed { index: Int, id: String ->
            runBlocking { scheduler.cancel(id) }
            assertEquals(ids.size - index - 1, shadowAlarmManager.scheduledAlarms.size, "after cancelling #$index")
        }
    }

    @Test
    fun `extreme fire times arm exactly and round trip`() {
        val keys = AlarmIntentKeys.from(AlarmSchedulerConfig(), context.packageName)
        val scheduler: AlarmScheduler = AndroidAlarmScheduler(context, keys)
        val times: List<Long> = listOf(Long.MIN_VALUE, -1L, 0L, 1L, Long.MAX_VALUE)
        times.forEachIndexed { index: Int, at: Long ->
            assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm("id$index", at)) })
        }
        assertEquals(times.toSet(), fired(keys).map { requireNotNull(it).fireAtEpochMillis }.toSet())
    }
}
