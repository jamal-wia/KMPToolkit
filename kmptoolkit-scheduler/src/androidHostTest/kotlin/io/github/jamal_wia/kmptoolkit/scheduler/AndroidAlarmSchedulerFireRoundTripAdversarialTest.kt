package io.github.jamal_wia.kmptoolkit.scheduler

import android.app.AlarmManager
import android.content.Context
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlarmManager

/** Odd ids and payloads survive the whole trip: schedule, the armed intent, the receiver, the handler. Many ids, one scheme each. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AndroidAlarmSchedulerFireRoundTripAdversarialTest {

    private val context: Context = RuntimeEnvironment.getApplication()
    private val shadowAlarmManager: ShadowAlarmManager = shadowOf(context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)

    @Before
    fun grant() { ShadowAlarmManager.setCanScheduleExactAlarms(true) }

    @After
    fun reset() { AlarmDispatch.reset() }

    @Test
    fun `odd ids, huge payloads and unusual schemes reach the handler unchanged`() {
        val fired: MutableList<ScheduledAlarm> = CopyOnWriteArrayList()
        val done = CountDownLatch(SCHEMES.size * IDS.size)
        val handler = object : AlarmHandler {
            override val type: String = "T"
            override suspend fun onFire(alarm: ScheduledAlarm) { fired += alarm; done.countDown() }
        }
        val sent: MutableList<ScheduledAlarm> = mutableListOf()
        for (scheme: String in SCHEMES) {
            val config = AlarmSchedulerConfig(alarmIntentScheme = scheme)
            val scheduler: AlarmScheduler = createAlarmScheduler(context, handlers = listOf(handler), config = config)
            IDS.forEachIndexed { index: Int, id: String ->
                val alarm = ScheduledAlarm(
                    id = id, type = "T", fireAtEpochMillis = index.toLong(),
                    notification = AlarmNotification(title = "t$index", body = "b".repeat(5_000), channelId = "c"),
                    payload = (0 until 50).associate { "k$it" to "v".repeat(100) + id.take(10) },
                )
                assertEquals(AlarmScheduleResult.Exact, runBlocking { scheduler.schedule(alarm) })
                sent += alarm
            }
        }
        // Same ids under different schemes are different alarms: nothing collapsed.
        assertEquals(SCHEMES.size * IDS.size, shadowAlarmManager.scheduledAlarms.size)
        val receiver = AlarmReceiver()
        shadowAlarmManager.scheduledAlarms.forEach { receiver.onReceive(context, shadowOf(it.operation).savedIntent) }
        assertTrue(done.await(30, TimeUnit.SECONDS), "handlers did not all run")
        assertEquals(sent.toSet(), fired.toSet())
    }

    private companion object {
        val SCHEMES: List<String> = listOf("a", "A+b.c-d", "content", "kmptoolkit.alarm")
        val IDS: List<String> = listOf("a/b", "a%2Fb", ".", "..", "\u0000", "a\u0000", "x".repeat(20_000), "é", "é", "%00", " a", "a ")
    }
}
